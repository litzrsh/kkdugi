package client

import (
	"bytes"
	"encoding/json"
	"errors"
	"strings"
	"testing"
)

func reportFixture() ProgramReport {
	return ProgramReport{Version: "1", Revision: "rev1", Availability: "AVAILABLE", Manifest: ProgramManifest{Executable: "/bin/program", WorkingDirectory: "/tmp", InputContract: "1", InputMode: "JSON_FILE"}}
}
func TestProgramApprovalRequiredAndConsistent(t *testing.T) {
	valid := `{"programId":"BP1","revision":"rev1","approvedRevision":"rev1","enabled":true,"runnable":true}`
	for _, body := range []string{
		strings.Replace(valid, `"enabled":true,`, "", 1), strings.Replace(valid, `"enabled":true`, `"enabled":null`, 1), strings.Replace(valid, `"runnable":true`, `"runnable":null`, 1), strings.Replace(valid, `"approvedRevision":"rev1",`, "", 1), strings.Replace(valid, `"approvedRevision":"rev1"`, `"approvedRevision":null`, 1), strings.Replace(valid, `"enabled":true`, `"enabled":false`, 1), strings.Replace(valid, `"revision":"rev1"`, `"revision":"other"`, 1), strings.Replace(valid, `"approvedRevision":"rev1"`, `"approvedRevision":"other"`, 1), strings.Replace(valid, `"programId":"BP1"`, `"programId":""`, 1),
	} {
		if _, err := DecodeProgramApproval([]byte(body), reportFixture()); !errors.Is(err, ErrProtocol) {
			t.Fatalf("accepted %s: %v", body, err)
		}
	}
	if _, err := DecodeProgramApproval([]byte(valid), reportFixture()); err != nil {
		t.Fatal(err)
	}
	denied := `{"programId":"BP1","revision":"rev1","approvedRevision":null,"enabled":false,"runnable":false,"futureField":true}`
	if _, err := DecodeProgramApproval([]byte(denied), reportFixture()); err != nil {
		t.Fatal(err)
	}
	r := reportFixture()
	r.Availability = "MISSING"
	if _, err := DecodeProgramApproval([]byte(valid), r); !errors.Is(err, ErrProtocol) {
		t.Fatal(err)
	}
}
func TestCanonicalProgramLimitsAndCopy(t *testing.T) {
	r := reportFixture()
	r.Manifest.Files = []ProgramFile{{Path: "/bin/program", SHA256: strings.Repeat("A", 64)}}
	b, err := CanonicalProgram(r)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Contains(b, []byte(`"arguments":[]`)) || !bytes.Contains(b, []byte(strings.Repeat("a", 64))) {
		t.Fatal(string(b))
	}
	if r.Manifest.Files[0].SHA256 != strings.Repeat("A", 64) {
		t.Fatal("mutated input")
	}
	var decoded ProgramReport
	if json.Unmarshal(b, &decoded) != nil {
		t.Fatal("invalid wire JSON")
	}
	decoded.Manifest.Arguments = []string{strings.Repeat("x", 256<<10)}
	if _, err = CanonicalProgram(decoded); !errors.Is(err, ErrTooLarge) {
		t.Fatal(err)
	}
}
