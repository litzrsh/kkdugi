package client

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"regexp"
	"strconv"
	"strings"
	"unicode/utf8"
)

var programCodePattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,20}$`)

// CanonicalProgram returns owned wire bytes. Availability is deliberately not part
// of the immutable revision definition; callers hash a copy with AVAILABLE.
func CanonicalProgram(input ProgramReport) ([]byte, error) {
	if !textValid(input.Version, 50) || !textValid(input.Revision, 200) {
		return nil, ErrProtocol
	}
	if input.Availability != "AVAILABLE" && input.Availability != "MISSING" && input.Availability != "INVALID" {
		return nil, ErrProtocol
	}
	m := &input.Manifest
	if !textValid(m.Executable, 4096) || !textValid(m.WorkingDirectory, 4096) || !textValid(m.InputContract, 200) || m.InputMode != "JSON_FILE" {
		return nil, ErrProtocol
	}
	m.Arguments = append([]string{}, m.Arguments...)
	m.SecretNames = append([]string{}, m.SecretNames...)
	m.Files = append([]ProgramFile{}, m.Files...)
	for _, arg := range m.Arguments {
		if !utf8.ValidString(arg) || strings.ContainsRune(arg, 0) {
			return nil, ErrProtocol
		}
	}
	for _, name := range m.SecretNames {
		if !textValid(name, 200) {
			return nil, ErrProtocol
		}
	}
	for i := range m.Files {
		f := &m.Files[i]
		b, err := hex.DecodeString(f.SHA256)
		if !textValid(f.Path, 4096) || err != nil || len(b) != 32 {
			return nil, ErrProtocol
		}
		f.SHA256 = strings.ToLower(f.SHA256)
	}
	manifest, err := json.Marshal(m)
	if err != nil || len(manifest) > 256<<10 {
		return nil, ErrTooLarge
	}
	b, err := json.Marshal(input)
	if err != nil {
		return nil, ErrProtocol
	}
	if validateObject(b) != nil {
		return nil, ErrTooLarge
	}
	return b, nil
}

func DecodeProgramApproval(body []byte, report ProgramReport) (ProgramApproval, error) {
	var result ProgramApproval
	if validateObject(body) != nil {
		return result, ErrProtocol
	}
	var required struct {
		ProgramID        *string         `json:"programId"`
		Revision         *string         `json:"revision"`
		ApprovedRevision json.RawMessage `json:"approvedRevision"`
		Enabled          *bool           `json:"enabled"`
		Runnable         *bool           `json:"runnable"`
	}
	if json.Unmarshal(body, &required) != nil || required.ProgramID == nil || required.Revision == nil || required.Enabled == nil || required.Runnable == nil || len(required.ApprovedRevision) == 0 {
		return result, ErrProtocol
	}
	if json.Unmarshal(body, &result) != nil || !programCodePattern.MatchString(result.ProgramID) || result.Revision != report.Revision {
		return ProgramApproval{}, ErrProtocol
	}
	if result.ApprovedRevision != nil && !textValid(*result.ApprovedRevision, 200) {
		return ProgramApproval{}, ErrProtocol
	}
	if result.Runnable && (!result.Enabled || result.ApprovedRevision == nil || *result.ApprovedRevision != report.Revision || report.Availability != "AVAILABLE") {
		return ProgramApproval{}, ErrProtocol
	}
	return result, nil
}

// ReportProgram consumes the original durable body and request headers. It never
// reserializes or retries that request, and does not mint an idempotency key.
func (c *Client) ReportProgram(ctx context.Context, token Secret, session Decimal, key string, createdAt Timestamp, code string, body []byte) (ProgramApproval, error) {
	var report ProgramReport
	if !programCodePattern.MatchString(code) || validateObject(body) != nil || json.Unmarshal(body, &report) != nil {
		return ProgramApproval{}, ErrProtocol
	}
	if _, err := CanonicalProgram(report); err != nil {
		return ProgramApproval{}, err
	}
	n, err := strconv.ParseInt(string(session), 10, 64)
	if session.Validate() != nil || err != nil || n <= 0 {
		return ProgramApproval{}, ErrProtocol
	}
	b, err := c.send(ctx, request{method: "PUT", path: "/programs/" + code, token: token, session: session, key: &requestKey{Value: key, CreatedAt: createdAt}, body: body, statuses: []int{200}})
	if err != nil {
		return ProgramApproval{}, err
	}
	return DecodeProgramApproval(b, report)
}
