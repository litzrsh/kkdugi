package catalog

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
	"time"

	"kkdugi-runner/internal/client"
	"kkdugi-runner/internal/config"
	"kkdugi-runner/internal/state"
)

func fixture(t *testing.T) config.Program {
	t.Helper()
	dir := t.TempDir()
	path := filepath.Join(dir, "program")
	data := []byte("executable fixture")
	if err := os.WriteFile(path, data, 0700); err != nil {
		t.Fatal(err)
	}
	h := sha256.Sum256(data)
	return config.Program{Code: "daily", Version: "1.0", Revision: "rev1", Manifest: config.Manifest{Executable: path, WorkingDirectory: dir, InputContract: "1", InputMode: "JSON_FILE", Files: []config.FileDigest{{Path: path, SHA256: hex.EncodeToString(h[:])}}}}
}
func open(t *testing.T, dir, base string) *state.Store {
	t.Helper()
	s, err := state.Open(context.Background(), dir, state.Identity{BaseURL: base, RunnerID: "BR1"})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}
func activate(t *testing.T, s *state.Store) {
	t.Helper()
	ctx := context.Background()
	q, err := s.PrepareSession(ctx, "v1")
	if err != nil {
		t.Fatal(err)
	}
	n, _ := strconv.ParseInt(string(q.ExpectedSession), 10, 64)
	err = s.ConfirmSession(ctx, q, client.SessionResponse{RunnerID: "BR1", Session: client.Decimal(strconv.FormatInt(n+1, 10)), ServerTime: client.Timestamp(time.Now().UTC().Format(time.RFC3339)), HeartbeatSeconds: 10, PollSeconds: 3, LeaseSeconds: 60, Capacity: 1, Limits: client.Limits{JSONBytes: 1 << 20, InputBytes: 1 << 18, ResultBytes: 1 << 18, LogChunkBytes: 1 << 15}})
	if err != nil {
		t.Fatal(err)
	}
}

type fakeReporter struct {
	mu       sync.Mutex
	reports  []client.ProgramReport
	keys     []string
	response func(client.ProgramReport) (client.ProgramApproval, error)
}

func (f *fakeReporter) ReportProgram(_ context.Context, _ client.Secret, _ client.Decimal, key string, _ client.Timestamp, _ string, b []byte) (client.ProgramApproval, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	var r client.ProgramReport
	if json.Unmarshal(b, &r) != nil {
		return client.ProgramApproval{}, errors.New("bad fixture request")
	}
	f.reports = append(f.reports, r)
	f.keys = append(f.keys, key)
	return f.response(r)
}
func allowed(r client.ProgramReport) (client.ProgramApproval, error) {
	rev := r.Revision
	return client.ProgramApproval{ProgramID: "BP1", Revision: rev, ApprovedRevision: &rev, Enabled: true, Runnable: r.Availability == "AVAILABLE"}, nil
}
func setup(t *testing.T) (*Catalog, *state.Store, config.Program, string) {
	t.Helper()
	dir := filepath.Join(t.TempDir(), "state")
	s := open(t, dir, "https://admin.example/api/v1.0/batch-agent")
	activate(t, s)
	f := &fakeReporter{response: allowed}
	c := New(s, f, "test-token")
	p := fixture(t)
	if err := c.Refresh(context.Background(), []config.Program{p}); err != nil {
		t.Fatal(err)
	}
	return c, s, p, dir
}

func TestApprovalGateAndFileRecheck(t *testing.T) {
	ctx := context.Background()
	c, s, p, _ := setup(t)
	if _, err := c.Resolve(ctx, "BP1", p.Code, p.Version, p.Revision); !errors.Is(err, ErrNotApproved) {
		t.Fatal(err)
	}
	if err := c.Publish(ctx, p.Code); err != nil {
		t.Fatal(err)
	}
	resolved, err := c.Resolve(ctx, "BP1", p.Code, p.Version, p.Revision)
	if err != nil {
		t.Fatal(err)
	}
	resolved.Manifest.Files[0].SHA256 = "changed"
	if _, err = c.Resolve(ctx, "OTHER", p.Code, p.Version, p.Revision); !errors.Is(err, ErrNotApproved) {
		t.Fatal(err)
	}
	if _, err = c.Resolve(ctx, "BP1", p.Code, "2.0", p.Revision); !errors.Is(err, ErrNotApproved) {
		t.Fatal(err)
	}
	if _, err = c.Resolve(ctx, "BP1", p.Code, p.Version, p.Revision); err != nil {
		t.Fatal("returned slice aliased", err)
	}
	if err = os.WriteFile(p.Manifest.Executable, []byte("tampered"), 0700); err != nil {
		t.Fatal(err)
	}
	if _, err = c.Resolve(ctx, "BP1", p.Code, p.Version, p.Revision); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	stored, err := s.Program(ctx, p.Code)
	if err != nil || stored.Report.Availability != "INVALID" || stored.Approval != nil {
		t.Fatal("invalid approval retained", err)
	}
	if err = c.Publish(ctx, p.Code); err != nil {
		t.Fatal(err)
	}
	if err = os.Remove(p.Manifest.Executable); err != nil {
		t.Fatal(err)
	}
	if err = c.Refresh(ctx, []config.Program{p}); err != nil {
		t.Fatal(err)
	}
	stored, _ = s.Program(ctx, p.Code)
	if stored.Report.Availability != "MISSING" {
		t.Fatal(stored.Report.Availability)
	}
}

func TestRevisionHistoryMissingAndRestart(t *testing.T) {
	ctx := context.Background()
	c, s, p, dir := setup(t)
	if err := c.Publish(ctx, p.Code); err != nil {
		t.Fatal(err)
	}
	if err := c.Refresh(ctx, nil); err != nil {
		t.Fatal(err)
	}
	if err := c.Publish(ctx, p.Code); err != nil {
		t.Fatal(err)
	}
	stored, _ := s.Program(ctx, p.Code)
	if stored.Report.Availability != "MISSING" || stored.Report.Manifest.Executable != p.Manifest.Executable {
		t.Fatal("missing lost manifest")
	}
	codes, err := s.ProgramCodes(ctx, "", 200)
	if err != nil || len(codes) != 1 {
		t.Fatal(err)
	}
	s.Close()
	s = open(t, dir, "https://admin.example/api/v1.0/batch-agent")
	activate(t, s)
	c = New(s, &fakeReporter{response: allowed}, "test-token")
	changed := p
	changed.Version = "changed"
	if err = c.Refresh(ctx, []config.Program{changed}); !errors.Is(err, state.ErrConflict) {
		t.Fatal("revision overwritten after restart", err)
	}
	if err = c.Refresh(ctx, []config.Program{p}); err != nil {
		t.Fatal(err)
	}
	if _, err = c.Resolve(ctx, "BP1", p.Code, p.Version, p.Revision); !errors.Is(err, ErrNotApproved) {
		t.Fatal("old approval reused", err)
	}
	if err = c.Publish(ctx, p.Code); err != nil {
		t.Fatal(err)
	}
	if _, err = c.Resolve(ctx, "BP1", p.Code, p.Version, p.Revision); err != nil {
		t.Fatal(err)
	}
	changed.Revision = "rev2"
	if err = c.Refresh(ctx, []config.Program{changed}); err != nil {
		t.Fatal(err)
	}
	if _, err = c.Resolve(ctx, "BP1", p.Code, changed.Version, changed.Revision); !errors.Is(err, ErrNotApproved) {
		t.Fatal(err)
	}
	changed.Revision = "rev1"
	if err = c.Refresh(ctx, []config.Program{changed}); !errors.Is(err, state.ErrConflict) {
		t.Fatal("history overwritten", err)
	}
}

func TestRefreshIsAtomic(t *testing.T) {
	ctx := context.Background()
	c, s, p, _ := setup(t)
	newProgram := fixture(t)
	newProgram.Code = "new"
	changed := p
	changed.Manifest.Arguments = []string{"different"}
	if err := c.Refresh(ctx, []config.Program{newProgram, changed}); !errors.Is(err, state.ErrConflict) {
		t.Fatal(err)
	}
	if _, err := s.Program(ctx, "new"); !errors.Is(err, state.ErrNotFound) {
		t.Fatal("partial refresh", err)
	}
	changed = p
	changed.Code = "../invalid"
	if err := c.Refresh(ctx, []config.Program{changed}); !errors.Is(err, ErrConfiguration) {
		t.Fatal(err)
	}
	stored, _ := s.Program(ctx, p.Code)
	if stored.Report.Availability != "AVAILABLE" {
		t.Fatal("bad config removed program")
	}
}

func TestLostResponseAndStaleInstallationAck(t *testing.T) {
	ctx := context.Background()
	c, s, p, _ := setup(t)
	f := c.api.(*fakeReporter)
	f.response = func(client.ProgramReport) (client.ProgramApproval, error) {
		return client.ProgramApproval{}, errors.New("response lost")
	}
	if err := c.Publish(ctx, p.Code); err == nil {
		t.Fatal("expected loss")
	}
	changed := p
	changed.Revision = "rev2"
	if err := c.Refresh(ctx, []config.Program{changed}); err != nil {
		t.Fatal(err)
	}
	f.response = allowed
	if err := c.Publish(ctx, p.Code); err != nil {
		t.Fatal(err)
	} // resolves original rev1 request
	if f.keys[0] != f.keys[1] || f.reports[1].Revision != "rev1" {
		t.Fatal("request replaced")
	}
	stored, _ := s.Program(ctx, p.Code)
	if stored.Approval != nil {
		t.Fatal("stale response approved current installation")
	}
	if err := c.Publish(ctx, p.Code); err != nil {
		t.Fatal(err)
	}
	if f.keys[2] == f.keys[1] || f.reports[2].Revision != "rev2" {
		t.Fatal("new snapshot not reported")
	}
	if _, err := c.Resolve(ctx, "BP1", p.Code, p.Version, "rev2"); err != nil {
		t.Fatal(err)
	}
}

func TestRealTLSProgramReport(t *testing.T) {
	ctx := context.Background()
	observedHeaders := make(chan http.Header, 1)
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		observedHeaders <- r.Header.Clone()
		if r.Method != "PUT" || r.URL.Path != "/api/v1.0/batch-agent/programs/daily" {
			t.Error("wrong endpoint")
		}
		var report client.ProgramReport
		if json.NewDecoder(r.Body).Decode(&report) != nil {
			t.Error("invalid body")
		}
		if report.Manifest.Arguments == nil || report.Manifest.SecretNames == nil {
			t.Error("null arrays")
		}
		response, _ := allowed(report)
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()
	base := server.URL + "/api/v1.0/batch-agent"
	s := open(t, filepath.Join(t.TempDir(), "state"), base)
	activate(t, s)
	api, err := client.New(client.Options{BaseURL: base, Timeout: time.Second, CAPEM: pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: server.Certificate().Raw})})
	if err != nil {
		t.Fatal(err)
	}
	defer api.Close()
	c := New(s, api, "test-access-token")
	p := fixture(t)
	if err = c.Refresh(ctx, []config.Program{p}); err != nil {
		t.Fatal(err)
	}
	if err = c.Publish(ctx, p.Code); err != nil {
		t.Fatal(err)
	}
	headers := <-observedHeaders
	if headers.Get("Authorization") != "Bearer test-access-token" || headers.Get("X-Runner-Session") != "1" || headers.Get("Idempotency-Key") == "" || headers.Get("X-Request-Created-At") == "" {
		t.Fatal("missing durable headers")
	}
	if _, err = c.Resolve(ctx, "BP1", p.Code, p.Version, p.Revision); err != nil {
		t.Fatal(err)
	}
}

func TestApprovalRevocationAndRestart(t *testing.T) {
	ctx := context.Background()
	c, s, p, dir := setup(t)
	if err := c.Publish(ctx, p.Code); err != nil {
		t.Fatal(err)
	}
	f := c.api.(*fakeReporter)
	f.response = func(r client.ProgramReport) (client.ProgramApproval, error) {
		a, _ := allowed(r)
		a.Enabled = false
		a.Runnable = false
		return a, nil
	}
	if err := c.Publish(ctx, p.Code); err != nil {
		t.Fatal(err)
	}
	if _, err := c.Resolve(ctx, "BP1", p.Code, p.Version, p.Revision); !errors.Is(err, ErrNotApproved) {
		t.Fatal("revocation ignored", err)
	}
	f.response = allowed
	if err := c.Publish(ctx, p.Code); err != nil {
		t.Fatal(err)
	}
	s.Close()
	s = open(t, dir, "https://admin.example/api/v1.0/batch-agent")
	activate(t, s)
	c = New(s, f, "test-token")
	if _, err := c.Resolve(ctx, "BP1", p.Code, p.Version, p.Revision); !errors.Is(err, ErrNotApproved) {
		t.Fatal("old boot approval reused", err)
	}
	if err := c.Publish(ctx, p.Code); err != nil {
		t.Fatal(err)
	}
	f.response = func(client.ProgramReport) (client.ProgramApproval, error) {
		return client.ProgramApproval{}, errors.New("offline")
	}
	if err := c.Publish(ctx, p.Code); err == nil {
		t.Fatal("offline report succeeded")
	}
	if _, err := c.Resolve(ctx, "BP1", p.Code, p.Version, p.Revision); !errors.Is(err, ErrNotApproved) {
		t.Fatal("old approval used after failed refresh", err)
	}
}
