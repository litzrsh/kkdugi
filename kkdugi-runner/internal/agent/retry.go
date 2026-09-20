package agent

import (
	"context"
	"crypto/rand"
	"errors"
	"time"

	"kkdugi-runner/internal/client"
	"kkdugi-runner/internal/state"
)

func wait(ctx context.Context, d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-t.C:
		return true
	}
}
func retryable(err error) bool {
	var api *client.APIError
	if errors.As(err, &api) {
		return api.Status == 429 || api.Status >= 500
	}
	var transport *client.TransportError
	return errors.As(err, &transport) && !errors.Is(err, client.ErrRedirect)
}
func retryDelay(err error, base time.Duration) time.Duration {
	var b [1]byte
	rand.Read(b[:])
	d := base + time.Duration(b[0])*base/512
	var api *client.APIError
	if errors.As(err, &api) && api.RetryAfter > d {
		d = api.RetryAfter
	}
	return d
}
func (a *Agent) retry(ctx context.Context, call func() error) error {
	delay := 100 * time.Millisecond
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		err := call()
		if err == nil {
			return nil
		}
		a.disconnected()
		if !retryable(err) {
			return err
		}
		if !wait(ctx, retryDelay(err, delay)) {
			return ctx.Err()
		}
		delay = min(delay*2, 5*time.Second)
	}
}
func (a *Agent) send(ctx context.Context, id string) ([]byte, error) {
	delay := 100 * time.Millisecond
	for {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		if err := a.failure(); err != nil {
			return nil, err
		}
		superseded, err := a.o.Store.Superseded(ctx, id)
		if err != nil {
			return nil, err
		}
		if superseded {
			return nil, errReconcile
		}
		r, err := a.o.Store.BeginSend(context.Background(), id)
		if errors.Is(err, state.ErrNotDue) {
			if !wait(ctx, 100*time.Millisecond) {
				return nil, ctx.Err()
			}
			continue
		}
		if err != nil {
			if superseded, _ := a.o.Store.Superseded(ctx, id); superseded {
				return nil, errReconcile
			}
			return nil, err
		}
		if len(r.Body) > a.settings.Limits.JSONBytes {
			return nil, client.ErrTooLarge
		}
		body, err := a.o.Client.AssignmentCommand(ctx, a.o.Token, r.Session, r.Key, r.CreatedAt, r.Path, r.Body)
		if err == nil {
			return body, nil
		}
		a.disconnected()
		if !retryable(err) {
			return nil, err
		}
		d := retryDelay(err, delay)
		next := client.Timestamp(time.Now().Add(d).UTC().Format("2006-01-02T15:04:05.000000Z"))
		if err = a.o.Store.ScheduleRetry(context.Background(), id, next); err != nil {
			return nil, err
		}
		if !wait(ctx, d) {
			return nil, ctx.Err()
		}
		delay = min(delay*2, 5*time.Second)
	}
}
