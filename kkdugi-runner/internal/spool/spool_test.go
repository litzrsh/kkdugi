package spool

import (
	"context"
	"errors"
	"kkdugi-runner/internal/client"
	"kkdugi-runner/internal/credential"
	"kkdugi-runner/internal/state"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
	"unicode/utf8"
)

func testSpool(t *testing.T, limit int64) (*Spool, *state.Store) {
	t.Helper()
	ctx := context.Background()
	dir := filepath.Join(t.TempDir(), "data")
	s, e := state.Open(ctx, dir, state.Identity{BaseURL: "https://example.test/api/v1.0/batch-agent", RunnerID: "BR1"})
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { s.Close() })
	q, e := s.PrepareSession(ctx, "test")
	if e != nil {
		t.Fatal(e)
	}
	e = s.ConfirmSession(ctx, q, client.SessionResponse{RunnerID: "BR1", Session: "1", ServerTime: client.Timestamp(time.Now().UTC().Format(time.RFC3339)), HeartbeatSeconds: 1, PollSeconds: 1, LeaseSeconds: 60, Capacity: 1, Limits: client.Limits{JSONBytes: 1 << 20, InputBytes: 1 << 18, ResultBytes: 1 << 18, LogChunkBytes: 32768}})
	if e != nil {
		t.Fatal(e)
	}
	_, e = s.Commit(ctx, state.Mutation{Assignment: &state.AssignmentChange{ID: "BA1", NextPhase: state.Received, Snapshot: []byte(`{"id":"BA1"}`), Detail: []byte(`{}`)}})
	if e != nil {
		t.Fatal(e)
	}
	spool, e := New(Options{Directory: filepath.Join(dir, "spool"), Store: s, ChunkBytes: 16, AttemptBytes: limit})
	if e != nil {
		t.Fatal(e)
	}
	return spool, s
}
func TestMaskEveryReadBoundary(t *testing.T) {
	raw := []byte("한글 abcdef 비밀🙂 password END\xff")
	values := []string{"abcd", "cdef", "비밀🙂", "password"}
	for step := 1; step <= len(raw); step++ {
		m := newMasker(values)
		out := ""
		for at := 0; at < len(raw); {
			n := min(len(raw)-at, step)
			out += m.feed(raw[at:at+n], false)
			at += n
		}
		out += m.feed(nil, true)
		if out != "한글 ****** ********** ******** END�" {
			t.Fatalf("read step %d changed non-secret bytes: %q", step, out)
		}
		if !utf8.ValidString(out) || strings.Contains(out, "abcd") || strings.Contains(out, "cdef") || strings.Contains(out, "password") || strings.Contains(out, "비밀") || !strings.Contains(out, "******") {
			t.Fatalf("step %d: %q", step, out)
		}
	}
}
func TestDurableChunksAckAndReopen(t *testing.T) {
	sp, s := testSpool(t, 0)
	out, stderr, e := sp.Open("BA1", []string{"secret"})
	if e != nil {
		t.Fatal(e)
	}
	raw := strings.Repeat("한글 secret data\n", 10)
	for _, b := range []byte(raw) {
		out.Write([]byte{b})
	}
	end := out.Close()
	stderr.Close()
	if end.Status != "COMPLETE" || end.LastSequence == nil {
		t.Fatal(end)
	}
	files, e := s.LogFiles(context.Background())
	if e != nil || len(files) < 2 {
		t.Fatal(e, files)
	}
	all := ""
	for _, f := range files {
		c, e := sp.Read(f)
		if e != nil || len(c.Text) > 16 || !utf8.ValidString(c.Text) {
			t.Fatal(e)
		}
		all += c.Text
	}
	if strings.Contains(all, "secret") || !strings.Contains(all, "******") {
		t.Fatal(all)
	}
	zero := client.Decimal("0")
	if e = sp.Ack("BA1", "STDOUT", &zero); e != nil {
		t.Fatal(e)
	}
	if e = sp.Ack("BA1", "STDOUT", &zero); e != nil {
		t.Fatal(e)
	}
	left, _ := s.LogFiles(context.Background())
	if len(left) != len(files)-1 {
		t.Fatal("deleted past contiguous ACK")
	}
	if _, e = New(sp.o); e != nil {
		t.Fatal(e)
	}
	tooFar := client.Decimal("999")
	if sp.Ack("BA1", "STDOUT", &tooFar) == nil {
		t.Fatal("accepted impossible offset")
	}
	if e = sp.Ack("BA1", "STDOUT", end.LastSequence); e != nil {
		t.Fatal(e)
	}
	left, _ = s.LogFiles(context.Background())
	if len(left) != 0 {
		t.Fatal(left)
	}
}
func TestLimitAndStorageFailureStillDrain(t *testing.T) {
	t.Run("attempt", func(t *testing.T) {
		sp, _ := testSpool(t, 24)
		out, errout, e := sp.Open("BA1", nil)
		if e != nil {
			t.Fatal(e)
		}
		b := []byte(strings.Repeat("x", 1<<20))
		n, e := out.Write(b)
		if n != len(b) || e != nil {
			t.Fatal(n, e)
		}
		if end := out.Close(); end.Status != "TRUNCATED" {
			t.Fatal(end)
		}
		errout.Close()
	})
	t.Run("local", func(t *testing.T) {
		sp, _ := testSpool(t, 0)
		sp.o.MaxBytes = 1
		out, errout, e := sp.Open("BA1", nil)
		if e != nil {
			t.Fatal(e)
		}
		n, e := out.Write([]byte(strings.Repeat("x", 100)))
		if n != 100 || e != nil {
			t.Fatal(n, e)
		}
		if out.Close().Status != "LOST" || sp.Healthy() {
			t.Fatal("storage failure not latched")
		}
		errout.Close()
	})
	t.Run("missing", func(t *testing.T) {
		sp, s := testSpool(t, 0)
		out, errout, e := sp.Open("BA1", nil)
		if e != nil {
			t.Fatal(e)
		}
		out.Write([]byte("durable log"))
		out.Close()
		errout.Close()
		files, _ := s.LogFiles(context.Background())
		os.Remove(sp.path(files[0]))
		if _, e = New(sp.o); !errors.Is(e, ErrCorrupt) {
			t.Fatal(e)
		}
	})
}
func TestCrashTailAndOrphanAreNotUploaded(t *testing.T) {
	sp, s := testSpool(t, 0)
	out, _, e := sp.Open("BA1", nil)
	if e != nil {
		t.Fatal(e)
	}
	out.Write([]byte("tail")) // no finalized chunk
	// This simulates the file durable / metadata not committed boundary.
	orphan := sp.path(state.LogFile{AssignmentID: "BA1", Stream: "STDOUT", Sequence: 0})
	f, e := credential.OpenPrivateFile(orphan, true)
	if e != nil {
		t.Fatal(e)
	}
	f.Write([]byte(`{"masked":"***"}`))
	f.Sync()
	f.Close()
	if _, e = New(sp.o); e != nil {
		t.Fatal(e)
	}
	streams, _ := s.LogStreams(context.Background())
	for _, v := range streams {
		if !v.Closed || v.Status != "LOST" {
			t.Fatal(v)
		}
	}
	files, _ := s.LogFiles(context.Background())
	if len(files) != 0 {
		t.Fatal("uploaded uncommitted tail")
	}
}
