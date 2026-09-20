package state

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"math"
	"regexp"
	"unicode/utf8"

	"kkdugi-runner/internal/client"
)

var idPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,20}$`)

func validID(id string) bool { return idPattern.MatchString(id) }
func object(body []byte) bool {
	b := bytes.TrimSpace(body)
	return len(b) > 0 && len(body) <= client.MaxJSONBytes && b[0] == '{' && utf8.Valid(body) && json.Valid(body)
}
func transition(from, to Phase) bool {
	switch from {
	case Received:
		return to == Prepared || to == Finished || to == Unknown
	case Prepared:
		return to == StartRequested || to == Finished || to == Unknown
	case StartRequested:
		return to == Permitted || to == Finished || to == Unknown
	case Permitted:
		return to == StartIntent || to == Finished || to == Unknown
	case StartIntent:
		return to == Running || to == Finished || to == Unknown
	case Running:
		return to == Stopping || to == Finished || to == Unknown
	case Stopping:
		return to == Finished || to == Unknown
	case Finished:
		return to == Acked
	default:
		return false
	}
}
func getAssignment(ctx context.Context, q queryer, id string) (Assignment, error) {
	var a Assignment
	err := q.QueryRowContext(ctx, "SELECT id,session,boot_id,phase,version,snapshot,detail,start_intent FROM kkdugi_runner_assignment WHERE id=?", id).Scan(&a.ID, &a.Session, &a.BootID, &a.Phase, &a.Version, &a.Snapshot, &a.Detail, &a.StartIntent)
	if errors.Is(err, sql.ErrNoRows) {
		return a, ErrNotFound
	}
	if err != nil {
		return a, ErrStorage
	}
	if !object(a.Snapshot) || !object(a.Detail) || a.Session.Validate() != nil {
		return Assignment{}, ErrSchema
	}
	return a, nil
}
func (s *Store) Assignment(ctx context.Context, id string) (Assignment, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed || s.faulted {
		return Assignment{}, ErrStorage
	}
	a, err := getAssignment(ctx, s.conn, id)
	if errors.Is(err, ErrSchema) || errors.Is(err, ErrStorage) {
		s.faulted = true
	}
	return a, err
}

// Commit atomically records a journal transition, an immutable outgoing command,
// and an incoming ACK. It grants no authority beyond the caller's validated API response.
func (s *Store) Commit(ctx context.Context, m Mutation) (Request, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var result Request
	if m.Assignment == nil && m.Request == nil && m.Ack == nil {
		return result, ErrInvalid
	}
	err := s.transact(ctx, func(tx *sql.Tx) error {
		session, err := s.active(ctx, tx)
		if err != nil {
			return err
		}
		if m.Assignment != nil {
			if err = s.changeAssignment(ctx, tx, session, m); err != nil {
				return err
			}
		}
		if m.Ack != nil {
			if err = s.ack(ctx, tx, session, *m.Ack); err != nil {
				return err
			}
		}
		if m.Request != nil {
			result, err = s.enqueue(ctx, tx, session, *m.Request)
			if err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		return Request{}, err
	}
	return result, nil
}
func (s *Store) changeAssignment(ctx context.Context, tx *sql.Tx, session client.Decimal, m Mutation) error {
	c := m.Assignment
	if !validID(c.ID) || !object(c.Detail) || c.ExpectedVersion < 0 || c.ExpectedVersion == math.MaxInt64 {
		return ErrInvalid
	}
	if c.ExpectedVersion == 0 {
		if c.ExpectedPhase != "" || c.NextPhase != Received || !object(c.Snapshot) {
			return ErrInvalid
		}
		if _, err := tx.ExecContext(ctx, "INSERT INTO kkdugi_runner_assignment VALUES(?,?,?,?,1,?,?,?)", c.ID, session, s.bootID, c.NextPhase, []byte(c.Snapshot), []byte(c.Detail), false); err != nil {
			return s.sqlFailure(err)
		}
		return nil
	}
	a, err := getAssignment(ctx, tx, c.ID)
	if err != nil {
		return err
	}
	if a.Session != session || a.BootID != s.bootID {
		return ErrSession
	}
	if a.Version != c.ExpectedVersion || a.Phase != c.ExpectedPhase || !transition(a.Phase, c.NextPhase) {
		return ErrConflict
	}
	if c.Snapshot != nil && !bytes.Equal(a.Snapshot, c.Snapshot) {
		return ErrConflict
	}
	if c.NextPhase == StartIntent && a.StartIntent {
		return ErrConflict
	}
	if c.NextPhase == StartRequested && (m.Request == nil || m.Request.Path != "/assignments/"+a.ID+"/start" || m.Request.AssignmentID != a.ID) {
		return ErrInvalid
	}
	if c.NextPhase == Permitted {
		if m.Ack == nil {
			return ErrInvalid
		}
		r, err := getRequest(ctx, tx, m.Ack.RequestID)
		if err != nil {
			return err
		}
		if r.AssignmentID != a.ID || r.Path != "/assignments/"+a.ID+"/start" {
			return ErrConflict
		}
	}
	if c.NextPhase == Finished && m.Request != nil && (m.Request.AssignmentID != a.ID || m.Request.Path != "/assignments/"+a.ID+"/completion" || !bytes.Equal(m.Request.Body, c.Detail)) {
		return ErrInvalid
	}
	if c.NextPhase == Acked {
		if !bytes.Equal(a.Detail, c.Detail) || m.Ack == nil {
			return ErrConflict
		}
		r, err := getRequest(ctx, tx, m.Ack.RequestID)
		if err != nil {
			return err
		}
		if r.AssignmentID != a.ID || r.Path != "/assignments/"+a.ID+"/completion" || !bytes.Equal(r.Body, a.Detail) {
			return ErrConflict
		}
	}
	_, err = tx.ExecContext(ctx, "UPDATE kkdugi_runner_assignment SET phase=?,version=version+1,detail=?,start_intent=? WHERE id=?", c.NextPhase, []byte(c.Detail), a.StartIntent || c.NextPhase == StartIntent, c.ID)
	if err != nil {
		return ErrStorage
	}
	return nil
}
