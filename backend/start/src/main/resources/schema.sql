CREATE TABLE IF NOT EXISTS sys_user (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 username TEXT NOT NULL UNIQUE COLLATE NOCASE,
 password_hash TEXT NOT NULL,
 role TEXT NOT NULL CHECK(role IN ('OPERATOR','MONITOR','MAINTAINER','VIEWER')),
 created_at INTEGER NOT NULL
);
-- Additive access table: existing sys_user rows and all foreign-key audit history remain intact.
CREATE TABLE IF NOT EXISTS sys_user_access (
 user_id INTEGER PRIMARY KEY REFERENCES sys_user(id) ON DELETE RESTRICT,
 permissions TEXT NOT NULL,
 is_admin INTEGER NOT NULL DEFAULT 0 CHECK(is_admin IN (0,1)),
 disabled INTEGER NOT NULL DEFAULT 0 CHECK(disabled IN (0,1))
);
CREATE TABLE IF NOT EXISTS patrol_route (
 id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL CHECK(length(trim(name)) BETWEEN 1 AND 100),
 description TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS route_point (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 route_id INTEGER NOT NULL REFERENCES patrol_route(id) ON DELETE CASCADE,
 sequence INTEGER NOT NULL CHECK(sequence>=0), x REAL NOT NULL, y REAL NOT NULL, yaw REAL,
 UNIQUE(route_id,sequence)
);
CREATE TABLE IF NOT EXISTS patrol_task (
 id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
 route_id INTEGER NOT NULL REFERENCES patrol_route(id) ON DELETE RESTRICT,
 creator_id INTEGER NOT NULL REFERENCES sys_user(id) ON DELETE RESTRICT,
 planned_at INTEGER, status TEXT NOT NULL CHECK(status IN ('DRAFT','PLANNED','CANCELLED')),
 created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
 CHECK(status != 'PLANNED' OR planned_at IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS idx_task_route ON patrol_task(route_id);
CREATE INDEX IF NOT EXISTS idx_task_status ON patrol_task(status,id);
CREATE TABLE IF NOT EXISTS alarm_record (
 id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, description TEXT,
 level TEXT NOT NULL CHECK(level IN ('INFO','WARNING','CRITICAL')),
 status TEXT NOT NULL CHECK(status IN ('UNACKNOWLEDGED','ACKNOWLEDGED')),
 source TEXT NOT NULL, seed_key TEXT UNIQUE, occurred_at INTEGER NOT NULL,
 acknowledged_by INTEGER REFERENCES sys_user(id), acknowledged_at INTEGER,
 CHECK((status='UNACKNOWLEDGED' AND acknowledged_by IS NULL AND acknowledged_at IS NULL)
 OR (status='ACKNOWLEDGED' AND acknowledged_by IS NOT NULL AND acknowledged_at IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS idx_alarm_filters ON alarm_record(level,status,occurred_at);
CREATE TABLE IF NOT EXISTS control_log (
 id INTEGER PRIMARY KEY AUTOINCREMENT, actor_id INTEGER REFERENCES sys_user(id), actor_name TEXT NOT NULL,
 operation TEXT NOT NULL, target TEXT NOT NULL, result TEXT NOT NULL CHECK(result IN ('SUCCESS','FAILURE')),
 occurred_at INTEGER NOT NULL, error TEXT
);
CREATE INDEX IF NOT EXISTS idx_log_filter ON control_log(operation,occurred_at);

CREATE INDEX IF NOT EXISTS idx_log_actor_time ON control_log(actor_id,occurred_at);
