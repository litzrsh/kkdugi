// Command kkdugi-runner-probe is a bounded acceptance-test workload, not an admin.
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"time"
)

type input struct {
	Message     string          `json:"message"`
	DelayMillis int             `json:"delayMillis"`
	ExitCode    int             `json:"exitCode"`
	OutputLines int             `json:"outputLines"`
	Value       json.RawMessage `json:"value"`
}

func parse(b []byte) (input, error) {
	var v input
	if len(b) > 256<<10 || len(bytes.TrimSpace(b)) == 0 || bytes.TrimSpace(b)[0] != '{' {
		return v, fmt.Errorf("invalid probe input")
	}
	d := json.NewDecoder(bytes.NewReader(b))
	d.DisallowUnknownFields()
	if e := d.Decode(&v); e != nil {
		return v, e
	}
	var extra any
	if d.Decode(&extra) != io.EOF {
		return v, fmt.Errorf("extra input")
	}
	if v.DelayMillis < 0 || v.DelayMillis > 60000 || v.ExitCode < 0 || v.ExitCode > 255 || v.OutputLines < 0 || v.OutputLines > 20000 || len(v.Message) > 1024 {
		return v, fmt.Errorf("probe input out of range")
	}
	return v, nil
}
func run() int {
	in, out := os.Getenv("KKDUGI_INPUT_FILE"), os.Getenv("KKDUGI_RESULT_FILE")
	if in == "" || out == "" {
		fmt.Fprintln(os.Stderr, "runner input/result paths are required")
		return 2
	}
	f, e := os.Open(in)
	if e != nil {
		return 2
	}
	b, e := io.ReadAll(io.LimitReader(f, (256<<10)+1))
	f.Close()
	if e != nil {
		return 2
	}
	v, e := parse(b)
	if e != nil {
		fmt.Fprintln(os.Stderr, "invalid probe input")
		return 2
	}
	for i := 0; i < v.OutputLines; i++ {
		fmt.Fprintln(os.Stdout, v.Message)
	}
	time.Sleep(time.Duration(v.DelayMillis) * time.Millisecond)
	result, e := json.Marshal(map[string]any{"message": v.Message, "value": v.Value, "outputLines": v.OutputLines})
	if e != nil {
		return 2
	}
	if e = os.WriteFile(out, result, 0600); e != nil {
		return 2
	}
	return v.ExitCode
}
func main() { os.Exit(run()) }
