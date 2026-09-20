package main

import (
	"bytes"
	"encoding/json"
	"encoding/pem"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/pelletier/go-toml/v2"
	"kkdugi-runner/internal/client"
	"kkdugi-runner/internal/config"
	"kkdugi-runner/internal/credential"
)

func TestCommands(t *testing.T) {
	for _, test := range []struct {
		args []string
		code int
	}{{[]string{"version"}, 0}, {nil, 2}, {[]string{"run"}, 2}, {[]string{"verify"}, 2}, {[]string{"verify", "--config", "missing.toml"}, 1}} {
		var out, err bytes.Buffer
		if code := run(test.args, strings.NewReader(""), &out, &err); code != test.code {
			t.Fatalf("%v: %d %s", test.args, code, err.String())
		}
		if test.code == 0 && !strings.Contains(out.String(), "protocol=1") {
			t.Fatal(out.String())
		}
	}
}

func TestRegisterCLIEndToEnd(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != client.BasePath+"/registrations" || r.Header.Get("Authorization") != "Bearer enrollment-secret" {
			t.Error("invalid registration request")
		}
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(201)
		json.NewEncoder(w).Encode(client.RegistrationResponse{RunnerID: "BR1", CredentialID: "BC1", AccessToken: "access-secret", TokenExpiresAt: client.Timestamp(time.Now().Add(time.Hour).UTC().Format(time.RFC3339)), Session: "0"})
	}))
	defer server.Close()
	dir := t.TempDir()
	ca := filepath.Join(dir, "ca.pem")
	if err := os.WriteFile(ca, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: server.Certificate().Raw}), 0600); err != nil {
		t.Fatal(err)
	}
	cfg := config.Config{DataDir: filepath.Join(dir, "data"), Capacity: 1, Admin: &config.Admin{BaseURL: server.URL + client.BasePath, RunnerCode: "worker-01", CAFile: ca}}
	b, err := toml.Marshal(cfg)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "runner.toml")
	if err := os.WriteFile(path, b, 0600); err != nil {
		t.Fatal(err)
	}
	var out, stderr bytes.Buffer
	if code := run([]string{"register", "--config", path}, strings.NewReader("enrollment-secret\n"), &out, &stderr); code != 0 {
		t.Fatal(code, stderr.String())
	}
	if _, err := credential.Load(filepath.Join(dir, "data", "credentials"), cfg.Admin.BaseURL); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(out.String()+stderr.String(), "secret") {
		t.Fatal("CLI exposed token")
	}
	out.Reset()
	stderr.Reset()
	if code := run([]string{"register", "--config", path, "--enrollment-token=argument-secret"}, strings.NewReader(""), &out, &stderr); code != 2 {
		t.Fatal(code)
	}
	if strings.Contains(out.String()+stderr.String(), "argument-secret") {
		t.Fatal("invalid flag echoed token")
	}
}
