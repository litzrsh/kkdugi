package credential

import (
	"errors"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"unicode/utf8"
)

var secretName = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*$`)

func ReadSecret(dir, name string) (string, error) {
	if !secretName.MatchString(name) || strings.HasPrefix(strings.ToUpper(name), "KKDUGI_") || !filepath.IsAbs(dir) || filepath.Clean(dir) != dir || checkPrivate(dir, true) != nil {
		return "", ErrPermissions
	}
	path := filepath.Join(dir, name)
	if noLinks(path) != nil || checkPrivate(path, false) != nil {
		return "", ErrPermissions
	}
	f, err := os.Open(path)
	if err != nil {
		return "", ErrStorage
	}
	defer f.Close()
	b, err := io.ReadAll(io.LimitReader(f, (64<<10)+1))
	if err != nil || len(b) > 64<<10 || !utf8.Valid(b) || strings.ContainsRune(string(b), 0) {
		return "", errors.New("invalid protected secret value")
	}
	return strings.TrimSuffix(strings.TrimSuffix(string(b), "\n"), "\r"), nil
}
