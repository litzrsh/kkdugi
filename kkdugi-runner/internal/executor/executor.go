package executor

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

type Spec struct {
	Executable         string
	Arguments          []string
	Directory          string
	Environment        []string  // Explicit allowlist; the runner environment is never inherited.
	Stdout, Stderr     io.Writer // Must return promptly; called independently for each stream.
	Timeout, StopGrace time.Duration
}

type Result struct {
	PID                   int
	StartedAt, FinishedAt time.Time
	ExitCode              *int64
	ProcessExited         bool
	Reason                string // EXITED, CANCELED, TIMED_OUT, START_FAILED, UNKNOWN
	Err                   error
}

type process interface {
	PID() int
	Poll() (bool, int64, error)
	TreeEmpty() (bool, error)
	Stop() error
	Kill() error
	Close() error
}

func validate(s Spec) error {
	if !filepath.IsAbs(s.Executable) || !filepath.IsAbs(s.Directory) {
		return errors.New("executable and directory must be absolute")
	}
	if s.Timeout <= 0 || s.StopGrace < 0 {
		return errors.New("positive timeout and nonnegative stop grace required")
	}
	for _, value := range append([]string{s.Executable, s.Directory}, s.Arguments...) {
		if strings.ContainsRune(value, 0) || !utf8.ValidString(value) {
			return errors.New("invalid encoding in execution specification")
		}
	}
	seen := map[string]bool{}
	for _, entry := range s.Environment {
		key, _, ok := strings.Cut(entry, "=")
		if runtime.GOOS == "windows" {
			key = strings.ToUpper(key)
		}
		if !ok || key == "" || strings.ContainsRune(entry, 0) || !utf8.ValidString(entry) || seen[key] {
			return errors.New("invalid or duplicate environment entry")
		}
		seen[key] = true
	}
	return nil
}

// Run is an internal primitive. The future agent must commit START_INTENT before calling it.
func Run(ctx context.Context, s Spec) Result {
	r := Result{Reason: "START_FAILED"}
	if err := validate(s); err != nil {
		r.Err = err
		return r
	}
	if err := ctx.Err(); err != nil {
		r.Reason = "CANCELED"
		r.Err = err
		r.ProcessExited = true
		return r
	}
	outR, outW, err := os.Pipe()
	if err != nil {
		r.Err = err
		return r
	}
	defer outR.Close()
	defer outW.Close()
	errR, errW, err := os.Pipe()
	if err != nil {
		r.Err = err
		return r
	}
	defer errR.Close()
	defer errW.Close()
	p, err := startProcess(s, outW, errW)
	if err != nil {
		r.Err = err
		return r
	}
	defer p.Close()
	r.PID, r.StartedAt, r.Reason = p.PID(), time.Now().UTC(), "EXITED"
	outW.Close()
	errW.Close()
	var wg sync.WaitGroup
	var outputErrors [2]error
	for i, stream := range []struct {
		reader *os.File
		writer io.Writer
	}{{outR, s.Stdout}, {errR, s.Stderr}} {
		wg.Add(1)
		go func() {
			defer wg.Done()
			outputErrors[i] = drain(stream.reader, stream.writer)
		}()
	}
	deadline := time.Now().Add(s.Timeout)
	var stopping, killed, parentExited time.Time
	ticker := time.NewTicker(10 * time.Millisecond)
	defer ticker.Stop()
	for {
		done, code, pollErr := p.Poll()
		if pollErr != nil {
			r.Err = errors.Join(r.Err, pollErr)
			r.Reason = "UNKNOWN"
			break
		}
		if done {
			if parentExited.IsZero() {
				parentExited = time.Now()
			}
			r.ExitCode = &code
			empty, treeErr := p.TreeEmpty()
			if treeErr != nil {
				r.Err = errors.Join(r.Err, treeErr)
				r.Reason = "UNKNOWN"
				break
			}
			if empty {
				r.ProcessExited = true
				break
			}
			// Job accounting may lag the process handle becoming signaled briefly.
			if killed.IsZero() && time.Since(parentExited) >= 250*time.Millisecond {
				r.Err = errors.Join(r.Err, errors.New("foreground process left descendants running"), p.Kill())
				killed = time.Now()
			}
		}
		now := time.Now()
		if stopping.IsZero() && (ctx.Err() != nil || !now.Before(deadline)) {
			if ctx.Err() != nil {
				r.Reason = "CANCELED"
			} else {
				r.Reason = "TIMED_OUT"
			}
			stopping = now
			r.Err = errors.Join(r.Err, p.Stop())
		}
		if !stopping.IsZero() && killed.IsZero() && !now.Before(stopping.Add(s.StopGrace)) {
			r.Err = errors.Join(r.Err, p.Kill())
			killed = now
		}
		if !killed.IsZero() && now.Sub(killed) >= 3*time.Second {
			r.Reason = "UNKNOWN"
			r.Err = errors.Join(r.Err, errors.New("process tree termination unconfirmed"))
			break
		}
		<-ticker.C
	}
	if !r.ProcessExited {
		r.Err = errors.Join(r.Err, p.Kill())
	}
	// Pipe EOF normally follows the confirmed tree exit. Bound collection on escaped handles.
	collected := make(chan struct{})
	go func() { wg.Wait(); close(collected) }()
	select {
	case <-collected:
	case <-time.After(2 * time.Second):
		outR.Close()
		errR.Close()
		<-collected
		r.Err = errors.Join(r.Err, errors.New("output collection did not reach EOF"))
	}
	r.Err = errors.Join(r.Err, outputErrors[0], outputErrors[1])
	r.FinishedAt = time.Now().UTC()
	return r
}

// A failing destination must not stop draining the child's pipes.
func drain(src io.Reader, dst io.Writer) error {
	if dst == nil {
		dst = io.Discard
	}
	buf := make([]byte, 32<<10)
	var first error
	for {
		n, err := src.Read(buf)
		if n > 0 && first == nil {
			written, writeErr := dst.Write(buf[:n])
			if writeErr != nil {
				first = writeErr
			} else if written != n {
				first = io.ErrShortWrite
			}
		}
		if err != nil {
			if !errors.Is(err, io.EOF) {
				first = errors.Join(first, fmt.Errorf("read output: %w", err))
			}
			return first
		}
	}
}
