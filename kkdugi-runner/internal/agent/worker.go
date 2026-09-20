package agent

import (
	"context"
	"encoding/json"
	"errors"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"kkdugi-runner/internal/client"
	"kkdugi-runner/internal/executor"
	"kkdugi-runner/internal/state"
	"kkdugi-runner/internal/workspace"
)

type entry struct {
	mu         sync.Mutex
	recoveryMu sync.Mutex
	journalMu  sync.Mutex
	identity   client.ProcessIdentity
	hold       bool
	assignment client.Assignment
	phase      state.Phase
	lease      time.Time
	reconcile  bool
	stopReason string
	grace      time.Duration
	cancel     context.CancelFunc
}

func (e *entry) stop(reason string, grace time.Duration) {
	e.mu.Lock()
	if e.stopReason == "" {
		e.stopReason = reason
	}
	if grace < e.grace {
		e.grace = grace
	}
	e.mu.Unlock()
	e.cancel()
}
func (e *entry) stopState() (string, time.Duration) {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.stopReason, e.grace
}

func (a *Agent) change(e *entry, next state.Phase, detail json.RawMessage, request *state.RequestDraft, ack *state.Acknowledgement) (state.Request, error) {
	e.journalMu.Lock()
	defer e.journalMu.Unlock()
	ctx := context.Background()
	saved, err := a.o.Store.Assignment(ctx, e.assignment.ID)
	if err != nil {
		return state.Request{}, err
	}
	r, err := a.o.Store.Commit(ctx, state.Mutation{Assignment: &state.AssignmentChange{ID: saved.ID, ExpectedVersion: saved.Version, ExpectedPhase: saved.Phase, NextPhase: next, Detail: detail}, Request: request, Ack: ack})
	if err == nil {
		e.mu.Lock()
		e.phase = next
		e.mu.Unlock()
	}
	return r, err
}
func (a *Agent) gate(e *entry, permit client.StartPermit) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	e.mu.Lock()
	defer e.mu.Unlock()
	start, _ := permit.StartBefore.Time()
	lease, _ := permit.LeaseUntil.Time()
	if a.inputCtx.Err() != nil || a.draining || a.fatal != nil || !a.healthy || a.diskBlocked.Load() || e.stopReason != "" || e.reconcile || !time.Now().Before(start) || !time.Now().Before(lease) || !time.Now().Before(e.lease) {
		return ErrStartBlocked
	}
	saved, err := a.o.Store.Assignment(context.Background(), e.assignment.ID)
	if err != nil {
		return err
	}
	_, err = a.o.Store.Commit(context.Background(), state.Mutation{Assignment: &state.AssignmentChange{ID: saved.ID, ExpectedVersion: saved.Version, ExpectedPhase: saved.Phase, NextPhase: state.StartIntent, Detail: encode(permit)}})
	if err == nil {
		e.phase = state.StartIntent
		if a.boundary != nil {
			a.boundary("start-intent")
		}
		if a.inputCtx.Err() != nil || !time.Now().Before(start) || !time.Now().Before(lease) || !time.Now().Before(e.lease) {
			return ErrStartBlocked
		}
	}
	return err
}
func (a *Agent) worker(ctx context.Context, e *entry) error {
	assignment := e.assignment
	p := assignment.Program
	program, err := a.catalog.Resolve(context.Background(), p.ID, p.Code, p.Version, p.Revision)
	if err != nil {
		return a.finish(ctx, e, notStarted("FAILED", "PROGRAM_UNAPPROVED"), nil)
	}
	work, err := workspace.Create(filepath.Join(a.o.Config.DataDir, "work"), workspace.Context{RunID: assignment.RunID, AssignmentID: assignment.ID, Attempt: assignment.Attempt, Session: string(assignment.Session), BusinessKey: assignment.Execution.BusinessKey}, assignment.Input)
	if err != nil {
		return a.finish(ctx, e, notStarted("FAILED", "WORKSPACE_FAILED"), nil)
	}
	env := work.Environment()
	secrets := []string{string(a.o.Token)}
	for _, name := range program.Manifest.SecretNames {
		if a.o.ResolveSecret == nil {
			return a.finish(ctx, e, notStarted("FAILED", "SECRET_MISSING"), nil)
		}
		value, err := a.o.ResolveSecret(name)
		if err != nil || strings.ContainsRune(value, 0) {
			return a.finish(ctx, e, notStarted("FAILED", "SECRET_MISSING"), nil)
		}
		env = append(env, name+"="+value)
		secrets = append(secrets, value)
	}
	if _, err = a.change(e, state.Prepared, encode(map[string]string{"workspace": work.Dir}), nil, nil); err != nil {
		return err
	}
	request, err := a.change(e, state.StartRequested, encode(map[string]string{}), &state.RequestDraft{AssignmentID: assignment.ID, Method: "POST", Path: "/assignments/" + assignment.ID + "/start", Body: encode(map[string]string{"programRevision": p.Revision})}, nil)
	if err != nil {
		return err
	}
	body, err := a.send(ctx, request.ID)
	if err != nil {
		return a.finish(ctx, e, notStarted("FAILED", "START_UNCONFIRMED"), err)
	}
	permit, err := client.DecodeStartPermit(body, assignment.ID)
	if err != nil {
		return a.finish(ctx, e, notStarted("FAILED", "START_UNCONFIRMED"), err)
	}
	if _, err = a.change(e, state.Permitted, body, nil, &state.Acknowledgement{RequestID: request.ID, Response: body}); err != nil {
		return err
	}
	e.mu.Lock()
	e.lease, _ = permit.LeaseUntil.Time()
	e.mu.Unlock()
	a.mu.Lock()
	if a.fatal == nil {
		a.healthy = true
	}
	a.mu.Unlock()
	// Re-read approval and digest after waiting for the start permission response.
	program, err = a.catalog.Resolve(context.Background(), p.ID, p.Code, p.Version, p.Revision)
	if err != nil {
		return a.finish(ctx, e, notStarted("FAILED", "REVISION_MISMATCH"), nil)
	}
	out, stderr, err := a.spool.Open(assignment.ID, secrets)
	if err != nil {
		a.diskBlocked.Store(true)
		return a.finish(ctx, e, notStarted("FAILED", "LOG_STORAGE_FAILED"), nil)
	}
	var journalErr error
	startedDone := make(chan error, 1)
	hasStarted := false
	var identity client.Started
	spec := executor.Spec{Executable: program.Manifest.Executable, Arguments: program.Manifest.Arguments, Directory: program.Manifest.WorkingDirectory, Environment: env, Stdout: out, Stderr: stderr, Timeout: time.Duration(assignment.Execution.TimeoutSeconds) * time.Second, StopGrace: time.Duration(assignment.Execution.StopGraceSeconds) * time.Second}
	spec.BeforeStart = func() error { return a.gate(e, permit) }
	spec.CancelGrace = func() time.Duration { _, g := e.stopState(); return g }
	spec.OnStarted = func(pid int, at time.Time) {
		timestamp := client.Timestamp(at.UTC().Format("2006-01-02T15:04:05.000000Z"))
		identity = client.Started{StartedAt: timestamp, Process: client.ProcessIdentity{PID: client.Decimal(strconv.Itoa(pid)), StartedAt: timestamp, BootID: a.boot}}
		e.mu.Lock()
		e.identity = identity.Process
		e.mu.Unlock()
		r, err := a.change(e, state.Running, encode(identity), &state.RequestDraft{AssignmentID: assignment.ID, Method: "POST", Path: "/assignments/" + assignment.ID + "/started", Body: encode(identity)}, nil)
		if err != nil {
			journalErr = err
			e.stop("SERVICE_STOP", 0)
			return
		}
		if a.boundary != nil {
			a.boundary("started")
		}
		hasStarted = true
		go func() {
			body, err := a.send(a.workCtx, r.ID)
			if err == nil {
				ack, decodeErr := client.DecodeStartedAck(body, assignment.ID)
				err = decodeErr
				if err == nil {
					_, err = a.o.Store.Commit(context.Background(), state.Mutation{Ack: &state.Acknowledgement{RequestID: r.ID, Response: body}})
					if ack.Action == "STOP" {
						e.stop("CANCEL", spec.StopGrace)
					}
				}
			}
			if err != nil {
				if needsReconcile(err) {
					e.mu.Lock()
					e.reconcile = true
					e.mu.Unlock()
				} else {
					a.fail(err)
				}
			}
			startedDone <- err
		}()
	}
	spec.OnStopping = func(reason string) {
		stop, _ := e.stopState()
		if stop == "" {
			stop = reason
		}
		_, err := a.change(e, state.Stopping, encode(map[string]any{"started": identity, "reason": stop}), nil, nil)
		if err != nil && journalErr == nil {
			journalErr = err
		}
	}
	result := executor.Run(ctx, spec)
	if a.boundary != nil {
		a.boundary("exited")
	}
	stdoutEnd, stderrEnd := out.Close(), stderr.Close()
	if journalErr != nil {
		if hasStarted {
			<-startedDone
		}
		return journalErr
	}
	if !result.ProcessExited {
		_, err = a.change(e, state.Unknown, encode(map[string]any{"process": identity.Process, "reason": "PROCESS_UNCONFIRMED"}), nil, nil)
		a.fail(ErrRecovery)
		if hasStarted {
			<-startedDone
		}
		return errors.Join(ErrRecovery, err)
	}
	completion := client.Completion{Outcome: "SUCCEEDED", FinishedAt: now(), ExitCode: result.ExitCode, ProcessExited: true, Logs: map[string]client.LogEnd{"STDOUT": stdoutEnd, "STDERR": stderrEnd}}
	if !result.StartedAt.IsZero() {
		start := client.Timestamp(result.StartedAt.UTC().Format("2006-01-02T15:04:05.000000Z"))
		completion.StartedAt = &start
	}
	if !result.FinishedAt.IsZero() {
		completion.FinishedAt = client.Timestamp(result.FinishedAt.UTC().Format("2006-01-02T15:04:05.000000Z"))
	}
	reason, _ := e.stopState()
	switch result.Reason {
	case "CANCELED":
		completion.Outcome = "CANCELED"
		if reason == "TIMEOUT" {
			completion.Outcome = "TIMED_OUT"
		} else if reason == "SERVICE_STOP" || reason == "" {
			completion.Outcome = "FAILED"
			completion.Failure = &client.Failure{Code: "RUNNER_STOPPED", Message: "runner stopped before completion"}
		}
	case "TIMED_OUT":
		completion.Outcome = "TIMED_OUT"
	case "START_FAILED":
		completion.Outcome = "FAILED"
		completion.Failure = &client.Failure{Code: "SPAWN_FAILED", Message: "program was not started"}
		if reason == "CANCEL" {
			completion.Outcome = "CANCELED"
			completion.Failure = nil
		} else if reason == "TIMEOUT" {
			completion.Outcome = "TIMED_OUT"
			completion.Failure = nil
		}
	default:
		if result.Err != nil || result.ExitCode == nil || *result.ExitCode != 0 {
			completion.Outcome = "FAILED"
			completion.Failure = &client.Failure{Code: "EXIT_NONZERO", Message: "program execution failed"}
		}
	}
	if completion.StartedAt != nil {
		value, err := work.Result()
		if err != nil || len(value) > a.settings.Limits.ResultBytes {
			if completion.Outcome == "SUCCEEDED" {
				completion.Outcome = "FAILED"
				completion.Failure = &client.Failure{Code: "OUTPUT_INVALID", Message: "program result is invalid"}
			}
		} else {
			completion.Result = value
		}
	}
	// Persist completion before waiting on the started-report HTTP response.
	r, err := a.saveCompletion(e, completion)
	if err != nil {
		if hasStarted {
			<-startedDone
		}
		return err
	}
	if hasStarted {
		if err = <-startedDone; err != nil && !needsReconcile(err) {
			return err
		}
	}
	return a.reportCompletion(a.workCtx, e, r, completion)
}
func notStarted(outcome, code string) client.Completion {
	c := client.Completion{Outcome: outcome, FinishedAt: now(), ProcessExited: true, Logs: map[string]client.LogEnd{"STDOUT": {Status: "COMPLETE"}, "STDERR": {Status: "COMPLETE"}}}
	if outcome == "FAILED" {
		c.Failure = &client.Failure{Code: code, Message: "program was not started"}
	}
	return c
}
func (a *Agent) saveCompletion(e *entry, c client.Completion) (state.Request, error) {
	body := encode(c)
	_, err := a.change(e, state.Finished, body, nil, nil)
	if err == nil && a.boundary != nil {
		a.boundary("finished")
	}
	return state.Request{Body: body}, err
}
func (a *Agent) finish(ctx context.Context, e *entry, c client.Completion, prior error) error {
	reason, _ := e.stopState()
	if c.StartedAt == nil {
		if reason == "CANCEL" {
			c.Outcome = "CANCELED"
			c.Failure = nil
		} else if reason == "TIMEOUT" {
			c.Outcome = "TIMED_OUT"
			c.Failure = nil
		}
	}
	if reason != "" && errors.Is(prior, context.Canceled) {
		prior = nil
	}
	r, err := a.saveCompletion(e, c)
	if err != nil {
		return err
	}
	if prior != nil {
		if needsReconcile(prior) {
			e.mu.Lock()
			e.reconcile = true
			e.mu.Unlock()
			return a.reportCompletion(a.workCtx, e, r, c)
		}
		return prior
	}
	return a.reportCompletion(a.workCtx, e, r, c)
}
func (a *Agent) reportCompletion(ctx context.Context, e *entry, r state.Request, c client.Completion) error {
	e.recoveryMu.Lock()
	defer e.recoveryMu.Unlock()
	e.mu.Lock()
	needs := e.reconcile || e.hold
	e.mu.Unlock()
	if needs {
		return a.reconcileCompletion(ctx, e)
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	if err := a.failure(); err != nil {
		return err
	}
	var err error
	r, err = a.o.Store.Commit(context.Background(), state.Mutation{Request: &state.RequestDraft{AssignmentID: e.assignment.ID, Method: "POST", Path: "/assignments/" + e.assignment.ID + "/completion", Body: r.Body}})
	if err != nil {
		return err
	}
	body, err := a.send(ctx, r.ID)
	if needsReconcile(err) {
		return a.reconcileCompletion(ctx, e)
	}
	if err != nil {
		return err
	}
	if _, err = client.DecodeCompletionAck(body, e.assignment.ID, c.Outcome); err != nil {
		return err
	}
	_, err = a.change(e, state.Acked, r.Body, nil, &state.Acknowledgement{RequestID: r.ID, Response: body})
	return err
}
