package client

import (
	"encoding/json"
	"testing"
)

func TestAssignmentWireValidation(t *testing.T) {
	valid := `{"id":"BA1","runId":"BX1","attempt":1,"session":"9007199254740993","program":{"id":"BP1","code":"test","version":"1","revision":"r1"},"input":{"n":9007199254740993},"execution":{"timeoutSeconds":1,"stopGraceSeconds":0,"businessKey":"key"},"leaseUntil":"2026-09-20T01:00:00Z","state":"ASSIGNED","startAllowed":false,"startBefore":null}`
	if _, err := DecodeAssignment([]byte(valid), "9007199254740993"); err != nil {
		t.Fatal(err)
	}
	for _, field := range []string{"id", "runId", "attempt", "session", "program", "input", "execution", "leaseUntil", "state", "startAllowed"} {
		var fields map[string]json.RawMessage
		json.Unmarshal([]byte(valid), &fields)
		delete(fields, field)
		body, _ := json.Marshal(fields)
		if _, err := DecodeAssignment(body, "9007199254740993"); err == nil {
			t.Fatal("missing field accepted", field)
		}
	}
	var fields map[string]json.RawMessage
	json.Unmarshal([]byte(valid), &fields)
	fields["session"] = json.RawMessage(`9007199254740993`)
	body, _ := json.Marshal(fields)
	if _, err := DecodeAssignment(body, "9007199254740993"); err == nil {
		t.Fatal("numeric session accepted")
	}
	if _, err := DecodeStartPermit([]byte(`{"id":"BA1","startAllowed":true,"startBefore":"2026-09-20T01:01:00Z","leaseUntil":"2026-09-20T01:00:00Z"}`), "BA1"); err == nil {
		t.Fatal("start exceeds lease")
	}
	if _, err := DecodeCompletionAck([]byte(`{"id":"BA1","completionAccepted":true,"attemptState":"FAILED","runState":"RETRY_WAIT","logState":"LOST"}`), "BA1", "SUCCEEDED"); err == nil {
		t.Fatal("mismatched completion ACK")
	}
}
