package agent

import (
	"context"
	"encoding/json"
	"encoding/pem"
	"errors"
	"kkdugi-runner/internal/client"
	"kkdugi-runner/internal/config"
	"kkdugi-runner/internal/state"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestLogsSurviveCompletionAndRestart(t *testing.T) {
	f := newAdmin(t)
	q := assignment(t, "BA1", "echo", 0, 2)
	f.queue = []client.Assignment{q}
	f.holdLogs = true
	a, s := newTestAgent(t, f, 1)
	cancel, done := runAgent(t, a)
	eventually(t, func() bool { return acked(s, 1) })
	files, _ := s.LogFiles(context.Background())
	if len(files) == 0 {
		t.Fatal("completion discarded unACKed logs")
	}
	first := files[0]
	c, e := a.spool.Read(first)
	if e != nil {
		t.Fatal(e)
	}
	cancel()
	<-done
	s.Close()
	reopened, e := state.Open(context.Background(), a.o.Config.DataDir, state.Identity{BaseURL: a.o.Client.BaseURL(), RunnerID: "BR1"})
	if e != nil {
		t.Fatal(e)
	}
	defer reopened.Close()
	o := a.o
	o.Store = reopened
	next, e := New(o)
	if e != nil {
		t.Fatal(e)
	}
	f.mu.Lock()
	f.holdLogs = false
	f.lost["log"] = true
	f.lost["reconcile"] = true
	f.mu.Unlock()
	stop, _ := runAgent(t, next)
	defer stop()
	eventually(t, func() bool { v, e := reopened.LogFiles(context.Background()); return e == nil && len(v) == 0 })
	f.mu.Lock()
	defer f.mu.Unlock()
	stored := f.logs[first.AssignmentID+"/"+first.Stream][first.Sequence]
	if stored != c {
		t.Fatal("replay changed immutable chunk")
	}
	if countStarts(t, q) != 1 {
		t.Fatal("restart duplicated execution")
	}
	if len(f.reconcileObservations) != 1 || f.reconcileObservations[0].Observation != "FINISHED" {
		t.Fatal(f.reconcileObservations)
	}
}
func TestLiveReconcileKeepsExistingProcess(t *testing.T) {
	f := newAdmin(t)
	q := assignment(t, "BA1", "echo", 2500, 4)
	f.queue = []client.Assignment{q}
	a, s := newTestAgent(t, f, 1)
	runAgent(t, a)
	eventually(t, func() bool { return countStarts(t, q) == 1 })
	f.mu.Lock()
	f.forceReconcile = true
	f.mu.Unlock()
	eventually(t, func() bool { f.mu.Lock(); defer f.mu.Unlock(); return len(f.reconcileObservations) > 0 })
	eventually(t, func() bool { return acked(s, 1) })
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.reconcileObservations[0].Observation != "RUNNING" || f.completed[q.ID].Outcome != "SUCCEEDED" || countStarts(t, q) != 1 {
		t.Fatal("existing execution not reconciled")
	}
}
func TestFinishedRecoveryReportsSameCompletion(t *testing.T) {
	f := newAdmin(t)
	q := assignment(t, "BA1", "echo", 0, 2)
	f.queue = []client.Assignment{q}
	f.holdCompletion = true
	a, s := newTestAgent(t, f, 1)
	cancel, done := runAgent(t, a)
	eventually(t, func() bool {
		v, e := s.Assignment(context.Background(), q.ID)
		return e == nil && v.Phase == state.Finished
	})
	saved, _ := s.Assignment(context.Background(), q.ID)
	cancel()
	<-done
	s.Close()
	reopened, e := state.Open(context.Background(), a.o.Config.DataDir, state.Identity{BaseURL: a.o.Client.BaseURL(), RunnerID: "BR1"})
	if e != nil {
		t.Fatal(e)
	}
	defer reopened.Close()
	o := a.o
	o.Store = reopened
	next, e := New(o)
	if e != nil {
		t.Fatal(e)
	}
	f.mu.Lock()
	f.holdCompletion = false
	f.reportRecovered = true
	f.mu.Unlock()
	runAgent(t, next)
	eventually(t, func() bool { return acked(reopened, 1) })
	after, _ := reopened.Assignment(context.Background(), q.ID)
	if string(saved.Detail) != string(after.Detail) {
		t.Fatal("completion mutated")
	}
	if countStarts(t, q) != 1 {
		t.Fatal("restarted work")
	}
}

// A real runner subprocess is killed at durable boundaries, then restarted against
// the same SQLite/spool/identity and the still-running TLS fake admin.
func TestCrashRunnerHelper(t *testing.T) {
	path := os.Getenv("R7_HELPER_CONFIG")
	if path == "" {
		return
	}
	b, e := os.ReadFile(path)
	if e != nil {
		os.Exit(81)
	}
	var cfg config.Config
	if json.Unmarshal(b, &cfg) != nil {
		os.Exit(82)
	}
	ca, _ := os.ReadFile(path + ".pem")
	api, e := client.New(client.Options{BaseURL: os.Getenv("R7_HELPER_URL"), CAPEM: ca, Timeout: time.Second})
	if e != nil {
		os.Exit(83)
	}
	s, e := state.Open(context.Background(), cfg.DataDir, state.Identity{BaseURL: api.BaseURL(), RunnerID: "BR1"})
	if e != nil {
		os.Exit(84)
	}
	a, e := New(Options{Config: &cfg, Store: s, Client: api, Token: "test-access", Version: "test", DrainTimeout: time.Millisecond})
	if e != nil {
		os.Exit(85)
	}
	a.pollInterval = 10 * time.Millisecond
	a.heartbeatInterval = 20 * time.Millisecond
	a.boundary = func(at string) {
		if at == os.Getenv("R7_HELPER_BOUNDARY") {
			os.WriteFile(path+".ready", []byte(at), 0600)
			select {}
		}
	}
	if a.Run(context.Background()) != nil {
		os.Exit(86)
	}
	os.Exit(0)
}
func TestRunnerKilledAtExecutionBoundaries(t *testing.T) {
	for _, boundary := range []string{"received", "start-intent", "started", "exited", "finished"} {
		t.Run(boundary, func(t *testing.T) {
			f := newAdmin(t)
			q := assignment(t, "BA1", "echo", 1000, 3)
			f.queue = []client.Assignment{q}
			a, s := newTestAgent(t, f, 1)
			s.Close()
			p := filepath.Join(t.TempDir(), "helper.json")
			os.WriteFile(p, encode(a.o.Config), 0600)
			os.WriteFile(p+".pem", pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: f.server.Certificate().Raw}), 0600)
			exe, _ := os.Executable()
			cmd := exec.Command(exe, "-test.run=^TestCrashRunnerHelper$")
			cmd.Env = append(os.Environ(), "R7_HELPER_CONFIG="+p, "R7_HELPER_URL="+a.o.Client.BaseURL(), "R7_HELPER_BOUNDARY="+boundary)
			if e := cmd.Start(); e != nil {
				t.Fatal(e)
			}
			defer func() { cmd.Process.Kill(); cmd.Wait() }()
			eventually(t, func() bool { _, e := os.Stat(p + ".ready"); return e == nil })
			if boundary == "started" {
				eventually(t, func() bool { return countStarts(t, q) == 1 })
			}
			cmd.Process.Kill()
			cmd.Wait()
			reopened, e := state.Open(context.Background(), a.o.Config.DataDir, state.Identity{BaseURL: a.o.Client.BaseURL(), RunnerID: "BR1"})
			if e != nil {
				t.Fatal(e)
			}
			defer reopened.Close()
			o := a.o
			o.Store = reopened
			next, e := New(o)
			if e != nil {
				t.Fatal(e)
			}
			cancel, done := runAgent(t, next)
			expected := "UNKNOWN"
			if boundary == "received" {
				expected = "NEVER_STARTED"
			}
			if boundary == "finished" {
				expected = "FINISHED"
			}
			eventually(t, func() bool { f.mu.Lock(); defer f.mu.Unlock(); return len(f.reconcileObservations) > 0 })
			f.mu.Lock()
			actual := f.reconcileObservations[0].Observation
			f.mu.Unlock()
			if actual != expected {
				t.Fatalf("%s want %s", actual, expected)
			}
			if expected == "UNKNOWN" {
				eventually(t, func() bool {
					v, e := reopened.Assignment(context.Background(), q.ID)
					return e == nil && v.Phase == state.Unknown && !next.recovering.Load() && next.Snapshot().FreeSlots == 0
				})
			} else {
				eventually(t, func() bool { return acked(reopened, 1) })
			}
			cancel()
			err := <-done
			if err != nil && !errors.Is(err, context.Canceled) {
				t.Fatal(err)
			}
			want := 0
			if boundary == "started" || boundary == "exited" || boundary == "finished" {
				want = 1
			}
			if countStarts(t, q) != want {
				t.Fatal("unexpected spawn count")
			}
			// Linux cannot reattach exit collection after runner death; bounded child ends itself.
			if boundary == "started" {
				time.Sleep(1100 * time.Millisecond)
			}
		})
	}
}
func TestTokenIsMaskedInDurableLogs(t *testing.T) {
	f := newAdmin(t)
	q := assignment(t, "BA1", "token", 0, 2)
	f.queue = []client.Assignment{q}
	f.holdLogs = true
	a, s := newTestAgent(t, f, 1)
	runAgent(t, a)
	eventually(t, func() bool { return acked(s, 1) })
	files, _ := s.LogFiles(context.Background())
	if len(files) == 0 {
		t.Fatal("no log to verify")
	}
	for _, v := range files {
		c, e := a.spool.Read(v)
		if e != nil || strings.Contains(c.Text, "test-access") || !strings.Contains(c.Text, "***********") {
			t.Fatal("token leaked", e)
		}
	}
}

func TestExpiredClaimIsInventoriedBeforeNewWork(t *testing.T) {
	f := newAdmin(t)
	q := assignment(t, "BA1", "echo", 0, 2)
	f.queue = []client.Assignment{q}
	f.lost["claim"] = true
	f.expireClaim = true
	a, _ := newTestAgent(t, f, 1)
	runAgent(t, a)
	eventually(t, func() bool { f.mu.Lock(); defer f.mu.Unlock(); return len(f.reconcileObservations) > 0 })
	eventually(t, func() bool { return !a.recovering.Load() && a.Snapshot().FreeSlots == 0 })
	if countStarts(t, q) != 0 {
		t.Fatal("expired claim was executed")
	}
}
func TestStartConflictReconcilesWithoutSpawn(t *testing.T) {
	f := newAdmin(t)
	q := assignment(t, "BA1", "echo", 0, 2)
	f.queue = []client.Assignment{q}
	f.rejectStart = true
	a, s := newTestAgent(t, f, 1)
	runAgent(t, a)
	eventually(t, func() bool { return acked(s, 1) })
	if countStarts(t, q) != 0 {
		t.Fatal("rejected start spawned")
	}
}
func TestLogFileFailureStopsNewClaimsButPreservesOutcome(t *testing.T) {
	f := newAdmin(t)
	q := assignment(t, "BA1", "echo", 500, 2)
	next := assignment(t, "BA2", "echo", 0, 2)
	f.queue = []client.Assignment{q, next}
	a, s := newTestAgent(t, f, 1)
	runAgent(t, a)
	eventually(t, func() bool { return countStarts(t, q) == 1 })
	if e := os.Mkdir(filepath.Join(a.o.Config.DataDir, "spool", "BA1.STDOUT.0.json.tmp"), 0700); e != nil {
		t.Fatal(e)
	}
	eventually(t, func() bool { return acked(s, 1) })
	if !a.diskBlocked.Load() || a.Snapshot().FreeSlots != 0 || countStarts(t, next) != 0 {
		t.Fatal("storage failure allowed claim")
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	c := f.completed[q.ID]
	if c.Outcome != "SUCCEEDED" || c.Logs["STDOUT"].Status != "LOST" {
		t.Fatal(c)
	}
}

func TestLogWireOutOfOrderDuplicateAndConflict(t *testing.T) {
	f := newAdmin(t)
	a, _ := newTestAgent(t, f, 1)
	f.session = 1
	c := client.LogChunk{EmittedAt: now(), Text: "한글", SHA256: client.LogHash("한글")}
	ack, e := a.o.Client.UploadLog(context.Background(), a.o.Token, "1", "BA1", "STDOUT", "1", c)
	if e != nil || ack.ContiguousThrough != nil {
		t.Fatal(ack, e)
	}
	ack, e = a.o.Client.UploadLog(context.Background(), a.o.Token, "1", "BA1", "STDOUT", "0", c)
	if e != nil || ack.ContiguousThrough == nil || *ack.ContiguousThrough != "1" {
		t.Fatal(ack, e)
	}
	ack, e = a.o.Client.UploadLog(context.Background(), a.o.Token, "1", "BA1", "STDOUT", "1", c)
	if e != nil || *ack.ContiguousThrough != "1" {
		t.Fatal(ack, e)
	}
	c.Text = "changed"
	c.SHA256 = client.LogHash(c.Text)
	_, e = a.o.Client.UploadLog(context.Background(), a.o.Token, "1", "BA1", "STDOUT", "1", c)
	var api *client.APIError
	if !errors.As(e, &api) || api.Status != 409 {
		t.Fatal("conflicting chunk accepted", e)
	}
}
