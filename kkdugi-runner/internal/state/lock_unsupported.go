//go:build !linux && !windows

package state

import "os"

func lockFile(*os.File) error { return ErrStorage }
