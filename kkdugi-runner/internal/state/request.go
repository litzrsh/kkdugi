package state

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"strings"
	"time"

	"kkdugi-runner/internal/client"
)

func validDraft(r RequestDraft) bool {
	if !object(r.Body) {
		return false
	}
	if r.Path == "/assignments/claim" {
		return r.Method == "POST" && r.AssignmentID == ""
	}
	if strings.HasPrefix(r.Path, "/programs/") {
		return r.Method == "PUT" && r.AssignmentID == "" && validID(strings.TrimPrefix(r.Path, "/programs/"))
	}
	if !validID(r.AssignmentID) || r.Method != "POST" {
		return false
	}
	base := "/assignments/" + r.AssignmentID + "/"
	for _, action := range []string{"start", "started", "reconcile", "completion"} {
		if r.Path == base+action {
			return true
		}
	}
	return false
}
func getRequest(ctx context.Context, q queryer, id string) (Request, error) {
	var r Request
	err := q.QueryRowContext(ctx, "SELECT id,coalesce(assignment_id,''),method,path,request_key,created_at,session,body,body_hash,status,next_at FROM kkdugi_runner_request WHERE id=?", id).Scan(&r.ID, &r.AssignmentID, &r.Method, &r.Path, &r.Key, &r.CreatedAt, &r.Session, &r.Body, &r.Hash, &r.Status, &r.NextAt)
	if errors.Is(err, sql.ErrNoRows) {
		return r, ErrNotFound
	}
	if err != nil {
		return r, ErrStorage
	}
	_, t1 := r.CreatedAt.Time()
	_, t2 := r.NextAt.Time()
	if digest(r.Body) != r.Hash || !validDraft(RequestDraft{r.AssignmentID, r.Method, r.Path, r.Body}) || r.Session.Validate() != nil || t1 != nil || t2 != nil {
		return Request{}, ErrSchema
	}
	return r, nil
}
func (s *Store) enqueue(ctx context.Context, tx *sql.Tx, session client.Decimal, d RequestDraft) (Request, error) {
	var r Request
	if !validDraft(d) {
		return r, ErrInvalid
	}
	if d.AssignmentID != "" {
		a, err := getAssignment(ctx, tx, d.AssignmentID)
		if err != nil {
			return r, err
		}
		if a.Session != session || a.BootID != s.bootID {
			return r, ErrSession
		}
		if strings.HasSuffix(d.Path, "/start") && a.Phase != StartRequested {
			return r, ErrConflict
		}
		if strings.HasSuffix(d.Path, "/started") && a.Phase != Running {
			return r, ErrConflict
		}
		if strings.HasSuffix(d.Path, "/completion") && (a.Phase != Finished || !bytes.Equal(a.Detail, d.Body)) {
			return r, ErrConflict
		}
		var n int
		if tx.QueryRowContext(ctx, "SELECT count(*) FROM kkdugi_runner_request WHERE assignment_id=? AND path=? AND session=? AND id NOT IN (SELECT request_id FROM kkdugi_runner_superseded)", d.AssignmentID, d.Path, session).Scan(&n) != nil {
			return r, ErrStorage
		}
		if n > 0 {
			return r, ErrConflict
		} // resolving an expired key needs a later explicit recovery API
	}
	id, err := uuid()
	if err != nil {
		return r, err
	}
	key, err := uuid()
	if err != nil {
		return r, err
	}
	now := stamp(time.Now())
	r = Request{ID: id, AssignmentID: d.AssignmentID, Method: d.Method, Path: d.Path, Key: key, CreatedAt: now, Session: session, Body: append([]byte(nil), d.Body...), Hash: digest(d.Body), Status: "PENDING", NextAt: now}
	var assignment any
	if r.AssignmentID != "" {
		assignment = r.AssignmentID
	}
	_, err = tx.ExecContext(ctx, "INSERT INTO kkdugi_runner_request(id,assignment_id,method,path,request_key,created_at,session,body,body_hash,status,next_at,response) VALUES(?,?,?,?,?,?,?,?,?,?,?,NULL)", r.ID, assignment, r.Method, r.Path, r.Key, r.CreatedAt, r.Session, []byte(r.Body), r.Hash, r.Status, r.NextAt)
	if err != nil {
		return Request{}, s.sqlFailure(err)
	}
	return r, nil
}
func (s *Store) ack(ctx context.Context, tx *sql.Tx, session client.Decimal, a Acknowledgement) error {
	if len(a.Response) > 0 && !object(a.Response) {
		return ErrInvalid
	}
	r, err := getRequest(ctx, tx, a.RequestID)
	if err != nil {
		return err
	}
	if r.Session != session {
		return ErrSession
	}
	if r.Status == "PENDING" {
		return ErrConflict
	}
	if r.Status == "ACKED" {
		var previous []byte
		if tx.QueryRowContext(ctx, "SELECT response FROM kkdugi_runner_request WHERE id=?", r.ID).Scan(&previous) != nil {
			return ErrStorage
		}
		if !bytes.Equal(previous, a.Response) {
			return ErrConflict
		}
		return nil
	}
	_, err = tx.ExecContext(ctx, "UPDATE kkdugi_runner_request SET status='ACKED',response=? WHERE id=?", []byte(a.Response), r.ID)
	if err != nil {
		return ErrStorage
	}
	return nil
}

// BeginSend commits IN_FLIGHT before exposing bytes. A retry returns the original
// immutable headers/body, never a new key or a new session for an old command.
func (s *Store) BeginSend(ctx context.Context, id string) (Request, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var r Request
	err := s.transact(ctx, func(tx *sql.Tx) error {
		session, err := s.active(ctx, tx)
		if err != nil {
			return err
		}
		r, err = getRequest(ctx, tx, id)
		if err != nil {
			return err
		}
		if r.Session != session {
			return ErrSession
		}
		var superseded int
		if tx.QueryRowContext(ctx, "SELECT count(*) FROM kkdugi_runner_superseded WHERE request_id=?", id).Scan(&superseded) != nil {
			return ErrStorage
		}
		if superseded > 0 {
			return ErrConflict
		}
		if r.Status == "ACKED" {
			return ErrConflict
		}
		next, _ := r.NextAt.Time()
		if time.Now().Before(next) {
			return ErrNotDue
		}
		_, err = tx.ExecContext(ctx, "UPDATE kkdugi_runner_request SET status='IN_FLIGHT' WHERE id=?", id)
		if err != nil {
			return ErrStorage
		}
		r.Status = "IN_FLIGHT"
		return nil
	})
	if err != nil {
		return Request{}, err
	}
	return r, nil
}
func (s *Store) ScheduleRetry(ctx context.Context, id string, next client.Timestamp) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, err := next.Time(); err != nil {
		return ErrInvalid
	}
	return s.transact(ctx, func(tx *sql.Tx) error {
		session, err := s.active(ctx, tx)
		if err != nil {
			return err
		}
		r, err := getRequest(ctx, tx, id)
		if err != nil {
			return err
		}
		if r.Session != session {
			return ErrSession
		}
		if r.Status != "IN_FLIGHT" {
			return ErrConflict
		}
		_, err = tx.ExecContext(ctx, "UPDATE kkdugi_runner_request SET next_at=? WHERE id=?", next, id)
		if err != nil {
			return ErrStorage
		}
		return nil
	})
}
func (s *Store) Request(ctx context.Context, id string) (Request, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed || s.faulted {
		return Request{}, ErrStorage
	}
	r, err := getRequest(ctx, s.conn, id)
	if errors.Is(err, ErrSchema) || errors.Is(err, ErrStorage) {
		s.faulted = true
	}
	return r, err
}

// Recovery pages use an exclusive lexical ID cursor, not numeric session ordering.
// Rows are retained including ACKs; callers decide which need reconciliation.
func (s *Store) Requests(ctx context.Context, after string, limit int) ([]Request, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed || s.faulted {
		return nil, ErrStorage
	}
	ids, err := s.ids(ctx, "kkdugi_runner_request", after, limit)
	if err != nil {
		if errors.Is(err, ErrStorage) {
			s.faulted = true
		}
		return nil, err
	}
	result := make([]Request, 0, len(ids))
	for _, id := range ids {
		r, e := getRequest(ctx, s.conn, id)
		if e != nil {
			s.faulted = true
			return nil, e
		}
		result = append(result, r)
	}
	return result, nil
}
func (s *Store) Assignments(ctx context.Context, after string, limit int) ([]Assignment, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed || s.faulted {
		return nil, ErrStorage
	}
	ids, err := s.ids(ctx, "kkdugi_runner_assignment", after, limit)
	if err != nil {
		if errors.Is(err, ErrStorage) {
			s.faulted = true
		}
		return nil, err
	}
	result := make([]Assignment, 0, len(ids))
	for _, id := range ids {
		a, e := getAssignment(ctx, s.conn, id)
		if e != nil {
			s.faulted = true
			return nil, e
		}
		result = append(result, a)
	}
	return result, nil
}
func (s *Store) ids(ctx context.Context, table, after string, limit int) ([]string, error) {
	if limit < 1 || limit > 200 {
		return nil, ErrInvalid
	}
	rows, err := s.conn.QueryContext(ctx, "SELECT id FROM "+table+" WHERE id>? ORDER BY id LIMIT ?", after, limit)
	if err != nil {
		return nil, ErrStorage
	}
	defer rows.Close()
	var ids []string
	for rows.Next() {
		var id string
		if rows.Scan(&id) != nil {
			return nil, ErrStorage
		}
		ids = append(ids, id)
	}
	if rows.Err() != nil {
		return nil, ErrStorage
	}
	return ids, nil
}
