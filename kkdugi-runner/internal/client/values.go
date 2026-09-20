package client

import (
	"encoding/json"
	"errors"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

var decimalPattern = regexp.MustCompile(`^[0-9]+$`)
var timestampPattern = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,6})?Z$`)
var tokenPattern = regexp.MustCompile(`^[A-Za-z0-9._~+/-]+=*$`)
var bootPattern = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

type Decimal string

func (d Decimal) Validate() error {
	if len(d) > 19 || !decimalPattern.MatchString(string(d)) {
		return errors.New("invalid decimal string")
	}
	if _, err := strconv.ParseInt(string(d), 10, 64); err != nil {
		return errors.New("decimal exceeds signed bigint")
	}
	return nil
}
func (d *Decimal) UnmarshalJSON(b []byte) error {
	if len(b) == 0 || b[0] != '"' {
		return errors.New("decimal must be a JSON string")
	}
	var s string
	if err := json.Unmarshal(b, &s); err != nil {
		return errors.New("invalid decimal string")
	}
	v := Decimal(s)
	if err := v.Validate(); err != nil {
		return err
	}
	*d = v
	return nil
}
func (d Decimal) MarshalJSON() ([]byte, error) {
	if err := d.Validate(); err != nil {
		return nil, err
	}
	return json.Marshal(string(d))
}

type Timestamp string

func (s Timestamp) Time() (time.Time, error) {
	if !timestampPattern.MatchString(string(s)) {
		return time.Time{}, errors.New("timestamp must be UTC with at most six fractional digits")
	}
	t, err := time.Parse(time.RFC3339Nano, string(s))
	if err != nil {
		return time.Time{}, errors.New("invalid UTC timestamp")
	}
	return t, nil
}
func (s *Timestamp) UnmarshalJSON(b []byte) error {
	if len(b) == 0 || b[0] != '"' {
		return errors.New("timestamp must be a JSON string")
	}
	var v string
	if json.Unmarshal(b, &v) != nil {
		return errors.New("invalid timestamp")
	}
	stamp := Timestamp(v)
	if _, err := stamp.Time(); err != nil {
		return err
	}
	*s = stamp
	return nil
}
func (s Timestamp) MarshalJSON() ([]byte, error) {
	if _, err := s.Time(); err != nil {
		return nil, err
	}
	return json.Marshal(string(s))
}

type Secret string

func (Secret) String() string   { return "[REDACTED]" }
func (Secret) GoString() string { return "[REDACTED]" }
func (s Secret) Validate() error {
	if len(s) == 0 || len(s) > 8192 || !tokenPattern.MatchString(string(s)) {
		return errors.New("invalid bearer token")
	}
	return nil
}

func textValid(s string, max int) bool {
	if !utf8.ValidString(s) || strings.TrimSpace(s) == "" || utf8.RuneCountInString(s) > max {
		return false
	}
	for _, r := range s {
		if r < 32 || r == 127 {
			return false
		}
	}
	return true
}

func (r RegistrationRequest) Validate() error {
	if !textValid(r.RunnerCode, 20) || !textValid(r.AgentVersion, 50) || !textValid(r.Hostname, 200) {
		return errors.New("invalid registration identity")
	}
	if r.OS != "LINUX" && r.OS != "WINDOWS" {
		return errors.New("unsupported runner OS")
	}
	if r.Architecture != "AMD64" && r.Architecture != "ARM64" {
		return errors.New("unsupported runner architecture")
	}
	return nil
}
func (r RegistrationResponse) Validate(now time.Time) error {
	if !textValid(r.RunnerID, 20) || !textValid(r.CredentialID, 20) || r.AccessToken.Validate() != nil || r.Session != "0" {
		return errors.New("invalid registration response")
	}
	expires, err := r.TokenExpiresAt.Time()
	if err != nil || !expires.After(now) {
		return errors.New("invalid or expired credential")
	}
	return nil
}
func (r SessionRequest) Validate() error {
	if !bootPattern.MatchString(r.BootID) || !textValid(r.AgentVersion, 50) {
		return errors.New("invalid session request")
	}
	return r.ExpectedSession.Validate()
}
func (r SessionResponse) Validate() error {
	session, sessionErr := strconv.ParseInt(string(r.Session), 10, 64)
	if !textValid(r.RunnerID, 20) || r.Session.Validate() != nil || sessionErr != nil || session == 0 {
		return errors.New("invalid session response")
	}
	if _, err := r.ServerTime.Time(); err != nil {
		return err
	}
	if r.HeartbeatSeconds <= 0 || r.HeartbeatSeconds > 2147483647 || r.PollSeconds <= 0 || r.PollSeconds > 2147483647 || r.LeaseSeconds <= 0 || r.LeaseSeconds > 2147483647 || r.Capacity < 1 || r.Capacity > 200 {
		return errors.New("invalid session operating limits")
	}
	l := r.Limits
	if l.JSONBytes < 1 || l.JSONBytes > MaxJSONBytes || l.InputBytes < 1 || l.InputBytes > 256<<10 || l.ResultBytes < 1 || l.ResultBytes > 256<<10 || l.LogChunkBytes < 1 || l.LogChunkBytes > 32<<10 {
		return errors.New("invalid payload limits")
	}
	return nil
}
