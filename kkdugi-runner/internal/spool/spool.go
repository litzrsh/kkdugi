package spool

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"kkdugi-runner/internal/client"
	"kkdugi-runner/internal/credential"
	"kkdugi-runner/internal/state"
)

var ErrCapacity = errors.New("log spool capacity or disk reserve exhausted")
var ErrCorrupt = errors.New("log spool requires recovery: missing or invalid durable chunk")

type Options struct {
	Directory                            string
	Store                                *state.Store
	ChunkBytes                           int
	AttemptBytes, MaxBytes, ReserveBytes int64
	OnError                              func(error)
}
type Spool struct {
	mu    sync.Mutex
	o     Options
	used  int64
	fault error
}
type Writer struct {
	spool       *Spool
	id, stream  string
	mask        *masker
	pending     string
	next, bytes int64
	status      string
	closed      bool
}

func New(o Options) (*Spool, error) {
	if o.Store == nil || !filepath.IsAbs(o.Directory) || o.ChunkBytes < 4 || o.ChunkBytes > 32768 {
		return nil, ErrCorrupt
	}
	if o.AttemptBytes == 0 {
		o.AttemptBytes = 10 << 20
	}
	if o.MaxBytes == 0 {
		o.MaxBytes = 256 << 20
	}
	if o.ReserveBytes == 0 {
		o.ReserveBytes = 64 << 20
	}
	if o.AttemptBytes < 1 || o.MaxBytes < 1 || o.ReserveBytes < 0 {
		return nil, ErrCapacity
	}
	if e := credential.EnsureDirectory(o.Directory); e != nil {
		return nil, e
	}
	s := &Spool{o: o}
	if e := s.recover(); e != nil {
		return nil, e
	}
	return s, nil
}
func (s *Spool) path(f state.LogFile) string {
	return filepath.Join(s.o.Directory, fmt.Sprintf("%s.%s.%d.json", f.AssignmentID, f.Stream, f.Sequence))
}
func (s *Spool) setFault(e error) {
	if s.fault == nil {
		s.fault = e
		if s.o.OnError != nil {
			s.o.OnError(e)
		}
	}
}
func (s *Spool) Healthy() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.fault == nil && s.capacity(0) == nil
}
func (s *Spool) capacity(n int64) error {
	if s.used+n > s.o.MaxBytes {
		return ErrCapacity
	}
	free, e := available(s.o.Directory)
	if e != nil {
		return e
	}
	if free < s.o.ReserveBytes+n {
		return ErrCapacity
	}
	return nil
}
func (s *Spool) Open(id string, secrets []string) (*Writer, *Writer, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.fault != nil {
		return nil, nil, s.fault
	}
	if e := s.capacity(0); e != nil {
		return nil, nil, e
	}
	if e := s.o.Store.OpenLogs(context.Background(), id); e != nil {
		return nil, nil, e
	}
	makeWriter := func(stream string) *Writer {
		return &Writer{spool: s, id: id, stream: stream, mask: newMasker(secrets), status: "COMPLETE"}
	}
	return makeWriter("STDOUT"), makeWriter("STDERR"), nil
}
func (w *Writer) Write(b []byte) (int, error) {
	s := w.spool
	s.mu.Lock()
	defer s.mu.Unlock()
	n := len(b)
	if w.closed {
		return n, nil
	}
	// Even after storage failure consume every byte from executor's pipes.
	for len(b) > 0 {
		take := min(len(b), 32768)
		if w.status == "COMPLETE" {
			w.pending += w.mask.feed(b[:take], false)
			w.flush(false)
		}
		b = b[take:]
	}
	return n, nil
}
func (w *Writer) flush(eof bool) {
	for len(w.pending) >= w.spool.o.ChunkBytes || (eof && len(w.pending) > 0) {
		n := min(len(w.pending), w.spool.o.ChunkBytes)
		for n > 0 && n < len(w.pending) && !utf8.RuneStart(w.pending[n]) {
			n--
		}
		if n == 0 {
			w.status = "LOST"
			w.spool.setFault(ErrCorrupt)
			return
		}
		text := w.pending[:n]
		if e := w.append(text); e != nil {
			if errors.Is(e, errAttempt) {
				w.status = "TRUNCATED"
			} else {
				w.status = "LOST"
				w.spool.setFault(e)
			}
			w.pending = ""
			w.mask = nil
			return
		}
		w.pending = w.pending[n:]
	}
}

var errAttempt = errors.New("attempt log limit exceeded")

func (w *Writer) append(text string) error {
	s := w.spool
	ctx := context.Background()
	streams, e := s.o.Store.LogStreams(ctx)
	if e != nil {
		return e
	}
	var total int64
	for _, v := range streams {
		if v.AssignmentID == w.id {
			total += v.Bytes
		}
	}
	if total+int64(len(text)) > s.o.AttemptBytes {
		return errAttempt
	}
	chunk := client.LogChunk{EmittedAt: client.Timestamp(time.Now().UTC().Format("2006-01-02T15:04:05.000000Z")), Text: text, SHA256: client.LogHash(text)}
	b, e := json.Marshal(chunk)
	if e != nil {
		return e
	}
	if e = s.capacity(int64(len(b))); e != nil {
		return e
	}
	meta := state.LogFile{AssignmentID: w.id, Stream: w.stream, Sequence: w.next, Hash: client.LogHash(string(b)), Bytes: int64(len(b))}
	path := s.path(meta)
	temp := path + ".tmp"
	f, e := credential.OpenPrivateFile(temp, true)
	if e != nil {
		return e
	}
	_, e = f.Write(b)
	if e == nil {
		e = f.Sync()
	}
	closeErr := f.Close()
	if e == nil {
		e = closeErr
	}
	if e != nil {
		return e
	}
	if _, e = os.Lstat(path); !errors.Is(e, os.ErrNotExist) {
		return ErrCorrupt
	}
	if e = os.Rename(temp, path); e != nil {
		return e
	}
	if e = credential.SyncDirectory(s.o.Directory); e != nil {
		return e
	}
	s.used += meta.Bytes
	if e = s.o.Store.AppendLog(ctx, meta, int64(len(text))); e != nil {
		return e
	}
	w.next++
	w.bytes += int64(len(text))
	return nil
}
func (w *Writer) Close() client.LogEnd {
	s := w.spool
	s.mu.Lock()
	defer s.mu.Unlock()
	if !w.closed {
		if w.status == "COMPLETE" {
			w.pending += w.mask.feed(nil, true)
			w.flush(true)
		}
		w.closed = true
		if e := s.o.Store.CloseLog(context.Background(), w.id, w.stream, w.status); e != nil {
			w.status = "LOST"
			s.setFault(e)
		}
	}
	var seq *client.Decimal
	if w.next > 0 {
		d := client.Decimal(strconv.FormatInt(w.next-1, 10))
		seq = &d
	}
	return client.LogEnd{LastSequence: seq, Status: w.status}
}
func (s *Spool) Read(f state.LogFile) (client.LogChunk, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.read(f)
}
func (s *Spool) read(f state.LogFile) (client.LogChunk, error) {
	var c client.LogChunk
	path := s.path(f)
	if credential.CheckFile(path) != nil {
		return c, ErrCorrupt
	}
	info, e := os.Stat(path)
	if e != nil || info.Size() != f.Bytes || info.Size() > 256<<10 {
		return c, ErrCorrupt
	}
	b, e := os.ReadFile(path)
	if e != nil || client.LogHash(string(b)) != f.Hash || json.Unmarshal(b, &c) != nil || !utf8.ValidString(c.Text) || client.LogHash(c.Text) != c.SHA256 {
		return c, ErrCorrupt
	}
	return c, nil
}
func (s *Spool) Ack(id, stream string, through *client.Decimal) error {
	if through == nil {
		return nil
	}
	n, e := client.Sequence(*through)
	if e != nil {
		return e
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if e = s.o.Store.AckLog(context.Background(), id, stream, n); e != nil {
		return e
	}
	return s.cleanup()
}
func (s *Spool) cleanup() error {
	ctx := context.Background()
	streams, e := s.o.Store.LogStreams(ctx)
	if e != nil {
		return e
	}
	files, e := s.o.Store.LogFiles(ctx)
	if e != nil {
		return e
	}
	offsets := map[string]int64{}
	for _, v := range streams {
		offsets[v.AssignmentID+"/"+v.Stream] = v.Acked
	}
	for _, f := range files {
		if f.Sequence > offsets[f.AssignmentID+"/"+f.Stream] {
			continue
		}
		path := s.path(f)
		if credential.CheckFile(path) != nil {
			return ErrCorrupt
		}
		if e = os.Remove(path); e != nil && !errors.Is(e, os.ErrNotExist) {
			return e
		}
		if e = credential.SyncDirectory(s.o.Directory); e != nil {
			return e
		}
		if e = s.o.Store.ForgetLogFile(ctx, f); e != nil {
			return e
		}
		s.used = max(0, s.used-f.Bytes)
	}
	return nil
}
func (s *Spool) recover() error {
	ctx := context.Background()
	if e := s.cleanup(); e != nil {
		return e
	}
	files, e := s.o.Store.LogFiles(ctx)
	if e != nil {
		return e
	}
	known := map[string]bool{}
	for _, f := range files {
		if _, e = s.read(f); e != nil {
			return e
		}
		known[filepath.Base(s.path(f))] = true
		s.used += f.Bytes
	}
	entries, e := os.ReadDir(s.o.Directory)
	if e != nil {
		return e
	}
	for _, f := range entries {
		if known[f.Name()] {
			continue
		}
		p := filepath.Join(s.o.Directory, f.Name())
		if f.IsDir() || credential.CheckFile(p) != nil {
			return ErrCorrupt
		}
		parts := strings.Split(f.Name(), ".")
		if len(parts) < 4 || len(parts) > 5 || (parts[1] != "STDOUT" && parts[1] != "STDERR") || parts[3] != "json" || (len(parts) == 5 && parts[4] != "tmp") {
			return ErrCorrupt
		}
		if _, e = strconv.ParseInt(parts[2], 10, 64); e != nil {
			return ErrCorrupt
		}
		// An unreferenced temp/final file never became a committed chunk. Never upload it.
		if e = os.Remove(p); e != nil {
			return e
		}
	}
	if e = credential.SyncDirectory(s.o.Directory); e != nil {
		return e
	}
	streams, e := s.o.Store.LogStreams(ctx)
	if e != nil {
		return e
	}
	for _, v := range streams {
		if !v.Closed {
			if e = s.o.Store.CloseLog(ctx, v.AssignmentID, v.Stream, "LOST"); e != nil {
				return e
			}
		}
	}
	return nil
}
