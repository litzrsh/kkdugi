package state

import (
	"context"
	"database/sql"
	"errors"
)

type LogStream struct {
	AssignmentID, Stream, Status string
	Next, Acked, Bytes           int64
	Closed                       bool
}
type LogFile struct {
	AssignmentID, Stream, Hash string
	Sequence, Bytes            int64
}

func logStream(ctx context.Context, q queryer, id, stream string) (LogStream, error) {
	var v LogStream
	v.AssignmentID = id
	v.Stream = stream
	e := q.QueryRowContext(ctx, "SELECT next_sequence,acked_through,total_bytes,status,closed FROM kkdugi_runner_log_stream WHERE assignment_id=? AND stream=?", id, stream).Scan(&v.Next, &v.Acked, &v.Bytes, &v.Status, &v.Closed)
	if errors.Is(e, sql.ErrNoRows) {
		return v, ErrNotFound
	}
	if e != nil {
		return v, ErrStorage
	}
	return v, nil
}
func (s *Store) OpenLogs(ctx context.Context, id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.transact(ctx, func(tx *sql.Tx) error {
		session, e := s.active(ctx, tx)
		if e != nil {
			return e
		}
		a, e := getAssignment(ctx, tx, id)
		if e != nil {
			return e
		}
		if a.Session != session || a.BootID != s.bootID {
			return ErrSession
		}
		for _, stream := range []string{"STDOUT", "STDERR"} {
			if _, e = tx.ExecContext(ctx, "INSERT INTO kkdugi_runner_log_stream(assignment_id,stream) VALUES(?,?)", id, stream); e != nil {
				return s.sqlFailure(e)
			}
		}
		return nil
	})
}
func (s *Store) LogStreams(ctx context.Context) ([]LogStream, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	rows, e := s.conn.QueryContext(ctx, "SELECT assignment_id,stream,next_sequence,acked_through,total_bytes,status,closed FROM kkdugi_runner_log_stream ORDER BY assignment_id,stream")
	if e != nil {
		return nil, ErrStorage
	}
	defer rows.Close()
	var result []LogStream
	for rows.Next() {
		var v LogStream
		if rows.Scan(&v.AssignmentID, &v.Stream, &v.Next, &v.Acked, &v.Bytes, &v.Status, &v.Closed) != nil {
			return nil, ErrStorage
		}
		result = append(result, v)
	}
	return result, rows.Err()
}
func (s *Store) LogFiles(ctx context.Context) ([]LogFile, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	rows, e := s.conn.QueryContext(ctx, "SELECT assignment_id,stream,sequence,hash,file_bytes FROM kkdugi_runner_log_chunk ORDER BY assignment_id,stream,sequence")
	if e != nil {
		return nil, ErrStorage
	}
	defer rows.Close()
	var result []LogFile
	for rows.Next() {
		var v LogFile
		if rows.Scan(&v.AssignmentID, &v.Stream, &v.Sequence, &v.Hash, &v.Bytes) != nil {
			return nil, ErrStorage
		}
		result = append(result, v)
	}
	return result, rows.Err()
}
func (s *Store) AppendLog(ctx context.Context, f LogFile, textBytes int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.transact(ctx, func(tx *sql.Tx) error {
		v, e := logStream(ctx, tx, f.AssignmentID, f.Stream)
		if e != nil {
			return e
		}
		if v.Closed || v.Status != "COMPLETE" || v.Next != f.Sequence || textBytes < 1 || textBytes > 32768 || len(f.Hash) != 64 || f.Bytes < 1 {
			return ErrConflict
		}
		if _, e = tx.ExecContext(ctx, "INSERT INTO kkdugi_runner_log_chunk VALUES(?,?,?,?,?)", f.AssignmentID, f.Stream, f.Sequence, f.Hash, f.Bytes); e != nil {
			return s.sqlFailure(e)
		}
		if _, e = tx.ExecContext(ctx, "UPDATE kkdugi_runner_log_stream SET next_sequence=next_sequence+1,total_bytes=total_bytes+? WHERE assignment_id=? AND stream=?", textBytes, f.AssignmentID, f.Stream); e != nil {
			return ErrStorage
		}
		return nil
	})
}
func (s *Store) CloseLog(ctx context.Context, id, stream, status string) error {
	if status != "COMPLETE" && status != "TRUNCATED" && status != "LOST" {
		return ErrInvalid
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.transact(ctx, func(tx *sql.Tx) error {
		v, e := logStream(ctx, tx, id, stream)
		if e != nil {
			return e
		}
		if v.Closed {
			if v.Status != status {
				return ErrConflict
			}
			return nil
		}
		_, e = tx.ExecContext(ctx, "UPDATE kkdugi_runner_log_stream SET closed=1,status=? WHERE assignment_id=? AND stream=?", status, id, stream)
		if e != nil {
			return ErrStorage
		}
		return nil
	})
}

// Persist contiguous ACK first. Physical file cleanup is safe to repeat after crash.
func (s *Store) AckLog(ctx context.Context, id, stream string, through int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.transact(ctx, func(tx *sql.Tx) error {
		v, e := logStream(ctx, tx, id, stream)
		if e != nil {
			return e
		}
		if through < -1 || through >= v.Next {
			return ErrConflict
		}
		if through <= v.Acked {
			return nil
		}
		_, e = tx.ExecContext(ctx, "UPDATE kkdugi_runner_log_stream SET acked_through=? WHERE assignment_id=? AND stream=?", through, id, stream)
		if e != nil {
			return ErrStorage
		}
		return nil
	})
}
func (s *Store) ForgetLogFile(ctx context.Context, f LogFile) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.transact(ctx, func(tx *sql.Tx) error {
		v, e := logStream(ctx, tx, f.AssignmentID, f.Stream)
		if e != nil {
			return e
		}
		if f.Sequence > v.Acked {
			return ErrConflict
		}
		_, e = tx.ExecContext(ctx, "DELETE FROM kkdugi_runner_log_chunk WHERE assignment_id=? AND stream=? AND sequence=?", f.AssignmentID, f.Stream, f.Sequence)
		if e != nil {
			return ErrStorage
		}
		return nil
	})
}
