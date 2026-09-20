package credential

import (
	"errors"
	"os"
	"path/filepath"
)

// EnsureDirectory shares the credential permission policy with private runner state.
// Existing directories must already be private; never silently chmod user paths.
func EnsureDirectory(dir string) error {
	if !filepath.IsAbs(dir) || filepath.Clean(dir) != dir || noLinks(dir) != nil {
		return ErrPermissions
	}
	if _, err := os.Lstat(dir); errors.Is(err, os.ErrNotExist) {
		if makeParents(filepath.Dir(dir)) != nil {
			return ErrStorage
		}
		if err := os.Mkdir(dir, 0700); err == nil {
			if secure(dir, true) != nil {
				return ErrPermissions
			}
			if syncDirectory(filepath.Dir(dir)) != nil {
				return ErrStorage
			}
		} else if !errors.Is(err, os.ErrExist) {
			return ErrStorage
		}
	}
	if noLinks(dir) != nil || checkPrivate(dir, true) != nil {
		return ErrPermissions
	}
	return nil
}

// CheckFile rejects links and non-private existing files, allowing an absent file.
func CheckFile(path string) error {
	if noLinks(path) != nil {
		return ErrPermissions
	}
	if _, err := os.Lstat(path); errors.Is(err, os.ErrNotExist) {
		return nil
	} else if err != nil {
		return ErrStorage
	}
	if checkPrivate(path, false) != nil {
		return ErrPermissions
	}
	return nil
}

func OpenPrivateFile(path string, exclusive bool) (*os.File, error) {
	if CheckFile(path) != nil || checkPrivate(filepath.Dir(path), true) != nil {
		return nil, ErrPermissions
	}
	flags := os.O_RDWR | os.O_CREATE | os.O_EXCL
	f, err := os.OpenFile(path, flags, 0600)
	if errors.Is(err, os.ErrExist) && !exclusive {
		return os.OpenFile(path, os.O_RDWR, 0600)
	}
	if err != nil {
		return nil, err
	}
	if secure(path, false) != nil {
		f.Close()
		return nil, ErrPermissions
	}
	return f, nil
}

func SyncDirectory(dir string) error { return syncDirectory(dir) }
