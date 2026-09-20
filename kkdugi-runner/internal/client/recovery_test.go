package client

import (
	"encoding/json"
	"testing"
)

func TestRecoveryWireFieldsAndNoStartPermission(t *testing.T) {
	b, _ := json.Marshal(ReconcileRequest{PreviousSession: "9007199254740993", Observation: "UNKNOWN"})
	var fields map[string]json.RawMessage
	json.Unmarshal(b, &fields)
	for _, key := range []string{"previousSession", "observation", "process", "completion"} {
		if _, ok := fields[key]; !ok {
			t.Fatal("missing", key)
		}
	}
	good := `{"id":"BA1","disposition":"HOLD","session":"9007199254740993","leaseUntil":null,"startAllowed":false}`
	if _, e := DecodeReconcile([]byte(good), "BA1", "9007199254740993"); e != nil {
		t.Fatal(e)
	}
	for _, bad := range []string{`{"id":"BA1","disposition":"HOLD","session":"9007199254740993","leaseUntil":null,"startAllowed":true}`, `{"id":"BA1","disposition":"HOLD","session":9007199254740993,"leaseUntil":null,"startAllowed":false}`, `{"id":"BA1","disposition":"CONTINUE_EXISTING","session":"9007199254740993","leaseUntil":null,"startAllowed":false}`, `{"id":"BA1","disposition":"SPAWN","session":"9007199254740993","leaseUntil":null,"startAllowed":false}`} {
		if _, e := DecodeReconcile([]byte(bad), "BA1", "9007199254740993"); e == nil {
			t.Fatal("accepted invalid recovery")
		}
	}
	for _, v := range []Decimal{"-1", "01", "9223372036854775808"} {
		if _, e := Sequence(v); e == nil {
			t.Fatal("invalid sequence", v)
		}
	}
	b, _ = json.Marshal(LogAck{AcceptedSequence: "0"})
	json.Unmarshal(b, &fields)
	if string(fields["contiguousThrough"]) != "null" {
		t.Fatal("nullable ACK omitted")
	}
}
