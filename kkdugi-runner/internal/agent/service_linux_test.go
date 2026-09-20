//go:build linux

package agent

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"github.com/pelletier/go-toml/v2"
	"kkdugi-runner/internal/client"
	"kkdugi-runner/internal/config"
	"kkdugi-runner/internal/credential"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

// Opt-in: creates a temporary systemd unit/account under dedicated generated paths.
// Requires root; normal go test never modifies host services.
func TestSystemdLifecycle(t *testing.T) {
	binary := os.Getenv("KKDUGI_SERVICE_TEST_BINARY")
	script := os.Getenv("KKDUGI_SERVICE_TEST_SCRIPT")
	if binary == "" || script == "" {
		t.Skip("opt-in root/systemd integration")
	}
	if os.Geteuid() != 0 {
		t.Fatal("root required")
	}
	name := fmt.Sprintf("kkdugi-r8-%d", os.Getpid())
	base := "/opt/kkdugi-runner/" + name
	conf := "/etc/kkdugi-runner/" + name
	data := "/var/lib/kkdugi-runner/" + name
	command := func(args ...string) string {
		t.Helper()
		out, e := exec.Command(args[0], args[1:]...).CombinedOutput()
		if e != nil {
			t.Fatalf("%s failed: %v %s", args[0], e, out)
		}
		return strings.TrimSpace(string(out))
	}
	for _, p := range []string{base, conf, data} {
		if _, e := os.Stat(p); !os.IsNotExist(e) {
			t.Fatal("test path already exists", p)
		}
	}
	t.Cleanup(func() {
		exec.Command("systemctl", "stop", name).Run()
		exec.Command("systemctl", "disable", name).Run()
		os.Remove("/etc/systemd/system/" + name + ".service")
		exec.Command("systemctl", "daemon-reload").Run()
		exec.Command("systemctl", "reset-failed", name).Run()
		for _, p := range []string{base, conf, data} {
			os.RemoveAll(p)
		}
		exec.Command("userdel", name).Run()
	})
	f := newAdmin(t)
	q := assignment(t, "BA1", "echo", 300, 5)
	q.Input = json.RawMessage(fmt.Sprintf(`{"counter":%q,"mode":"echo","sleep":300}`, data+"/count"))
	f.queue = []client.Assignment{q}
	exe, _ := os.Executable()
	ca := filepath.Join(t.TempDir(), "ca.pem")
	os.WriteFile(ca, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: f.server.Certificate().Raw}), 0644)
	cfg := config.Config{DataDir: data, Capacity: 1, Admin: &config.Admin{BaseURL: f.server.URL + client.BasePath, RunnerCode: "test-runner", CredentialDir: data + "/credentials", CAFile: conf + "/ca.pem", RequestTimeoutSeconds: 2}, Programs: []config.Program{{Code: "test", Version: "1", Revision: "rev1", Manifest: config.Manifest{Executable: exe, Arguments: []string{"-test.run=^TestWorkProgram$"}, WorkingDirectory: data, InputMode: "JSON_FILE", InputContract: "1"}}}}
	body, e := toml.Marshal(cfg)
	if e != nil {
		t.Fatal(e)
	}
	cfgPath := filepath.Join(t.TempDir(), "runner.toml")
	os.WriteFile(cfgPath, body, 0644)
	bin, e := os.ReadFile(binary)
	if e != nil {
		t.Fatal(e)
	}
	sum := sha256.Sum256(bin)
	hash := hex.EncodeToString(sum[:])
	command("bash", script, "install", name, binary, cfgPath, hash)
	caBody, _ := os.ReadFile(ca)
	os.WriteFile(conf+"/ca.pem", caBody, 0644)
	attempt, e := credential.Begin(cfg.Admin.CredentialDir)
	if e != nil {
		t.Fatal(e)
	}
	e = attempt.Commit(credential.Stored{SchemaVersion: 1, BaseURL: cfg.Admin.BaseURL, RunnerCode: "test-runner", Registration: client.RegistrationResponse{RunnerID: "BR1", CredentialID: "BC1", AccessToken: "test-access", Session: "0", TokenExpiresAt: client.Timestamp(time.Now().Add(time.Hour).UTC().Format(time.RFC3339))}})
	if e != nil {
		t.Fatal(e)
	}
	uid, _ := strconv.Atoi(command("id", "-u", name))
	gid, _ := strconv.Atoi(command("id", "-g", name))
	filepath.Walk(data, func(p string, info os.FileInfo, e error) error {
		if e != nil {
			return e
		}
		return os.Chown(p, uid, gid)
	})
	command("systemctl", "start", name)
	t.Cleanup(func() {
		if t.Failed() {
			out, _ := exec.Command("journalctl", "-u", name, "--no-pager", "-n", "40").CombinedOutput()
			t.Log(string(out))
		}
	})
	eventually(t, func() bool { f.mu.Lock(); defer f.mu.Unlock(); return f.completed[q.ID].Outcome == "SUCCEEDED" })
	if countStarts(t, q) != 1 {
		t.Fatal("service execution count")
	}
	// Candidate checksum failure must not stop the healthy service.
	if exec.Command("bash", script, "update", name, binary, strings.Repeat("0", 64)).Run() == nil {
		t.Fatal("bad update accepted")
	}
	if command("systemctl", "is-active", name) != "active" {
		t.Fatal("bad update stopped service")
	}
	command("bash", script, "update", name, binary, hash)
	eventually(t, func() bool { f.mu.Lock(); defer f.mu.Unlock(); return f.session >= 2 })
	if countStarts(t, q) != 1 {
		t.Fatal("update repeated old work")
	}
	q2 := assignment(t, "BA2", "echo", 500, 5)
	q2.Input = json.RawMessage(fmt.Sprintf(`{"counter":%q,"mode":"echo","sleep":500}`, data+"/count2"))
	f.mu.Lock()
	f.queue = append(f.queue, q2)
	f.mu.Unlock()
	eventually(t, func() bool { return countStarts(t, q2) == 1 })
	command("systemctl", "stop", name)
	if command("systemctl", "show", name, "--property=ExecMainStatus", "--value") != "0" {
		t.Fatal("normal stop failed")
	}
	f.mu.Lock()
	outcome := f.completed[q2.ID].Outcome
	f.mu.Unlock()
	if outcome != "SUCCEEDED" {
		t.Fatal("service stop did not drain running work", outcome)
	}
	if _, e = os.Stat(data + "/state.db"); e != nil {
		t.Fatal("update lost state")
	}
	command("bash", script, "remove", name)
	if _, e = os.Stat(data + "/credentials/credential.json"); e != nil {
		t.Fatal("remove lost credential", e)
	}
}
