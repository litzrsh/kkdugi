package state

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
	"time"

	"kkdugi-runner/internal/client"
)

var testIdentity = Identity{BaseURL: "https://admin.example/api/v1.0/batch-agent", RunnerID: "BR000000000000000001"}

func newStore(t *testing.T) (*Store, string) {
	t.Helper()
	dir := filepath.Join(t.TempDir(), "state")
	s := openStore(t, dir)
	return s, dir
}
func openStore(t *testing.T, dir string) *Store {
	t.Helper()
	s, err := Open(context.Background(), dir, testIdentity)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}
func sessionResult(input client.SessionRequest) client.SessionResponse {
	n, _ := strconv.ParseInt(string(input.ExpectedSession), 10, 64)
	return client.SessionResponse{RunnerID: testIdentity.RunnerID, Session: client.Decimal(strconv.FormatInt(n+1, 10)), ServerTime: stamp(time.Now()), HeartbeatSeconds: 10, PollSeconds: 3, LeaseSeconds: 60, Capacity: 1, Limits: client.Limits{JSONBytes: 1 << 20, InputBytes: 1 << 18, ResultBytes: 1 << 18, LogChunkBytes: 1 << 15}}
}
func activate(t *testing.T, s *Store) {
	t.Helper()
	q, err := s.PrepareSession(context.Background(), "0.1.0")
	if err != nil {
		t.Fatal(err)
	}
	if err = s.ConfirmSession(context.Background(), q, sessionResult(q)); err != nil {
		t.Fatal(err)
	}
}
func claim() Mutation {
	return Mutation{Request: &RequestDraft{Method: "POST", Path: "/assignments/claim", Body: json.RawMessage(`{ "big":9007199254740993,"v":1 }`)}}
}
func commit(t *testing.T, s *Store, m Mutation) Request {
	t.Helper()
	r, err := s.Commit(context.Background(), m)
	if err != nil {
		t.Fatal(err)
	}
	return r
}
func receive(t *testing.T, s *Store, id string) {
	t.Helper()
	commit(t, s, Mutation{Assignment: &AssignmentChange{ID: id, NextPhase: Received, Snapshot: json.RawMessage(`{"input":{"n":9007199254740993}}`), Detail: json.RawMessage(`{}`)}})
}
func advance(t *testing.T, s *Store, id string, to Phase, request *RequestDraft, ack *Acknowledgement) Request {
	t.Helper()
	a, err := s.Assignment(context.Background(), id)
	if err != nil {
		t.Fatal(err)
	}
	return commit(t, s, Mutation{Assignment: &AssignmentChange{ID: id, ExpectedVersion: a.Version, ExpectedPhase: a.Phase, NextPhase: to, Detail: json.RawMessage(`{}`)}, Request: request, Ack: ack})
}
func toPermitted(t *testing.T, s *Store, id string) {
	receive(t, s, id)
	advance(t, s, id, Prepared, nil, nil)
	r := advance(t, s, id, StartRequested, &RequestDraft{AssignmentID: id, Method: "POST", Path: "/assignments/" + id + "/start", Body: json.RawMessage(`{}`)}, nil)
	if _, err := s.BeginSend(context.Background(), r.ID); err != nil {
		t.Fatal(err)
	}
	advance(t, s, id, Permitted, nil, &Acknowledgement{RequestID: r.ID, Response: json.RawMessage(`{"startAllowed":true}`)})
}

func TestSessionRecoveryAndStaleReplay(t *testing.T) {
	ctx := context.Background()
	s, dir := newStore(t)
	if _, err := s.Commit(ctx, claim()); !errors.Is(err, ErrSession) {
		t.Fatal(err)
	}
	old, err := s.PrepareSession(ctx, "0.1.0")
	if err != nil {
		t.Fatal(err)
	}
	s.Close()
	s = openStore(t, dir)
	resumed, err := s.PrepareSession(ctx, "0.2.0")
	if err != nil || resumed != old {
		t.Fatalf("pending changed: %v", err)
	}
	wrong := sessionResult(old)
	wrong.Session = "9"
	if err = s.ConfirmSession(ctx, old, wrong); !errors.Is(err, ErrConflict) {
		t.Fatal(err)
	}
	if err = s.ConfirmSession(ctx, old, sessionResult(old)); err != nil {
		t.Fatal(err)
	}
	if _, err = s.Commit(ctx, claim()); !errors.Is(err, ErrSession) {
		t.Fatal("previous boot became active", err)
	}
	activate(t, s)
	r := commit(t, s, claim())
	if r.Session != "2" {
		t.Fatal(r.Session)
	}
	first, err := s.BeginSend(ctx, r.ID)
	if err != nil {
		t.Fatal(err)
	}
	second, err := s.BeginSend(ctx, r.ID)
	if err != nil || first.Key != second.Key || first.CreatedAt != second.CreatedAt || !bytes.Equal(first.Body, second.Body) {
		t.Fatal("retry mutated request", err)
	}
	s.Close()
	s = openStore(t, dir)
	if _, err = s.BeginSend(ctx, r.ID); !errors.Is(err, ErrSession) {
		t.Fatal(err)
	}
	activate(t, s)
	if _, err = s.BeginSend(ctx, r.ID); !errors.Is(err, ErrSession) {
		t.Fatal("stale request sent", err)
	}
	retained, err := s.Request(ctx, r.ID)
	if err != nil || retained.Session != "2" || retained.Hash != digest(claim().Request.Body) {
		t.Fatal("lost original", err)
	}
	if _, err = s.Commit(ctx, Mutation{Ack: &Acknowledgement{RequestID: r.ID}}); !errors.Is(err, ErrSession) {
		t.Fatal("stale ACK accepted", err)
	}
}

func TestRequestDurabilityAndClaimExclusion(t *testing.T) {
	ctx := context.Background()
	s, dir := newStore(t)
	activate(t, s)
	r := commit(t, s, claim())
	r.Body[0] = 'x'
	saved, err := s.Request(ctx, r.ID)
	if err != nil || !bytes.Equal(saved.Body, claim().Request.Body) {
		t.Fatal("alias modified durable body", err)
	}
	if _, err = s.Commit(ctx, claim()); !errors.Is(err, ErrConflict) {
		t.Fatal("two claims", err)
	}
	if _, err = s.Commit(ctx, Mutation{Ack: &Acknowledgement{RequestID: r.ID}}); !errors.Is(err, ErrConflict) {
		t.Fatal("unsent ack", err)
	}
	if _, err = s.BeginSend(ctx, r.ID); err != nil {
		t.Fatal(err)
	}
	if err = s.ScheduleRetry(ctx, r.ID, stamp(time.Now().Add(time.Hour))); err != nil {
		t.Fatal(err)
	}
	if _, err = s.BeginSend(ctx, r.ID); !errors.Is(err, ErrNotDue) {
		t.Fatal(err)
	}
	if err = s.ScheduleRetry(ctx, r.ID, stamp(time.Now().Add(-time.Second))); err != nil {
		t.Fatal(err)
	}
	commit(t, s, Mutation{Ack: &Acknowledgement{RequestID: r.ID}})
	commit(t, s, Mutation{Ack: &Acknowledgement{RequestID: r.ID}}) // identical 204 ACK
	if _, err = s.Commit(ctx, Mutation{Ack: &Acknowledgement{RequestID: r.ID, Response: json.RawMessage(`{"changed":true}`)}}); !errors.Is(err, ErrConflict) {
		t.Fatal(err)
	}
	commit(t, s, claim())
	if stringsContains(fmt.Sprintf("%v %#v", saved, saved), "9007199254740993") {
		t.Fatal("request leaked")
	}
	s.Close()
	s = openStore(t, dir)
	stored, err := s.Request(ctx, r.ID)
	if err != nil || stored.Status != "ACKED" || stored.Key != r.Key {
		t.Fatal(err)
	}
	page, err := s.Requests(ctx, "", 1)
	if err != nil || len(page) != 1 {
		t.Fatal(err)
	}
	page2, err := s.Requests(ctx, page[0].ID, 1)
	if err != nil || len(page2) != 1 || page2[0].ID == page[0].ID {
		t.Fatal(err)
	}
}
func stringsContains(s, sub string) bool { return bytes.Contains([]byte(s), []byte(sub)) }

func TestJournalAtomicityAndStartIntent(t *testing.T) {
	ctx := context.Background()
	s, dir := newStore(t)
	activate(t, s)
	toPermitted(t, s, "BA1")
	a, _ := s.Assignment(ctx, "BA1")
	m := Mutation{Assignment: &AssignmentChange{ID: a.ID, ExpectedVersion: a.Version, ExpectedPhase: a.Phase, NextPhase: StartIntent, Detail: json.RawMessage(`{}`)}}
	var wg sync.WaitGroup
	out := make(chan error, 8)
	for range 8 {
		wg.Add(1)
		go func() { defer wg.Done(); _, e := s.Commit(ctx, m); out <- e }()
	}
	wg.Wait()
	close(out)
	wins := 0
	for e := range out {
		if e == nil {
			wins++
		} else if !errors.Is(e, ErrConflict) {
			t.Fatal(e)
		}
	}
	if wins != 1 {
		t.Fatal("intent winners", wins)
	}
	a, _ = s.Assignment(ctx, "BA1")
	if !a.StartIntent || a.Phase != StartIntent {
		t.Fatal("lost intent")
	}
	// Failing ACK rolls back both the phase and the new completion request.
	_, err := s.Commit(ctx, Mutation{Assignment: &AssignmentChange{ID: a.ID, ExpectedVersion: a.Version, ExpectedPhase: a.Phase, NextPhase: Finished, Detail: json.RawMessage(`{}`)}, Request: &RequestDraft{AssignmentID: a.ID, Method: "POST", Path: "/assignments/BA1/completion", Body: json.RawMessage(`{}`)}, Ack: &Acknowledgement{RequestID: "missing"}})
	if !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	after, _ := s.Assignment(ctx, a.ID)
	if after.Version != a.Version || after.Phase != StartIntent {
		t.Fatal("partial phase update")
	}
	requests, _ := s.Requests(ctx, "", 200)
	if len(requests) != 1 {
		t.Fatal("partial request insertion")
	}
	s.Close()
	s = openStore(t, dir)
	activate(t, s)
	if _, err = s.Commit(ctx, Mutation{Assignment: &AssignmentChange{ID: a.ID, ExpectedVersion: a.Version, ExpectedPhase: a.Phase, NextPhase: Running, Detail: json.RawMessage(`{}`)}}); !errors.Is(err, ErrSession) {
		t.Fatal("old boot modified", err)
	}
	after, _ = s.Assignment(ctx, a.ID)
	if !after.StartIntent || after.Phase != StartIntent {
		t.Fatal("reopen cleared intent")
	}
}

func TestCompletionImmutabilityAndAck(t *testing.T) {
	ctx := context.Background()
	s, _ := newStore(t)
	activate(t, s)
	receive(t, s, "BA1")
	r := advance(t, s, "BA1", Finished, &RequestDraft{AssignmentID: "BA1", Method: "POST", Path: "/assignments/BA1/completion", Body: json.RawMessage(`{}`)}, nil)
	if _, err := s.BeginSend(ctx, r.ID); err != nil {
		t.Fatal(err)
	}
	a, _ := s.Assignment(ctx, "BA1")
	if _, err := s.Commit(ctx, Mutation{Assignment: &AssignmentChange{ID: a.ID, ExpectedVersion: a.Version, ExpectedPhase: a.Phase, NextPhase: Acked, Detail: json.RawMessage(`{"changed":true}`)}, Ack: &Acknowledgement{RequestID: r.ID}}); !errors.Is(err, ErrConflict) {
		t.Fatal(err)
	}
	advance(t, s, "BA1", Acked, nil, &Acknowledgement{RequestID: r.ID})
	a, _ = s.Assignment(ctx, "BA1")
	saved, _ := s.Request(ctx, r.ID)
	if a.Phase != Acked || saved.Status != "ACKED" {
		t.Fatal("ack not atomic")
	}
}

func TestStateIdentitySchemaAndMissingFiles(t *testing.T) {
	for _, mode := range []string{"identity", "missing", "empty", "corrupt", "future", "checksum"} {
		t.Run(mode, func(t *testing.T) {
			s, dir := newStore(t)
			if mode == "future" {
				if _, err := s.conn.ExecContext(context.Background(), "PRAGMA user_version=99"); err != nil {
					t.Fatal(err)
				}
			}
			if mode == "checksum" {
				if _, err := s.conn.ExecContext(context.Background(), "UPDATE kkdugi_runner_migration SET checksum='changed'"); err != nil {
					t.Fatal(err)
				}
			}
			s.Close()
			p := filepath.Join(dir, "state.db")
			switch mode {
			case "missing":
				if err := os.Remove(p); err != nil {
					t.Fatal(err)
				}
			case "empty":
				if err := os.Truncate(p, 0); err != nil {
					t.Fatal(err)
				}
			case "corrupt":
				if err := os.WriteFile(p, []byte("not SQLite"), 0600); err != nil {
					t.Fatal(err)
				}
			}
			identity := testIdentity
			if mode == "identity" {
				identity.RunnerID = "OTHER"
			}
			opened, err := Open(context.Background(), dir, identity)
			if err == nil {
				opened.Close()
				t.Fatal("bad state accepted")
			}
			if mode == "missing" {
				if _, err := os.Stat(p); !errors.Is(err, os.ErrNotExist) {
					t.Fatal("missing DB replaced")
				}
			}
		})
	}
}

func TestMigrationRollback(t *testing.T) {
	s, _ := newStore(t)
	// Exercise the same transactional DDL boundary used by migrations.
	err := s.transact(context.Background(), func(tx *sql.Tx) error {
		if _, e := tx.Exec("CREATE TABLE migration_probe(id INTEGER)"); e != nil {
			return e
		}
		return errors.New("injected migration failure")
	})
	if err == nil {
		t.Fatal("fault ignored")
	}
	var n int
	if err = s.conn.QueryRowContext(context.Background(), "SELECT count(*) FROM sqlite_master WHERE name='migration_probe'").Scan(&n); err != nil || n != 0 {
		t.Fatal("DDL not rolled back", err)
	}
}

func TestInvalidInputs(t *testing.T) {
	s, _ := newStore(t)
	activate(t, s)
	for _, r := range []RequestDraft{{Method: "POST", Path: "/sessions", Body: json.RawMessage(`{}`)}, {Method: "POST", Path: "/assignments/claim?token=x", Body: json.RawMessage(`{}`)}, {Method: "POST", Path: "/assignments/claim", Body: json.RawMessage(`[]`)}, {Method: "POST", Path: "/assignments/claim", Body: append([]byte(`{"x":"`), bytes.Repeat([]byte("x"), 1<<20)...)}} {
		if _, err := s.Commit(context.Background(), Mutation{Request: &r}); !errors.Is(err, ErrInvalid) {
			t.Fatal(err)
		}
	}
	if _, err := s.Requests(context.Background(), "", 201); !errors.Is(err, ErrInvalid) {
		t.Fatal(err)
	}
}

func TestDeferredCompletionAndBigSession(t *testing.T) {
	s, _ := newStore(t)
	ctx := context.Background()
	if _, err := s.conn.ExecContext(ctx, "UPDATE kkdugi_runner_meta SET session='9007199254740993',boot_id='95358b21-7e8b-4f51-b7a4-93b1d0a4ab73'"); err != nil {
		t.Fatal(err)
	}
	activate(t, s)
	receive(t, s, "BA1")
	advance(t, s, "BA1", Finished, nil, nil) // offline: no idempotency timestamp yet
	requests, _ := s.Requests(ctx, "", 200)
	if len(requests) != 0 {
		t.Fatal("premature request")
	}
	r := commit(t, s, Mutation{Request: &RequestDraft{AssignmentID: "BA1", Method: "POST", Path: "/assignments/BA1/completion", Body: json.RawMessage(`{}`)}})
	if r.Session != "9007199254740994" {
		t.Fatal("session rounded", r.Session)
	}
	if _, err := s.Commit(ctx, Mutation{Request: &RequestDraft{AssignmentID: "BA1", Method: "POST", Path: "/assignments/BA1/completion", Body: json.RawMessage(`{"different":true}`)}}); !errors.Is(err, ErrConflict) {
		t.Fatal(err)
	}
}

func TestSQLiteFullRollsBackAndStopsWrites(t *testing.T) {
	s, dir := newStore(t)
	ctx := context.Background()
	activate(t, s)
	receive(t, s, "BA1")
	var pages int
	if err := s.conn.QueryRowContext(ctx, "PRAGMA page_count").Scan(&pages); err != nil {
		t.Fatal(err)
	}
	if _, err := s.conn.ExecContext(ctx, fmt.Sprintf("PRAGMA max_page_count=%d", pages)); err != nil {
		t.Fatal(err)
	}
	body := json.RawMessage(`{"payload":"` + string(bytes.Repeat([]byte("x"), 100000)) + `"}`)
	_, err := s.Commit(ctx, Mutation{Assignment: &AssignmentChange{ID: "BA1", ExpectedVersion: 1, ExpectedPhase: Received, NextPhase: Prepared, Detail: json.RawMessage(`{}`)}, Request: &RequestDraft{Method: "PUT", Path: "/programs/test", Body: body}})
	if !errors.Is(err, ErrStorage) {
		t.Fatal("SQLite FULL not reported as storage failure", err)
	}
	if _, err = s.Commit(ctx, claim()); !errors.Is(err, ErrStorage) {
		t.Fatal("writes continued after failure", err)
	}
	s.Close()
	s = openStore(t, dir)
	a, err := s.Assignment(ctx, "BA1")
	if err != nil || a.Phase != Received || a.Version != 1 {
		t.Fatal("partial update after disk full", err)
	}
	r, err := s.Requests(ctx, "", 200)
	if err != nil || len(r) != 0 {
		t.Fatal("partial request after disk full", err)
	}
}

func TestStoragePathsAndHashCorruption(t *testing.T) {
	t.Run("symlink", func(t *testing.T) {
		root := t.TempDir()
		target := filepath.Join(root, "target")
		if err := os.Mkdir(target, 0700); err != nil {
			t.Fatal(err)
		}
		link := filepath.Join(root, "link")
		if err := os.Symlink(target, link); err != nil {
			t.Skip("symlink unavailable:", err)
		}
		if s, err := Open(context.Background(), filepath.Join(link, "state"), testIdentity); err == nil {
			s.Close()
			t.Fatal("link accepted")
		}
	})
	t.Run("hash", func(t *testing.T) {
		s, _ := newStore(t)
		activate(t, s)
		r := commit(t, s, claim())
		if _, err := s.conn.ExecContext(context.Background(), "UPDATE kkdugi_runner_request SET body=? WHERE id=?", []byte(`{"changed":true}`), r.ID); err != nil {
			t.Fatal(err)
		}
		if _, err := s.BeginSend(context.Background(), r.ID); !errors.Is(err, ErrSchema) {
			t.Fatal("corrupted body sent", err)
		}
	})
	t.Run("pending-session", func(t *testing.T) {
		s, dir := newStore(t)
		if _, err := s.PrepareSession(context.Background(), "v1"); err != nil {
			t.Fatal(err)
		}
		if _, err := s.conn.ExecContext(context.Background(), "UPDATE kkdugi_runner_meta SET pending_hash='changed'"); err != nil {
			t.Fatal(err)
		}
		s.Close()
		if opened, err := Open(context.Background(), dir, testIdentity); !errors.Is(err, ErrSchema) {
			if opened != nil {
				opened.Close()
			}
			t.Fatal(err)
		}
	})
}

func TestAckAndNextClaimAreAtomic(t *testing.T) {
	s, _ := newStore(t)
	ctx := context.Background()
	activate(t, s)
	first := commit(t, s, claim())
	if _, err := s.BeginSend(ctx, first.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Commit(ctx, Mutation{Ack: &Acknowledgement{RequestID: first.ID}, Request: &RequestDraft{Method: "POST", Path: "/invalid", Body: json.RawMessage(`{}`)}}); !errors.Is(err, ErrInvalid) {
		t.Fatal(err)
	}
	retained, _ := s.Request(ctx, first.ID)
	if retained.Status != "IN_FLIGHT" {
		t.Fatal("ACK was not rolled back")
	}
	second := commit(t, s, Mutation{Ack: &Acknowledgement{RequestID: first.ID}, Request: claim().Request})
	retained, _ = s.Request(ctx, first.ID)
	if retained.Status != "ACKED" || second.Status != "PENDING" || second.Key == first.Key {
		t.Fatal("claim replacement not atomic")
	}
}
