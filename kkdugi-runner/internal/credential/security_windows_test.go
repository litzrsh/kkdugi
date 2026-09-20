package credential

import (
	"os"
	"path/filepath"
	"testing"

	"golang.org/x/sys/windows"
)

func grantEveryoneRead(t *testing.T, path string) {
	t.Helper()
	sid, err := currentSID()
	if err != nil {
		t.Fatal(err)
	}
	sd, err := windows.SecurityDescriptorFromString("D:P(A;;FA;;;" + sid + ")(A;;FR;;;WD)")
	if err != nil {
		t.Fatal(err)
	}
	acl, _, err := sd.DACL()
	if err != nil {
		t.Fatal(err)
	}
	if err := windows.SetNamedSecurityInfo(path, windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION, nil, nil, acl, nil); err != nil {
		t.Fatal(err)
	}
}
func TestRejectBroadWindowsACL(t *testing.T) {
	dir := directory(t)
	if err := os.Mkdir(dir, 0700); err != nil {
		t.Fatal(err)
	}
	grantEveryoneRead(t, dir)
	if _, err := Begin(dir); err == nil {
		t.Fatal("broad directory ACL accepted")
	}
	if err := secure(dir, true); err != nil {
		t.Fatal(err)
	}
	a, err := Begin(dir)
	if err != nil {
		t.Fatal(err)
	}
	if err := a.Commit(stored()); err != nil {
		t.Fatal(err)
	}
	grantEveryoneRead(t, filepath.Join(dir, fileName))
	if _, err := Load(dir, stored().BaseURL); err == nil {
		t.Fatal("readable token accepted")
	}
}
