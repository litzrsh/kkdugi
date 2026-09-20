package state

import (
	"context"
	"os"
	"path/filepath"
	"testing"
)

func TestStatePrivateLinuxFiles(t *testing.T) {
	s, dir := newStore(t)
	activate(t, s)
	commit(t, s, claim())
	for _, name := range []string{"state.db", "state.db-wal", "state.db-shm", "runner.lock", ".state.initialized"} {
		info, err := os.Stat(filepath.Join(dir, name))
		if err != nil || info.Mode().Perm()&0077 != 0 {
			t.Fatal("file not private", name, err)
		}
	}
	s.Close()
	if err := os.Chmod(dir, 0755); err != nil {
		t.Fatal(err)
	}
	if s, err := Open(context.Background(), dir, testIdentity); err == nil {
		s.Close()
		t.Fatal("broad directory accepted")
	}
}
