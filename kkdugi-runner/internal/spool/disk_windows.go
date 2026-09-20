//go:build windows

package spool

import "golang.org/x/sys/windows"

func available(path string) (int64, error) {
	p, e := windows.UTF16PtrFromString(path)
	if e != nil {
		return 0, e
	}
	var free, total, totalFree uint64
	e = windows.GetDiskFreeSpaceEx(p, &free, &total, &totalFree)
	return int64(free), e
}
