package client

import "encoding/json"

type AssignmentProgram struct {
	ID       string `json:"id"`
	Code     string `json:"code"`
	Version  string `json:"version"`
	Revision string `json:"revision"`
}
type Execution struct {
	TimeoutSeconds   int    `json:"timeoutSeconds"`
	StopGraceSeconds int    `json:"stopGraceSeconds"`
	BusinessKey      string `json:"businessKey"`
}
type Assignment struct {
	ID           string            `json:"id"`
	RunID        string            `json:"runId"`
	Attempt      int               `json:"attempt"`
	Session      Decimal           `json:"session"`
	Program      AssignmentProgram `json:"program"`
	Input        json.RawMessage   `json:"input"`
	Execution    Execution         `json:"execution"`
	LeaseUntil   Timestamp         `json:"leaseUntil"`
	State        string            `json:"state"`
	StartAllowed bool              `json:"startAllowed"`
	StartBefore  *Timestamp        `json:"startBefore"`
}
type StartPermit struct {
	ID           string    `json:"id"`
	StartAllowed bool      `json:"startAllowed"`
	StartBefore  Timestamp `json:"startBefore"`
	LeaseUntil   Timestamp `json:"leaseUntil"`
}
type ProcessIdentity struct {
	PID       Decimal   `json:"pid"`
	StartedAt Timestamp `json:"startedAt"`
	BootID    string    `json:"bootId"`
}
type Started struct {
	StartedAt Timestamp       `json:"startedAt"`
	Process   ProcessIdentity `json:"process"`
}
type StartedAck struct {
	ID     string `json:"id"`
	State  string `json:"state"`
	Action string `json:"action"`
}
type CompletionAck struct {
	ID                 string `json:"id"`
	CompletionAccepted bool   `json:"completionAccepted"`
	AttemptState       string `json:"attemptState"`
	RunState           string `json:"runState"`
	LogState           string `json:"logState"`
}
type HeartbeatItem struct {
	ID    string `json:"id"`
	Phase string `json:"phase"`
}
type Heartbeat struct {
	ObservedAt  Timestamp       `json:"observedAt"`
	Mode        string          `json:"mode"`
	FreeSlots   int             `json:"freeSlots"`
	Assignments []HeartbeatItem `json:"assignments"`
}
type HeartbeatAction struct {
	ID           string     `json:"id"`
	Action       string     `json:"action"`
	LeaseUntil   *Timestamp `json:"leaseUntil"`
	StopReason   *string    `json:"stopReason"`
	GraceSeconds *int       `json:"graceSeconds"`
}
type HeartbeatResponse struct {
	ServerTime           Timestamp         `json:"serverTime"`
	AcceptingAssignments bool              `json:"acceptingAssignments"`
	Assignments          []HeartbeatAction `json:"assignments"`
}
type Failure struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}
type LogEnd struct {
	LastSequence *Decimal `json:"lastSequence"`
	Status       string   `json:"status"`
}
type Completion struct {
	Outcome       string            `json:"outcome"`
	StartedAt     *Timestamp        `json:"startedAt"`
	FinishedAt    Timestamp         `json:"finishedAt"`
	ExitCode      *int64            `json:"exitCode"`
	ProcessExited bool              `json:"processExited"`
	Failure       *Failure          `json:"failure"`
	Result        json.RawMessage   `json:"result"`
	Logs          map[string]LogEnd `json:"logs"`
}
