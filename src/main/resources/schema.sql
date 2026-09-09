create table if not exists approval_record (
    instance_id varchar(128) primary key,
    originator_user_id varchar(128) not null,
    dept_id bigint,
    business_id varchar(128),
    title varchar(500),
    status varchar(32) not null,
    result varchar(32),
    created_at timestamp with time zone not null,
    finished_at timestamp with time zone,
    synced_at timestamp with time zone not null
);

create index if not exists idx_approval_user on approval_record(originator_user_id);

create table if not exists dingtalk_event_receipt (
    event_id varchar(128) primary key,
    event_type varchar(128) not null,
    instance_id varchar(128),
    received_at timestamp with time zone not null
);
