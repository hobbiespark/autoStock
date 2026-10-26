create table telegram_operator_handover_request (
 request_id uuid primary key,
 operator_key_id varchar(80) not null check(operator_key_id ~ '^[A-Za-z0-9_-]{1,80}$'),
 instance_id varchar(80) not null check(instance_id ~ '^[A-Za-z0-9_-]{1,80}$'),
 request_sha256 varchar(64) not null check(request_sha256 ~ '^[a-f0-9]{64}$'),
 action text not null check(action='stop'),state text not null default 'received' check(state='received'),
 issued_at_text text not null check(length(issued_at_text) between 20 and 64),
 expires_at_text text not null check(length(expires_at_text) between 20 and 64),
 received_at timestamptz not null default clock_timestamp()
);
create function telegram_operator_handover_request_immutable() returns trigger language plpgsql as $$
begin raise exception 'operator handover receipt immutable' using errcode='42501'; end $$;
create trigger telegram_operator_handover_request_immutable before update or delete on telegram_operator_handover_request for each row execute function telegram_operator_handover_request_immutable();
