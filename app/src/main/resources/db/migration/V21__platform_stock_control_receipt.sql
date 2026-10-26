create table platform_stock_control_receiver (
 bot_ref varchar(64) primary key check(bot_ref ~ '^[a-f0-9]{64}$'),
 highest_fence bigint not null check(highest_fence>0)
);
create table platform_stock_control_receipt (
 bot_ref varchar(64) not null references platform_stock_control_receiver(bot_ref),
 source_update_id bigint not null check(source_update_id>0 and source_update_id<9223372036854775807),
 receiver_fence bigint not null check(receiver_fence>0),
 intent_sha256 varchar(64) not null check(intent_sha256 ~ '^[a-f0-9]{64}$'),
 request_sha256 varchar(64) not null check(request_sha256 ~ '^[a-f0-9]{64}$'),
 key_id varchar(80) not null check(key_id ~ '^[A-Za-z0-9_-]{1,80}$'),
 proof_enc text not null check(octet_length(proof_enc)<=12000),
 state text not null default 'received' check(state='received'),
 received_at timestamptz not null default clock_timestamp(),
 primary key(bot_ref,source_update_id)
);
create function platform_stock_control_receipt_immutable() returns trigger language plpgsql as $$
begin raise exception 'stock control receipt immutable' using errcode='42501'; end $$;
create trigger platform_stock_control_receipt_immutable before update or delete on platform_stock_control_receipt for each row execute function platform_stock_control_receipt_immutable();
