package credential

import (
	"os"
	"path/filepath"
	"testing"
)

func TestRejectBroadUnixPermissions(t *testing.T) {
	dir := directory(t)
	if err := os.Mkdir(dir, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(dir, 0755); err != nil {
		t.Fatal(err)
	}
	if _, err := Begin(dir); err == nil {
		t.Fatal("broad directory accepted")
	}
	if err := os.Chmod(dir, 0700); err != nil {
		t.Fatal(err)
	}
	a, err := Begin(dir)
	if err != nil {
		t.Fatal(err)
	}
	if err := a.Commit(stored()); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(filepath.Join(dir, fileName), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(dir, stored().BaseURL); err == nil {
		t.Fatal("world-readable token accepted")
	}
}
