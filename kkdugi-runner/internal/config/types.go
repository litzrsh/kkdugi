package config

type Config struct {
	DataDir  string    `toml:"data_dir"`
	Capacity int       `toml:"capacity"`
	Programs []Program `toml:"programs"`
}

type Program struct {
	Code     string   `toml:"code"`
	Version  string   `toml:"version"`
	Revision string   `toml:"revision"`
	Manifest Manifest `toml:"manifest"`
}

type Manifest struct {
	Executable       string       `toml:"executable"`
	Arguments        []string     `toml:"arguments"`
	WorkingDirectory string       `toml:"working_directory"`
	InputContract    string       `toml:"input_contract"`
	InputMode        string       `toml:"input_mode"`
	SecretNames      []string     `toml:"secret_names"`
	Files            []FileDigest `toml:"files"`
}

type FileDigest struct {
	Path   string `toml:"path"`
	SHA256 string `toml:"sha256"`
}
