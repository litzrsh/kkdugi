package main

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestProbeInputBoundsAndLargeInteger(t *testing.T) {
	v, e := parse([]byte(`{"message":"검증","value":9007199254740993,"outputLines":2}`))
	if e != nil || string(v.Value) != "9007199254740993" {
		t.Fatal(v, e)
	}
	b, _ := json.Marshal(v)
	if !strings.Contains(string(b), "9007199254740993") {
		t.Fatal("rounded integer")
	}
	for _, b := range []string{`null`, `[]`, `{} {}`, `{"command":"sh"}`, `{"delayMillis":60001}`, `{"exitCode":-1}`, `{"outputLines":20001}`} {
		if _, e := parse([]byte(b)); e == nil {
			t.Fatal("invalid probe input accepted", b)
		}
	}
}
