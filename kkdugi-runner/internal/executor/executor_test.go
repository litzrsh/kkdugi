package executor

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"kkdugi-runner/internal/workspace"
)

func TestHelperProcess(t *testing.T) {
	if os.Getenv("KKDUGI_TEST_HELPER") != "1" {
		return
	}
	args := os.Args
	for len(args) > 0 && args[0] != "--" {
		args = args[1:]
	}
	if len(args) < 2 {
		os.Exit(80)
	}
	args = args[1:]
	switch args[0] {
	case "echo":
		input, err := os.ReadFile(os.Getenv("KKDUGI_INPUT_FILE"))
		if err != nil {
			os.Exit(81)
		}
		b, err := json.Marshal(map[string]any{"input": json.RawMessage(input), "args": args[1:], "parentSecret": os.Getenv("RUNNER_PRIVATE_TEST_TOKEN")})
		if err != nil {
			os.Exit(82)
		}
		if os.WriteFile(os.Getenv("KKDUGI_RESULT_FILE"), b, 0600) != nil {
			os.Exit(83)
		}
		fmt.Fprint(os.Stdout, "stdout 한글")
		fmt.Fprint(os.Stderr, "stderr 한글")
	case "fail":
		os.Exit(7)
	case "sleep":
		time.Sleep(30 * time.Second)
	case "orphan":
		child := exec.Command(os.Args[0], "-test.run=TestHelperProcess", "--", "sleep")
		child.Env = os.Environ()
		if child.Start() != nil {
			os.Exit(89)
		}
		if os.WriteFile(args[1], []byte(strconv.Itoa(child.Process.Pid)), 0600) != nil {
			os.Exit(90)
		}
	case "flood":
		b := bytes.Repeat([]byte("x"), 32<<10)
		for i := 0; i < 64; i++ {
			os.Stdout.Write(b)
			os.Stderr.Write(b)
		}
	case "tree":
		child := exec.Command(os.Args[0], "-test.run=TestHelperProcess", "--", "tree-child", args[1])
		child.Env = os.Environ()
		if child.Start() != nil {
			os.Exit(84)
		}
		if os.WriteFile(args[1]+".child", []byte(strconv.Itoa(child.Process.Pid)), 0600) != nil {
			os.Exit(85)
		}
		time.Sleep(30 * time.Second)
	case "tree-child":
		grandchild := exec.Command(os.Args[0], "-test.run=TestHelperProcess", "--", "sleep")
		grandchild.Env = os.Environ()
		if grandchild.Start() != nil {
			os.Exit(86)
		}
		if os.WriteFile(args[1]+".grandchild", []byte(strconv.Itoa(grandchild.Process.Pid)), 0600) != nil {
			os.Exit(87)
		}
		time.Sleep(30 * time.Second)
	default:
		os.Exit(88)
	}
	os.Exit(0)
}

func helperSpec(t *testing.T, mode string, args ...string) Spec {
	t.Helper()
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	env := []string{"KKDUGI_TEST_HELPER=1"}
	// Windows runtime facilities may require these; never copy the full parent environment.
	for _, key := range []string{"SystemRoot", "TEMP", "TMP"} {
		if value := os.Getenv(key); value != "" {
			env = append(env, key+"="+value)
		}
	}
	return Spec{Executable: executable, Arguments: append([]string{"-test.run=TestHelperProcess", "--", mode}, args...), Directory: t.TempDir(), Environment: env, Timeout: 10 * time.Second, StopGrace: 20 * time.Millisecond}
}

func TestJSONExecutionAndArgumentBoundaries(t *testing.T) {
	t.Setenv("RUNNER_PRIVATE_TEST_TOKEN", "must-not-inherit")
	w, err := workspace.Create(t.TempDir(), workspace.Context{RunID: "BX1", AssignmentID: "BA1", Attempt: 1, Session: "1", BusinessKey: "BX1"}, []byte(`{"n":9007199254740993}`))
	if err != nil {
		t.Fatal(err)
	}
	args := []string{"공백 있는 인자", `a"b`, `C:\folder with space\`, ""}
	s := helperSpec(t, "echo", args...)
	// The executable path itself also contains spaces and non-ASCII characters.
	copyPath := filepath.Join(t.TempDir(), "실행 프로그램.exe")
	source, err := os.Open(s.Executable)
	if err != nil {
		t.Fatal(err)
	}
	dest, err := os.OpenFile(copyPath, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0700)
	if err != nil {
		source.Close()
		t.Fatal(err)
	}
	_, copyErr := io.Copy(dest, source)
	closeErr := errors.Join(source.Close(), dest.Close())
	if err := errors.Join(copyErr, closeErr); err != nil {
		t.Fatal(err)
	}
	s.Executable = copyPath
	s.Environment = append(s.Environment, w.Environment()...)
	var stdout, stderr bytes.Buffer
	s.Stdout, s.Stderr = &stdout, &stderr
	r := Run(context.Background(), s)
	if r.Err != nil || !r.ProcessExited || r.ExitCode == nil || *r.ExitCode != 0 {
		t.Fatalf("%+v", r)
	}
	b, err := w.Result()
	if err != nil {
		t.Fatal(err)
	}
	var result struct {
		Input        json.RawMessage `json:"input"`
		Args         []string        `json:"args"`
		ParentSecret string          `json:"parentSecret"`
	}
	if err := json.Unmarshal(b, &result); err != nil {
		t.Fatal(err)
	}
	if string(result.Input) != `{"n":9007199254740993}` || result.ParentSecret != "" {
		t.Fatal(string(b))
	}
	if len(result.Args) != len(args) {
		t.Fatal(result.Args)
	}
	for i := range args {
		if result.Args[i] != args[i] {
			t.Fatalf("argument %d: %q != %q", i, result.Args[i], args[i])
		}
	}
	if stdout.String() != "stdout 한글" || stderr.String() != "stderr 한글" {
		t.Fatal(stdout.String(), stderr.String())
	}
}

func TestNonzeroExitAndNoStartAfterCancel(t *testing.T) {
	r := Run(context.Background(), helperSpec(t, "fail"))
	if r.Err != nil || !r.ProcessExited || r.ExitCode == nil || *r.ExitCode != 7 {
		t.Fatalf("%+v", r)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	r = Run(ctx, helperSpec(t, "sleep"))
	if r.PID != 0 || r.Reason != "CANCELED" {
		t.Fatalf("%+v", r)
	}
}

func TestInvalidSpecificationNeverStarts(t *testing.T) {
	for name, change := range map[string]func(*Spec){
		"relative executable":      func(s *Spec) { s.Executable = "program.exe" },
		"zero timeout":             func(s *Spec) { s.Timeout = 0 },
		"negative grace":           func(s *Spec) { s.StopGrace = -time.Second },
		"nul argument":             func(s *Spec) { s.Arguments = []string{"a\x00b"} },
		"invalid argument utf8":    func(s *Spec) { s.Arguments = []string{string([]byte{0xff})} },
		"nul environment":          func(s *Spec) { s.Environment = []string{"A=b\x00c"} },
		"invalid environment utf8": func(s *Spec) { s.Environment = []string{"A=" + string([]byte{0xff})} },
		"duplicate environment":    func(s *Spec) { s.Environment = []string{"A=1", "A=2"} },
	} {
		t.Run(name, func(t *testing.T) {
			s := helperSpec(t, "sleep")
			change(&s)
			r := Run(context.Background(), s)
			if r.PID != 0 || r.Reason != "START_FAILED" || r.Err == nil {
				t.Fatalf("%+v", r)
			}
		})
	}
}

func TestTimeout(t *testing.T) {
	s := helperSpec(t, "sleep")
	s.Timeout = 150 * time.Millisecond
	start := time.Now()
	r := Run(context.Background(), s)
	if r.Reason != "TIMED_OUT" || !r.ProcessExited || time.Since(start) > 5*time.Second {
		t.Fatalf("%+v", r)
	}
}

func TestRejectBackgroundDescendants(t *testing.T) {
	marker := filepath.Join(t.TempDir(), "child.pid")
	r := Run(context.Background(), helperSpec(t, "orphan", marker))
	if !r.ProcessExited || r.Err == nil || !strings.Contains(r.Err.Error(), "descendants") {
		t.Fatalf("%+v", r)
	}
	b, err := os.ReadFile(marker)
	if err != nil {
		t.Fatal(err)
	}
	pid, err := strconv.Atoi(string(b))
	if err != nil {
		t.Fatal(err)
	}
	if processAlive(pid) {
		t.Fatal("background descendant survived")
	}
}

func TestCancelEntireTree(t *testing.T) {
	marker := filepath.Join(t.TempDir(), "pid")
	s := helperSpec(t, "tree", marker)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	result := make(chan Result, 1)
	go func() { result <- Run(ctx, s) }()
	deadline := time.Now().Add(5 * time.Second)
	for {
		if _, err := os.Stat(marker + ".grandchild"); err == nil {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("grandchild not ready")
		}
		time.Sleep(10 * time.Millisecond)
	}
	cancel()
	r := <-result
	if r.Reason != "CANCELED" || !r.ProcessExited {
		t.Fatalf("%+v", r)
	}
	for _, suffix := range []string{".child", ".grandchild"} {
		b, err := os.ReadFile(marker + suffix)
		if err != nil {
			t.Fatal(err)
		}
		pid, err := strconv.Atoi(string(b))
		if err != nil {
			t.Fatal(err)
		}
		if processAlive(pid) {
			t.Fatalf("descendant %d remains alive", pid)
		}
	}
}

type failingWriter struct{}

func (failingWriter) Write([]byte) (int, error) { return 0, errors.New("disk full") }

func TestDrainAfterOutputFailure(t *testing.T) {
	s := helperSpec(t, "flood")
	s.Stdout = failingWriter{}
	s.Stderr = io.Discard
	r := Run(context.Background(), s)
	if !r.ProcessExited || r.Reason != "EXITED" || r.ExitCode == nil || *r.ExitCode != 0 || r.Err == nil || !strings.Contains(r.Err.Error(), "disk full") {
		t.Fatalf("%+v", r)
	}
}

func TestParallelWorkspaces(t *testing.T) {
	for i := 0; i < 3; i++ {
		t.Run(strconv.Itoa(i), func(t *testing.T) {
			t.Parallel()
			w, err := workspace.Create(t.TempDir(), workspace.Context{RunID: "BX1", AssignmentID: "BA1", Attempt: 1, Session: "1", BusinessKey: "BX1"}, []byte(fmt.Sprintf(`{"index":%d}`, i)))
			if err != nil {
				t.Fatal(err)
			}
			s := helperSpec(t, "echo")
			s.Environment = append(s.Environment, w.Environment()...)
			r := Run(context.Background(), s)
			if r.Err != nil || !r.ProcessExited {
				t.Fatalf("%+v", r)
			}
			b, err := w.Result()
			if err != nil || !bytes.Contains(b, []byte(fmt.Sprintf(`"index":%d`, i))) {
				t.Fatal(string(b), err)
			}
		})
	}
}
