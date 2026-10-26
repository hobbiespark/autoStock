create table platform_stock_control_result_ack (
 bot_ref varchar(64) not null,source_update_id bigint not null,attempt_id uuid not null,
 request_sha256 varchar(64) not null check(request_sha256 ~ '^[a-f0-9]{64}$'),
 outcome text not null check(outcome in ('succeeded','failed')),
 result_sha256 varchar(64) not null check(result_sha256 ~ '^[a-f0-9]{64}$'),
 result_proof_sha256 varchar(64) not null check(result_proof_sha256 ~ '^[a-f0-9]{64}$'),
 platform_key_id varchar(80) not null check(platform_key_id ~ '^[A-Za-z0-9_-]{1,80}$'),
 platform_recorded_at timestamptz not null,
 ack_sha256 varchar(64) not null check(ack_sha256 ~ '^[a-f0-9]{64}$'),
 ack_envelope text not null check(octet_length(ack_envelope)<=8192),
 acknowledged_at timestamptz not null default clock_timestamp(),
 primary key(bot_ref,source_update_id),
 foreign key(bot_ref,source_update_id) references platform_stock_control_result(bot_ref,source_update_id)
);
create trigger stock_control_result_ack_immutable before update or delete on platform_stock_control_result_ack for each row execute function platform_stock_control_receipt_immutable();
create function stock_control_result_ack_insert_guard() returns trigger language plpgsql as $$
begin
 if not exists(select 1 from platform_stock_control_result r join platform_stock_control_attempt a on a.attempt_id=r.attempt_id
   where r.bot_ref=new.bot_ref and r.source_update_id=new.source_update_id and r.attempt_id=new.attempt_id and r.request_sha256=new.request_sha256
     and r.outcome=new.outcome and r.result_sha256=new.result_sha256 and a.state=r.outcome) then raise exception 'control result ack mismatch' using errcode='42501'; end if;
 return new;
end $$;
create trigger stock_control_result_ack_insert_guard before insert on platform_stock_control_result_ack for each row execute function stock_control_result_ack_insert_guard();
