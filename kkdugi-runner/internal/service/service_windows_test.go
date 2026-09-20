//go:build windows

package service

import (
	"context"
	"errors"
	"golang.org/x/sys/windows/svc"
	"testing"
	"time"
)

func TestHandlerStopDrainsBeforeExit(t *testing.T) {
	requests := make(chan svc.ChangeRequest)
	statuses := make(chan svc.Status, 8)
	canceled := make(chan struct{})
	release := make(chan struct{})
	done := make(chan uint32, 1)
	h := handler{run: func(ctx context.Context) error { <-ctx.Done(); close(canceled); <-release; return ctx.Err() }}
	go func() { _, code := h.Execute(nil, requests, statuses); done <- code }()
	if (<-statuses).State != svc.StartPending || (<-statuses).State != svc.Running {
		t.Fatal("startup states")
	}
	requests <- svc.ChangeRequest{Cmd: svc.Stop}
	if (<-statuses).State != svc.StopPending {
		t.Fatal("stop state")
	}
	<-canceled
	select {
	case <-done:
		t.Fatal("returned before drain")
	default:
	}
	requests <- svc.ChangeRequest{Cmd: svc.Interrogate}
	if (<-statuses).State != svc.StopPending {
		t.Fatal("interrogate")
	}
	close(release)
	select {
	case code := <-done:
		if code != 0 {
			t.Fatal(code)
		}
	case <-time.After(time.Second):
		t.Fatal("drain did not finish")
	}
}
func TestHandlerReportsFailure(t *testing.T) {
	h := handler{run: func(context.Context) error { return errors.New("failed") }}
	status := make(chan svc.Status, 4)
	specific, code := h.Execute(nil, make(chan svc.ChangeRequest), status)
	if !specific || code == 0 {
		t.Fatal("hidden failure")
	}
}
