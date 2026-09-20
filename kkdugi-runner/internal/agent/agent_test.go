package agent

import (
	"context"
	"database/sql"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"kkdugi-runner/internal/client"
	"kkdugi-runner/internal/config"
	"kkdugi-runner/internal/credential"
	"kkdugi-runner/internal/state"
)

func TestWorkProgram(t *testing.T) {
	path := os.Getenv("KKDUGI_INPUT_FILE")
	if path == "" {
		return
	}
	b, err := os.ReadFile(path)
	if err != nil {
		os.Exit(10)
	}
	var input struct {
		Counter string `json:"counter"`
		Sleep   int    `json:"sleep"`
		Mode    string `json:"mode"`
		Secret  bool   `json:"secret"`
	}
	if json.Unmarshal(b, &input) != nil {
		os.Exit(11)
	}
	f, err := os.OpenFile(input.Counter, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		os.Exit(12)
	}
	f.WriteString("start\n")
	f.Close()
	if os.Getenv("R5_PARENT_SECRET") != "" || os.Getenv("RUNNER_ACCESS_TOKEN") != "" {
		os.Exit(13)
	}
	if input.Secret && os.Getenv("DB_PASSWORD") != "db-only-value" {
		os.Exit(14)
	}
	if input.Mode != "silent" {
		fmt.Print("test output")
	}
	time.Sleep(time.Duration(input.Sleep) * time.Millisecond)
	result := b
	if input.Mode == "invalid" {
		result = []byte(`[]`)
	}
	if os.WriteFile(os.Getenv("KKDUGI_RESULT_FILE"), result, 0600) != nil {
		os.Exit(15)
	}
	if input.Mode == "fail" {
		os.Exit(7)
	}
	os.Exit(0)
}

type response struct {
	status int
	body   []byte
}
type fakeAdmin struct {
	mu                sync.Mutex
	server            *httptest.Server
	queue             []client.Assignment
	cache             map[string]response
	signatures        map[string]string
	lost              map[string]bool
	counts            map[string]int
	completed         map[string]client.Completion
	active, maxActive int
	session           int
	boot              string
	holdCompletion    bool
	expired           bool
	stop              bool
	deny              bool
	offline           bool
	unknown           []string
}

func newAdmin(t *testing.T) *fakeAdmin {
	t.Helper()
	f := &fakeAdmin{cache: map[string]response{}, signatures: map[string]string{}, lost: map[string]bool{}, counts: map[string]int{}, completed: map[string]client.Completion{}}
	f.server = httptest.NewTLSServer(http.HandlerFunc(f.handle))
	t.Cleanup(f.server.Close)
	return f
}
func (f *fakeAdmin) handle(w http.ResponseWriter, r *http.Request) {
	b, err := io.ReadAll(r.Body)
	if err != nil {
		return
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	path := strings.TrimPrefix(r.URL.Path, "/api/v1.0/batch-agent")
	f.counts[path]++
	w.Header().Set("Content-Type", "application/json")
	if f.offline {
		http.Error(w, "admin unavailable", 503)
		return
	}
	if r.Header.Get("Authorization") != "Bearer test-access" || r.Header.Get("X-Protocol-Version") != "1" {
		http.Error(w, "bad authorization", 401)
		return
	}
	if path == "/sessions" {
		var q client.SessionRequest
		if json.Unmarshal(b, &q) != nil {
			http.Error(w, "bad session", 400)
			return
		}
		if q.BootID != f.boot {
			if string(q.ExpectedSession) != strconv.Itoa(f.session) {
				http.Error(w, "stale", 409)
				return
			}
			f.session++
			f.boot = q.BootID
		}
		json.NewEncoder(w).Encode(client.SessionResponse{RunnerID: "BR1", Session: client.Decimal(strconv.Itoa(f.session)), ServerTime: now(), HeartbeatSeconds: 1, PollSeconds: 1, LeaseSeconds: 60, Capacity: 2, Limits: client.Limits{JSONBytes: 1 << 20, InputBytes: 1 << 18, ResultBytes: 1 << 18, LogChunkBytes: 1 << 15}})
		return
	}
	if r.Header.Get("X-Runner-Session") != strconv.Itoa(f.session) {
		http.Error(w, "bad session header", 409)
		return
	}
	if path == "/assignments" {
		items := []map[string]string{}
		for _, id := range f.unknown {
			items = append(items, map[string]string{"id": id})
		}
		json.NewEncoder(w).Encode(items)
		return
	}
	if path == "/heartbeat" {
		var hb client.Heartbeat
		if json.Unmarshal(b, &hb) != nil {
			http.Error(w, "bad heartbeat", 400)
			return
		}
		actions := []client.HeartbeatAction{}
		lease := client.Timestamp(time.Now().Add(time.Minute).UTC().Format(time.RFC3339))
		reason := "CANCEL"
		grace := 0
		for _, item := range hb.Assignments {
			a := client.HeartbeatAction{ID: item.ID, Action: "CONTINUE", LeaseUntil: &lease}
			if f.stop && (item.Phase == "RUNNING" || item.Phase == "STARTING") {
				a.Action = "STOP"
				a.StopReason = &reason
				a.GraceSeconds = &grace
			}
			actions = append(actions, a)
		}
		json.NewEncoder(w).Encode(client.HeartbeatResponse{ServerTime: now(), AcceptingAssignments: len(f.queue) > 0, Assignments: actions})
		return
	}
	key := r.Header.Get("Idempotency-Key")
	signature := path + "|" + r.Header.Get("X-Runner-Session") + "|" + r.Header.Get("X-Request-Created-At") + "|" + string(b)
	if key == "" || r.Header.Get("X-Request-Created-At") == "" {
		http.Error(w, "missing durable key", 400)
		return
	}
	if old, ok := f.signatures[key]; ok && old != signature {
		http.Error(w, "key changed", 409)
		return
	}
	f.signatures[key] = signature
	if prior, ok := f.cache[key]; ok {
		w.WriteHeader(prior.status)
		w.Write(prior.body)
		return
	}
	var reply any
	status := 200
	kind := ""
	switch {
	case strings.HasPrefix(path, "/programs/"):
		var p client.ProgramReport
		json.Unmarshal(b, &p)
		rev := p.Revision
		reply = client.ProgramApproval{ProgramID: "BP1", Revision: rev, ApprovedRevision: &rev, Enabled: !f.deny, Runnable: !f.deny && p.Availability == "AVAILABLE"}
	case path == "/assignments/claim":
		kind = "claim"
		if len(f.queue) == 0 {
			status = 204
			break
		}
		item := f.queue[0]
		f.queue = f.queue[1:]
		item.Session = client.Decimal(strconv.Itoa(f.session))
		reply = item
		f.active++
		f.maxActive = max(f.active, f.maxActive)
	case strings.HasSuffix(path, "/start"):
		kind = "start"
		id := strings.Split(path, "/")[2]
		deadline := time.Now().Add(15 * time.Second)
		if f.expired {
			deadline = time.Now().Add(-time.Second)
		}
		reply = client.StartPermit{ID: id, StartAllowed: true, StartBefore: client.Timestamp(deadline.UTC().Format(time.RFC3339)), LeaseUntil: client.Timestamp(time.Now().Add(time.Minute).UTC().Format(time.RFC3339))}
	case strings.HasSuffix(path, "/started"):
		kind = "started"
		id := strings.Split(path, "/")[2]
		reply = client.StartedAck{ID: id, State: "RUNNING", Action: "CONTINUE"}
	case strings.HasSuffix(path, "/completion"):
		kind = "completion"
		if f.holdCompletion {
			http.Error(w, "temporarily unavailable", 503)
			return
		}
		id := strings.Split(path, "/")[2]
		var c client.Completion
		if json.Unmarshal(b, &c) != nil {
			http.Error(w, "invalid completion", 400)
			return
		}
		if _, ok := f.completed[id]; !ok {
			f.active--
		}
		f.completed[id] = c
		reply = client.CompletionAck{ID: id, CompletionAccepted: true, AttemptState: c.Outcome, RunState: c.Outcome, LogState: "LOST"}
	default:
		http.Error(w, "unknown API", 404)
		return
	}
	var body []byte
	if status != 204 {
		body = encode(reply)
	}
	f.cache[key] = response{status, body}
	if f.lost[kind] {
		f.lost[kind] = false
		connection, _, err := w.(http.Hijacker).Hijack()
		if err == nil {
			connection.Close()
		}
		return
	}
	w.WriteHeader(status)
	w.Write(body)
}
func assignment(t *testing.T, id, mode string, sleep, timeout int) client.Assignment {
	t.Helper()
	counter := filepath.Join(t.TempDir(), "count")
	input := json.RawMessage(fmt.Sprintf(`{"counter":%q,"mode":%q,"sleep":%d,"big":9007199254740993}`, filepath.ToSlash(counter), mode, sleep))
	return client.Assignment{ID: id, RunID: "BX" + id, Attempt: 1, Program: client.AssignmentProgram{ID: "BP1", Code: "test", Version: "1", Revision: "rev1"}, Input: input, Execution: client.Execution{TimeoutSeconds: timeout, StopGraceSeconds: 0, BusinessKey: "test"}, LeaseUntil: client.Timestamp(time.Now().Add(time.Minute).UTC().Format(time.RFC3339)), State: "ASSIGNED"}
}
func newTestAgent(t *testing.T, f *fakeAdmin, capacity int) (*Agent, *state.Store) {
	t.Helper()
	base := f.server.URL + "/api/v1.0/batch-agent"
	dir := filepath.Join(t.TempDir(), "state")
	s, err := state.Open(context.Background(), dir, state.Identity{BaseURL: base, RunnerID: "BR1"})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	api, err := client.New(client.Options{BaseURL: base, Timeout: time.Second, CAPEM: pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: f.server.Certificate().Raw})})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(api.Close)
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	cfg := &config.Config{DataDir: dir, Capacity: capacity, Programs: []config.Program{{Code: "test", Version: "1", Revision: "rev1", Manifest: config.Manifest{Executable: executable, Arguments: []string{"-test.run=^TestWorkProgram$"}, WorkingDirectory: t.TempDir(), InputMode: "JSON_FILE", InputContract: "1"}}}}
	a, err := New(Options{Config: cfg, Store: s, Client: api, Token: "test-access", Version: "test", DrainTimeout: 200 * time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	a.pollInterval = 10 * time.Millisecond
	a.heartbeatInterval = 20 * time.Millisecond
	return a, s
}
func runAgent(t *testing.T, a *Agent) (context.CancelFunc, <-chan error) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	done := make(chan error, 1)
	go func() { done <- a.Run(ctx); close(done) }()
	t.Cleanup(func() {
		cancel()
		select {
		case <-done:
		case <-time.After(6 * time.Second):
			t.Error("agent did not stop")
		}
	})
	return cancel, done
}
func eventually(t *testing.T, fn func() bool) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if fn() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("condition did not become true")
}
func acked(s *state.Store, n int) bool {
	items, err := s.Assignments(context.Background(), "", 200)
	if err != nil || len(items) != n {
		return false
	}
	for _, item := range items {
		if item.Phase != state.Acked {
			return false
		}
	}
	return true
}
func countStarts(t *testing.T, q client.Assignment) int {
	t.Helper()
	var input struct {
		Counter string `json:"counter"`
	}
	json.Unmarshal(q.Input, &input)
	b, err := os.ReadFile(filepath.FromSlash(input.Counter))
	if errors.Is(err, os.ErrNotExist) {
		return 0
	}
	if err != nil {
		t.Fatal(err)
	}
	return strings.Count(string(b), "start\n")
}

func TestResponseLossNeverRepeatsProcess(t *testing.T) {
	t.Setenv("R5_PARENT_SECRET", "not-for-child")
	t.Setenv("RUNNER_ACCESS_TOKEN", "parent-access")
	f := newAdmin(t)
	q := assignment(t, "BA1", "echo", 100, 10)
	f.queue = []client.Assignment{q}
	for _, kind := range []string{"claim", "start", "started", "completion"} {
		f.lost[kind] = true
	}
	a, s := newTestAgent(t, f, 1)
	cancel, _ := runAgent(t, a)
	eventually(t, func() bool { return acked(s, 1) })
	cancel()
	if n := countStarts(t, q); n != 1 {
		t.Fatal("spawn count", n)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	c := f.completed[q.ID]
	if c.Outcome != "SUCCEEDED" || !strings.Contains(string(c.Result), "9007199254740993") || c.Logs["STDOUT"].Status != "LOST" {
		t.Fatalf("unexpected completion %+v", c)
	}
	for _, path := range []string{"/assignments/claim", "/assignments/BA1/start", "/assignments/BA1/started", "/assignments/BA1/completion"} {
		if f.counts[path] != 2 {
			t.Fatal(path, f.counts[path])
		}
	}
}
func TestCompletionAckHoldsSlot(t *testing.T) {
	f := newAdmin(t)
	q1 := assignment(t, "BA1", "silent", 0, 10)
	q2 := assignment(t, "BA2", "silent", 0, 10)
	f.queue = []client.Assignment{q1, q2}
	f.holdCompletion = true
	a, s := newTestAgent(t, f, 1)
	cancel, _ := runAgent(t, a)
	eventually(t, func() bool {
		item, err := s.Assignment(context.Background(), "BA1")
		return err == nil && item.Phase == state.Finished
	})
	time.Sleep(100 * time.Millisecond)
	f.mu.Lock()
	claims := f.counts["/assignments/claim"]
	f.holdCompletion = false
	f.mu.Unlock()
	if claims != 1 || a.Snapshot().FreeSlots != 0 || countStarts(t, q2) != 0 {
		t.Fatal("slot released before ACK")
	}
	eventually(t, func() bool { return acked(s, 2) })
	cancel()
}
func TestParallelCapacityAndOutcomes(t *testing.T) {
	f := newAdmin(t)
	f.queue = []client.Assignment{assignment(t, "BA1", "echo", 250, 10), assignment(t, "BA2", "fail", 250, 10), assignment(t, "BA3", "invalid", 0, 10)}
	a, s := newTestAgent(t, f, 3) // server advertises 2; effective capacity must remain 2
	cancel, _ := runAgent(t, a)
	eventually(t, func() bool { return acked(s, 3) })
	cancel()
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.maxActive != 2 {
		t.Fatal("capacity", f.maxActive)
	}
	if f.completed["BA1"].Outcome != "SUCCEEDED" || f.completed["BA2"].Outcome != "FAILED" || f.completed["BA3"].Failure.Code != "OUTPUT_INVALID" {
		t.Fatal("wrong outcomes")
	}
}
func TestStopTimeoutAndExpiredPermit(t *testing.T) {
	for _, mode := range []string{"stop", "timeout", "expired", "denied"} {
		t.Run(mode, func(t *testing.T) {
			f := newAdmin(t)
			q := assignment(t, "BA1", "echo", 3000, 1)
			f.queue = []client.Assignment{q}
			f.stop = mode == "stop"
			f.expired = mode == "expired"
			f.deny = mode == "denied"
			a, s := newTestAgent(t, f, 1)
			cancel, _ := runAgent(t, a)
			eventually(t, func() bool { return acked(s, 1) })
			cancel()
			f.mu.Lock()
			c := f.completed[q.ID]
			f.mu.Unlock()
			want := "FAILED"
			if mode == "stop" {
				want = "CANCELED"
			} else if mode == "timeout" {
				want = "TIMED_OUT"
			}
			if c.Outcome != want {
				t.Fatal(mode, c.Outcome)
			}
			if (mode == "expired" || mode == "denied") && countStarts(t, q) != 0 {
				t.Fatal("unpermitted process started")
			}
		})
	}
}

func TestAdminOutageDoesNotStopLocalTimeout(t *testing.T) {
	f := newAdmin(t)
	q := assignment(t, "BA1", "echo", 5000, 1)
	f.queue = []client.Assignment{q}
	a, s := newTestAgent(t, f, 1)
	cancel, _ := runAgent(t, a)
	eventually(t, func() bool { return countStarts(t, q) == 1 })
	f.mu.Lock()
	f.offline = true
	f.mu.Unlock()
	eventually(t, func() bool {
		item, err := s.Assignment(context.Background(), q.ID)
		return err == nil && item.Phase == state.Finished
	})
	if a.Snapshot().FreeSlots != 0 {
		t.Fatal("offline completion released slot")
	}
	f.mu.Lock()
	f.offline = false
	f.mu.Unlock()
	eventually(t, func() bool { return acked(s, 1) })
	cancel()
	f.mu.Lock()
	c := f.completed[q.ID]
	f.mu.Unlock()
	if c.Outcome != "TIMED_OUT" || countStarts(t, q) != 1 {
		t.Fatal("outage changed execution")
	}
}

func TestDrainPersistsResultAndRestartBlocksReplay(t *testing.T) {
	f := newAdmin(t)
	q := assignment(t, "BA1", "echo", 5000, 10)
	f.queue = []client.Assignment{q}
	f.holdCompletion = true
	a, s := newTestAgent(t, f, 1)
	cancel, done := runAgent(t, a)
	eventually(t, func() bool { return countStarts(t, q) == 1 })
	cancel()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("drain did not stop worker")
	}
	item, err := s.Assignment(context.Background(), q.ID)
	if err != nil || item.Phase != state.Finished {
		t.Fatal("shutdown lost result", err)
	}
	var c client.Completion
	if json.Unmarshal(item.Detail, &c) != nil || c.Outcome != "FAILED" || c.Failure.Code != "RUNNER_STOPPED" {
		t.Fatal("shutdown outcome", string(item.Detail))
	}
	s.Close()
	reopened, err := state.Open(context.Background(), a.o.Config.DataDir, state.Identity{BaseURL: a.o.Client.BaseURL(), RunnerID: "BR1"})
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	o := a.o
	o.Store = reopened
	next, err := New(o)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancelNext := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancelNext()
	if err = next.Run(ctx); !errors.Is(err, ErrRecovery) {
		t.Fatal("unresolved restart did not stop", err)
	}
	if countStarts(t, q) != 1 {
		t.Fatal("restart duplicated process")
	}
}

func TestServerUnresolvedBlocksClaim(t *testing.T) {
	f := newAdmin(t)
	f.unknown = []string{"BA_OLD"}
	f.queue = []client.Assignment{assignment(t, "BA1", "echo", 0, 1)}
	a, _ := newTestAgent(t, f, 1)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := a.Run(ctx); !errors.Is(err, ErrRecovery) {
		t.Fatal(err)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.counts["/assignments/claim"] != 0 {
		t.Fatal("claimed before recovery")
	}
}

func TestStartIntentWriteFailurePreventsSpawn(t *testing.T) {
	f := newAdmin(t)
	q := assignment(t, "BA1", "echo", 0, 10)
	f.queue = []client.Assignment{q}
	a, _ := newTestAgent(t, f, 1)
	db, err := sql.Open("sqlite", filepath.Join(a.o.Config.DataDir, "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	_, err = db.Exec(`CREATE TRIGGER fail_start_intent BEFORE UPDATE ON kkdugi_runner_assignment WHEN NEW.phase='START_INTENT' BEGIN SELECT RAISE(ABORT,'injected storage failure'); END`)
	if err != nil {
		t.Fatal(err)
	}
	_, done := runAgent(t, a)
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("storage failure ignored")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("agent did not stop")
	}
	if countStarts(t, q) != 0 {
		t.Fatal("spawn happened before successful intent commit")
	}
	var phase string
	var intent int
	if err = db.QueryRow("SELECT phase,start_intent FROM kkdugi_runner_assignment WHERE id=?", q.ID).Scan(&phase, &intent); err != nil || phase != "PERMITTED" || intent != 0 {
		t.Fatal("failed commit changed journal", phase, intent, err)
	}
}

func TestConfiguredRuntimeLoadsCredentialAndSecret(t *testing.T) {
	f := newAdmin(t)
	q := assignment(t, "BA1", "silent", 0, 10)
	q.Input = json.RawMessage(strings.TrimSuffix(string(q.Input), "}") + `,"secret":true}`)
	f.queue = []client.Assignment{q}
	a, s := newTestAgent(t, f, 1)
	s.Close()
	cfg := a.o.Config
	credentials := filepath.Join(cfg.DataDir, "credentials")
	attempt, err := credential.Begin(credentials)
	if err != nil {
		t.Fatal(err)
	}
	err = attempt.Commit(credential.Stored{SchemaVersion: 1, BaseURL: a.o.Client.BaseURL(), RunnerCode: "test-runner", Registration: client.RegistrationResponse{RunnerID: "BR1", CredentialID: "BC1", AccessToken: "test-access", Session: "0", TokenExpiresAt: client.Timestamp(time.Now().Add(time.Hour).UTC().Format(time.RFC3339))}})
	if err != nil {
		t.Fatal(err)
	}
	secretDir := filepath.Join(credentials, "secrets")
	if err = credential.EnsureDirectory(secretDir); err != nil {
		t.Fatal(err)
	}
	file, err := credential.OpenPrivateFile(filepath.Join(secretDir, "DB_PASSWORD"), true)
	if err != nil {
		t.Fatal(err)
	}
	file.WriteString("db-only-value\r\n")
	file.Sync()
	file.Close()
	ca := filepath.Join(t.TempDir(), "ca.pem")
	if err = os.WriteFile(ca, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: f.server.Certificate().Raw}), 0600); err != nil {
		t.Fatal(err)
	}
	cfg.Admin = &config.Admin{BaseURL: a.o.Client.BaseURL(), RunnerCode: "test-runner", CredentialDir: credentials, CAFile: ca, RequestTimeoutSeconds: 1}
	cfg.Programs[0].Manifest.SecretNames = []string{"DB_PASSWORD"}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- RunConfigured(ctx, cfg, "test") }()
	eventually(t, func() bool { f.mu.Lock(); defer f.mu.Unlock(); return len(f.completed) == 1 })
	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(4 * time.Second):
		t.Fatal("configured runner did not drain")
	}
	f.mu.Lock()
	c := f.completed[q.ID]
	f.mu.Unlock()
	if c.Outcome != "SUCCEEDED" || c.Logs["STDOUT"].Status != "COMPLETE" || countStarts(t, q) != 1 {
		t.Fatal("configured runner failed")
	}
}
