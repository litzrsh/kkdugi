package client

import "encoding/json"

type LogChunk struct {
	EmittedAt Timestamp `json:"emittedAt"`
	Text      string    `json:"text"`
	SHA256    string    `json:"sha256"`
}
type LogAck struct {
	AcceptedSequence  Decimal  `json:"acceptedSequence"`
	ContiguousThrough *Decimal `json:"contiguousThrough"`
	LogsClosed        bool     `json:"logsClosed"`
}
type AssignmentDetail struct {
	Assignment
	Outcome    json.RawMessage     `json:"outcome"`
	LogOffsets map[string]*Decimal `json:"logOffsets"`
	RunState   string              `json:"runState"`
}
type ReconcileRequest struct {
	PreviousSession Decimal          `json:"previousSession"`
	Observation     string           `json:"observation"`
	Process         *ProcessIdentity `json:"process"`
	Completion      json.RawMessage  `json:"completion"`
}
type ReconcileResponse struct {
	ID           string     `json:"id"`
	Disposition  string     `json:"disposition"`
	Session      Decimal    `json:"session"`
	LeaseUntil   *Timestamp `json:"leaseUntil"`
	StartAllowed bool       `json:"startAllowed"`
}
