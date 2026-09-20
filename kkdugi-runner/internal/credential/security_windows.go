package credential

import (
	"os"
	"runtime"
	"unsafe"

	"golang.org/x/sys/windows"
)

func currentSID() (string, error) {
	u, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		return "", err
	}
	return u.User.Sid.String(), nil
}
func secure(path string, dir bool) error {
	sid, err := currentSID()
	if err != nil {
		return err
	}
	flags := ""
	if dir {
		flags = "OICI"
	}
	sd, err := windows.SecurityDescriptorFromString("D:P(A;" + flags + ";FA;;;SY)(A;" + flags + ";FA;;;" + sid + ")")
	if err != nil {
		return err
	}
	acl, _, err := sd.DACL()
	if err != nil {
		return err
	}
	err = windows.SetNamedSecurityInfo(path, windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION, nil, nil, acl, nil)
	runtime.KeepAlive(sd)
	return err
}
func checkPrivate(path string, dir bool) error {
	info, err := os.Lstat(path)
	if err != nil {
		return err
	}
	if info.IsDir() != dir || (!dir && !info.Mode().IsRegular()) || isReparse(path) {
		return ErrPermissions
	}
	sid, err := currentSID()
	if err != nil {
		return err
	}
	sd, err := windows.GetNamedSecurityInfo(path, windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION|windows.OWNER_SECURITY_INFORMATION)
	if err != nil {
		return err
	}
	owner, _, err := sd.Owner()
	if err != nil || owner == nil || owner.String() != sid {
		return ErrPermissions
	}
	acl, _, err := sd.DACL()
	if err != nil || acl == nil || acl.AceCount == 0 {
		return ErrPermissions
	}
	for i := uint32(0); i < uint32(acl.AceCount); i++ {
		var ace *windows.ACCESS_ALLOWED_ACE
		if windows.GetAce(acl, i, &ace) != nil || ace == nil || ace.Header.AceType != windows.ACCESS_ALLOWED_ACE_TYPE {
			return ErrPermissions
		}
		allowedSID := (*windows.SID)(unsafe.Pointer(&ace.SidStart)).String()
		if allowedSID != sid && allowedSID != "S-1-5-18" {
			return ErrPermissions
		}
	}
	runtime.KeepAlive(sd)
	return nil
}
func isReparse(path string) bool {
	p, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return true
	}
	attrs, err := windows.GetFileAttributes(p)
	return err != nil || attrs&windows.FILE_ATTRIBUTE_REPARSE_POINT != 0
}

// Commit uses file FlushFileBuffers and MOVEFILE_WRITE_THROUGH. Windows directory
// handles do not support the Unix directory-fsync contract.
func syncDirectory(string) error { return nil }
func publish(from, to string) error {
	f, err := windows.UTF16PtrFromString(from)
	if err != nil {
		return err
	}
	t, err := windows.UTF16PtrFromString(to)
	if err != nil {
		return err
	}
	return windows.MoveFileEx(f, t, windows.MOVEFILE_WRITE_THROUGH)
}
