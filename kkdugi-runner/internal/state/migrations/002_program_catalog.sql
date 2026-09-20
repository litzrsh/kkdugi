CREATE TABLE kkdugi_runner_program_revision (
 code TEXT NOT NULL,
 revision TEXT NOT NULL,
 definition BLOB NOT NULL,
 definition_hash TEXT NOT NULL,
 PRIMARY KEY(code,revision)
) STRICT;
CREATE TABLE kkdugi_runner_program (
 code TEXT PRIMARY KEY,
 revision TEXT NOT NULL,
 report BLOB NOT NULL,
 report_hash TEXT NOT NULL,
 request_id TEXT REFERENCES kkdugi_runner_request(id),
 approval BLOB,
 approval_session TEXT,
 approval_boot TEXT,
 FOREIGN KEY(code,revision) REFERENCES kkdugi_runner_program_revision(code,revision)
) STRICT;
