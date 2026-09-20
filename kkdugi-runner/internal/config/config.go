package config

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"unicode/utf8"

	"github.com/pelletier/go-toml/v2"
)

const MaxConfigBytes = 1 << 20

var codePattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,20}$`)
var envPattern = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*$`)

func Load(path string) (*Config, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("open config: %w", err)
	}
	defer f.Close()
	b, err := io.ReadAll(io.LimitReader(f, MaxConfigBytes+1))
	if err != nil {
		return nil, fmt.Errorf("read config: %w", err)
	}
	if len(b) > MaxConfigBytes || !utf8.Valid(b) {
		return nil, errors.New("config must be UTF-8 and at most 1 MiB")
	}
	var cfg Config
	decoder := toml.NewDecoder(bytes.NewReader(b)).DisallowUnknownFields()
	if err := decoder.Decode(&cfg); err != nil {
		return nil, errors.New("invalid TOML config or unknown field")
	}
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	if !filepath.IsAbs(cfg.DataDir) {
		cfg.DataDir = filepath.Join(filepath.Dir(path), cfg.DataDir)
	}
	cfg.DataDir, err = filepath.Abs(cfg.DataDir)
	if err != nil {
		return nil, err
	}
	return &cfg, nil
}

func validText(value string, max int) bool {
	return strings.TrimSpace(value) != "" && !strings.ContainsRune(value, 0) && utf8.ValidString(value) && utf8.RuneCountInString(value) <= max
}

func (c *Config) Validate() error {
	if !validText(c.DataDir, 4096) {
		return errors.New("data_dir is required")
	}
	if c.Capacity < 1 || c.Capacity > 200 {
		return errors.New("capacity must be between 1 and 200")
	}
	if len(c.Programs) == 0 {
		return errors.New("at least one program is required")
	}
	seen := make(map[string]bool)
	for i := range c.Programs {
		p := &c.Programs[i]
		if seen[p.Code] {
			return errors.New("duplicate program code")
		}
		seen[p.Code] = true
		if err := p.Validate(); err != nil {
			return fmt.Errorf("program[%d]: %w", i, err)
		}
	}
	return nil
}

func (p Program) Validate() error {
	if !codePattern.MatchString(p.Code) || !validText(p.Version, 50) || !validText(p.Revision, 200) {
		return errors.New("invalid code, version or revision")
	}
	m := p.Manifest
	if !validText(m.Executable, 4096) || !filepath.IsAbs(m.Executable) || !validText(m.WorkingDirectory, 4096) || !filepath.IsAbs(m.WorkingDirectory) {
		return errors.New("executable and working_directory must be absolute paths")
	}
	if m.InputMode != "JSON_FILE" || !validText(m.InputContract, 200) {
		return errors.New("input_mode must be JSON_FILE with a nonempty input_contract")
	}
	for _, arg := range m.Arguments {
		if !utf8.ValidString(arg) || strings.ContainsRune(arg, 0) {
			return errors.New("invalid argument encoding")
		}
	}
	seen := make(map[string]bool)
	for _, name := range m.SecretNames {
		key := strings.ToUpper(name)
		if !envPattern.MatchString(name) || strings.HasPrefix(key, "KKDUGI_") || seen[key] {
			return errors.New("invalid, duplicate or reserved secret name")
		}
		seen[key] = true
	}
	seen = make(map[string]bool)
	for _, file := range m.Files {
		key := filepath.Clean(file.Path)
		if runtime.GOOS == "windows" {
			key = strings.ToUpper(key)
		}
		digest, err := hex.DecodeString(file.SHA256)
		if !validText(file.Path, 4096) || !filepath.IsAbs(file.Path) || seen[key] || err != nil || len(digest) != sha256.Size {
			return errors.New("invalid or duplicate file digest")
		}
		seen[key] = true
	}
	return nil
}

// VerifyFiles is called by verify and must also be called immediately before execution.
func (p Program) VerifyFiles() error {
	if err := p.Validate(); err != nil {
		return err
	}
	info, err := os.Stat(p.Manifest.Executable)
	if err != nil {
		return fmt.Errorf("executable: %w", err)
	}
	if !info.Mode().IsRegular() {
		return errors.New("executable must be a regular file")
	}
	if runtime.GOOS != "windows" && info.Mode().Perm()&0111 == 0 {
		return errors.New("executable has no execute permission")
	}
	info, err = os.Stat(p.Manifest.WorkingDirectory)
	if err != nil {
		return fmt.Errorf("working directory: %w", err)
	}
	if !info.IsDir() {
		return errors.New("working directory must be a directory")
	}
	for _, expected := range p.Manifest.Files {
		if err := verifyDigest(expected); err != nil {
			return err
		}
	}
	return nil
}

func verifyDigest(expected FileDigest) error {
	f, err := os.Open(expected.Path)
	if err != nil {
		return fmt.Errorf("digest file: %w", err)
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() {
		return errors.New("digest target must be a regular file")
	}
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return fmt.Errorf("digest read: %w", err)
	}
	if !strings.EqualFold(hex.EncodeToString(h.Sum(nil)), expected.SHA256) {
		return errors.New("program file digest mismatch")
	}
	return nil
}

func (c *Config) Verify() error {
	if err := c.Validate(); err != nil {
		return err
	}
	for _, p := range c.Programs {
		if err := p.VerifyFiles(); err != nil {
			return fmt.Errorf("program %s: %w", p.Code, err)
		}
	}
	if err := os.MkdirAll(c.DataDir, 0700); err != nil {
		return err
	}
	probe, err := os.CreateTemp(c.DataDir, ".verify-*")
	if err != nil {
		return fmt.Errorf("data directory is not writable: %w", err)
	}
	name := probe.Name()
	err = probe.Close()
	return errors.Join(err, os.Remove(name))
}
