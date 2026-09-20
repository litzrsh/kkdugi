package state

import (
	"encoding/json"
	"kkdugi-runner/internal/client"
)

type Phase string

const (
	Received       Phase = "RECEIVED"
	Prepared       Phase = "PREPARED"
	StartRequested Phase = "START_REQUESTED"
	Permitted      Phase = "PERMITTED"
	StartIntent    Phase = "START_INTENT"
	Running        Phase = "RUNNING"
	Stopping       Phase = "STOPPING"
	Finished       Phase = "FINISHED"
	Acked          Phase = "ACKED"
	Unknown        Phase = "UNKNOWN"
)

type Identity struct {
	BaseURL  string
	RunnerID string
}
type Assignment struct {
	ID          string
	Session     client.Decimal
	BootID      string
	Phase       Phase
	Version     int64
	Snapshot    json.RawMessage
	Detail      json.RawMessage
	StartIntent bool
}
type AssignmentChange struct {
	ID              string
	ExpectedVersion int64
	ExpectedPhase   Phase
	NextPhase       Phase
	Snapshot        json.RawMessage
	Detail          json.RawMessage
}
type RequestDraft struct {
	AssignmentID string
	Method       string
	Path         string
	Body         json.RawMessage
}
type Request struct {
	ID           string
	AssignmentID string
	Method       string
	Path         string
	Key          string
	CreatedAt    client.Timestamp
	Session      client.Decimal
	Body         json.RawMessage
	Hash         string
	Status       string
	NextAt       client.Timestamp
}
type Acknowledgement struct {
	RequestID string
	Response  json.RawMessage
}
type Mutation struct {
	Assignment *AssignmentChange
	Request    *RequestDraft
	Ack        *Acknowledgement
}

func (Request) String() string      { return "[PROTECTED REQUEST]" }
func (Request) GoString() string    { return "[PROTECTED REQUEST]" }
func (Assignment) String() string   { return "[PROTECTED JOURNAL]" }
func (Assignment) GoString() string { return "[PROTECTED JOURNAL]" }
