package workspace

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"unicode/utf8"
)

const MaxJSONBytes = 256 << 10

var idPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,20}$`)
var devicePattern = regexp.MustCompile(`^(COM|LPT)[0-9]$`)
var sessionPattern = regexp.MustCompile(`^[0-9]+$`)

type Context struct {
	RunID        string `json:"runId"`
	AssignmentID string `json:"assignmentId"`
	Attempt      int    `json:"attempt"`
	Session      string `json:"session"`
	BusinessKey  string `json:"businessKey"`
}

type Workspace struct{ Dir, InputFile, ContextFile, ResultFile string }

// Object preserves the original JSON bytes, including integers above 2^53.
func Object(b []byte) (json.RawMessage, error) {
	if len(b) > MaxJSONBytes || !utf8.Valid(b) {
		return nil, errors.New("JSON must be UTF-8 and at most 256 KiB")
	}
	trimmed := bytes.TrimSpace(b)
	if len(trimmed) == 0 || trimmed[0] != '{' || !json.Valid(trimmed) {
		return nil, errors.New("JSON object required")
	}
	return append(json.RawMessage(nil), trimmed...), nil
}

func Create(root string, ctx Context, input []byte) (*Workspace, error) {
	if !idPattern.MatchString(ctx.AssignmentID) {
		return nil, errors.New("invalid assignment ID")
	}
	// Avoid Windows device names even when another OS creates the workspace.
	name := strings.ToUpper(ctx.AssignmentID)
	if name == "CON" || name == "PRN" || name == "AUX" || name == "NUL" || devicePattern.MatchString(name) {
		return nil, errors.New("reserved assignment ID")
	}
	_, sessionErr := strconv.ParseInt(ctx.Session, 10, 64)
	if !idPattern.MatchString(ctx.RunID) || ctx.Attempt < 1 || ctx.Attempt > 2147483647 || !sessionPattern.MatchString(ctx.Session) || sessionErr != nil || !utf8.ValidString(ctx.BusinessKey) || strings.ContainsRune(ctx.BusinessKey, 0) {
		return nil, errors.New("invalid execution context")
	}
	b, err := Object(input)
	if err != nil {
		return nil, err
	}
	root, err = filepath.Abs(root)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(root, 0700); err != nil {
		return nil, err
	}
	w := &Workspace{Dir: filepath.Join(root, ctx.AssignmentID)}
	// Ongoing/recovered assignments must not accidentally recreate their inputs.
	if err := os.Mkdir(w.Dir, 0700); err != nil {
		return nil, fmt.Errorf("reserve workspace: %w", err)
	}
	w.InputFile = filepath.Join(w.Dir, "input.json")
	w.ContextFile = filepath.Join(w.Dir, "context.json")
	w.ResultFile = filepath.Join(w.Dir, "result.json")
	contextBytes, err := json.Marshal(ctx)
	if err != nil {
		return nil, err
	}
	if err := writeNew(w.InputFile, b); err != nil {
		return nil, err
	}
	if err := writeNew(w.ContextFile, contextBytes); err != nil {
		return nil, err
	}
	return w, nil
}

func writeNew(path string, b []byte) error {
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return err
	}
	_, writeErr := f.Write(b)
	syncErr := f.Sync()
	return errors.Join(writeErr, syncErr, f.Close())
}

func (w *Workspace) Environment() []string {
	return []string{"KKDUGI_INPUT_FILE=" + w.InputFile, "KKDUGI_RESULT_FILE=" + w.ResultFile, "KKDUGI_CONTEXT_FILE=" + w.ContextFile, "KKDUGI_WORK_DIR=" + w.Dir}
}

func (w *Workspace) Result() (json.RawMessage, error) {
	info, err := os.Lstat(w.ResultFile)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() {
		return nil, errors.New("result must be a regular file, not a link")
	}
	f, err := os.Open(w.ResultFile)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	actual, err := f.Stat()
	if err != nil {
		return nil, err
	}
	if !os.SameFile(info, actual) {
		return nil, errors.New("result changed while opening")
	}
	b, err := io.ReadAll(io.LimitReader(f, MaxJSONBytes+1))
	if err != nil {
		return nil, err
	}
	return Object(b)
}
