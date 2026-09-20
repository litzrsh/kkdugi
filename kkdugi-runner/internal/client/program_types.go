package client

type ProgramFile struct {
	Path   string `json:"path"`
	SHA256 string `json:"sha256"`
}
type ProgramManifest struct {
	Executable       string        `json:"executable"`
	Arguments        []string      `json:"arguments"`
	WorkingDirectory string        `json:"workingDirectory"`
	InputContract    string        `json:"inputContract"`
	InputMode        string        `json:"inputMode"`
	SecretNames      []string      `json:"secretNames"`
	Files            []ProgramFile `json:"files"`
}
type ProgramReport struct {
	Version      string          `json:"version"`
	Revision     string          `json:"revision"`
	Availability string          `json:"availability"`
	Manifest     ProgramManifest `json:"manifest"`
}
type ProgramApproval struct {
	ProgramID        string  `json:"programId"`
	Revision         string  `json:"revision"`
	ApprovedRevision *string `json:"approvedRevision"`
	Enabled          bool    `json:"enabled"`
	Runnable         bool    `json:"runnable"`
}
