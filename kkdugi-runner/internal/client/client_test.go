package client

import (
	"context"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func registrationInput() RegistrationRequest {
	return RegistrationRequest{RunnerCode: "worker-01", AgentVersion: "0.1.0", Hostname: "host", OS: "WINDOWS", Architecture: "AMD64"}
}
func registrationOutput() RegistrationResponse {
	return RegistrationResponse{RunnerID: "BR1", CredentialID: "BC1", AccessToken: "access-secret", TokenExpiresAt: Timestamp(time.Now().Add(time.Hour).UTC().Format(time.RFC3339)), Session: "0"}
}
func reply(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(value)
}
func tlsClient(t *testing.T, h http.HandlerFunc) (*Client, *httptest.Server) {
	t.Helper()
	s := httptest.NewUnstartedServer(h)
	s.Config.ErrorLog = log.New(io.Discard, "", 0)
	s.StartTLS()
	t.Cleanup(s.Close)
	ca := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: s.Certificate().Raw})
	c, err := New(Options{BaseURL: s.URL + "/context" + BasePath, Timeout: time.Second, CAPEM: ca})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(c.Close)
	return c, s
}

func TestRegistrationHeadersContextAndUnknownResponseFields(t *testing.T) {
	c, _ := tlsClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" || r.URL.Path != "/context"+BasePath+"/registrations" {
			t.Error(r.Method, r.URL.Path)
		}
		if r.Header.Get("Authorization") != "Bearer enrollment-secret" || r.Header.Get("X-Protocol-Version") != "1" || r.Header.Get("Content-Type") != "application/json" {
			t.Error("invalid headers")
		}
		if r.Header.Get("X-Runner-Session") != "" || r.Header.Get("Idempotency-Key") != "" {
			t.Error("unexpected generation/key")
		}
		var input RegistrationRequest
		if json.NewDecoder(r.Body).Decode(&input) != nil || input != registrationInput() {
			t.Error("registration request changed")
		}
		b, _ := json.Marshal(registrationOutput())
		b = append(b[:len(b)-1], []byte(`,"futureField":true}`)...)
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("Cache-Control", "no-cache, no-store")
		w.WriteHeader(201)
		w.Write(b)
	})
	r, err := c.Register(context.Background(), "enrollment-secret", registrationInput())
	if err != nil || r.RunnerID != "BR1" || r.Session != "0" {
		t.Fatal(r, err)
	}
	for _, format := range []string{"%v", "%+v", "%#v", "%q"} {
		if strings.Contains(fmt.Sprintf(format, r.AccessToken), "access-secret") {
			t.Fatal("token leaked in fmt")
		}
	}
}

func TestRejectUntrustedTLSAndRedirect(t *testing.T) {
	var received atomic.Int32
	c, s := tlsClient(t, func(w http.ResponseWriter, r *http.Request) {
		received.Add(1)
		http.Redirect(w, r, "https://example.invalid/steal", 307)
	})
	plain, err := New(Options{BaseURL: s.URL + BasePath, Timeout: time.Second})
	if err != nil {
		t.Fatal(err)
	}
	defer plain.Close()
	if _, err := plain.Register(context.Background(), "secret", registrationInput()); err == nil {
		t.Fatal("untrusted TLS accepted")
	}
	if received.Load() != 0 {
		t.Fatal("request reached untrusted server")
	}
	_, err = c.Register(context.Background(), "secret", registrationInput())
	if !errors.Is(err, ErrRedirect) || received.Load() != 1 {
		t.Fatal(err, received.Load())
	}
}

func TestInvalidBaseURLs(t *testing.T) {
	for _, base := range []string{"http://host" + BasePath, "https://u:p@host" + BasePath, "https://host" + BasePath + "?x=secret", "https://host" + BasePath + "#secret", "https://host/a/../" + strings.TrimPrefix(BasePath, "/"), "https://host/%2e%2e" + BasePath, "https://host/api", "https:///api/v1.0/batch-agent"} {
		if _, err := NormalizeBaseURL(base); err == nil {
			t.Fatal(base)
		}
	}
}

func TestCustomCAStillChecksHostname(t *testing.T) {
	var received atomic.Int32
	c, s := tlsClient(t, func(w http.ResponseWriter, r *http.Request) { received.Add(1); reply(w, 201, registrationOutput()) })
	c.base = "https://wrong-host.invalid" + BasePath
	c.http.Transport.(*http.Transport).Proxy = nil
	c.http.Transport.(*http.Transport).DialContext = func(ctx context.Context, network, address string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, s.Listener.Addr().String())
	}
	if _, err := c.Register(context.Background(), "secret", registrationInput()); err == nil {
		t.Fatal("hostname mismatch accepted")
	}
	if received.Load() != 0 {
		t.Fatal("token sent to wrong host")
	}
}

func TestRejectEmptyCustomCA(t *testing.T) {
	if _, err := New(Options{BaseURL: "https://admin.example" + BasePath, Timeout: time.Second, CAPEM: []byte{}}); err == nil {
		t.Fatal("empty custom CA accepted")
	}
}

func TestHTTPErrorPreservesCodeButDoesNotPrintRemoteMessage(t *testing.T) {
	for _, status := range []int{400, 401, 403, 404, 409, 410, 413, 429, 500, 503} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			c, _ := tlsClient(t, func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Retry-After", "17")
				reply(w, status, map[string]string{"code": "batch.session.stale", "message": "enrollment-secret access-secret"})
			})
			_, err := c.Register(context.Background(), "enrollment-secret", registrationInput())
			var api *APIError
			if !errors.As(err, &api) || api.Status != status || api.Code != "batch.session.stale" || api.RetryAfter != 17*time.Second {
				t.Fatal(err)
			}
			for _, format := range []string{"%v", "%+v", "%#v"} {
				if strings.Contains(fmt.Sprintf(format, err), "secret") {
					t.Fatal("remote data leaked")
				}
			}
		})
	}
}

func TestDroppedResponseAndTimeoutAreNotRetried(t *testing.T) {
	var calls atomic.Int32
	c, _ := tlsClient(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		connection, _, err := w.(http.Hijacker).Hijack()
		if err != nil {
			t.Error(err)
			return
		}
		connection.Close()
	})
	if _, err := c.Register(context.Background(), "secret", registrationInput()); err == nil || calls.Load() != 1 {
		t.Fatal(err, calls.Load())
	}
	c, _ = tlsClient(t, func(w http.ResponseWriter, r *http.Request) {
		io.Copy(io.Discard, r.Body)
		select {
		case <-r.Context().Done():
		case <-time.After(250 * time.Millisecond):
		}
	})
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	if _, err := c.Register(ctx, "secret", registrationInput()); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatal(err)
	}
}

func TestRejectMalformedAndOversizedRegistrationResponses(t *testing.T) {
	valid, _ := json.Marshal(registrationOutput())
	for name, b := range map[string][]byte{"missing": []byte(`{}`), "numericSession": []byte(strings.Replace(string(valid), `"session":"0"`, `"session":0`, 1)), "nullSession": []byte(strings.Replace(string(valid), `"session":"0"`, `"session":null`, 1)), "twoObjects": append(append([]byte{}, valid...), []byte(`{}`)...), "array": []byte(`[]`), "utf8": {0xff}, "tooLarge": []byte(strings.Repeat(" ", MaxJSONBytes+1))} {
		t.Run(name, func(t *testing.T) {
			c, _ := tlsClient(t, func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				w.Header().Set("Cache-Control", "no-store")
				w.WriteHeader(201)
				w.Write(b)
			})
			if _, err := c.Register(context.Background(), "secret", registrationInput()); err == nil {
				t.Fatal("invalid response accepted")
			}
		})
	}
	for _, which := range []string{"contentType", "cacheControl", "status"} {
		t.Run(which, func(t *testing.T) {
			c, _ := tlsClient(t, func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				w.Header().Set("Cache-Control", "no-store")
				status := 201
				if which == "contentType" {
					w.Header().Set("Content-Type", "text/html")
				}
				if which == "cacheControl" {
					w.Header().Del("Cache-Control")
				}
				if which == "status" {
					status = 200
				}
				w.WriteHeader(status)
				w.Write(valid)
			})
			if _, err := c.Register(context.Background(), "secret", registrationInput()); err == nil {
				t.Fatal("invalid metadata accepted")
			}
		})
	}
}

func TestSessionAndCommonRequestHeaders(t *testing.T) {
	const large = "9223372036854775807"
	input := SessionRequest{BootID: "95358b21-7e8b-4f51-b7a4-93b1d0a4ab73", ExpectedSession: "9007199254740993", AgentVersion: "0.1.0"}
	c, _ := tlsClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/context"+BasePath+"/sessions" {
			var actual SessionRequest
			if json.NewDecoder(r.Body).Decode(&actual) != nil || actual != input {
				t.Error("session precision lost")
			}
			if r.Header.Get("Authorization") != "Bearer access-secret" || r.Header.Get("X-Runner-Session") != "" {
				t.Error("session auth")
			}
			reply(w, 200, SessionResponse{RunnerID: "BR1", Session: large, ServerTime: "2026-09-20T00:00:00.123456Z", HeartbeatSeconds: 10, PollSeconds: 3, LeaseSeconds: 60, Capacity: 200, Limits: Limits{MaxJSONBytes, 256 << 10, 256 << 10, 32 << 10}})
			return
		}
		if r.Header.Get("X-Runner-Session") != large || r.Header.Get("Idempotency-Key") != "saved-key" || r.Header.Get("X-Request-Created-At") != "2026-09-20T00:00:00Z" {
			t.Error("saved headers changed")
		}
		w.WriteHeader(204)
	})
	output, err := c.OpenSession(context.Background(), "access-secret", input)
	if err != nil || output.Session != large {
		t.Fatal(output, err)
	}
	body, err := c.send(context.Background(), request{method: "POST", path: "/assignments/claim", token: "access-secret", session: large, key: &requestKey{Value: "saved-key", CreatedAt: "2026-09-20T00:00:00Z"}, body: []byte(`{"freeSlots":1}`), statuses: []int{200, 204}})
	if err != nil || body != nil {
		t.Fatal(string(body), err)
	}
}

func TestRejectInvalidCommandsBeforeNetwork(t *testing.T) {
	var calls atomic.Int32
	c, _ := tlsClient(t, func(w http.ResponseWriter, r *http.Request) { calls.Add(1) })
	for _, key := range []string{"", "x\r\nInjected: value", strings.Repeat("x", 201)} {
		_, err := c.send(context.Background(), request{method: "POST", path: "/assignments/claim", token: "token", session: "1", key: &requestKey{Value: key, CreatedAt: "2026-09-20T00:00:00Z"}, body: []byte(`{}`), statuses: []int{200}})
		if err == nil {
			t.Fatal("bad key accepted")
		}
	}
	_, err := c.send(context.Background(), request{method: "POST", path: "/registrations", token: "token", body: []byte(strings.Repeat(" ", MaxJSONBytes+1)), statuses: []int{201}})
	if !errors.Is(err, ErrTooLarge) {
		t.Fatal(err)
	}
	bad := registrationInput()
	bad.Hostname = strings.Repeat("x", 201)
	if _, err := c.Register(context.Background(), "token", bad); err == nil {
		t.Fatal("bad identity accepted")
	}
	if calls.Load() != 0 {
		t.Fatal("invalid input reached server")
	}
}
