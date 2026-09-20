package client

import (
	"encoding/json"
	"testing"
)

func TestDecimalWireFormat(t *testing.T) {
	for _, s := range []string{`"0"`, `"9007199254740993"`, `"9223372036854775807"`} {
		var d Decimal
		if err := json.Unmarshal([]byte(s), &d); err != nil {
			t.Fatal(err)
		}
		b, err := json.Marshal(d)
		if err != nil || string(b) != s {
			t.Fatal(string(b), err)
		}
	}
	for _, s := range []string{`0`, `null`, `""`, `"-1"`, `"+1"`, `"1.0"`, `"9223372036854775808"`} {
		var d Decimal
		if err := json.Unmarshal([]byte(s), &d); err == nil {
			t.Fatal(s)
		}
	}
}
func TestTimestampWireFormat(t *testing.T) {
	for _, s := range []string{`null`, `"2026-09-20T00:00:00+09:00"`, `"2026-09-20T00:00:00.1234567Z"`, `"2026-99-20T00:00:00Z"`} {
		var d Timestamp
		if err := json.Unmarshal([]byte(s), &d); err == nil {
			t.Fatal(s)
		}
	}
	var d Timestamp
	if err := json.Unmarshal([]byte(`"2026-09-20T00:00:00.123456Z"`), &d); err != nil {
		t.Fatal(err)
	}
}
