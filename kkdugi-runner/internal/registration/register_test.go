package registration

import (
	"context"
	"encoding/json"
	"encoding/pem"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"kkdugi-runner/internal/client"
	"kkdugi-runner/internal/config"
	"kkdugi-runner/internal/credential"
)

func setup(t *testing.T, handler http.HandlerFunc) *config.Admin {
	t.Helper()
	s := httptest.NewTLSServer(handler)
	t.Cleanup(s.Close)
	dir := t.TempDir()
	caFile := filepath.Join(dir, "ca.pem")
	if err := os.WriteFile(caFile, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: s.Certificate().Raw}), 0600); err != nil {
		t.Fatal(err)
	}
	return &config.Admin{BaseURL: s.URL + client.BasePath, RunnerCode: "worker-01", CAFile: caFile, CredentialDir: filepath.Join(dir, "credentials"), RequestTimeoutSeconds: 1}
}
func successful(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(201)
	json.NewEncoder(w).Encode(client.RegistrationResponse{RunnerID: "BR1", CredentialID: "BC1", AccessToken: "new-access-secret", TokenExpiresAt: client.Timestamp(time.Now().Add(time.Hour).UTC().Format(time.RFC3339)), Session: "0"})
}

func TestRegisterPersistsAccessButNotEnrollment(t *testing.T) {
	var calls atomic.Int32
	a := setup(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.Header.Get("Authorization") != "Bearer enrollment-secret" {
			t.Error("missing enrollment auth")
		}
		successful(w, r)
	})
	if err := Run(context.Background(), a, "0.1.0", strings.NewReader("enrollment-secret\r\n")); err != nil {
		t.Fatal(err)
	}
	s, err := credential.Load(a.CredentialDir, a.BaseURL)
	if err != nil || s.Registration.AccessToken != "new-access-secret" {
		t.Fatal(err)
	}
	b, err := os.ReadFile(filepath.Join(a.CredentialDir, "credential.json"))
	if err != nil || strings.Contains(string(b), "enrollment-secret") {
		t.Fatal("enrollment token saved", err)
	}
	if err := Run(context.Background(), a, "0.1.0", strings.NewReader("enrollment-secret")); !errors.Is(err, credential.ErrExists) {
		t.Fatal(err)
	}
	if calls.Load() != 1 {
		t.Fatal("duplicate registration")
	}
}

func TestUnknownRegistrationKeepsReservation(t *testing.T) {
	var calls atomic.Int32
	a := setup(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		c, _, err := w.(http.Hijacker).Hijack()
		if err != nil {
			t.Error(err)
			return
		}
		c.Close()
	})
	err := Run(context.Background(), a, "0.1.0", strings.NewReader("enrollment-secret"))
	if err == nil || strings.Contains(err.Error(), "enrollment-secret") {
		t.Fatal(err)
	}
	if err := Run(context.Background(), a, "0.1.0", strings.NewReader("another-token")); !errors.Is(err, credential.ErrExists) {
		t.Fatal(err)
	}
	if calls.Load() != 1 {
		t.Fatal("ambiguous registration retried")
	}
	if _, err := os.Stat(filepath.Join(a.CredentialDir, ".registration.pending")); err != nil {
		t.Fatal(err)
	}
}

func TestInvalidTokenDoesNotReserveOrSend(t *testing.T) {
	var calls atomic.Int32
	a := setup(t, func(w http.ResponseWriter, r *http.Request) { calls.Add(1) })
	for _, token := range []string{"", "line1\nline2", strings.Repeat("x", 8193), "Bearer secret"} {
		if err := Run(context.Background(), a, "0.1.0", strings.NewReader(token)); err == nil {
			t.Fatal("bad token accepted")
		}
	}
	if calls.Load() != 0 {
		t.Fatal("invalid token sent")
	}
	if _, err := os.Stat(a.CredentialDir); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("invalid input created recovery state")
	}
}
