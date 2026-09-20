package state

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"kkdugi-runner/internal/client"
	"time"
)

func (s *Store) Superseded(ctx context.Context, id string) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var n int
	e := s.conn.QueryRowContext(ctx, "SELECT count(*) FROM kkdugi_runner_superseded WHERE request_id=?", id).Scan(&n)
	if e != nil {
		return false, ErrStorage
	}
	return n > 0, nil
}

// Recovery requests have their own journal: preparing one never grants the new
// session permission to replay an old start/started/completion request.
func (s *Store) PrepareReconcile(ctx context.Context, id string, body []byte) (Request, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var r Request
	e := s.transact(ctx, func(tx *sql.Tx) error {
		session, e := s.active(ctx, tx)
		if e != nil {
			return e
		}
		if !object(body) {
			return ErrInvalid
		}
		if _, e = getAssignment(ctx, tx, id); e != nil {
			return e
		}
		e = tx.QueryRowContext(ctx, "SELECT id,request_key,created_at,body,hash FROM kkdugi_runner_reconcile WHERE assignment_id=? AND session=? AND applied=0", id, session).Scan(&r.ID, &r.Key, &r.CreatedAt, &r.Body, &r.Hash)
		if e != nil && !errors.Is(e, sql.ErrNoRows) {
			return ErrStorage
		}
		if errors.Is(e, sql.ErrNoRows) {
			r.ID, e = uuid()
			if e != nil {
				return e
			}
			r.Key, e = uuid()
			if e != nil {
				return e
			}
			r.CreatedAt = stamp(time.Now())
			r.Body = append([]byte(nil), body...)
			r.Hash = digest(body)
			if _, e = tx.ExecContext(ctx, "INSERT INTO kkdugi_runner_reconcile(id,assignment_id,session,request_key,created_at,body,hash) VALUES(?,?,?,?,?,?,?)", r.ID, id, session, r.Key, r.CreatedAt, r.Body, r.Hash); e != nil {
				return ErrStorage
			}
		}
		if r.Hash != digest(r.Body) {
			return ErrSchema
		}
		r.AssignmentID = id
		r.Session = session
		r.Path = "/assignments/" + id + "/reconcile"
		r.Method = "POST"
		return nil
	})
	return r, e
}
func (s *Store) ApplyReconcile(ctx context.Context, r Request, response []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.transact(ctx, func(tx *sql.Tx) error {
		session, e := s.active(ctx, tx)
		if e != nil {
			return e
		}
		if session != r.Session {
			return ErrSession
		}
		ack, e := client.DecodeReconcile(response, r.AssignmentID, session)
		if e != nil {
			return ErrInvalid
		}
		var stored, prior []byte
		var applied bool
		if tx.QueryRowContext(ctx, "SELECT body,response,applied FROM kkdugi_runner_reconcile WHERE id=? AND session=?", r.ID, session).Scan(&stored, &prior, &applied) != nil {
			return ErrNotFound
		}
		if digest(stored) != r.Hash {
			return ErrConflict
		}
		if applied {
			if string(prior) != string(response) {
				return ErrConflict
			}
			return nil
		}
		var observation client.ReconcileRequest
		if json.Unmarshal(stored, &observation) != nil {
			return ErrSchema
		}
		a, e := getAssignment(ctx, tx, r.AssignmentID)
		if e != nil {
			return e
		}
		phase := a.Phase
		switch ack.Disposition {
		case "RESOLVED":
			phase = Acked
		case "REPORT_COMPLETION":
			if a.Phase != Finished && a.Phase != Acked {
				return ErrConflict
			}
			phase = Finished
		case "CONTINUE_EXISTING", "STOP_AND_REPORT":
			if observation.Observation != "RUNNING" {
				return ErrConflict
			}
		case "HOLD":
			if observation.Observation != "RUNNING" && a.Phase != Finished && a.Phase != Acked {
				phase = Unknown
			}
		}
		if _, e = tx.ExecContext(ctx, "UPDATE kkdugi_runner_assignment SET session=?,boot_id=?,phase=?,version=version+1 WHERE id=?", session, s.bootID, phase, a.ID); e != nil {
			return ErrStorage
		}
		if _, e = tx.ExecContext(ctx, "INSERT OR IGNORE INTO kkdugi_runner_superseded SELECT id,? FROM kkdugi_runner_request WHERE assignment_id=? AND status!='ACKED'", r.ID, a.ID); e != nil {
			return ErrStorage
		}
		if _, e = tx.ExecContext(ctx, "UPDATE kkdugi_runner_reconcile SET response=?,applied=1 WHERE id=?", response, r.ID); e != nil {
			return ErrStorage
		}
		return nil
	})
}

// A complete remote inventory plus reconciliation accounts for lost claim replies.
func (s *Store) ResolveClaims(ctx context.Context) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.transact(ctx, func(tx *sql.Tx) error {
		_, e := s.active(ctx, tx)
		if e != nil {
			return e
		}
		_, e = tx.ExecContext(ctx, "INSERT OR IGNORE INTO kkdugi_runner_superseded SELECT id,'remote-inventory-reconciled' FROM kkdugi_runner_request WHERE path='/assignments/claim' AND status!='ACKED'")
		if e != nil {
			return ErrStorage
		}
		_, e = tx.ExecContext(ctx, "UPDATE kkdugi_runner_request SET superseded=1 WHERE path='/assignments/claim' AND status!='ACKED'")
		if e != nil {
			return ErrStorage
		}
		return nil
	})
}
