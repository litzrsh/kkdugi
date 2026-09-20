//go:build !windows && !linux

package credential

func secure(string, bool) error       { return ErrPermissions }
func checkPrivate(string, bool) error { return ErrPermissions }
func isReparse(string) bool           { return true }
func syncDirectory(string) error      { return ErrPermissions }
func publish(string, string) error    { return ErrPermissions }
