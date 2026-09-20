package workspace

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

func testContext(id string) Context {
	return Context{RunID: "BX1", AssignmentID: id, Attempt: 1, Session: "1", BusinessKey: "BX1"}
}

func TestWorkspaceIsolationAndNoOverwrite(t *testing.T) {
	root := t.TempDir()
	one, err := Create(root, testContext("BA1"), []byte(`{"number":9007199254740993}`))
	if err != nil {
		t.Fatal(err)
	}
	two, err := Create(root, testContext("BA2"), []byte(`{"number":2}`))
	if err != nil {
		t.Fatal(err)
	}
	if one.Dir == two.Dir {
		t.Fatal("shared workspace")
	}
	if _, err := Create(root, testContext("BA1"), []byte(`{}`)); err == nil {
		t.Fatal("reused assignment")
	}
	b, err := os.ReadFile(one.InputFile)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Contains(b, []byte("9007199254740993")) {
		t.Fatal("input precision lost")
	}
	if result, err := one.Result(); err != nil || result != nil {
		t.Fatal(result, err)
	}
	if err := os.WriteFile(two.ResultFile, []byte(`{"ok":true}`), 0600); err != nil {
		t.Fatal(err)
	}
	if result, err := two.Result(); err != nil || string(result) != `{"ok":true}` {
		t.Fatal(string(result), err)
	}
}

func TestRejectInvalidInputAndPaths(t *testing.T) {
	for _, session := range []string{"", "-1", "+1", "9223372036854775808", "not-a-number"} {
		ctx := testContext("BA1")
		ctx.Session = session
		if _, err := Create(t.TempDir(), ctx, []byte(`{}`)); err == nil {
			t.Fatalf("accepted session %q", session)
		}
	}
	for _, id := range []string{"../other", "a/b", "CON", "LPT1", "", "a\\b"} {
		if _, err := Create(t.TempDir(), testContext(id), []byte(`{}`)); err == nil {
			t.Fatalf("accepted %q", id)
		}
	}
	for _, data := range [][]byte{[]byte(`null`), []byte(`[]`), []byte(`{} {}`), {0xff}, bytes.Repeat([]byte(" "), MaxJSONBytes+1)} {
		if _, err := Object(data); err == nil {
			t.Fatal("invalid object accepted")
		}
	}
}

func TestRejectInvalidResult(t *testing.T) {
	w, err := Create(t.TempDir(), testContext("BA1"), []byte(`{}`))
	if err != nil {
		t.Fatal(err)
	}
	for _, b := range [][]byte{[]byte(`[]`), bytes.Repeat([]byte(" "), MaxJSONBytes+1)} {
		if err := os.WriteFile(w.ResultFile, b, 0600); err != nil {
			t.Fatal(err)
		}
		if _, err := w.Result(); err == nil {
			t.Fatal("accepted invalid result")
		}
	}
	if err := os.Remove(w.ResultFile); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(w.ResultFile, 0700); err != nil {
		t.Fatal(err)
	}
	if _, err := w.Result(); err == nil {
		t.Fatal("accepted directory")
	}
}

func TestRejectSymlinkResult(t *testing.T) {
	w, err := Create(t.TempDir(), testContext("BA1"), []byte(`{}`))
	if err != nil {
		t.Fatal(err)
	}
	other := filepath.Join(t.TempDir(), "result.json")
	if err := os.WriteFile(other, []byte(`{}`), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(other, w.ResultFile); err != nil {
		t.Skipf("symlink unavailable: %v", err)
	}
	if _, err := w.Result(); err == nil {
		t.Fatal("accepted symlink")
	}
}
