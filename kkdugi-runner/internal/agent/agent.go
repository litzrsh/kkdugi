package agent

import (
	"context"
	"encoding/json"
	"errors"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"

	"kkdugi-runner/internal/catalog"
	"kkdugi-runner/internal/client"
	"kkdugi-runner/internal/config"
	"kkdugi-runner/internal/credential"
	"kkdugi-runner/internal/spool"
	"kkdugi-runner/internal/state"
)

var ErrRecovery = errors.New("runner has unresolved state; reconciliation is required before new work")
var ErrStartBlocked = errors.New("start blocked by cancellation, connection state or expired permission")

type Options struct {
	Config        *config.Config
	Store         *state.Store
	Client        *client.Client
	Token         client.Secret
	Version       string
	ResolveSecret func(string) (string, error)
	DrainTimeout  time.Duration
}
type Agent struct {
	o                                      Options
	catalog                                *catalog.Catalog
	settings                               client.SessionResponse
	session                                client.Decimal
	boot                                   string
	capacity                               int
	mu                                     sync.Mutex
	entries                                map[string]*entry
	active                                 int
	reserved, healthy, accepting, draining bool
	fatal                                  error
	once                                   atomic.Bool
	diskBlocked, recovering                atomic.Bool
	spool                                  *spool.Spool
	boundary                               func(string) // deterministic crash boundaries, nil in production
	wg                                     sync.WaitGroup
	workCtx                                context.Context
	inputCtx                               context.Context
	cancelClaim                            context.CancelFunc
	pollInterval, heartbeatInterval        time.Duration // tests may shorten server ticks
}

func New(o Options) (*Agent, error) {
	if o.Config == nil || o.Store == nil || o.Client == nil || o.Config.Validate() != nil || o.Token.Validate() != nil || o.Version == "" {
		return nil, errors.New("invalid agent options")
	}
	if o.DrainTimeout == 0 {
		o.DrainTimeout = 30 * time.Second
	}
	if o.DrainTimeout < 0 {
		return nil, errors.New("invalid drain timeout")
	}
	return &Agent{o: o, catalog: catalog.New(o.Store, o.Client, o.Token), entries: map[string]*entry{}}, nil
}
func now() client.Timestamp {
	return client.Timestamp(time.Now().UTC().Format("2006-01-02T15:04:05.000000Z"))
}
func encode(v any) json.RawMessage { b, _ := json.Marshal(v); return b }
func (a *Agent) fail(err error) {
	a.mu.Lock()
	if a.fatal == nil {
		a.fatal = err
	}
	a.healthy = false
	cancel := a.cancelClaim
	a.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}
func (a *Agent) failure() error { a.mu.Lock(); defer a.mu.Unlock(); return a.fatal }
func (a *Agent) disconnected()  { a.mu.Lock(); a.healthy = false; a.mu.Unlock() }

func (a *Agent) bootstrap(ctx context.Context) error {
	for {
		q, err := a.o.Store.PrepareSession(ctx, a.o.Version)
		if err != nil {
			return err
		}
		var result client.SessionResponse
		err = a.retry(ctx, func() error { var e error; result, e = a.o.Client.OpenSession(ctx, a.o.Token, q); return e })
		if err != nil {
			return err
		}
		if err = a.o.Store.ConfirmSession(ctx, q, result); err != nil {
			return err
		}
		a.session, a.boot, err = a.o.Store.CurrentSession(ctx)
		if errors.Is(err, state.ErrSession) {
			continue
		}
		if err != nil {
			return err
		}
		a.settings = result
		break
	}
	a.mu.Lock()
	a.capacity = min(a.o.Config.Capacity, a.settings.Capacity)
	a.mu.Unlock()
	var spoolErr error
	a.spool, spoolErr = spool.New(spool.Options{Directory: filepath.Join(a.o.Config.DataDir, "spool"), Store: a.o.Store, ChunkBytes: min(a.settings.Limits.LogChunkBytes, (a.settings.Limits.JSONBytes-256)/6), OnError: func(error) { a.diskBlocked.Store(true) }})
	if spoolErr != nil {
		return spoolErr
	}
	if a.pollInterval == 0 {
		a.pollInterval = time.Duration(a.settings.PollSeconds) * time.Second
	}
	if a.heartbeatInterval == 0 {
		a.heartbeatInterval = time.Duration(a.settings.HeartbeatSeconds) * time.Second
	}
	if err := a.restore(ctx); err != nil {
		return err
	}
	if err := credential.EnsureDirectory(filepath.Join(a.o.Config.DataDir, "work")); err != nil {
		return err
	}
	if err := a.catalog.Refresh(ctx, a.o.Config.Programs); err != nil {
		return err
	}
	for cursor := ""; ; {
		codes, err := a.o.Store.ProgramCodes(ctx, cursor, 200)
		if err != nil {
			return err
		}
		if len(codes) == 0 {
			break
		}
		for _, code := range codes {
			if err = a.retry(ctx, func() error { return a.catalog.Publish(ctx, code) }); err != nil {
				return err
			}
			cursor = code
			// A previous boot's old report is never replayed by QueueProgramReport.
		}
	}
	return nil
}

func (a *Agent) Run(ctx context.Context) error {
	if !a.once.CompareAndSwap(false, true) {
		return errors.New("agent cannot be run twice")
	}
	if err := a.bootstrap(ctx); err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return err
	}
	a.inputCtx = ctx
	workCtx, cancel := context.WithCancel(context.Background())
	a.workCtx = workCtx
	claimCtx, cancelClaim := context.WithCancel(ctx)
	a.mu.Lock()
	a.cancelClaim = cancelClaim
	a.mu.Unlock()
	defer cancelClaim()
	defer cancel()
	heartDone := make(chan struct{})
	go func() { defer close(heartDone); a.heartbeats(workCtx) }()
	recoveryDone := make(chan struct{})
	go func() { defer close(recoveryDone); a.recoverLive(workCtx) }()
	defer func() { cancel(); <-recoveryDone }()
	logDone := make(chan struct{})
	go func() { defer close(logDone); a.uploadLogs(workCtx) }()
	defer func() { cancel(); <-logDone }()
	defer func() { cancel(); <-heartDone }()
	for {
		if ctx.Err() != nil {
			return a.drain(cancel)
		}
		if !a.spool.Healthy() {
			a.diskBlocked.Store(true)
		}
		a.mu.Lock()
		fatal := a.fatal
		active := a.active
		free := a.capacity - len(a.entries)
		canClaim := !a.diskBlocked.Load() && !a.recovering.Load() && fatal == nil && a.healthy && a.accepting && !a.reserved && free > 0
		for _, entry := range a.entries {
			entry.mu.Lock()
			pending := entry.reconcile
			entry.mu.Unlock()
			if pending {
				canClaim = false
			}
		}
		if canClaim {
			a.reserved = true
		}
		a.mu.Unlock()
		if fatal != nil && active == 0 {
			return fatal
		}
		if canClaim {
			err := a.claim(claimCtx, free)
			if needsReconcile(err) && ctx.Err() == nil {
				err = a.recoverLostClaim(claimCtx)
			}
			if err != nil && ctx.Err() == nil {
				a.fail(err)
			}
		}
		if !wait(ctx, a.pollInterval) {
			return a.drain(cancel)
		}
	}
}
func (a *Agent) claim(ctx context.Context, free int) error {
	r, err := a.o.Store.Commit(ctx, state.Mutation{Request: &state.RequestDraft{Method: "POST", Path: "/assignments/claim", Body: encode(map[string]int{"freeSlots": free})}})
	if err != nil {
		return err
	}
	b, err := a.send(ctx, r.ID)
	if err != nil {
		return err
	}
	if b == nil {
		_, err = a.o.Store.Commit(ctx, state.Mutation{Ack: &state.Acknowledgement{RequestID: r.ID}})
		a.mu.Lock()
		if err == nil {
			a.reserved = false
		}
		a.mu.Unlock()
		return err
	}
	assignment, err := client.DecodeAssignment(b, a.session)
	if err != nil {
		return err
	}
	if len(b) > a.settings.Limits.JSONBytes || len(assignment.Input) > a.settings.Limits.InputBytes {
		return client.ErrTooLarge
	}
	_, err = a.o.Store.Commit(ctx, state.Mutation{Assignment: &state.AssignmentChange{ID: assignment.ID, NextPhase: state.Received, Snapshot: b, Detail: encode(map[string]any{})}, Ack: &state.Acknowledgement{RequestID: r.ID, Response: b}})
	if err != nil {
		return err
	}
	if a.boundary != nil {
		a.boundary("received")
	}
	workerCtx, stop := context.WithCancel(a.workCtx)
	lease, _ := assignment.LeaseUntil.Time()
	e := &entry{assignment: assignment, phase: state.Received, lease: lease, cancel: stop, grace: time.Duration(assignment.Execution.StopGraceSeconds) * time.Second}
	a.mu.Lock()
	a.reserved = false
	a.entries[assignment.ID] = e
	a.active++
	a.mu.Unlock()
	a.wg.Add(1)
	go func() {
		defer a.wg.Done()
		defer stop()
		err := a.worker(workerCtx, e)
		if err != nil && !errors.Is(err, errHeld) {
			a.fail(err)
		}
		a.mu.Lock()
		a.active--
		if err == nil {
			delete(a.entries, assignment.ID)
		}
		a.mu.Unlock()
	}()
	return nil
}
func (a *Agent) Snapshot() client.Heartbeat {
	a.mu.Lock()
	defer a.mu.Unlock()
	mode := "ACCEPTING"
	free := a.capacity - len(a.entries)
	if a.reserved {
		free--
	}
	if a.draining {
		mode = "DRAINING"
		free = 0
	} else if a.fatal != nil || !a.healthy || a.diskBlocked.Load() || a.recovering.Load() {
		mode = "DEGRADED"
		free = 0
	}
	items := []client.HeartbeatItem{}
	for id, e := range a.entries {
		e.mu.Lock()
		phase := e.phase
		e.mu.Unlock()
		wire := "ASSIGNED"
		switch phase {
		case state.StartRequested, state.Permitted, state.StartIntent:
			wire = "STARTING"
		case state.Running:
			wire = "RUNNING"
		case state.Stopping:
			wire = "STOPPING"
		case state.Finished, state.Acked:
			wire = "FINISHED"
		case state.Unknown:
			wire = "UNKNOWN"
		}
		items = append(items, client.HeartbeatItem{ID: id, Phase: wire})
	}
	return client.Heartbeat{ObservedAt: now(), Mode: mode, FreeSlots: max(0, free), Assignments: items}
}
func (a *Agent) heartbeats(ctx context.Context) {
	for ctx.Err() == nil {
		a.mu.Lock()
		for _, e := range a.entries {
			e.mu.Lock()
			if (e.phase == state.Running || e.phase == state.Stopping) && !time.Now().Before(e.lease) {
				e.reconcile = true
			}
			e.mu.Unlock()
		}
		a.mu.Unlock()

		response, err := a.o.Client.Heartbeat(ctx, a.o.Token, a.session, a.Snapshot())
		if err != nil {
			a.disconnected()
			if !retryable(err) && ctx.Err() == nil {
				a.fail(err)
				return
			}
		} else {
			a.mu.Lock()
			a.healthy = true
			a.accepting = response.AcceptingAssignments
			entries := map[string]*entry{}
			for id, e := range a.entries {
				entries[id] = e
			}
			a.mu.Unlock()
			for _, item := range response.Assignments {
				e := entries[item.ID]
				if e == nil {
					continue
				}
				if item.Action == "STOP" {
					e.stop(*item.StopReason, time.Duration(*item.GraceSeconds)*time.Second)
				}
				if item.Action == "RECONCILE" {
					e.mu.Lock()
					e.reconcile = true
					e.mu.Unlock()
					// Reconcile the existing executor; never restart it.
				}
				if item.LeaseUntil != nil {
					lease, _ := item.LeaseUntil.Time()
					e.mu.Lock()
					e.lease = lease
					e.mu.Unlock()
				}
			}
			for _, e := range entries {
				e.mu.Lock()
				expired := (e.phase == state.Running || e.phase == state.Stopping || e.phase == state.StartIntent) && !time.Now().Before(e.lease)
				if expired {
					e.reconcile = true
				}
				e.mu.Unlock()

			}
		}
		delay := a.heartbeatInterval
		if err != nil {
			delay = retryDelay(err, delay)
		}
		if !wait(ctx, delay) {
			return
		}
	}
}
func (a *Agent) drain(cancel context.CancelFunc) error {
	a.mu.Lock()
	a.draining = true
	a.mu.Unlock()
	done := make(chan struct{})
	go func() { a.wg.Wait(); close(done) }()
	timer := time.NewTimer(a.o.DrainTimeout)
	defer timer.Stop()
	select {
	case <-done:
		return a.failure()
	case <-timer.C:
	}
	a.mu.Lock()
	entries := []*entry{}
	for _, e := range a.entries {
		entries = append(entries, e)
	}
	a.mu.Unlock()
	for _, e := range entries {
		e.stop("SERVICE_STOP", 0)
	}
	cancel() // abandon HTTP waits only after the drain deadline; durable bodies remain
	<-done
	return a.failure()
}
