//go:build !linux && !windows

package spool

func available(string) (int64, error) { return 0, ErrCapacity }
