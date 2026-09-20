package config

import (
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/pelletier/go-toml/v2"
)

func fixture(t *testing.T) Config {
	t.Helper()
	dir := t.TempDir()
	file := filepath.Join(dir, "프로그램 파일")
	content := []byte("fixture")
	if err := os.WriteFile(file, content, 0700); err != nil {
		t.Fatal(err)
	}
	digest := sha256.Sum256(content)
	return Config{DataDir: filepath.Join(dir, "data"), Capacity: 1, Programs: []Program{{Code: "demo", Version: "1", Revision: "r1", Manifest: Manifest{Executable: file, WorkingDirectory: dir, InputContract: "1", InputMode: "JSON_FILE", Files: []FileDigest{{Path: file, SHA256: hex.EncodeToString(digest[:])}}}}}}
}

func TestVerifyDetectsChangedFiles(t *testing.T) {
	cfg := fixture(t)
	if err := cfg.Verify(); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(cfg.Programs[0].Manifest.Executable, []byte("changed"), 0700); err != nil {
		t.Fatal(err)
	}
	if err := cfg.Verify(); err == nil {
		t.Fatal("changed file accepted")
	}
}

func TestLoadStrictAndRelativeDataDir(t *testing.T) {
	cfg := fixture(t)
	cfg.DataDir = "runner-data"
	b, err := toml.Marshal(cfg)
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	path := filepath.Join(dir, "runner.toml")
	if err := os.WriteFile(path, b, 0600); err != nil {
		t.Fatal(err)
	}
	loaded, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if loaded.DataDir != filepath.Join(dir, "runner-data") {
		t.Fatal(loaded.DataDir)
	}
	for name, bad := range map[string][]byte{"unknown": append([]byte("unknown = 1\n"), b...), "tooLarge": []byte(strings.Repeat(" ", MaxConfigBytes+1)), "encoding": {0xff}} {
		t.Run(name, func(t *testing.T) {
			if err := os.WriteFile(path, bad, 0600); err != nil {
				t.Fatal(err)
			}
			if _, err := Load(path); err == nil {
				t.Fatal("invalid config accepted")
			}
		})
	}
}

func TestValidationBoundaries(t *testing.T) {
	for name, mutate := range map[string]func(*Config){
		"capacity":            func(c *Config) { c.Capacity = 201 },
		"duplicate":           func(c *Config) { c.Programs = append(c.Programs, c.Programs[0]) },
		"path traversal code": func(c *Config) { c.Programs[0].Code = "../escape" },
		"relative executable": func(c *Config) { c.Programs[0].Manifest.Executable = "program" },
		"revision length":     func(c *Config) { c.Programs[0].Revision = strings.Repeat("r", 201) },
		"nul argument":        func(c *Config) { c.Programs[0].Manifest.Arguments = []string{"a\x00b"} },
		"input mode":          func(c *Config) { c.Programs[0].Manifest.InputMode = "SHELL" },
		"secret reserved":     func(c *Config) { c.Programs[0].Manifest.SecretNames = []string{"KKDUGI_INPUT_FILE"} },
		"secret duplicate":    func(c *Config) { c.Programs[0].Manifest.SecretNames = []string{"TOKEN", "token"} },
		"bad digest":          func(c *Config) { c.Programs[0].Manifest.Files[0].SHA256 = "123" },
	} {
		t.Run(name, func(t *testing.T) {
			c := fixture(t)
			mutate(&c)
			if err := c.Validate(); err == nil {
				t.Fatal("invalid config accepted")
			}
		})
	}
}
