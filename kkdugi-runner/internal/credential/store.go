package credential

import (
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"kkdugi-runner/internal/client"
)

const fileName = "credential.json"
const pendingName = ".registration.pending"
const tempName = ".credential.pending"
const maxBytes = 64 << 10

var ErrExists = errors.New("credential or registration attempt already exists; inspect admin and local state before recovery")
var ErrStorage = errors.New("credential storage failed; preserve registration state for recovery")
var ErrPermissions = errors.New("credential directory or file is not private, owned, or regular")
var runnerCodePattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,20}$`)

type Stored struct {
	SchemaVersion int                         `json:"schemaVersion"`
	BaseURL       string                      `json:"baseUrl"`
	RunnerCode    string                      `json:"runnerCode"`
	Registration  client.RegistrationResponse `json:"registration"`
}

func (Stored) String() string   { return "[PROTECTED CREDENTIAL]" }
func (Stored) GoString() string { return "[PROTECTED CREDENTIAL]" }

type Attempt struct {
	dir       string
	committed bool
}

// CheckAvailable is read-only. Begin reserves the name atomically after this preflight.
func CheckAvailable(dir string) error {
	if !filepath.IsAbs(dir) || filepath.Clean(dir) != dir || strings.ContainsRune(dir, 0) {
		return ErrPermissions
	}
	if err := noLinks(dir); err != nil {
		return err
	}
	if info, err := os.Lstat(dir); err == nil {
		if !info.IsDir() || checkPrivate(dir, true) != nil {
			return ErrPermissions
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return ErrStorage
	}
	for _, name := range []string{fileName, pendingName, tempName} {
		if _, err := os.Lstat(filepath.Join(dir, name)); err == nil {
			return ErrExists
		} else if !errors.Is(err, os.ErrNotExist) {
			return ErrStorage
		}
	}
	return nil
}

func noLinks(path string) error {
	for {
		info, err := os.Lstat(path)
		if err == nil {
			if info.Mode()&os.ModeSymlink != 0 || isReparse(path) {
				return ErrPermissions
			}
		} else if !errors.Is(err, os.ErrNotExist) {
			return ErrPermissions
		}
		parent := filepath.Dir(path)
		if parent == path {
			break
		}
		path = parent
	}
	return nil
}

func Begin(dir string) (*Attempt, error) {
	if err := CheckAvailable(dir); err != nil {
		return nil, err
	}
	if _, err := os.Stat(dir); errors.Is(err, os.ErrNotExist) {
		if err := makeParents(filepath.Dir(dir)); err != nil {
			return nil, ErrStorage
		}
		if err := os.Mkdir(dir, 0700); err != nil && !errors.Is(err, os.ErrExist) {
			return nil, ErrStorage
		} else if err == nil {
			if secure(dir, true) != nil {
				return nil, ErrPermissions
			}
			if syncDirectory(filepath.Dir(dir)) != nil {
				return nil, ErrStorage
			}
		}
	}
	if err := CheckAvailable(dir); err != nil {
		return nil, err
	}
	if err := writePrivate(filepath.Join(dir, pendingName), []byte("registration may have been submitted; do not retry automatically\n")); err != nil {
		if errors.Is(err, os.ErrExist) {
			return nil, ErrExists
		}
		return nil, ErrStorage
	}
	if syncDirectory(dir) != nil {
		return nil, ErrStorage
	}
	return &Attempt{dir: dir}, nil
}

// Persist newly created directory entries before submitting an enrollment request.
func makeParents(path string) error {
	if info, err := os.Lstat(path); err == nil {
		if !info.IsDir() {
			return ErrPermissions
		}
		return nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	parent := filepath.Dir(path)
	if parent == path {
		return ErrStorage
	}
	if err := makeParents(parent); err != nil {
		return err
	}
	if err := os.Mkdir(path, 0700); err != nil && !errors.Is(err, os.ErrExist) {
		return err
	}
	return syncDirectory(parent)
}

func writePrivate(path string, b []byte) error {
	f, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	if err := secure(path, false); err != nil {
		f.Close()
		return err
	}
	_, writeErr := f.Write(b)
	syncErr := f.Sync()
	return errors.Join(writeErr, syncErr, f.Close())
}

func (s Stored) validate(now time.Time) error {
	base, err := client.NormalizeBaseURL(s.BaseURL)
	if err != nil || base != s.BaseURL || s.SchemaVersion != 1 || !runnerCodePattern.MatchString(s.RunnerCode) {
		return ErrStorage
	}
	if s.Registration.Validate(now) != nil {
		return ErrStorage
	}
	return nil
}

// Commit never replaces an existing credential. After publication, cleanup errors retain it.
func (a *Attempt) Commit(s Stored) error {
	if a.committed {
		return ErrExists
	}
	if err := s.validate(time.Now()); err != nil {
		return err
	}
	if noLinks(a.dir) != nil || checkPrivate(a.dir, true) != nil {
		return ErrPermissions
	}
	b, err := json.Marshal(s)
	if err != nil || len(b) > maxBytes {
		return ErrStorage
	}
	if err := writePrivate(filepath.Join(a.dir, tempName), b); err != nil {
		return ErrStorage
	}
	if err := publish(filepath.Join(a.dir, tempName), filepath.Join(a.dir, fileName)); err != nil {
		return ErrStorage
	}
	a.committed = true
	if syncDirectory(a.dir) != nil {
		return ErrStorage
	}
	if err := os.Remove(filepath.Join(a.dir, pendingName)); err != nil {
		return ErrStorage
	}
	if syncDirectory(a.dir) != nil {
		return ErrStorage
	}
	return nil
}

func Load(dir, expectedBase string) (Stored, error) {
	var result Stored
	base, err := client.NormalizeBaseURL(expectedBase)
	if err != nil {
		return result, ErrStorage
	}
	path := filepath.Join(dir, fileName)
	if !filepath.IsAbs(dir) || filepath.Clean(dir) != dir || noLinks(path) != nil || checkPrivate(dir, true) != nil || checkPrivate(path, false) != nil {
		return result, ErrPermissions
	}
	f, err := os.Open(path)
	if err != nil {
		return result, ErrStorage
	}
	defer f.Close()
	b, err := io.ReadAll(io.LimitReader(f, maxBytes+1))
	if err != nil || len(b) > maxBytes {
		return result, ErrStorage
	}
	if json.Unmarshal(b, &result) != nil || result.validate(time.Now()) != nil || result.BaseURL != base {
		return Stored{}, ErrStorage
	}
	return result, nil
}
