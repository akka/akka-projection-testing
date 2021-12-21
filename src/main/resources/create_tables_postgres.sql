
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
