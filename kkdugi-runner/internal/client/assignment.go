package client

import (
	"context"
	"encoding/json"
	"strings"
)

func required(body []byte, keys ...string) bool {
	var fields map[string]json.RawMessage
	if json.Unmarshal(body, &fields) != nil {
		return false
	}
	for _, key := range keys {
		b, ok := fields[key]
		if !ok || strings.TrimSpace(string(b)) == "null" {
			return false
		}
	}
	return true
}
func DecodeAssignment(body []byte, session Decimal) (Assignment, error) {
	var a Assignment
	if validateObject(body) != nil || !required(body, "id", "runId", "attempt", "session", "program", "input", "execution", "leaseUntil", "state", "startAllowed") || json.Unmarshal(body, &a) != nil {
		return a, ErrProtocol
	}
	if !programCodePattern.MatchString(a.ID) || !programCodePattern.MatchString(a.RunID) || a.Session != session || a.Attempt < 1 || a.Attempt > 2147483647 || a.State != "ASSIGNED" || a.StartAllowed || a.StartBefore != nil {
		return Assignment{}, ErrProtocol
	}
	p := a.Program
	if !programCodePattern.MatchString(p.ID) || !programCodePattern.MatchString(p.Code) || !textValid(p.Version, 50) || !textValid(p.Revision, 200) {
		return Assignment{}, ErrProtocol
	}
	e := a.Execution
	if e.TimeoutSeconds < 1 || e.TimeoutSeconds > 2147483647 || e.StopGraceSeconds < 0 || e.StopGraceSeconds > 2147483647 || !textValid(e.BusinessKey, 200) {
		return Assignment{}, ErrProtocol
	}
	var fields map[string]json.RawMessage
	json.Unmarshal(body, &fields)
	if !required(fields["execution"], "timeoutSeconds", "stopGraceSeconds", "businessKey") {
		return Assignment{}, ErrProtocol
	}
	if len(a.Input) > 256<<10 || validateObject(a.Input) != nil {
		return Assignment{}, ErrProtocol
	}
	if _, err := a.LeaseUntil.Time(); err != nil {
		return Assignment{}, ErrProtocol
	}
	return a, nil
}
func DecodeStartPermit(body []byte, id string) (StartPermit, error) {
	var p StartPermit
	if !required(body, "id", "startAllowed", "startBefore", "leaseUntil") || json.Unmarshal(body, &p) != nil || p.ID != id || !p.StartAllowed {
		return p, ErrProtocol
	}
	start, e1 := p.StartBefore.Time()
	lease, e2 := p.LeaseUntil.Time()
	if e1 != nil || e2 != nil || start.After(lease) {
		return StartPermit{}, ErrProtocol
	}
	return p, nil
}
func DecodeStartedAck(body []byte, id string) (StartedAck, error) {
	var a StartedAck
	if !required(body, "id", "state", "action") || json.Unmarshal(body, &a) != nil || a.ID != id || a.State != "RUNNING" || (a.Action != "CONTINUE" && a.Action != "STOP") {
		return a, ErrProtocol
	}
	return a, nil
}
func DecodeCompletionAck(body []byte, id, outcome string) (CompletionAck, error) {
	var a CompletionAck
	if !required(body, "id", "completionAccepted", "attemptState", "runState", "logState") || json.Unmarshal(body, &a) != nil || a.ID != id || !a.CompletionAccepted || a.AttemptState != outcome || !textValid(a.RunState, 20) || !textValid(a.LogState, 20) {
		return a, ErrProtocol
	}
	return a, nil
}

// AssignmentCommand sends already-durable bytes; nil response means claim 204.
func (c *Client) AssignmentCommand(ctx context.Context, token Secret, session Decimal, key string, created Timestamp, path string, body []byte) ([]byte, error) {
	statuses := []int{200}
	if path == "/assignments/claim" {
		statuses = append(statuses, 204)
	} else {
		parts := strings.Split(path, "/")
		if len(parts) != 4 || parts[0] != "" || parts[1] != "assignments" || !programCodePattern.MatchString(parts[2]) || (parts[3] != "start" && parts[3] != "started" && parts[3] != "completion" && parts[3] != "reconcile") {
			return nil, ErrProtocol
		}
	}
	return c.send(ctx, request{method: "POST", path: path, token: token, session: session, key: &requestKey{Value: key, CreatedAt: created}, body: body, statuses: statuses})
}
func (c *Client) Unresolved(ctx context.Context, token Secret, session Decimal) ([]string, error) {
	b, err := c.send(ctx, request{method: "GET", path: "/assignments?state=UNRESOLVED", token: token, session: session, statuses: []int{200}, array: true})
	if err != nil {
		return nil, err
	}
	var items []struct {
		ID string `json:"id"`
	}
	if json.Unmarshal(b, &items) != nil || len(items) > 200 {
		return nil, ErrProtocol
	}
	result := []string{}
	seen := map[string]bool{}
	for _, a := range items {
		if !programCodePattern.MatchString(a.ID) || seen[a.ID] {
			return nil, ErrProtocol
		}
		seen[a.ID] = true
		result = append(result, a.ID)
	}
	return result, nil
}
func (c *Client) Heartbeat(ctx context.Context, token Secret, session Decimal, input Heartbeat) (HeartbeatResponse, error) {
	var result HeartbeatResponse
	body, err := json.Marshal(input)
	if err != nil {
		return result, ErrProtocol
	}
	b, err := c.send(ctx, request{method: "POST", path: "/heartbeat", token: token, session: session, body: body, statuses: []int{200}})
	if err != nil {
		return result, err
	}
	if !required(b, "serverTime", "acceptingAssignments", "assignments") || json.Unmarshal(b, &result) != nil || len(result.Assignments) > 200 {
		return HeartbeatResponse{}, ErrProtocol
	}
	if _, err := result.ServerTime.Time(); err != nil {
		return HeartbeatResponse{}, ErrProtocol
	}
	seen := map[string]bool{}
	requested := map[string]bool{}
	for _, a := range input.Assignments {
		requested[a.ID] = true
	}
	for _, a := range result.Assignments {
		if !requested[a.ID] || seen[a.ID] || (a.Action != "CONTINUE" && a.Action != "STOP" && a.Action != "RECONCILE") {
			return HeartbeatResponse{}, ErrProtocol
		}
		seen[a.ID] = true
		if a.LeaseUntil != nil {
			if _, err := a.LeaseUntil.Time(); err != nil {
				return HeartbeatResponse{}, ErrProtocol
			}
		}
		if a.Action == "STOP" && (a.StopReason == nil || (*a.StopReason != "CANCEL" && *a.StopReason != "TIMEOUT") || a.GraceSeconds == nil || *a.GraceSeconds < 0 || *a.GraceSeconds > 2147483647) {
			return HeartbeatResponse{}, ErrProtocol
		}
	}
	return result, nil
}
