CREATE TABLE kkdugi_runner_log_stream (
 assignment_id TEXT NOT NULL REFERENCES kkdugi_runner_assignment(id),
 stream TEXT NOT NULL CHECK(stream IN ('STDOUT','STDERR')),
 next_sequence INTEGER NOT NULL DEFAULT 0,
 acked_through INTEGER NOT NULL DEFAULT -1,
 total_bytes INTEGER NOT NULL DEFAULT 0,
 status TEXT NOT NULL DEFAULT 'COMPLETE' CHECK(status IN ('COMPLETE','TRUNCATED','LOST')),
 closed INTEGER NOT NULL DEFAULT 0,
 PRIMARY KEY(assignment_id,stream)
) STRICT;
CREATE TABLE kkdugi_runner_log_chunk (
 assignment_id TEXT NOT NULL,
 stream TEXT NOT NULL,
 sequence INTEGER NOT NULL,
 hash TEXT NOT NULL,
 file_bytes INTEGER NOT NULL,
 PRIMARY KEY(assignment_id,stream,sequence),
 FOREIGN KEY(assignment_id,stream) REFERENCES kkdugi_runner_log_stream(assignment_id,stream)
) STRICT;
CREATE TABLE kkdugi_runner_reconcile (
 id TEXT PRIMARY KEY,
 assignment_id TEXT NOT NULL REFERENCES kkdugi_runner_assignment(id),
 session TEXT NOT NULL,
 request_key TEXT NOT NULL,
 created_at TEXT NOT NULL,
 body BLOB NOT NULL,
 hash TEXT NOT NULL,
 response BLOB,
 applied INTEGER NOT NULL DEFAULT 0
) STRICT;
CREATE UNIQUE INDEX kkdugi_runner_reconcile_pending ON kkdugi_runner_reconcile(assignment_id,session) WHERE applied=0;
CREATE TABLE kkdugi_runner_superseded (
 request_id TEXT PRIMARY KEY REFERENCES kkdugi_runner_request(id),
 evidence TEXT NOT NULL
) STRICT;
ALTER TABLE kkdugi_runner_request ADD COLUMN superseded INTEGER NOT NULL DEFAULT 0;
DROP INDEX kkdugi_runner_single_claim;
CREATE UNIQUE INDEX kkdugi_runner_single_claim ON kkdugi_runner_request(session)
 WHERE path='/assignments/claim' AND status!='ACKED' AND superseded=0;
