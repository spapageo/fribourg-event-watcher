CREATE TABLE event (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    dedup_key        VARCHAR NOT NULL UNIQUE,
    title            VARCHAR NOT NULL,
    normalized_title VARCHAR NOT NULL,
    description      VARCHAR,
    venue_name       VARCHAR,
    address_line     VARCHAR,
    city             VARCHAR,
    canton           VARCHAR,
    country          VARCHAR NOT NULL DEFAULT 'CH',
    lat              DOUBLE,
    lon              DOUBLE,
    url              VARCHAR,
    categories       VARCHAR,
    price_info       VARCHAR,
    status           VARCHAR NOT NULL,
    created_at       VARCHAR NOT NULL,
    updated_at       VARCHAR NOT NULL
);

CREATE INDEX idx_event_city ON event (city);
CREATE INDEX idx_event_normalized_title ON event (normalized_title);

CREATE TABLE event_source_ref (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id        INTEGER NOT NULL,
    source          VARCHAR NOT NULL,
    source_event_id VARCHAR NOT NULL,
    source_url      VARCHAR,
    payload_hash    VARCHAR NOT NULL,
    first_seen_at   VARCHAR NOT NULL,
    last_seen_at    VARCHAR NOT NULL,
    CONSTRAINT uq_event_source_ref_source_event UNIQUE (source, source_event_id),
    CONSTRAINT fk_event_source_ref_event
        FOREIGN KEY (event_id) REFERENCES event (id) ON DELETE CASCADE
);

CREATE INDEX idx_event_source_ref_event ON event_source_ref (event_id);

CREATE TABLE event_occurrence (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id   INTEGER NOT NULL,
    start_utc  VARCHAR NOT NULL,
    end_utc    VARCHAR,
    all_day    BOOLEAN NOT NULL DEFAULT 0,
    local_date VARCHAR NOT NULL,
    CONSTRAINT uq_event_occurrence_event_start UNIQUE (event_id, start_utc),
    CONSTRAINT fk_event_occurrence_event
        FOREIGN KEY (event_id) REFERENCES event (id) ON DELETE CASCADE
);

CREATE INDEX idx_event_occurrence_start ON event_occurrence (start_utc);
CREATE INDEX idx_event_occurrence_local_date ON event_occurrence (local_date);
CREATE INDEX idx_event_occurrence_event ON event_occurrence (event_id);
