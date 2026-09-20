package state

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"kkdugi-runner/internal/client"
	"testing"
)

func TestCanceledUncommittedTransactionDoesNotPoisonStore(t *testing.T) {
	s, _ := newStore(t)
	activate(t, s)
	ctx, cancel := context.WithCancel(context.Background())
	err := s.transact(ctx, func(tx *sql.Tx) error { cancel(); return ErrStorage })
	if !errors.Is(err, context.Canceled) || s.faulted {
		t.Fatal("cancellation poisoned store", err)
	}
	receive(t, s, "AFTER_CANCEL")
}

func TestReconcileJournalTransferAndNoStartReplay(t *testing.T) {
	ctx := context.Background()
	s, dir := newStore(t)
	activate(t, s)
	toPermitted(t, s, "BA1")
	advance(t, s, "BA1", StartIntent, nil, nil)
	s.Close()
	s = openStore(t, dir)
	activate(t, s)
	body := []byte(`{"previousSession":"1","observation":"UNKNOWN","process":null,"completion":null}`)
	r, e := s.PrepareReconcile(ctx, "BA1", body)
	if e != nil {
		t.Fatal(e)
	}
	again, e := s.PrepareReconcile(ctx, "BA1", []byte(`{"different":true}`))
	if e != nil || again.Key != r.Key || string(again.Body) != string(body) {
		t.Fatal("lost request changed", e)
	}
	ack := []byte(`{"id":"BA1","disposition":"HOLD","session":"2","leaseUntil":null,"startAllowed":false}`)
	if e = s.ApplyReconcile(ctx, r, ack); e != nil {
		t.Fatal(e)
	}
	if e = s.ApplyReconcile(ctx, r, ack); e != nil {
		t.Fatal(e)
	}
	saved, e := s.Assignment(ctx, "BA1")
	if e != nil || saved.Session != "2" || saved.Phase != Unknown || !saved.StartIntent {
		t.Fatal(saved, e)
	}
	_, e = s.Commit(ctx, Mutation{Assignment: &AssignmentChange{ID: saved.ID, ExpectedVersion: saved.Version, ExpectedPhase: saved.Phase, NextPhase: StartIntent, Detail: json.RawMessage(`{}`)}})
	if !errors.Is(e, ErrConflict) {
		t.Fatal("reconcile allowed spawn", e)
	}
	if e = s.ApplyReconcile(ctx, r, []byte(`{"id":"BA1","disposition":"HOLD","session":"2","leaseUntil":null,"startAllowed":true}`)); e == nil {
		t.Fatal("new start permission accepted")
	}
}
func TestReconcileRollbackPreservesOwnership(t *testing.T) {
	ctx := context.Background()
	s, _ := newStore(t)
	activate(t, s)
	receive(t, s, "BA1")
	r, e := s.PrepareReconcile(ctx, "BA1", []byte(`{"previousSession":"1","observation":"NEVER_STARTED","process":null,"completion":null}`))
	if e != nil {
		t.Fatal(e)
	}
	if _, e = s.conn.ExecContext(ctx, "CREATE TRIGGER reject_recovery BEFORE UPDATE OF applied ON kkdugi_runner_reconcile BEGIN SELECT RAISE(ABORT,'injected failure'); END"); e != nil {
		t.Fatal(e)
	}
	e = s.ApplyReconcile(ctx, r, []byte(`{"id":"BA1","disposition":"RESOLVED","session":"1","leaseUntil":null,"startAllowed":false}`))
	if e == nil {
		t.Fatal("expected failed commit")
	}
	var phase string
	var applied int
	s.conn.QueryRowContext(ctx, "SELECT phase FROM kkdugi_runner_assignment WHERE id='BA1'").Scan(&phase)
	s.conn.QueryRowContext(ctx, "SELECT applied FROM kkdugi_runner_reconcile WHERE id=?", r.ID).Scan(&applied)
	if phase != string(Received) || applied != 0 {
		t.Fatal("partial recovery transaction", phase, applied)
	}
}
func TestResolvedLostClaimCannotBeReplayed(t *testing.T) {
	s, _ := newStore(t)
	activate(t, s)
	r := commit(t, s, claim())
	if e := s.ResolveClaims(context.Background()); e != nil {
		t.Fatal(e)
	}
	if _, e := s.BeginSend(context.Background(), r.ID); !errors.Is(e, ErrConflict) {
		t.Fatal("old claim replayed", e)
	}
	commit(t, s, claim())
}
func TestLogAckBoundsAndMonotonicity(t *testing.T) {
	s, _ := newStore(t)
	activate(t, s)
	receive(t, s, "BA1")
	ctx := context.Background()
	if e := s.OpenLogs(ctx, "BA1"); e != nil {
		t.Fatal(e)
	}
	for n := int64(0); n < 3; n++ {
		if e := s.AppendLog(ctx, LogFile{AssignmentID: "BA1", Stream: "STDOUT", Sequence: n, Hash: client.LogHash("log"), Bytes: 100}, 3); e != nil {
			t.Fatal(e)
		}
	}
	if s.AckLog(ctx, "BA1", "STDOUT", 3) == nil {
		t.Fatal("accepted future ACK")
	}
	if e := s.AckLog(ctx, "BA1", "STDOUT", 1); e != nil {
		t.Fatal(e)
	}
	if e := s.AckLog(ctx, "BA1", "STDOUT", 0); e != nil {
		t.Fatal(e)
	}
	streams, _ := s.LogStreams(ctx)
	for _, v := range streams {
		if v.Stream == "STDOUT" && v.Acked != 1 {
			t.Fatal("ACK regressed")
		}
	}
	if s.ForgetLogFile(ctx, LogFile{AssignmentID: "BA1", Stream: "STDOUT", Sequence: 2}) == nil {
		t.Fatal("deleted unACKed chunk")
	}
}
