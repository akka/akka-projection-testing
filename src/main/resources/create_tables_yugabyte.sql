create table if not exists events (
    name varchar(256),
    projection_id BIGINT,
    event varchar(256),
    primary key ((name, projection_id) HASH, event)
);

create table if not exists results (
    name varchar(256) primary key,
    result varchar(256)
);
