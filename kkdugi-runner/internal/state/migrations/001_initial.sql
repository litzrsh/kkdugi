CREATE TABLE kkdugi_runner_meta (
 singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
 base_url TEXT NOT NULL,
 runner_id TEXT NOT NULL CHECK(length(runner_id) BETWEEN 1 AND 20),
 session TEXT NOT NULL,
 boot_id TEXT NOT NULL,
 pending_session BLOB,
 pending_hash TEXT
) STRICT;

CREATE TABLE kkdugi_runner_assignment (
 id TEXT PRIMARY KEY CHECK(length(id) BETWEEN 1 AND 20),
 session TEXT NOT NULL,
 boot_id TEXT NOT NULL,
 phase TEXT NOT NULL CHECK(phase IN ('RECEIVED','PREPARED','START_REQUESTED','PERMITTED','START_INTENT','RUNNING','STOPPING','FINISHED','ACKED','UNKNOWN')),
 version INTEGER NOT NULL CHECK(version > 0),
 snapshot BLOB NOT NULL,
 detail BLOB NOT NULL,
 start_intent INTEGER NOT NULL CHECK(start_intent IN (0,1))
) STRICT;

CREATE TABLE kkdugi_runner_request (
 id TEXT PRIMARY KEY,
 assignment_id TEXT REFERENCES kkdugi_runner_assignment(id),
 method TEXT NOT NULL CHECK(method IN ('POST','PUT')),
 path TEXT NOT NULL,
 request_key TEXT NOT NULL UNIQUE,
 created_at TEXT NOT NULL,
 session TEXT NOT NULL,
 body BLOB NOT NULL,
 body_hash TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('PENDING','IN_FLIGHT','ACKED')),
 next_at TEXT NOT NULL,
 response BLOB
) STRICT;
CREATE INDEX kkdugi_runner_request_pending ON kkdugi_runner_request(status, session, id);
CREATE UNIQUE INDEX kkdugi_runner_single_claim ON kkdugi_runner_request(session)
 WHERE path = '/assignments/claim' AND status != 'ACKED';
CREATE TABLE kkdugi_runner_migration (
 version INTEGER PRIMARY KEY,
 checksum TEXT NOT NULL
) STRICT;
