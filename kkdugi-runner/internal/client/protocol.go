package client

type RegistrationRequest struct {
	RunnerCode   string `json:"runnerCode"`
	AgentVersion string `json:"agentVersion"`
	Hostname     string `json:"hostname"`
	OS           string `json:"os"`
	Architecture string `json:"architecture"`
}

type RegistrationResponse struct {
	RunnerID       string    `json:"runnerId"`
	CredentialID   string    `json:"credentialId"`
	AccessToken    Secret    `json:"accessToken"`
	TokenExpiresAt Timestamp `json:"tokenExpiresAt"`
	Session        Decimal   `json:"session"`
}

type SessionRequest struct {
	BootID          string  `json:"bootId"`
	ExpectedSession Decimal `json:"expectedSession"`
	AgentVersion    string  `json:"agentVersion"`
}

type SessionResponse struct {
	RunnerID         string    `json:"runnerId"`
	Session          Decimal   `json:"session"`
	ServerTime       Timestamp `json:"serverTime"`
	HeartbeatSeconds int       `json:"heartbeatSeconds"`
	PollSeconds      int       `json:"pollSeconds"`
	LeaseSeconds     int       `json:"leaseSeconds"`
	Capacity         int       `json:"capacity"`
	Limits           Limits    `json:"limits"`
}

type Limits struct {
	JSONBytes     int `json:"jsonBytes"`
	InputBytes    int `json:"inputBytes"`
	ResultBytes   int `json:"resultBytes"`
	LogChunkBytes int `json:"logChunkBytes"`
}
