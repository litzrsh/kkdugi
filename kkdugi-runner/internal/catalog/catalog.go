package catalog

import (
	"context"
	"errors"
	"os"

	"kkdugi-runner/internal/client"
	"kkdugi-runner/internal/config"
	"kkdugi-runner/internal/state"
)

var ErrConfiguration = errors.New("invalid program catalog configuration")
var ErrNotApproved = errors.New("program is not approved for this assignment and session")
var ErrUnavailable = errors.New("program installation is unavailable or changed")

type reporter interface {
	ReportProgram(context.Context, client.Secret, client.Decimal, string, client.Timestamp, string, []byte) (client.ProgramApproval, error)
}
type Catalog struct {
	store *state.Store
	api   reporter
	token client.Secret
}

func New(store *state.Store, api reporter, token client.Secret) *Catalog {
	return &Catalog{store: store, api: api, token: token}
}

func report(p config.Program, availability string) client.ProgramReport {
	m := p.Manifest
	files := make([]client.ProgramFile, 0, len(m.Files))
	for _, f := range m.Files {
		files = append(files, client.ProgramFile{Path: f.Path, SHA256: f.SHA256})
	}
	return client.ProgramReport{Version: p.Version, Revision: p.Revision, Availability: availability, Manifest: client.ProgramManifest{Executable: m.Executable, Arguments: append([]string{}, m.Arguments...), WorkingDirectory: m.WorkingDirectory, InputContract: m.InputContract, InputMode: m.InputMode, SecretNames: append([]string{}, m.SecretNames...), Files: files}}
}
func local(code string, r client.ProgramReport) config.Program {
	m := r.Manifest
	files := make([]config.FileDigest, 0, len(m.Files))
	for _, f := range m.Files {
		files = append(files, config.FileDigest{Path: f.Path, SHA256: f.SHA256})
	}
	return config.Program{Code: code, Version: r.Version, Revision: r.Revision, Manifest: config.Manifest{Executable: m.Executable, Arguments: append([]string{}, m.Arguments...), WorkingDirectory: m.WorkingDirectory, InputContract: m.InputContract, InputMode: m.InputMode, SecretNames: append([]string{}, m.SecretNames...), Files: files}}
}
func availability(err error) string {
	if err == nil {
		return "AVAILABLE"
	}
	if errors.Is(err, os.ErrNotExist) {
		return "MISSING"
	}
	return "INVALID"
}

// Refresh is atomic with respect to the complete configuration list. File failures
// are reportable installation states; malformed configuration is not an empty list.
func (c *Catalog) Refresh(ctx context.Context, programs []config.Program) error {
	seen := map[string]bool{}
	for _, p := range programs {
		if p.Validate() != nil || seen[p.Code] {
			return ErrConfiguration
		}
		seen[p.Code] = true
	}
	items := make([]state.Installation, 0, len(programs))
	for _, p := range programs {
		if err := ctx.Err(); err != nil {
			return err
		}
		items = append(items, state.Installation{Code: p.Code, Report: report(p, availability(p.VerifyFiles()))})
	}
	return c.store.SyncPrograms(ctx, items)
}

// Publish makes exactly one attempt. The next call resolves the retained request
// before a newer local snapshot can be reported.
func (c *Catalog) Publish(ctx context.Context, code string) error {
	if c.api == nil {
		return errors.New("program reporting client is required")
	}
	r, err := c.store.QueueProgramReport(ctx, code)
	if err != nil {
		return err
	}
	r, err = c.store.BeginSend(ctx, r.ID)
	if err != nil {
		return err
	}
	a, err := c.api.ReportProgram(ctx, c.token, r.Session, r.Key, r.CreatedAt, code, r.Body)
	if err != nil {
		return err
	}
	return c.store.AcceptProgramReport(ctx, r.ID, a)
}

// Resolve is a local eligibility check, not start authority. R5 must additionally
// validate the assignment's live start permission, lease and cancellation state.
func (c *Catalog) Resolve(ctx context.Context, programID, code, version, revision string) (config.Program, error) {
	p, err := c.store.Program(ctx, code)
	if err != nil {
		return config.Program{}, err
	}
	if !approved(p, programID, version, revision) {
		return config.Program{}, ErrNotApproved
	}
	result := local(code, p.Report)
	if err = result.VerifyFiles(); err != nil {
		storeErr := c.store.MarkProgramUnavailable(ctx, code, p.Report, availability(err))
		return config.Program{}, errors.Join(ErrUnavailable, storeErr)
	}
	// A concurrent refresh/report must not leave this call using an invalidated ACK.
	current, err := c.store.Program(ctx, code)
	if err != nil {
		return config.Program{}, err
	}
	if !approved(current, programID, version, revision) {
		return config.Program{}, ErrNotApproved
	}
	return result, nil
}
func approved(p state.Program, id, version, revision string) bool {
	a := p.Approval
	return p.Report.Version == version && p.Report.Revision == revision && p.Report.Availability == "AVAILABLE" && a != nil && a.ProgramID == id && a.Revision == revision && a.Enabled && a.Runnable && a.ApprovedRevision != nil && *a.ApprovedRevision == revision
}
