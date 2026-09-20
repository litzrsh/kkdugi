package state

import (
	"context"
	"golang.org/x/sys/windows"
	"kkdugi-runner/internal/credential"
	"path/filepath"
	"runtime"
	"testing"
)

func TestStatePrivateWindowsFiles(t *testing.T) {
	s, dir := newStore(t)
	activate(t, s)
	commit(t, s, claim())
	for _, name := range []string{"state.db", "state.db-wal", "state.db-shm", "runner.lock", ".state.initialized"} {
		if err := credential.CheckFile(filepath.Join(dir, name)); err != nil {
			t.Fatal("file not private", name, err)
		}
	}
	s.Close()
	sd, err := windows.SecurityDescriptorFromString("D:P(A;OICI;FA;;;WD)")
	if err != nil {
		t.Fatal(err)
	}
	acl, _, err := sd.DACL()
	if err != nil {
		t.Fatal(err)
	}
	err = windows.SetNamedSecurityInfo(dir, windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION, nil, nil, acl, nil)
	runtime.KeepAlive(sd)
	if err != nil {
		t.Fatal(err)
	}
	if s, err := Open(context.Background(), dir, testIdentity); err == nil {
		s.Close()
		t.Fatal("broad directory accepted")
	}
}
