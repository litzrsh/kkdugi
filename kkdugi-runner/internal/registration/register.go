package registration

import (
	"context"
	"errors"
	"io"
	"os"
	"runtime"
	"strings"
	"time"

	"kkdugi-runner/internal/client"
	"kkdugi-runner/internal/config"
	"kkdugi-runner/internal/credential"
)

func Run(ctx context.Context, settings *config.Admin, version string, stdin io.Reader) error {
	if settings == nil {
		return errors.New("register requires an [admin] configuration")
	}
	if err := credential.CheckAvailable(settings.CredentialDir); err != nil {
		return err
	}
	var ca []byte
	if settings.CAFile != "" {
		f, err := os.Open(settings.CAFile)
		if err != nil {
			return errors.New("cannot open admin CA file")
		}
		ca, err = io.ReadAll(io.LimitReader(f, client.MaxJSONBytes+1))
		closeErr := f.Close()
		if err != nil || closeErr != nil || len(ca) == 0 || len(ca) > client.MaxJSONBytes {
			return errors.New("cannot read admin CA file within limit")
		}
	}
	c, err := client.New(client.Options{BaseURL: settings.BaseURL, Timeout: time.Duration(settings.RequestTimeoutSeconds) * time.Second, CAPEM: ca})
	if err != nil {
		return err
	}
	defer c.Close()
	hostname, err := os.Hostname()
	if err != nil {
		return errors.New("cannot determine runner hostname")
	}
	input := client.RegistrationRequest{RunnerCode: settings.RunnerCode, AgentVersion: version, Hostname: hostname, OS: strings.ToUpper(runtime.GOOS), Architecture: strings.ToUpper(runtime.GOARCH)}
	if err := input.Validate(); err != nil {
		return err
	}
	b, err := io.ReadAll(io.LimitReader(stdin, 8195))
	if err != nil || len(b) > 8194 {
		return errors.New("cannot read enrollment token within limit")
	}
	token := client.Secret(strings.TrimSuffix(strings.TrimSuffix(string(b), "\n"), "\r"))
	if err := token.Validate(); err != nil {
		return err
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	attempt, err := credential.Begin(settings.CredentialDir)
	if err != nil {
		return err
	}
	response, err := c.Register(ctx, token, input)
	if err != nil {
		return errors.Join(errors.New("registration not confirmed; preserve local attempt and inspect admin before retrying"), err)
	}
	if err := attempt.Commit(credential.Stored{SchemaVersion: 1, BaseURL: c.BaseURL(), RunnerCode: settings.RunnerCode, Registration: response}); err != nil {
		return err
	}
	return nil
}
