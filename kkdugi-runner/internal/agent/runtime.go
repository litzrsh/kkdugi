package agent

import (
	"context"
	"errors"
	"io"
	"os"
	"path/filepath"
	"time"

	"kkdugi-runner/internal/client"
	"kkdugi-runner/internal/config"
	"kkdugi-runner/internal/credential"
	"kkdugi-runner/internal/state"
)

func RunConfigured(ctx context.Context, cfg *config.Config, version string) error {
	if cfg == nil || cfg.Admin == nil {
		return errors.New("run requires admin configuration")
	}
	saved, err := credential.Load(cfg.Admin.CredentialDir, cfg.Admin.BaseURL)
	if err != nil {
		return err
	}
	if saved.RunnerCode != cfg.Admin.RunnerCode {
		return errors.New("credential runner code does not match configuration")
	}
	var ca []byte
	if cfg.Admin.CAFile != "" {
		f, err := os.Open(cfg.Admin.CAFile)
		if err != nil {
			return errors.New("cannot read admin CA file")
		}
		ca, err = io.ReadAll(io.LimitReader(f, client.MaxJSONBytes+1))
		f.Close()
		if err != nil || len(ca) == 0 || len(ca) > client.MaxJSONBytes {
			return errors.New("invalid admin CA file")
		}
	}
	api, err := client.New(client.Options{BaseURL: cfg.Admin.BaseURL, Timeout: time.Duration(cfg.Admin.RequestTimeoutSeconds) * time.Second, CAPEM: ca})
	if err != nil {
		return err
	}
	defer api.Close()
	store, err := state.Open(ctx, cfg.DataDir, state.Identity{BaseURL: cfg.Admin.BaseURL, RunnerID: saved.Registration.RunnerID})
	if err != nil {
		return err
	}
	defer store.Close()
	a, err := New(Options{Config: cfg, Store: store, Client: api, Token: saved.Registration.AccessToken, Version: version, ResolveSecret: func(name string) (string, error) {
		return credential.ReadSecret(filepath.Join(cfg.Admin.CredentialDir, "secrets"), name)
	}})
	if err != nil {
		return err
	}
	return a.Run(ctx)
}
