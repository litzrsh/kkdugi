package executor

import (
	"errors"
	"fmt"
	"os"
	"runtime"
	"sort"
	"strings"
	"unicode/utf16"
	"unsafe"

	"golang.org/x/sys/windows"
)

type windowsProcess struct {
	job, handle windows.Handle
	pid         uint32
}

func startProcess(s Spec, out, stderr *os.File) (process, error) {
	job, err := windows.CreateJobObject(nil, nil)
	if err != nil {
		return nil, err
	}
	success := false
	defer func() {
		if !success {
			windows.CloseHandle(job)
		}
	}()
	limits := windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION{}
	limits.BasicLimitInformation.LimitFlags = windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
	if _, err := windows.SetInformationJobObject(job, windows.JobObjectExtendedLimitInformation, uintptr(unsafe.Pointer(&limits)), uint32(unsafe.Sizeof(limits))); err != nil {
		return nil, err
	}
	stdin, err := os.Open(os.DevNull)
	if err != nil {
		return nil, err
	}
	defer stdin.Close()
	var handles []windows.Handle
	defer func() {
		for _, h := range handles {
			windows.CloseHandle(h)
		}
	}()
	for _, f := range []*os.File{stdin, out, stderr} {
		var duplicate windows.Handle
		if err := windows.DuplicateHandle(windows.CurrentProcess(), windows.Handle(f.Fd()), windows.CurrentProcess(), &duplicate, 0, true, windows.DUPLICATE_SAME_ACCESS); err != nil {
			return nil, err
		}
		handles = append(handles, duplicate)
	}
	attrs, err := windows.NewProcThreadAttributeList(1)
	if err != nil {
		return nil, err
	}
	defer attrs.Delete()
	if err := attrs.Update(windows.PROC_THREAD_ATTRIBUTE_HANDLE_LIST, unsafe.Pointer(&handles[0]), uintptr(len(handles))*unsafe.Sizeof(handles[0])); err != nil {
		return nil, err
	}
	si := windows.StartupInfoEx{}
	si.Cb = uint32(unsafe.Sizeof(si))
	si.Flags = windows.STARTF_USESTDHANDLES
	si.StdInput, si.StdOutput, si.StdErr = handles[0], handles[1], handles[2]
	si.ProcThreadAttributeList = attrs.List()
	app, err := windows.UTF16PtrFromString(s.Executable)
	if err != nil {
		return nil, err
	}
	command, err := windows.UTF16PtrFromString(windows.ComposeCommandLine(append([]string{s.Executable}, s.Arguments...)))
	if err != nil {
		return nil, err
	}
	directory, err := windows.UTF16PtrFromString(s.Directory)
	if err != nil {
		return nil, err
	}
	env := append([]string{}, s.Environment...)
	sort.Slice(env, func(i, j int) bool { return strings.ToUpper(env[i]) < strings.ToUpper(env[j]) })
	block := utf16.Encode([]rune(strings.Join(env, "\x00") + "\x00\x00"))
	var pi windows.ProcessInformation
	flags := uint32(windows.CREATE_SUSPENDED | windows.CREATE_NO_WINDOW | windows.CREATE_UNICODE_ENVIRONMENT | windows.EXTENDED_STARTUPINFO_PRESENT)
	err = windows.CreateProcess(app, command, nil, nil, true, flags, &block[0], directory, &si.StartupInfo, &pi)
	runtime.KeepAlive(handles)
	if err != nil {
		return nil, err
	}
	defer windows.CloseHandle(pi.Thread)
	// The child cannot run (or create descendants) until it belongs to the Job Object.
	if err := windows.AssignProcessToJobObject(job, pi.Process); err != nil {
		killErr := windows.TerminateProcess(pi.Process, 1)
		windows.WaitForSingleObject(pi.Process, 3000)
		windows.CloseHandle(pi.Process)
		return nil, errors.Join(fmt.Errorf("assign job: %w", err), killErr)
	}
	if _, err := windows.ResumeThread(pi.Thread); err != nil {
		killErr := windows.TerminateJobObject(job, 1)
		windows.WaitForSingleObject(pi.Process, 3000)
		windows.CloseHandle(pi.Process)
		return nil, errors.Join(err, killErr)
	}
	success = true
	return &windowsProcess{job: job, handle: pi.Process, pid: pi.ProcessId}, nil
}

func (p *windowsProcess) PID() int { return int(p.pid) }
func (p *windowsProcess) Poll() (bool, int64, error) {
	status, err := windows.WaitForSingleObject(p.handle, 0)
	if err != nil {
		return false, 0, err
	}
	if status == uint32(windows.WAIT_TIMEOUT) {
		return false, 0, nil
	}
	if status != windows.WAIT_OBJECT_0 {
		return false, 0, fmt.Errorf("unexpected process wait status %d", status)
	}
	var code uint32
	err = windows.GetExitCodeProcess(p.handle, &code)
	return err == nil, int64(code), err
}

func (p *windowsProcess) TreeEmpty() (bool, error) {
	var stats struct {
		TotalUserTime, TotalKernelTime, PeriodUserTime, PeriodKernelTime int64
		PageFaults, TotalProcesses, ActiveProcesses, TerminatedProcesses uint32
	}
	err := windows.QueryInformationJobObject(p.job, windows.JobObjectBasicAccountingInformation, uintptr(unsafe.Pointer(&stats)), uint32(unsafe.Sizeof(stats)), nil)
	return err == nil && stats.ActiveProcesses == 0, err
}

// No universal graceful signal exists for a console-less Windows executable.
// Run allows the configured grace period, then terminates the entire job.
func (p *windowsProcess) Stop() error { return nil }
func (p *windowsProcess) Kill() error { return windows.TerminateJobObject(p.job, 1) }
func (p *windowsProcess) Close() error {
	return errors.Join(windows.CloseHandle(p.handle), windows.CloseHandle(p.job))
}
