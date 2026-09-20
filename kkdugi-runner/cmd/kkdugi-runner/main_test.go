package main

import (
	"bytes"
	"strings"
	"testing"
)

func TestCommands(t *testing.T) {
	for _, test := range []struct {
		args []string
		code int
	}{{[]string{"version"}, 0}, {nil, 2}, {[]string{"run"}, 2}, {[]string{"verify"}, 2}, {[]string{"verify", "--config", "missing.toml"}, 1}} {
		var out, err bytes.Buffer
		if code := run(test.args, &out, &err); code != test.code {
			t.Fatalf("%v: %d %s", test.args, code, err.String())
		}
		if test.code == 0 && !strings.Contains(out.String(), "protocol=1") {
			t.Fatal(out.String())
		}
	}
}
