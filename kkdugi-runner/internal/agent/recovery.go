package agent

import (
	"context"
	"encoding/json"
	"errors"
	"kkdugi-runner/internal/client"
	"kkdugi-runner/internal/state"
	"time"
)

var errReconcile = errors.New("assignment reconciliation required")
var errHeld = errors.New("assignment held for operator recovery")

func needsReconcile(err error) bool {
	var api *client.APIError
	return errors.Is(err, errReconcile) || (errors.As(err, &api) && (api.Status == 409 || api.Status == 410))
}
func (a *Agent) reconcile(ctx context.Context, saved state.Assignment, live *entry) (client.ReconcileResponse, error) {
	var result client.ReconcileResponse
	observation := client.ReconcileRequest{PreviousSession: saved.Session, Observation: "UNKNOWN"}
	var marker map[string]string
	if json.Unmarshal(saved.Detail, &marker) == nil && marker["recovery"] == "remote-only" {
		var original client.Assignment
		if json.Unmarshal(saved.Snapshot, &original) != nil {
			return result, state.ErrSchema
		}
		observation.PreviousSession = original.Session
	}
	switch {
	case saved.Phase == state.Finished || saved.Phase == state.Acked:
		var c client.Completion
		if json.Unmarshal(saved.Detail, &c) == nil && c.ProcessExited {
			observation.Observation = "FINISHED"
			observation.Completion = saved.Detail
		}
	case live != nil && (saved.Phase == state.Running || saved.Phase == state.Stopping):
		live.mu.Lock()
		identity := live.identity
		live.mu.Unlock()
		if identity.PID != "" {
			observation.Observation = "RUNNING"
			observation.Process = &identity
		}
	case !saved.StartIntent && saved.Phase != state.Unknown:
		observation.Observation = "NEVER_STARTED"
	}
	r, e := a.o.Store.PrepareReconcile(ctx, saved.ID, encode(observation))
	if e != nil {
		return result, e
	}
	// Pending response-loss recovery must validate against the original observation.
	if json.Unmarshal(r.Body, &observation) != nil {
		return result, state.ErrSchema
	}
	var b []byte
	e = a.retry(ctx, func() error {
		var err error
		b, err = a.o.Client.AssignmentCommand(ctx, a.o.Token, r.Session, r.Key, r.CreatedAt, r.Path, r.Body)
		return err
	})
	if e != nil {
		return result, e
	}
	result, e = client.DecodeReconcile(b, saved.ID, a.session)
	if e != nil {
		return result, e
	}
	if (result.Disposition == "CONTINUE_EXISTING" || result.Disposition == "STOP_AND_REPORT") && (live == nil || observation.Observation != "RUNNING") {
		return result, client.ErrProtocol
	}
	if result.Disposition == "REPORT_COMPLETION" && observation.Observation != "FINISHED" {
		return result, client.ErrProtocol
	}
	if result.Disposition == "RESOLVED" && observation.Observation == "RUNNING" {
		return result, ErrRecovery
	} // never abandon a locally running process
	if live != nil {
		live.journalMu.Lock()
		defer live.journalMu.Unlock()
	}
	if e = a.o.Store.ApplyReconcile(ctx, r, b); e != nil {
		return result, e
	}
	return result, nil
}
func (a *Agent) restore(ctx context.Context) error {
	a.recovering.Store(true)
	defer a.recovering.Store(false)
	hbCtx, stop := context.WithCancel(ctx)
	done := make(chan struct{})
	go func() {
		defer close(done)
		for hbCtx.Err() == nil {
			a.o.Client.Heartbeat(hbCtx, a.o.Token, a.session, client.Heartbeat{ObservedAt: now(), Mode: "DEGRADED", FreeSlots: 0, Assignments: []client.HeartbeatItem{}})
			if !wait(hbCtx, a.heartbeatInterval) {
				return
			}
		}
	}()
	defer func() { stop(); <-done }()
	var ids []string
	if e := a.retry(ctx, func() error { var e error; ids, e = a.o.Client.Unresolved(ctx, a.o.Token, a.session); return e }); e != nil {
		return e
	}
	local := map[string]state.Assignment{}
	for cursor := ""; ; {
		items, e := a.o.Store.Assignments(ctx, cursor, 200)
		if e != nil {
			return e
		}
		if len(items) == 0 {
			break
		}
		for _, v := range items {
			local[v.ID] = v
			cursor = v.ID
		}
	}
	remote := map[string]bool{}
	for _, id := range ids {
		remote[id] = true
	}
	files, e := a.o.Store.LogFiles(ctx)
	if e != nil {
		return e
	}
	pendingLogs := map[string]bool{}
	for _, f := range files {
		pendingLogs[f.AssignmentID] = true
	}
	for id := range remote {
		if _, ok := local[id]; !ok {
			d, e := a.o.Client.AssignmentDetail(ctx, a.o.Token, a.session, id)
			if e != nil {
				return e
			}
			// No local journal means no proof of NEVER_STARTED, even if remote says ASSIGNED.
			_, e = a.o.Store.Commit(ctx, state.Mutation{Assignment: &state.AssignmentChange{ID: id, NextPhase: state.Received, Snapshot: encode(d.Assignment), Detail: encode(map[string]string{"recovery": "remote-only"})}})
			if e != nil {
				return e
			}
			saved, e := a.o.Store.Assignment(ctx, id)
			if e != nil {
				return e
			}
			_, e = a.o.Store.Commit(ctx, state.Mutation{Assignment: &state.AssignmentChange{ID: id, ExpectedPhase: saved.Phase, ExpectedVersion: saved.Version, NextPhase: state.Unknown, Detail: saved.Detail}})
			if e != nil {
				return e
			}
			local[id], e = a.o.Store.Assignment(ctx, id)
			if e != nil {
				return e
			}
		}
	}
	for id, saved := range local {
		if saved.Phase == state.Acked && !pendingLogs[id] && !remote[id] {
			continue
		}
		var detail client.AssignmentDetail
		if e := a.retry(ctx, func() error {
			var e error
			detail, e = a.o.Client.AssignmentDetail(ctx, a.o.Token, a.session, id)
			return e
		}); e != nil {
			return e
		}
		result, e := a.reconcile(ctx, saved, nil)
		if e != nil {
			return e
		}
		saved, e = a.o.Store.Assignment(ctx, id)
		if e != nil {
			return e
		}
		if result.Disposition == "REPORT_COMPLETION" {
			var c client.Completion
			if json.Unmarshal(saved.Detail, &c) != nil {
				return state.ErrSchema
			}
			entry := &entry{assignment: client.Assignment{ID: id}, phase: saved.Phase}
			if e = a.reportCompletion(ctx, entry, state.Request{Body: saved.Detail}, c); e != nil {
				return e
			}
		} else if result.Disposition == "HOLD" {
			var assignment client.Assignment
			json.Unmarshal(saved.Snapshot, &assignment)
			assignment.ID = id
			a.mu.Lock()
			a.entries[id] = &entry{assignment: assignment, phase: state.Unknown, cancel: func() {}, hold: true}
			a.mu.Unlock()
		}
		for stream, through := range detail.LogOffsets {
			if through != nil {
				if e = a.spool.Ack(id, stream, through); e != nil {
					return e
				}
			}
		}
	}
	return a.o.Store.ResolveClaims(ctx)
}
func (a *Agent) recoverLive(ctx context.Context) {
	for ctx.Err() == nil {
		a.mu.Lock()
		entries := []*entry{}
		for _, e := range a.entries {
			entries = append(entries, e)
		}
		a.mu.Unlock()
		for _, e := range entries {
			e.recoveryMu.Lock()
			e.mu.Lock()
			needed := e.reconcile || e.hold
			phase := e.phase
			e.mu.Unlock()
			if !needed || (phase != state.Running && phase != state.Stopping && phase != state.Unknown) {
				e.recoveryMu.Unlock()
				continue
			}
			saved, err := a.o.Store.Assignment(ctx, e.assignment.ID)
			var result client.ReconcileResponse
			if err == nil {
				var live *entry
				if phase != state.Unknown {
					live = e
				}
				result, err = a.reconcile(ctx, saved, live)
			}
			if err == nil {
				e.mu.Lock()
				e.reconcile = false
				e.hold = result.Disposition == "HOLD"
				if result.LeaseUntil != nil {
					e.lease, _ = result.LeaseUntil.Time()
				}
				e.mu.Unlock()
				if result.Disposition == "STOP_AND_REPORT" {
					e.stop("CANCEL", 0)
				}
				if result.Disposition == "RESOLVED" {
					a.mu.Lock()
					delete(a.entries, e.assignment.ID)
					a.mu.Unlock()
				}
				if result.Disposition == "REPORT_COMPLETION" {
					err = a.sendRecoveredCompletion(ctx, e)
					if err == nil {
						a.mu.Lock()
						delete(a.entries, e.assignment.ID)
						a.mu.Unlock()
					} else if ctx.Err() == nil {
						a.fail(err)
					}
				}
			} else if ctx.Err() == nil {
				a.fail(err)
			}
			e.recoveryMu.Unlock()
		}
		if !wait(ctx, time.Second) {
			return
		}
	}
}
func (a *Agent) reconcileCompletion(ctx context.Context, e *entry) error {
	saved, err := a.o.Store.Assignment(ctx, e.assignment.ID)
	if err != nil {
		return err
	}
	result, err := a.reconcile(ctx, saved, nil)
	if err != nil {
		return err
	}
	switch result.Disposition {
	case "RESOLVED":
		return nil
	case "REPORT_COMPLETION":
		return a.sendRecoveredCompletion(ctx, e)
	case "HOLD":
		e.mu.Lock()
		e.hold = true
		e.phase = state.Unknown
		e.mu.Unlock()
		return errHeld
	default:
		return client.ErrProtocol
	}
}
func (a *Agent) sendRecoveredCompletion(ctx context.Context, e *entry) error {
	saved, err := a.o.Store.Assignment(ctx, e.assignment.ID)
	if err != nil {
		return err
	}
	var c client.Completion
	if json.Unmarshal(saved.Detail, &c) != nil {
		return state.ErrSchema
	}
	r, err := a.o.Store.Commit(ctx, state.Mutation{Request: &state.RequestDraft{AssignmentID: saved.ID, Method: "POST", Path: "/assignments/" + saved.ID + "/completion", Body: saved.Detail}})
	if err != nil {
		return err
	}
	b, err := a.send(ctx, r.ID)
	if err != nil {
		return err
	}
	if _, err = client.DecodeCompletionAck(b, saved.ID, c.Outcome); err != nil {
		return err
	}
	_, err = a.change(e, state.Acked, saved.Detail, nil, &state.Acknowledgement{RequestID: r.ID, Response: b})
	return err
}

func (a *Agent) recoverLostClaim(ctx context.Context) error {
	a.recovering.Store(true)
	defer a.recovering.Store(false)
	var ids []string
	err := a.retry(ctx, func() error { var e error; ids, e = a.o.Client.Unresolved(ctx, a.o.Token, a.session); return e })
	if err != nil {
		return err
	}
	for _, id := range ids {
		if _, e := a.o.Store.Assignment(ctx, id); e == nil {
			continue
		} else if !errors.Is(e, state.ErrNotFound) {
			return e
		}
		d, e := a.o.Client.AssignmentDetail(ctx, a.o.Token, a.session, id)
		if e != nil {
			return e
		}
		_, e = a.o.Store.Commit(ctx, state.Mutation{Assignment: &state.AssignmentChange{ID: id, NextPhase: state.Received, Snapshot: encode(d.Assignment), Detail: encode(map[string]string{"recovery": "remote-only"})}})
		if e != nil {
			return e
		}
		saved, e := a.o.Store.Assignment(ctx, id)
		if e != nil {
			return e
		}
		_, e = a.o.Store.Commit(ctx, state.Mutation{Assignment: &state.AssignmentChange{ID: id, ExpectedPhase: saved.Phase, ExpectedVersion: saved.Version, NextPhase: state.Unknown, Detail: saved.Detail}})
		if e != nil {
			return e
		}
		saved, e = a.o.Store.Assignment(ctx, id)
		if e != nil {
			return e
		}
		result, e := a.reconcile(ctx, saved, nil)
		if e != nil {
			return e
		}
		if result.Disposition == "HOLD" {
			a.mu.Lock()
			a.entries[id] = &entry{assignment: d.Assignment, phase: state.Unknown, hold: true, cancel: func() {}}
			a.mu.Unlock()
		} else if result.Disposition != "RESOLVED" {
			return ErrRecovery
		}
	}
	if err = a.o.Store.ResolveClaims(ctx); err != nil {
		return err
	}
	a.mu.Lock()
	a.reserved = false
	a.mu.Unlock()
	return nil
}
