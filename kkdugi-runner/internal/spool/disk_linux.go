//go:build linux

package spool

import "golang.org/x/sys/unix"

func available(path string) (int64, error) {
	var st unix.Statfs_t
	e := unix.Statfs(path, &st)
	return int64(st.Bavail) * int64(st.Bsize), e
}
