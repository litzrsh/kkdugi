package state

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strings"

	"kkdugi-runner/internal/client"
)

type Installation struct {
	Code   string
	Report client.ProgramReport
}
type Program struct {
	Code     string
	Report   client.ProgramReport
	Approval *client.ProgramApproval
}

func (Program) String() string   { return "[PROGRAM CATALOG]" }
func (Program) GoString() string { return "[PROGRAM CATALOG]" }

// SyncPrograms takes an authoritative complete configuration snapshot. A failed
// validation or immutable-revision check rolls back the entire refresh.
func (s *Store) SyncPrograms(ctx context.Context, items []Installation) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.transact(ctx, func(tx *sql.Tx) error {
		seen := map[string]bool{}
		for _, item := range items {
			if !validID(item.Code) || seen[item.Code] {
				return ErrInvalid
			}
			seen[item.Code] = true
			body, err := client.CanonicalProgram(item.Report)
			if err != nil {
				return ErrInvalid
			}
			definition := item.Report
			definition.Availability = "AVAILABLE"
			content, err := client.CanonicalProgram(definition)
			if err != nil {
				return ErrInvalid
			}
			var old []byte
			var oldHash string
			err = tx.QueryRowContext(ctx, "SELECT definition,definition_hash FROM kkdugi_runner_program_revision WHERE code=? AND revision=?", item.Code, item.Report.Revision).Scan(&old, &oldHash)
			if err == nil {
				if digest(old) != oldHash {
					return ErrSchema
				}
				if !bytes.Equal(old, content) {
					return ErrConflict
				}
			} else if errors.Is(err, sql.ErrNoRows) {
				if _, err = tx.ExecContext(ctx, "INSERT INTO kkdugi_runner_program_revision VALUES(?,?,?,?)", item.Code, item.Report.Revision, content, digest(content)); err != nil {
					return s.sqlFailure(err)
				}
			} else {
				return ErrStorage
			}
			_, err = tx.ExecContext(ctx, `INSERT INTO kkdugi_runner_program(code,revision,report,report_hash) VALUES(?,?,?,?)
ON CONFLICT(code) DO UPDATE SET revision=excluded.revision,report=excluded.report,report_hash=excluded.report_hash,
approval=CASE WHEN report=excluded.report THEN approval ELSE NULL END,
approval_session=CASE WHEN report=excluded.report THEN approval_session ELSE NULL END,
approval_boot=CASE WHEN report=excluded.report THEN approval_boot ELSE NULL END`, item.Code, item.Report.Revision, body, digest(body))
			if err != nil {
				return s.sqlFailure(err)
			}
		}
		rows, err := tx.QueryContext(ctx, "SELECT code,report,report_hash FROM kkdugi_runner_program")
		if err != nil {
			return ErrStorage
		}
		var missing []Installation
		for rows.Next() {
			var code, hash string
			var body []byte
			if rows.Scan(&code, &body, &hash) != nil {
				rows.Close()
				return ErrStorage
			}
			if !seen[code] {
				var report client.ProgramReport
				if digest(body) != hash || json.Unmarshal(body, &report) != nil {
					rows.Close()
					return ErrSchema
				}
				report.Availability = "MISSING"
				missing = append(missing, Installation{code, report})
			}
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return ErrStorage
		}
		for _, item := range missing {
			body, err := client.CanonicalProgram(item.Report)
			if err != nil {
				return ErrSchema
			}
			if _, err = tx.ExecContext(ctx, "UPDATE kkdugi_runner_program SET report=?,report_hash=?,approval=NULL,approval_session=NULL,approval_boot=NULL WHERE code=?", body, digest(body), item.Code); err != nil {
				return ErrStorage
			}
		}
		return nil
	})
}

type programRow struct {
	code          string
	body          []byte
	requestID     sql.NullString
	approval      []byte
	session, boot sql.NullString
}

func readProgram(ctx context.Context, q queryer, code string) (programRow, error) {
	var r programRow
	var hash string
	err := q.QueryRowContext(ctx, "SELECT code,report,report_hash,request_id,approval,approval_session,approval_boot FROM kkdugi_runner_program WHERE code=?", code).Scan(&r.code, &r.body, &hash, &r.requestID, &r.approval, &r.session, &r.boot)
	if errors.Is(err, sql.ErrNoRows) {
		return r, ErrNotFound
	}
	if err != nil {
		return r, ErrStorage
	}
	if digest(r.body) != hash || !object(r.body) {
		return r, ErrSchema
	}
	return r, nil
}

func (s *Store) QueueProgramReport(ctx context.Context, code string) (Request, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var result Request
	err := s.transact(ctx, func(tx *sql.Tx) error {
		session, err := s.active(ctx, tx)
		if err != nil {
			return err
		}
		p, err := readProgram(ctx, tx, code)
		if err != nil {
			return err
		}
		if p.requestID.Valid {
			r, err := getRequest(ctx, tx, p.requestID.String)
			if err != nil {
				return err
			}
			if r.Session == session && r.Status != "ACKED" {
				result = r
				return nil
			}
		}
		result, err = s.enqueue(ctx, tx, session, RequestDraft{Method: "PUT", Path: "/programs/" + code, Body: p.body})
		if err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, "UPDATE kkdugi_runner_program SET request_id=?,approval=NULL,approval_session=NULL,approval_boot=NULL WHERE code=?", result.ID, code)
		if err != nil {
			return ErrStorage
		}
		return nil
	})
	if err != nil {
		return Request{}, err
	}
	return result, nil
}

func (s *Store) AcceptProgramReport(ctx context.Context, id string, approval client.ProgramApproval) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.transact(ctx, func(tx *sql.Tx) error {
		session, err := s.active(ctx, tx)
		if err != nil {
			return err
		}
		r, err := getRequest(ctx, tx, id)
		if err != nil {
			return err
		}
		if r.Method != "PUT" || !strings.HasPrefix(r.Path, "/programs/") {
			return ErrInvalid
		}
		var report client.ProgramReport
		if json.Unmarshal(r.Body, &report) != nil {
			return ErrSchema
		}
		body, err := json.Marshal(approval)
		if err != nil {
			return ErrInvalid
		}
		if _, err = client.DecodeProgramApproval(body, report); err != nil {
			return ErrInvalid
		}
		code := strings.TrimPrefix(r.Path, "/programs/")
		p, err := readProgram(ctx, tx, code)
		if err != nil {
			return err
		}
		if err = s.ack(ctx, tx, session, Acknowledgement{RequestID: id, Response: body}); err != nil {
			return err
		}
		if !p.requestID.Valid || p.requestID.String != id || !bytes.Equal(p.body, r.Body) {
			return nil
		}
		_, err = tx.ExecContext(ctx, "UPDATE kkdugi_runner_program SET approval=?,approval_session=?,approval_boot=? WHERE code=?", body, session, s.bootID, code)
		if err != nil {
			return ErrStorage
		}
		return nil
	})
}

// Program only exposes approval from this boot's confirmed session and latest ACK.
func (s *Store) Program(ctx context.Context, code string) (_ Program, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	defer func() {
		if errors.Is(err, ErrStorage) || errors.Is(err, ErrSchema) {
			s.faulted = true
		}
	}()
	var result Program
	if s.closed || s.faulted {
		return result, ErrStorage
	}
	p, err := readProgram(ctx, s.conn, code)
	if err != nil {
		return result, err
	}
	result.Code = code
	if json.Unmarshal(p.body, &result.Report) != nil {
		return Program{}, ErrSchema
	}
	session, err := s.active(ctx, s.conn)
	if errors.Is(err, ErrSession) {
		return result, nil
	}
	if err != nil {
		return Program{}, err
	}
	if p.session.String == string(session) && p.boot.String == s.bootID && p.approval != nil && p.requestID.Valid {
		r, err := getRequest(ctx, s.conn, p.requestID.String)
		if err != nil {
			return Program{}, err
		}
		if r.Status == "ACKED" && bytes.Equal(r.Body, p.body) {
			a, err := client.DecodeProgramApproval(p.approval, result.Report)
			if err != nil {
				return Program{}, ErrSchema
			}
			result.Approval = &a
		}
	}
	return result, nil
}

func (s *Store) MarkProgramUnavailable(ctx context.Context, code string, expected client.ProgramReport, availability string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if availability != "MISSING" && availability != "INVALID" {
		return ErrInvalid
	}
	body, err := client.CanonicalProgram(expected)
	if err != nil {
		return ErrInvalid
	}
	return s.transact(ctx, func(tx *sql.Tx) error {
		p, err := readProgram(ctx, tx, code)
		if err != nil {
			return err
		}
		if !bytes.Equal(p.body, body) {
			return ErrConflict
		}
		expected.Availability = availability
		changed, err := client.CanonicalProgram(expected)
		if err != nil {
			return ErrInvalid
		}
		_, err = tx.ExecContext(ctx, "UPDATE kkdugi_runner_program SET report=?,report_hash=?,approval=NULL,approval_session=NULL,approval_boot=NULL WHERE code=?", changed, digest(changed), code)
		if err != nil {
			return ErrStorage
		}
		return nil
	})
}

func (s *Store) ProgramCodes(ctx context.Context, after string, limit int) ([]string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed || s.faulted {
		return nil, ErrStorage
	}
	if limit < 1 || limit > 200 {
		return nil, ErrInvalid
	}
	rows, err := s.conn.QueryContext(ctx, "SELECT code FROM kkdugi_runner_program WHERE code>? ORDER BY code LIMIT ?", after, limit)
	if err != nil {
		return nil, ErrStorage
	}
	defer rows.Close()
	result := []string{}
	for rows.Next() {
		var code string
		if rows.Scan(&code) != nil {
			return nil, ErrStorage
		}
		result = append(result, code)
	}
	if rows.Err() != nil {
		return nil, ErrStorage
	}
	return result, nil
}
