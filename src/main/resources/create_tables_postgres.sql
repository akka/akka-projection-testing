CREATE TABLE IF NOT EXISTS akka_projection_offset_store (
    projection_name VARCHAR(255) NOT NULL,
    projection_key VARCHAR(255) NOT NULL,
    current_offset VARCHAR(255) NOT NULL,
    manifest VARCHAR(255) NOT NULL,
    mergeable BOOLEAN NOT NULL,
    last_updated BIGINT NOT NULL,
    PRIMARY KEY(projection_name, projection_key)
    );

CREATE INDEX IF NOT EXISTS projection_name_index ON akka_projection_offset_store (projection_name);

CREATE TABLE IF NOT EXISTS akka_projection_management (
  projection_name VARCHAR(255) NOT NULL,
  projection_key VARCHAR(255) NOT NULL,
  paused BOOLEAN NOT NULL,
  last_updated BIGINT NOT NULL,
  PRIMARY KEY(projection_name, projection_key)
);

create table if not exists events (
    name varchar(256),
    projection_id BIGINT,
    event varchar(256),
    constraint pkey primary key (name, projection_id, event)
);

create table if not exists results (
    name varchar(256) primary key,
    result varchar(256)
);



CREATE TABLE IF NOT EXISTS event_journal2(
  slice INT NOT NULL,
  entity_type_hint VARCHAR(255) NOT NULL,
  persistence_id VARCHAR(255) NOT NULL,
  sequence_number BIGINT NOT NULL,
  db_timestamp timestamp with time zone NOT NULL,
  deleted BOOLEAN DEFAULT FALSE NOT NULL,

  writer VARCHAR(255) NOT NULL,
  write_timestamp BIGINT,
  adapter_manifest VARCHAR(255),

  event_ser_id INTEGER NOT NULL,
  event_ser_manifest VARCHAR(255) NOT NULL,
  event_payload BYTEA NOT NULL,

  meta_ser_id INTEGER,
  meta_ser_manifest VARCHAR(255),
  meta_payload BYTEA,

  PRIMARY KEY(slice, entity_type_hint, persistence_id, sequence_number)
);

CREATE INDEX IF NOT EXISTS event_journal_slice_idx ON event_journal2(slice, entity_type_hint, db_timestamp);
