package state

import (
	"context"
	"database/sql"
	"errors"
	"path/filepath"
	"testing"
)

func legacyState(t *testing.T) (*Store, string) {
	t.Helper()
	s, dir := newStore(t)
	activate(t, s)
	commit(t, s, claim())
	for _, statement := range []string{"DROP TABLE kkdugi_runner_log_chunk", "DROP TABLE kkdugi_runner_log_stream", "DROP TABLE kkdugi_runner_reconcile", "DROP TABLE kkdugi_runner_superseded", "DROP INDEX kkdugi_runner_single_claim", "ALTER TABLE kkdugi_runner_request DROP COLUMN superseded", "CREATE UNIQUE INDEX kkdugi_runner_single_claim ON kkdugi_runner_request(session) WHERE path='/assignments/claim' AND status!='ACKED'", "DELETE FROM kkdugi_runner_migration WHERE version=3", "DROP TABLE kkdugi_runner_program", "DROP TABLE kkdugi_runner_program_revision", "DELETE FROM kkdugi_runner_migration WHERE version=2", "PRAGMA user_version=1"} {
		if _, err := s.conn.ExecContext(context.Background(), statement); err != nil {
			t.Fatal(err)
		}
	}
	return s, dir
}
func TestUpgradeV1PreservesRequests(t *testing.T) {
	s, dir := legacyState(t)
	before, err := s.Requests(context.Background(), "", 200)
	if err != nil {
		t.Fatal(err)
	}
	s.Close()
	s = openStore(t, dir)
	after, err := s.Requests(context.Background(), "", 200)
	if err != nil || len(after) != 1 || after[0].Key != before[0].Key || after[0].Hash != before[0].Hash {
		t.Fatal("migration lost request", err)
	}
	var version int
	if err = s.conn.QueryRowContext(context.Background(), "PRAGMA user_version").Scan(&version); err != nil || version != 3 {
		t.Fatal(version, err)
	}
	if _, err = s.BeginSend(context.Background(), after[0].ID); !errors.Is(err, ErrSession) {
		t.Fatal("upgrade allowed old session", err)
	}
}
func TestUpgradeRollbackOnDDLConflict(t *testing.T) {
	s, dir := legacyState(t)
	if _, err := s.conn.ExecContext(context.Background(), "CREATE TABLE kkdugi_runner_program(conflict TEXT)"); err != nil {
		t.Fatal(err)
	}
	s.Close()
	if opened, err := Open(context.Background(), dir, testIdentity); !errors.Is(err, ErrSchema) {
		if opened != nil {
			opened.Close()
		}
		t.Fatal(err)
	}
	db, err := sql.Open("sqlite", filepath.Join(dir, "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	var version, n int
	if db.QueryRow("PRAGMA user_version").Scan(&version) != nil || version != 1 {
		t.Fatal("version partially advanced")
	}
	if db.QueryRow("SELECT count(*) FROM sqlite_master WHERE name='kkdugi_runner_program_revision'").Scan(&n) != nil || n != 0 {
		t.Fatal("DDL partially committed")
	}
	if db.QueryRow("SELECT count(*) FROM kkdugi_runner_migration").Scan(&n) != nil || n != 1 {
		t.Fatal("migration record partially committed")
	}
}
