package state

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"embed"
	"encoding/hex"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"kkdugi-runner/internal/client"
	"kkdugi-runner/internal/credential"
	"modernc.org/sqlite"
)

var (
	ErrLocked   = errors.New("runner state is already locked")
	ErrStorage  = errors.New("runner state unavailable; preserve files for recovery")
	ErrSchema   = errors.New("runner state schema is unsupported or damaged")
	ErrIdentity = errors.New("runner state belongs to a different identity")
	ErrConflict = errors.New("runner state transition conflicts with durable state")
	ErrSession  = errors.New("request requires the current boot's confirmed session")
	ErrInvalid  = errors.New("invalid runner state input")
	ErrNotFound = errors.New("runner state record not found")
	ErrNotDue   = errors.New("request retry is not due")
)

//go:embed migrations/*.sql
var migrationFiles embed.FS

type Store struct {
	mu       sync.Mutex
	db       *sql.DB
	conn     *sql.Conn
	lock     *os.File
	identity Identity
	bootID   string
	closed   bool
	faulted  bool
	// Test-only crash points run under the mutex and never perform normal external I/O.
	boundary func(string)
}

func uuid() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", ErrStorage
	}
	b[6] = (b[6] & 15) | 64
	b[8] = (b[8] & 63) | 128
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[:4], b[4:6], b[6:8], b[8:10], b[10:]), nil
}
func digest(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }
func stamp(t time.Time) client.Timestamp {
	return client.Timestamp(t.UTC().Format("2006-01-02T15:04:05.000000Z"))
}

// Open is the exclusive lifetime owner of this data directory. A missing established
// DB is a recovery error, never permission to replace state with a blank database.
func Open(ctx context.Context, dir string, identity Identity) (_ *Store, err error) {
	base, e := client.NormalizeBaseURL(identity.BaseURL)
	if e != nil || !validID(identity.RunnerID) {
		return nil, ErrInvalid
	}
	identity.BaseURL = base
	if credential.EnsureDirectory(dir) != nil {
		return nil, ErrStorage
	}
	for _, name := range []string{"runner.lock", "state.db", "state.db-wal", "state.db-shm", "state.db-journal", ".state.initialized"} {
		if credential.CheckFile(filepath.Join(dir, name)) != nil {
			return nil, ErrStorage
		}
	}
	lock, e := credential.OpenPrivateFile(filepath.Join(dir, "runner.lock"), false)
	if e != nil {
		return nil, ErrStorage
	}
	if lockFile(lock) != nil {
		lock.Close()
		return nil, ErrLocked
	}
	s := &Store{lock: lock, identity: identity}
	defer func() {
		if err != nil {
			s.Close()
		}
	}()
	s.bootID, e = uuid()
	if e != nil {
		return nil, e
	}
	dbPath := filepath.Join(dir, "state.db")
	marker := filepath.Join(dir, ".state.initialized")
	info, statErr := os.Stat(dbPath)
	_, markerErr := os.Stat(marker)
	fresh := errors.Is(statErr, os.ErrNotExist) && errors.Is(markerErr, os.ErrNotExist)
	if !fresh && (statErr != nil || markerErr != nil || info.Size() == 0) {
		return nil, ErrSchema
	}
	if fresh {
		// Stray SQLite sidecars are evidence of older state, even if the main DB is gone.
		for _, suffix := range []string{"-wal", "-shm", "-journal"} {
			if _, e := os.Stat(dbPath + suffix); !errors.Is(e, os.ErrNotExist) {
				return nil, ErrSchema
			}
		}
		f, e := credential.OpenPrivateFile(marker, true)
		if e != nil {
			return nil, ErrStorage
		}
		_, w := f.WriteString("state initialization started; never recreate a lost database automatically\n")
		sy := f.Sync()
		cl := f.Close()
		if w != nil || sy != nil || cl != nil || credential.SyncDirectory(dir) != nil {
			return nil, ErrStorage
		}
		f, e = credential.OpenPrivateFile(dbPath, true)
		if e != nil {
			return nil, ErrStorage
		}
		if f.Close() != nil {
			return nil, ErrStorage
		}
	}
	// URI construction escapes path/query characters instead of treating a path as SQL options.
	uriPath := filepath.ToSlash(dbPath)
	if !strings.HasPrefix(uriPath, "/") {
		uriPath = "/" + uriPath
	}
	u := url.URL{Scheme: "file", Path: uriPath}
	q := url.Values{"mode": {"rw"}, "_pragma": {"busy_timeout(5000)", "foreign_keys(1)", "synchronous(FULL)"}}
	u.RawQuery = q.Encode()
	s.db, e = sql.Open("sqlite", u.String())
	if e != nil {
		return nil, ErrStorage
	}
	s.db.SetMaxOpenConns(1)
	s.db.SetMaxIdleConns(1)
	s.conn, e = s.db.Conn(ctx)
	if e != nil {
		return nil, ErrStorage
	}
	var mode string
	if s.conn.QueryRowContext(ctx, "PRAGMA journal_mode=WAL").Scan(&mode) != nil || mode != "wal" {
		return nil, ErrStorage
	}
	for pragma, want := range map[string]int{"synchronous": 2, "foreign_keys": 1, "busy_timeout": 5000} {
		var got int
		if s.conn.QueryRowContext(ctx, "PRAGMA "+pragma).Scan(&got) != nil || got != want {
			return nil, ErrStorage
		}
	}
	var integrity string
	if s.conn.QueryRowContext(ctx, "PRAGMA quick_check").Scan(&integrity) != nil || integrity != "ok" {
		return nil, ErrSchema
	}
	if e = s.migrate(ctx, fresh); e != nil {
		return nil, e
	}
	var existing Identity
	if s.conn.QueryRowContext(ctx, "SELECT base_url,runner_id FROM kkdugi_runner_meta WHERE singleton=1").Scan(&existing.BaseURL, &existing.RunnerID) != nil {
		return nil, ErrSchema
	}
	if existing != identity {
		return nil, ErrIdentity
	}
	if _, e = readMeta(ctx, s.conn); e != nil {
		return nil, e
	}
	if credential.SyncDirectory(dir) != nil {
		return nil, ErrStorage
	}
	return s, nil
}

func (s *Store) migrate(ctx context.Context, fresh bool) error {
	var version int
	if s.conn.QueryRowContext(ctx, "PRAGMA user_version").Scan(&version) != nil {
		return ErrSchema
	}
	files := []string{"migrations/001_initial.sql", "migrations/002_program_catalog.sql", "migrations/003_logs_recovery.sql"}
	if version < 0 || version > len(files) || (version == 0 && !fresh) {
		return ErrSchema
	}
	for i := 0; i < version; i++ {
		source, err := migrationFiles.ReadFile(files[i])
		if err != nil {
			return ErrSchema
		}
		var checksum string
		if s.conn.QueryRowContext(ctx, "SELECT checksum FROM kkdugi_runner_migration WHERE version=?", i+1).Scan(&checksum) != nil || checksum != digest(source) {
			return ErrSchema
		}
	}
	if version == len(files) {
		return nil
	}
	return s.transact(ctx, func(tx *sql.Tx) error {
		for i := version; i < len(files); i++ {
			source, err := migrationFiles.ReadFile(files[i])
			if err != nil {
				return ErrSchema
			}
			if _, err := tx.ExecContext(ctx, string(source)); err != nil {
				return ErrSchema
			}
			if _, err := tx.ExecContext(ctx, "INSERT INTO kkdugi_runner_migration VALUES(?,?)", i+1, digest(source)); err != nil {
				return ErrSchema
			}
			if i == 0 {
				if _, err := tx.ExecContext(ctx, "INSERT INTO kkdugi_runner_meta VALUES(1,?,?, '0','',NULL,NULL)", s.identity.BaseURL, s.identity.RunnerID); err != nil {
					return ErrSchema
				}
			}
			if _, err := tx.ExecContext(ctx, fmt.Sprintf("PRAGMA user_version=%d", i+1)); err != nil {
				return ErrSchema
			}
		}
		return nil
	})
}

// A failed commit has an uncertain durable outcome. Reject further writes until reopen.
func (s *Store) transact(ctx context.Context, fn func(*sql.Tx) error) error {
	if ctx.Err() != nil {
		return ctx.Err()
	}
	if s.closed || s.faulted {
		return ErrStorage
	}
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return ErrStorage
	}
	defer tx.Rollback()
	if err = fn(tx); err != nil {
		// No commit was attempted. Cancellation-induced query failures are not
		// evidence of a damaged database; database/sql rolls this transaction back.
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if errors.Is(err, ErrStorage) || errors.Is(err, ErrSchema) {
			s.faulted = true
		}
		return err
	}
	if s.boundary != nil {
		s.boundary("before-commit")
	}
	if err = tx.Commit(); err != nil {
		s.faulted = true
		return ErrStorage
	}
	if s.boundary != nil {
		s.boundary("after-commit")
	}
	return nil
}

func (s *Store) sqlFailure(err error) error {
	var sqliteErr *sqlite.Error
	if errors.As(err, &sqliteErr) && sqliteErr.Code()&255 == 19 {
		return ErrConflict
	}
	s.faulted = true
	return ErrStorage
}
func (s *Store) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return nil
	}
	s.closed = true
	var errs []error
	if s.conn != nil {
		errs = append(errs, s.conn.Close())
	}
	if s.db != nil {
		errs = append(errs, s.db.Close())
	}
	if s.lock != nil {
		errs = append(errs, s.lock.Close())
	}
	if errors.Join(errs...) != nil {
		return ErrStorage
	}
	return nil
}
