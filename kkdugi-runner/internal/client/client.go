package client

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net"
	"net/http"
	"net/url"
	"path"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

const MaxJSONBytes = 1 << 20
const BasePath = "/api/v1.0/batch-agent"

var ErrProtocol = errors.New("invalid admin response")
var ErrRedirect = errors.New("admin redirect refused")
var ErrTooLarge = errors.New("admin payload exceeds limit")

type APIError struct {
	Status     int
	Code       string
	RetryAfter time.Duration
}

func (e *APIError) Error() string    { return fmt.Sprintf("admin HTTP %d", e.Status) }
func (e *APIError) GoString() string { return e.Error() }

type TransportError struct{ cause error }

func (*TransportError) Error() string {
	return "admin transport failed; request outcome may be unknown"
}
func (e *TransportError) Unwrap() error    { return e.cause }
func (e *TransportError) GoString() string { return e.Error() }

type Client struct {
	base string
	http *http.Client
}
type Options struct {
	BaseURL string
	Timeout time.Duration
	CAPEM   []byte
}

func NormalizeBaseURL(raw string) (string, error) {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" || u.RawPath != "" || strings.ContainsAny(raw, "\\\r\n\t") {
		return "", errors.New("admin base URL must be HTTPS without credentials, query or fragment")
	}
	u.Path = strings.TrimSuffix(u.Path, "/")
	if path.Clean(u.Path) != u.Path || !strings.HasSuffix(u.Path, BasePath) {
		return "", errors.New("admin base URL must end in /api/v1.0/batch-agent without path traversal")
	}
	u.Host = strings.ToLower(u.Host)
	return u.String(), nil
}

func New(o Options) (*Client, error) {
	base, err := NormalizeBaseURL(o.BaseURL)
	if err != nil {
		return nil, err
	}
	if o.Timeout <= 0 || o.Timeout > 300*time.Second {
		return nil, errors.New("request timeout must be between 0 and 300 seconds")
	}
	var roots *x509.CertPool
	if o.CAPEM != nil {
		if len(o.CAPEM) > MaxJSONBytes {
			return nil, errors.New("CA file exceeds limit")
		}
		roots, err = x509.SystemCertPool()
		if err != nil {
			roots = x509.NewCertPool()
		}
		if !roots.AppendCertsFromPEM(o.CAPEM) {
			return nil, errors.New("CA file contains no valid certificate")
		}
	}
	transport := &http.Transport{Proxy: http.ProxyFromEnvironment, DialContext: (&net.Dialer{Timeout: o.Timeout, KeepAlive: 30 * time.Second}).DialContext, TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12, RootCAs: roots}, TLSHandshakeTimeout: o.Timeout, ResponseHeaderTimeout: o.Timeout, IdleConnTimeout: 90 * time.Second, MaxIdleConns: 16, MaxIdleConnsPerHost: 8, MaxResponseHeaderBytes: 64 << 10, DisableCompression: true, ForceAttemptHTTP2: true}
	return &Client{base: base, http: &http.Client{Transport: transport, Timeout: o.Timeout, CheckRedirect: func(*http.Request, []*http.Request) error { return ErrRedirect }}}, nil
}
func (c *Client) Close()          { c.http.CloseIdleConnections() }
func (c *Client) BaseURL() string { return c.base }

type requestKey struct {
	Value     string
	CreatedAt Timestamp
}
type request struct {
	method, path string
	token        Secret
	session      Decimal
	key          *requestKey
	body         []byte
	statuses     []int
	noStore      bool
	array        bool
}

func validateObject(b []byte) error {
	if len(b) > MaxJSONBytes {
		return ErrTooLarge
	}
	b = bytes.TrimSpace(b)
	if !utf8.Valid(b) || len(b) == 0 || b[0] != '{' || !json.Valid(b) {
		return ErrProtocol
	}
	return nil
}

// send never creates keys or retries. The owning agent must retain a command before calling it.
func (c *Client) send(ctx context.Context, r request) ([]byte, error) {
	if r.token.Validate() != nil {
		return nil, errors.New("invalid bearer token")
	}
	u, err := url.Parse(r.path)
	if err != nil || u.IsAbs() || u.Host != "" || u.Fragment != "" || u.RawPath != "" || !strings.HasPrefix(r.path, "/") || strings.HasPrefix(r.path, "//") || path.Clean(u.Path) != u.Path || strings.ContainsAny(r.path, "\\\r\n") {
		return nil, errors.New("invalid relative API path")
	}
	if r.method != http.MethodGet && r.method != http.MethodPost && r.method != http.MethodPut {
		return nil, errors.New("unsupported API method")
	}
	if r.path != "/registrations" && r.path != "/sessions" {
		if err := r.session.Validate(); err != nil {
			return nil, err
		}
	} else if r.session != "" || r.key != nil {
		return nil, errors.New("unexpected session or key header")
	}
	if len(r.body) > 0 {
		if err := validateObject(r.body); err != nil {
			return nil, err
		}
	} else if r.method != http.MethodGet {
		return nil, errors.New("JSON body required")
	}
	var body io.ReadCloser
	if len(r.body) > 0 {
		body = io.NopCloser(bytes.NewReader(r.body))
	}
	req, err := http.NewRequestWithContext(ctx, r.method, c.base+r.path, body)
	if err != nil {
		return nil, errors.New("cannot construct API request")
	}
	// Keep GetBody nil so net/http cannot replay a POST after a broken pooled connection.
	req.ContentLength = int64(len(r.body))
	req.Header.Set("Authorization", "Bearer "+string(r.token))
	req.Header.Set("X-Protocol-Version", "1")
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if r.session != "" {
		req.Header.Set("X-Runner-Session", string(r.session))
	}
	if r.key != nil {
		if len(r.key.Value) < 1 || len(r.key.Value) > 200 {
			return nil, errors.New("invalid idempotency key")
		}
		for _, ch := range r.key.Value {
			if ch < 33 || ch > 126 {
				return nil, errors.New("idempotency key must be printable ASCII")
			}
		}
		if _, err := r.key.CreatedAt.Time(); err != nil {
			return nil, err
		}
		req.Header.Set("Idempotency-Key", r.key.Value)
		req.Header.Set("X-Request-Created-At", string(r.key.CreatedAt))
	}
	response, err := c.http.Do(req)
	if err != nil {
		return nil, &TransportError{cause: err}
	}
	defer response.Body.Close()
	b, err := io.ReadAll(io.LimitReader(response.Body, MaxJSONBytes+1))
	if err != nil {
		return nil, &TransportError{cause: err}
	}
	if len(b) > MaxJSONBytes {
		return nil, ErrTooLarge
	}
	if response.StatusCode >= 300 && response.StatusCode < 400 {
		return nil, ErrRedirect
	}
	if response.StatusCode >= 400 {
		apiErr := &APIError{Status: response.StatusCode}
		if seconds, err := strconv.ParseUint(response.Header.Get("Retry-After"), 10, 32); err == nil {
			apiErr.RetryAfter = time.Duration(seconds) * time.Second
		}
		var payload struct {
			Code string `json:"code"`
		}
		if validateObject(b) == nil && json.Unmarshal(b, &payload) == nil && textValid(payload.Code, 200) {
			apiErr.Code = payload.Code
		}
		return nil, apiErr
	}
	allowed := false
	for _, s := range r.statuses {
		if response.StatusCode == s {
			allowed = true
		}
	}
	if !allowed {
		return nil, ErrProtocol
	}
	if response.StatusCode == http.StatusNoContent {
		if len(b) != 0 {
			return nil, ErrProtocol
		}
		return nil, nil
	}
	media, params, err := mime.ParseMediaType(response.Header.Get("Content-Type"))
	if err != nil || media != "application/json" || (params["charset"] != "" && !strings.EqualFold(params["charset"], "utf-8")) {
		return nil, ErrProtocol
	}
	if r.array {
		trim := bytes.TrimSpace(b)
		if len(trim) == 0 || trim[0] != '[' || !utf8.Valid(trim) || !json.Valid(trim) {
			return nil, ErrProtocol
		}
	} else if err := validateObject(b); err != nil {
		return nil, err
	}
	if r.noStore {
		found := false
		for _, value := range response.Header.Values("Cache-Control") {
			for _, part := range strings.Split(value, ",") {
				if strings.EqualFold(strings.TrimSpace(part), "no-store") {
					found = true
				}
			}
		}
		if !found {
			return nil, ErrProtocol
		}
	}
	return b, nil
}

func (c *Client) Register(ctx context.Context, token Secret, input RegistrationRequest) (RegistrationResponse, error) {
	var output RegistrationResponse
	if err := input.Validate(); err != nil {
		return output, err
	}
	b, err := json.Marshal(input)
	if err != nil {
		return output, ErrProtocol
	}
	b, err = c.send(ctx, request{method: http.MethodPost, path: "/registrations", token: token, body: b, statuses: []int{201}, noStore: true})
	if err != nil {
		return output, err
	}
	if json.Unmarshal(b, &output) != nil {
		return RegistrationResponse{}, ErrProtocol
	}
	if err := output.Validate(time.Now()); err != nil {
		return RegistrationResponse{}, ErrProtocol
	}
	return output, nil
}

func (c *Client) OpenSession(ctx context.Context, token Secret, input SessionRequest) (SessionResponse, error) {
	var output SessionResponse
	if err := input.Validate(); err != nil {
		return output, err
	}
	b, err := json.Marshal(input)
	if err != nil {
		return output, ErrProtocol
	}
	b, err = c.send(ctx, request{method: http.MethodPost, path: "/sessions", token: token, body: b, statuses: []int{200}})
	if err != nil {
		return output, err
	}
	if json.Unmarshal(b, &output) != nil || output.Validate() != nil {
		return SessionResponse{}, ErrProtocol
	}
	return output, nil
}
