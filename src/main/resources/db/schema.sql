-- Apply through the enterprise database release process for MySQL 8; automatic init is local/test only.
CREATE TABLE IF NOT EXISTS skill_assets (
 workspace VARCHAR(100) NOT NULL, id VARCHAR(100) NOT NULL, owner_id VARCHAR(100) NOT NULL,
 name VARCHAR(100) NOT NULL, status VARCHAR(30) NOT NULL, revision BIGINT NOT NULL,
 payload LONGTEXT NOT NULL, created_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL,
 PRIMARY KEY(workspace,id), UNIQUE(workspace,name), INDEX assets_owner(workspace,owner_id,updated_at)
);
CREATE TABLE IF NOT EXISTS skill_versions (
 workspace VARCHAR(100) NOT NULL, id VARCHAR(140) NOT NULL, owner_id VARCHAR(100) NOT NULL,
 name VARCHAR(100) NOT NULL, status VARCHAR(30) NOT NULL, revision BIGINT NOT NULL,
 payload LONGTEXT NOT NULL, created_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL,
 PRIMARY KEY(workspace,id)
);
CREATE TABLE IF NOT EXISTS workflow_drafts (
 workspace VARCHAR(100) NOT NULL, id VARCHAR(100) NOT NULL, owner_id VARCHAR(100) NOT NULL,
 name VARCHAR(100) NOT NULL, status VARCHAR(30) NOT NULL, revision BIGINT NOT NULL,
 payload LONGTEXT NOT NULL, created_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL,
 PRIMARY KEY(workspace,id)
);
CREATE TABLE IF NOT EXISTS schedules (
 workspace VARCHAR(100) NOT NULL, id VARCHAR(100) NOT NULL, owner_id VARCHAR(100) NOT NULL,
 name VARCHAR(100) NOT NULL, status VARCHAR(30) NOT NULL, revision BIGINT NOT NULL,
 payload LONGTEXT NOT NULL, created_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL,
 next_at BIGINT NOT NULL DEFAULT 0, PRIMARY KEY(workspace,id), INDEX schedules_due(next_at), INDEX schedules_owner(workspace,owner_id,updated_at)
);
CREATE TABLE IF NOT EXISTS credential_bindings (
 workspace VARCHAR(100) NOT NULL, id VARCHAR(100) NOT NULL, owner_id VARCHAR(100) NOT NULL,
 name VARCHAR(100) NOT NULL, status VARCHAR(30) NOT NULL, revision BIGINT NOT NULL,
 payload LONGTEXT NOT NULL, created_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL,
 PRIMARY KEY(workspace,id)
);
CREATE TABLE IF NOT EXISTS runs (
 id VARCHAR(100) PRIMARY KEY, workspace VARCHAR(100) NOT NULL, owner_id VARCHAR(100) NOT NULL,
 status VARCHAR(30) NOT NULL, skill_id VARCHAR(100), workflow_id VARCHAR(100), schedule_id VARCHAR(100),
 payload LONGTEXT NOT NULL, snapshot LONGTEXT NOT NULL, input_json LONGTEXT NOT NULL,
 created_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL,
 lease_owner VARCHAR(100), lease_until BIGINT NOT NULL DEFAULT 0,
 cancel_requested BOOLEAN NOT NULL DEFAULT FALSE, timeout_seconds INT NOT NULL,
 deadline BIGINT NOT NULL DEFAULT 0, INDEX runs_scope(workspace,owner_id,created_at), INDEX runs_schedule(schedule_id,status), INDEX runs_state(status,lease_until)
);
CREATE TABLE IF NOT EXISTS execution_outbox (
 run_id VARCHAR(100) PRIMARY KEY, status VARCHAR(30) NOT NULL, attempts INT NOT NULL DEFAULT 0,
 available_at BIGINT NOT NULL, lease_until BIGINT NOT NULL DEFAULT 0, lease_owner VARCHAR(100), last_error VARCHAR(500), INDEX outbox_pending(status,available_at), INDEX outbox_leases(status,lease_until)
);
CREATE TABLE IF NOT EXISTS run_events (
 seq BIGINT AUTO_INCREMENT PRIMARY KEY, run_id VARCHAR(100) NOT NULL, workspace VARCHAR(100) NOT NULL,
 kind VARCHAR(40) NOT NULL, payload LONGTEXT NOT NULL, created_at VARCHAR(40) NOT NULL,
 INDEX events_run(run_id,seq)
);
CREATE TABLE IF NOT EXISTS audit_events (
 seq BIGINT AUTO_INCREMENT PRIMARY KEY, workspace VARCHAR(100) NOT NULL, actor VARCHAR(100) NOT NULL,
 action VARCHAR(80) NOT NULL, resource_id VARCHAR(160) NOT NULL, trace_id VARCHAR(100), created_at VARCHAR(40) NOT NULL,
 INDEX audit_scope(workspace,seq)
);
CREATE TABLE IF NOT EXISTS idempotency_keys (
 workspace VARCHAR(100) NOT NULL, actor VARCHAR(100) NOT NULL, operation VARCHAR(60) NOT NULL,
 key_hash VARCHAR(64) NOT NULL, request_hash VARCHAR(64) NOT NULL, response_json LONGTEXT NOT NULL,
 PRIMARY KEY(workspace,actor,operation,key_hash)
);
CREATE TABLE IF NOT EXISTS skill_packages (
 workspace VARCHAR(100) NOT NULL, skill_id VARCHAR(100) NOT NULL, version INT NOT NULL,
 owner_id VARCHAR(100) NOT NULL, content LONGBLOB NOT NULL, created_at VARCHAR(40) NOT NULL,
 PRIMARY KEY(workspace,skill_id,version)
);
