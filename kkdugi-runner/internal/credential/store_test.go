package credential

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"kkdugi-runner/internal/client"
)

func stored() Stored {
	return Stored{SchemaVersion: 1, BaseURL: "https://admin.example/api/v1.0/batch-agent", RunnerCode: "worker-01", Registration: client.RegistrationResponse{RunnerID: "BR1", CredentialID: "BC1", AccessToken: "saved-access-secret", TokenExpiresAt: client.Timestamp(time.Now().Add(time.Hour).UTC().Format(time.RFC3339)), Session: "0"}}
}
func directory(t *testing.T) string { t.Helper(); return filepath.Join(t.TempDir(), "private") }

func TestPrivateCredentialRoundTripAndBinding(t *testing.T) {
	dir := directory(t)
	a, err := Begin(dir)
	if err != nil {
		t.Fatal(err)
	}
	if err := a.Commit(stored()); err != nil {
		t.Fatal(err)
	}
	if err := checkPrivate(dir, true); err != nil {
		t.Fatal(err)
	}
	if err := checkPrivate(filepath.Join(dir, fileName), false); err != nil {
		t.Fatal(err)
	}
	r, err := Load(dir, stored().BaseURL)
	if err != nil || r.Registration.AccessToken != stored().Registration.AccessToken {
		t.Fatal(err)
	}
	if _, err := Load(dir, "https://other.example/api/v1.0/batch-agent"); err == nil {
		t.Fatal("credential used for another origin")
	}
	if _, err := Begin(dir); !errors.Is(err, ErrExists) {
		t.Fatal(err)
	}
	for _, format := range []string{"%v", "%+v", "%#v"} {
		if strings.Contains(fmt.Sprintf(format, r), "saved-access-secret") {
			t.Fatal("credential printed")
		}
	}
	for _, name := range []string{pendingName, tempName} {
		if _, err := os.Stat(filepath.Join(dir, name)); !errors.Is(err, os.ErrNotExist) {
			t.Fatal(name, err)
		}
	}
}

func TestPendingAttemptSurvivesRestart(t *testing.T) {
	dir := directory(t)
	if _, err := Begin(dir); err != nil {
		t.Fatal(err)
	}
	if _, err := Begin(dir); !errors.Is(err, ErrExists) {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(dir, pendingName)); err != nil {
		t.Fatal(err)
	}
}

func TestConcurrentRegistrationsHaveOneWinner(t *testing.T) {
	dir := directory(t)
	if err := os.Mkdir(dir, 0700); err != nil {
		t.Fatal(err)
	}
	if err := secure(dir, true); err != nil {
		t.Fatal(err)
	}
	var winners atomic.Int32
	var group sync.WaitGroup
	for i := 0; i < 8; i++ {
		group.Add(1)
		go func() {
			defer group.Done()
			if _, err := Begin(dir); err == nil {
				winners.Add(1)
			}
		}()
	}
	group.Wait()
	if winners.Load() != 1 {
		t.Fatal(winners.Load())
	}
}

func TestCommitCannotOverwriteAndLeavesRecoveryEvidence(t *testing.T) {
	dir := directory(t)
	a, err := Begin(dir)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, fileName)
	if err := writePrivate(path, []byte("existing credential")); err != nil {
		t.Fatal(err)
	}
	if err := a.Commit(stored()); err == nil {
		t.Fatal("existing credential replaced")
	}
	b, err := os.ReadFile(path)
	if err != nil || string(b) != "existing credential" {
		t.Fatal(string(b), err)
	}
	for _, name := range []string{pendingName, tempName} {
		if _, err := os.Stat(filepath.Join(dir, name)); err != nil {
			t.Fatal(err)
		}
	}
}

func TestRejectCorruptAndExpiredCredentials(t *testing.T) {
	dir := directory(t)
	a, err := Begin(dir)
	if err != nil {
		t.Fatal(err)
	}
	s := stored()
	s.Registration.TokenExpiresAt = "2000-01-01T00:00:00Z"
	if err := a.Commit(s); err == nil {
		t.Fatal("expired credential accepted")
	}
	if err := writePrivate(filepath.Join(dir, fileName), []byte(`{"accessToken":"secret"`)); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(dir, stored().BaseURL); err == nil {
		t.Fatal("corrupt credential accepted")
	}
}

func TestRejectSymlinkDirectory(t *testing.T) {
	base := t.TempDir()
	real := filepath.Join(base, "real")
	if err := os.Mkdir(real, 0700); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(base, "linked")
	if err := os.Symlink(real, link); err != nil {
		t.Skipf("symlink not available: %v", err)
	}
	if _, err := Begin(filepath.Join(link, "private")); err == nil {
		t.Fatal("symlink ancestor accepted")
	}
}

func TestRejectUncleanCredentialPath(t *testing.T) {
	base := t.TempDir()
	dir := base + string(os.PathSeparator) + "unused" + string(os.PathSeparator) + ".." + string(os.PathSeparator) + "private"
	if _, err := Begin(dir); !errors.Is(err, ErrPermissions) {
		t.Fatal(err)
	}
}
