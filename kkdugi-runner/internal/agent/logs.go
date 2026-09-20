package agent

import (
	"context"
	"kkdugi-runner/internal/client"
	"kkdugi-runner/internal/state"
	"strconv"
	"time"
)

// One pass gives each assignment/stream one turn. Failed chunks remain immutable.
func (a *Agent) uploadLogs(ctx context.Context) {
	next := map[string]time.Time{}
	delay := map[string]time.Duration{}
	for ctx.Err() == nil {
		files, err := a.o.Store.LogFiles(ctx)
		if err != nil {
			if ctx.Err() == nil {
				a.diskBlocked.Store(true)
			}
			return
		}
		seen := map[string]bool{}
		for _, f := range files {
			if ctx.Err() != nil {
				return
			}
			key := f.AssignmentID + "/" + f.Stream
			if seen[key] || time.Now().Before(next[key]) {
				continue
			}
			seen[key] = true
			if a.recovering.Load() {
				continue
			}
			c, e := a.spool.Read(f)
			if e != nil {
				a.diskBlocked.Store(true)
				return
			}
			ack, e := a.o.Client.UploadLog(ctx, a.o.Token, a.session, f.AssignmentID, f.Stream, client.Decimal(strconv.FormatInt(f.Sequence, 10)), c)
			if needsReconcile(e) {
				saved, readErr := a.o.Store.Assignment(ctx, f.AssignmentID)
				if readErr == nil && saved.Phase == state.Acked {
					result, recoveryErr := a.reconcile(ctx, saved, nil)
					if recoveryErr == nil && result.Disposition == "RESOLVED" {
						next[key] = time.Now().Add(time.Second)
						continue
					}
					e = recoveryErr
					if e == nil {
						e = ErrRecovery
					}
				} else {
					a.mu.Lock()
					entry := a.entries[f.AssignmentID]
					a.mu.Unlock()
					if entry != nil {
						entry.mu.Lock()
						entry.reconcile = true
						entry.mu.Unlock()
						next[key] = time.Now().Add(time.Second)
						continue
					}
				}
			}
			if e == nil {
				e = a.spool.Ack(f.AssignmentID, f.Stream, ack.ContiguousThrough)
			}
			if e != nil {
				if !retryable(e) {
					a.diskBlocked.Store(true)
					return
				}
				delay[key] = min(max(delay[key]*2, 100*time.Millisecond), 5*time.Second)
				next[key] = time.Now().Add(retryDelay(e, delay[key]))
				continue
			}
			delay[key] = 100 * time.Millisecond
			next[key] = time.Now().Add(100 * time.Millisecond)
		}
		if !wait(ctx, 100*time.Millisecond) {
			return
		}
	}
}
