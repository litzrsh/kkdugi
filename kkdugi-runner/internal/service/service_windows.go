//go:build windows

package service

import (
	"context"
	"errors"
	"golang.org/x/sys/windows/svc"
	"time"
)

type handler struct{ run func(context.Context) error }

func (h handler) Execute(_ []string, requests <-chan svc.ChangeRequest, status chan<- svc.Status) (bool, uint32) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	current := svc.Status{State: svc.StartPending, WaitHint: 10000}
	status <- current
	done := make(chan error, 1)
	go func() { done <- h.run(ctx) }()
	current = svc.Status{State: svc.Running, Accepts: svc.AcceptStop | svc.AcceptShutdown | svc.AcceptPreShutdown}
	status <- current
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case err := <-done:
			if err != nil && !(ctx.Err() != nil && errors.Is(err, context.Canceled)) {
				return true, 1
			}
			return false, 0
		case request, ok := <-requests:
			if !ok {
				cancel()
				requests = nil
				current = svc.Status{State: svc.StopPending, CheckPoint: 1, WaitHint: 45000}
				status <- current
				continue
			}
			switch request.Cmd {
			case svc.Interrogate:
				status <- current
			case svc.Stop, svc.Shutdown, svc.PreShutdown:
				if current.State != svc.StopPending {
					current = svc.Status{State: svc.StopPending, CheckPoint: 1, WaitHint: 45000}
					status <- current
					cancel()
				}
			}
		case <-ticker.C:
			if current.State == svc.StopPending {
				current.CheckPoint++
				status <- current
			}
		}
	}
}

// SCM owns process lifetime. Running means the agent loop is alive, not that
// admin has approved this runner or that recovery has completed.
func Run(name string, run func(context.Context) error) error {
	if err := ValidateName(name); err != nil {
		return err
	}
	if run == nil {
		return errors.New("missing service runtime")
	}
	isService, err := svc.IsWindowsService()
	if err != nil {
		return err
	}
	if !isService {
		return errors.New("service command requires Windows Service Control Manager")
	}
	return svc.Run(name, handler{run: run})
}
