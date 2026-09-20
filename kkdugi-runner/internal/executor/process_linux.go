package executor

import (
	"errors"
	"os"
	"os/exec"
	"syscall"
)

type linuxProcess struct {
	cmd  *exec.Cmd
	wait chan error
	done bool
	code int64
}

func startProcess(s Spec, out, stderr *os.File) (process, error) {
	cmd := exec.Command(s.Executable, s.Arguments...)
	cmd.Dir, cmd.Env, cmd.Stdout, cmd.Stderr = s.Directory, append([]string{}, s.Environment...), out, stderr
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if err := beforeStart(s); err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	p := &linuxProcess{cmd: cmd, wait: make(chan error, 1)}
	go func() { p.wait <- cmd.Wait() }()
	return p, nil
}

func (p *linuxProcess) PID() int { return p.cmd.Process.Pid }
func (p *linuxProcess) Poll() (bool, int64, error) {
	if !p.done {
		select {
		case err := <-p.wait:
			var exitErr *exec.ExitError
			if err != nil && !errors.As(err, &exitErr) {
				return false, 0, err
			}
			p.done, p.code = true, int64(p.cmd.ProcessState.ExitCode())
		default:
		}
	}
	return p.done, p.code, nil
}
func (p *linuxProcess) TreeEmpty() (bool, error) {
	err := syscall.Kill(-p.PID(), 0)
	if errors.Is(err, syscall.ESRCH) {
		return true, nil
	}
	return false, err
}
func (p *linuxProcess) signal(sig syscall.Signal) error {
	err := syscall.Kill(-p.PID(), sig)
	if errors.Is(err, syscall.ESRCH) {
		return nil
	}
	return err
}
func (p *linuxProcess) Stop() error  { return p.signal(syscall.SIGTERM) }
func (p *linuxProcess) Kill() error  { return p.signal(syscall.SIGKILL) }
func (p *linuxProcess) Close() error { return nil }
