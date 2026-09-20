//go:build !windows && !linux

package executor

import (
	"errors"
	"os"
)

func startProcess(Spec, *os.File, *os.File) (process, error) {
	return nil, errors.New("executor supports only Windows and Linux")
}
