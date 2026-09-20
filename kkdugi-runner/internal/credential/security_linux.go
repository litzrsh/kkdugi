package credential

import (
	"os"
	"path/filepath"
	"syscall"
)

func secure(path string, dir bool) error {
	mode := os.FileMode(0600)
	if dir {
		mode = 0700
	}
	return os.Chmod(path, mode)
}
func checkPrivate(path string, dir bool) error {
	info, err := os.Lstat(path)
	if err != nil {
		return err
	}
	if info.Mode().Perm()&0077 != 0 || info.IsDir() != dir || (!dir && !info.Mode().IsRegular()) {
		return ErrPermissions
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok || stat.Uid != uint32(os.Geteuid()) {
		return ErrPermissions
	}
	return nil
}
func isReparse(string) bool { return false }
func syncDirectory(path string) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	return f.Sync()
}
func publish(from, to string) error {
	if err := os.Link(from, to); err != nil {
		return err
	}
	// Both names refer to the same fully synced inode; never replace the destination.
	if err := syncDirectory(filepath.Dir(to)); err != nil {
		return err
	}
	return os.Remove(from)
}
