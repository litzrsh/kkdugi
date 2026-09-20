package state

import (
	"context"
	"database/sql"
	"encoding/json"
	"math"
	"strconv"

	"kkdugi-runner/internal/client"
)

type queryer interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}
type meta struct {
	session client.Decimal
	bootID  string
	pending []byte
	hash    sql.NullString
}

func (s *Store) CurrentSession(ctx context.Context) (client.Decimal, string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed || s.faulted {
		return "", "", ErrStorage
	}
	session, err := s.active(ctx, s.conn)
	if err != nil {
		return "", "", err
	}
	return session, s.bootID, nil
}

func readMeta(ctx context.Context, q queryer) (meta, error) {
	var m meta
	if q.QueryRowContext(ctx, "SELECT session,boot_id,pending_session,pending_hash FROM kkdugi_runner_meta WHERE singleton=1").Scan(&m.session, &m.bootID, &m.pending, &m.hash) != nil || m.session.Validate() != nil {
		return m, ErrSchema
	}
	if len(m.pending) > 0 && (!m.hash.Valid || digest(m.pending) != m.hash.String) {
		return m, ErrSchema
	}
	if m.pending == nil && m.hash.Valid {
		return m, ErrSchema
	}
	if m.session == "0" {
		if m.bootID != "" {
			return m, ErrSchema
		}
	} else if (client.SessionRequest{BootID: m.bootID, ExpectedSession: m.session, AgentVersion: "validate"}).Validate() != nil {
		return m, ErrSchema
	}
	if m.pending != nil {
		var request client.SessionRequest
		if len(m.pending) > client.MaxJSONBytes || json.Unmarshal(m.pending, &request) != nil || request.Validate() != nil || request.ExpectedSession != m.session {
			return m, ErrSchema
		}
	}
	return m, nil
}
func (s *Store) active(ctx context.Context, q queryer) (client.Decimal, error) {
	m, err := readMeta(ctx, q)
	if err != nil {
		return "", err
	}
	if m.bootID != s.bootID || m.session == "0" || m.pending != nil {
		return "", ErrSession
	}
	return m.session, nil
}

// PrepareSession first resolves a prior boot's pending request unchanged. Only
// after confirmation may this boot prepare its own request. No HTTP occurs here.
func (s *Store) PrepareSession(ctx context.Context, version string) (client.SessionRequest, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var result client.SessionRequest
	err := s.transact(ctx, func(tx *sql.Tx) error {
		m, err := readMeta(ctx, tx)
		if err != nil {
			return err
		}
		if m.pending != nil {
			if json.Unmarshal(m.pending, &result) != nil || result.Validate() != nil || result.ExpectedSession != m.session {
				return ErrSchema
			}
			return nil
		}
		if m.bootID == s.bootID {
			return ErrConflict
		}
		result = client.SessionRequest{BootID: s.bootID, ExpectedSession: m.session, AgentVersion: version}
		if result.Validate() != nil {
			return ErrInvalid
		}
		body, err := json.Marshal(result)
		if err != nil {
			return ErrInvalid
		}
		_, err = tx.ExecContext(ctx, "UPDATE kkdugi_runner_meta SET pending_session=?,pending_hash=? WHERE singleton=1", body, digest(body))
		if err != nil {
			return ErrStorage
		}
		return nil
	})
	if err != nil {
		return client.SessionRequest{}, err
	}
	return result, nil
}

func (s *Store) ConfirmSession(ctx context.Context, input client.SessionRequest, result client.SessionResponse) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if input.Validate() != nil || result.Validate() != nil || result.RunnerID != s.identity.RunnerID {
		return ErrInvalid
	}
	return s.transact(ctx, func(tx *sql.Tx) error {
		m, err := readMeta(ctx, tx)
		if err != nil {
			return err
		}
		var pending client.SessionRequest
		if m.pending == nil || json.Unmarshal(m.pending, &pending) != nil || pending != input || m.session != input.ExpectedSession {
			return ErrConflict
		}
		expected, err := strconv.ParseInt(string(m.session), 10, 64)
		if err != nil || expected == math.MaxInt64 {
			return ErrConflict
		}
		actual, err := strconv.ParseInt(string(result.Session), 10, 64)
		if err != nil || actual != expected+1 {
			return ErrConflict
		}
		// Canonical decimal prevents equal generations with different lexical forms.
		_, err = tx.ExecContext(ctx, "UPDATE kkdugi_runner_meta SET session=?,boot_id=?,pending_session=NULL,pending_hash=NULL WHERE singleton=1", strconv.FormatInt(actual, 10), input.BootID)
		if err != nil {
			return ErrStorage
		}
		return nil
	})
}
