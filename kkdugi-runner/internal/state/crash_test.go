package state

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// This subprocess blocks exactly before or after a real SQLite COMMIT. The parent
// kills it without running defers, so recovery exercises WAL and kernel locks.
func TestStateProcessHelper(t *testing.T) {
	mode := os.Getenv("KKDUGI_R3_HELPER")
	if mode == "" {
		return
	}
	s, err := Open(context.Background(), os.Getenv("KKDUGI_R3_DIR"), testIdentity)
	if err != nil {
		t.Fatal(err)
	}
	if mode == "lock" {
		fmt.Println("ready")
		select {}
	}
	activate(t, s)
	var mutation Mutation
	if strings.HasPrefix(mode, "intent-") {
		toPermitted(t, s, "BA1")
		a, _ := s.Assignment(context.Background(), "BA1")
		mutation = Mutation{Assignment: &AssignmentChange{ID: a.ID, ExpectedVersion: a.Version, ExpectedPhase: a.Phase, NextPhase: StartIntent, Detail: json.RawMessage(`{}`)}}
	} else {
		receive(t, s, "BA1")
		r := commit(t, s, claim())
		if _, err = s.BeginSend(context.Background(), r.ID); err != nil {
			t.Fatal(err)
		}
		mutation = Mutation{Assignment: &AssignmentChange{ID: "BA1", ExpectedVersion: 1, ExpectedPhase: Received, NextPhase: Prepared, Detail: json.RawMessage(`{"prepared":true}`)}, Request: &RequestDraft{Method: "PUT", Path: "/programs/test", Body: json.RawMessage(`{"revision":9007199254740993}`)}, Ack: &Acknowledgement{RequestID: r.ID}}
	}
	point := "before-commit"
	if strings.HasSuffix(mode, "after") {
		point = "after-commit"
	}
	s.boundary = func(p string) {
		if p == point {
			fmt.Println("ready")
			select {}
		}
	}
	if _, err = s.Commit(context.Background(), mutation); err != nil {
		t.Fatal(err)
	}
	t.Fatal("crash boundary was not reached")
}

func childAtBoundary(t *testing.T, dir, mode string) *exec.Cmd {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	t.Cleanup(cancel)
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.CommandContext(ctx, executable, "-test.run=^TestStateProcessHelper$", "-test.timeout=25s")
	cmd.Env = append(os.Environ(), "KKDUGI_R3_HELPER="+mode, "KKDUGI_R3_DIR="+dir)
	out, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	cmd.Stderr = os.Stderr
	if err = cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if cmd.ProcessState == nil {
			cmd.Process.Kill()
			cmd.Wait()
		}
	})
	line, err := bufio.NewReader(out).ReadString('\n')
	if err != nil || strings.TrimSpace(line) != "ready" {
		t.Fatalf("helper not ready: %q %v", line, err)
	}
	return cmd
}
func killChild(t *testing.T, cmd *exec.Cmd) {
	t.Helper()
	if err := cmd.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	if err := cmd.Wait(); err == nil {
		t.Fatal("expected killed process")
	}
}

func TestProcessLockReleasedAfterKill(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "state")
	child := childAtBoundary(t, dir, "lock")
	if s, err := Open(context.Background(), dir, testIdentity); !errors.Is(err, ErrLocked) {
		if s != nil {
			s.Close()
		}
		t.Fatal("second process accepted", err)
	}
	killChild(t, child)
	s := openStore(t, dir)
	if second, err := Open(context.Background(), dir, testIdentity); !errors.Is(err, ErrLocked) {
		if second != nil {
			second.Close()
		}
		t.Fatal("same process duplicate", err)
	}
	s.Close()
	if _, err := os.Stat(filepath.Join(dir, "runner.lock")); err != nil {
		t.Fatal("lock file should remain", err)
	}
	openStore(t, dir)
}

func TestCrashCommitBoundaries(t *testing.T) {
	for _, mode := range []string{"transaction-before", "transaction-after", "intent-before", "intent-after"} {
		t.Run(mode, func(t *testing.T) {
			dir := filepath.Join(t.TempDir(), "state")
			child := childAtBoundary(t, dir, mode)
			killChild(t, child)
			s := openStore(t, dir)
			a, err := s.Assignment(context.Background(), "BA1")
			if err != nil {
				t.Fatal(err)
			}
			after := strings.HasSuffix(mode, "after")
			if strings.HasPrefix(mode, "transaction") {
				want := Received
				if after {
					want = Prepared
				}
				if a.Phase != want {
					t.Fatal(a.Phase, want)
				}
				requests, err := s.Requests(context.Background(), "", 200)
				if err != nil {
					t.Fatal(err)
				}
				wantCount := 1
				if after {
					wantCount = 2
				}
				if len(requests) != wantCount {
					t.Fatal("partial request commit", len(requests))
				}
				for _, r := range requests {
					if r.Path == "/assignments/claim" {
						wantStatus := "IN_FLIGHT"
						if after {
							wantStatus = "ACKED"
						}
						if r.Status != wantStatus {
							t.Fatal("partial ACK", r.Status)
						}
					}
				}
			} else {
				want := Permitted
				if after {
					want = StartIntent
				}
				if a.Phase != want || a.StartIntent != after {
					t.Fatal("intent did not match commit")
				}
				activate(t, s)
				if _, err = s.Commit(context.Background(), Mutation{Assignment: &AssignmentChange{ID: a.ID, ExpectedVersion: a.Version, ExpectedPhase: a.Phase, NextPhase: StartIntent, Detail: json.RawMessage(`{}`)}}); !errors.Is(err, ErrSession) {
					t.Fatal("recovered worker could start", err)
				}
			}
		})
	}
}
