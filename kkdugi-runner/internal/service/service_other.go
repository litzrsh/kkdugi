//go:build !windows

package service

import (
	"context"
	"errors"
)

func Run(name string, run func(context.Context) error) error {
	return errors.New("use run under systemd on Linux")
}
