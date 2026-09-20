package client

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"strconv"
	"unicode/utf8"
)

func Sequence(d Decimal) (int64, error) {
	if d.Validate() != nil {
		return 0, ErrProtocol
	}
	n, e := strconv.ParseInt(string(d), 10, 64)
	if e != nil || strconv.FormatInt(n, 10) != string(d) {
		return 0, ErrProtocol
	}
	return n, nil
}
func LogHash(text string) string { h := sha256.Sum256([]byte(text)); return hex.EncodeToString(h[:]) }
func (c *Client) UploadLog(ctx context.Context, token Secret, session Decimal, id, stream string, sequence Decimal, chunk LogChunk) (LogAck, error) {
	var ack LogAck
	if _, e := Sequence(sequence); e != nil {
		return ack, e
	}
	if !programCodePattern.MatchString(id) || (stream != "STDOUT" && stream != "STDERR") || sequence.Validate() != nil || !utf8.ValidString(chunk.Text) || len(chunk.Text) == 0 || len(chunk.Text) > 32<<10 || LogHash(chunk.Text) != chunk.SHA256 {
		return ack, ErrProtocol
	}
	if _, e := chunk.EmittedAt.Time(); e != nil {
		return ack, ErrProtocol
	}
	b, e := json.Marshal(chunk)
	if e != nil {
		return ack, ErrProtocol
	}
	b, e = c.send(ctx, request{method: "PUT", path: "/assignments/" + id + "/logs/" + stream + "/" + string(sequence), token: token, session: session, body: b, statuses: []int{200}})
	if e != nil {
		return ack, e
	}
	if !required(b, "acceptedSequence", "logsClosed") || json.Unmarshal(b, &ack) != nil || ack.AcceptedSequence != sequence {
		return ack, ErrProtocol
	}
	var raw map[string]json.RawMessage
	json.Unmarshal(b, &raw)
	if _, ok := raw["contiguousThrough"]; !ok {
		return ack, ErrProtocol
	}
	if ack.ContiguousThrough != nil {
		if _, e = Sequence(*ack.ContiguousThrough); e != nil {
			return ack, e
		}
	}
	return ack, nil
}
func (c *Client) AssignmentDetail(ctx context.Context, token Secret, session Decimal, id string) (AssignmentDetail, error) {
	var d AssignmentDetail
	if !programCodePattern.MatchString(id) {
		return d, ErrProtocol
	}
	b, e := c.send(ctx, request{method: "GET", path: "/assignments/" + id, token: token, session: session, statuses: []int{200}})
	if e != nil {
		return d, e
	}
	if !required(b, "id", "session", "state", "startAllowed", "logOffsets") || json.Unmarshal(b, &d) != nil || d.ID != id || d.Session.Validate() != nil || !textValid(d.State, 20) {
		return d, ErrProtocol
	}
	var raw map[string]json.RawMessage
	json.Unmarshal(b, &raw)
	if _, ok := raw["outcome"]; !ok {
		return d, ErrProtocol
	}
	for _, s := range []string{"STDOUT", "STDERR"} {
		v, ok := d.LogOffsets[s]
		if !ok {
			return d, ErrProtocol
		}
		if v != nil {
			if _, e := Sequence(*v); e != nil {
				return d, e
			}
		}
	}
	return d, nil
}
func DecodeReconcile(b []byte, id string, session Decimal) (ReconcileResponse, error) {
	var r ReconcileResponse
	var fields map[string]json.RawMessage
	if json.Unmarshal(b, &fields) != nil {
		return r, ErrProtocol
	}
	if _, ok := fields["leaseUntil"]; !ok {
		return r, ErrProtocol
	}
	if !required(b, "id", "disposition", "session", "startAllowed") || json.Unmarshal(b, &r) != nil || r.ID != id || r.Session != session || r.StartAllowed {
		return r, ErrProtocol
	}
	switch r.Disposition {
	case "CONTINUE_EXISTING", "STOP_AND_REPORT", "REPORT_COMPLETION", "HOLD", "RESOLVED":
	default:
		return r, ErrProtocol
	}
	if r.LeaseUntil != nil {
		if _, e := r.LeaseUntil.Time(); e != nil {
			return r, ErrProtocol
		}
	}
	if r.Disposition == "CONTINUE_EXISTING" && r.LeaseUntil == nil {
		return r, ErrProtocol
	}
	return r, nil
}
