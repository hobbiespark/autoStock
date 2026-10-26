alter table platform_stock_control_attempt drop constraint platform_stock_control_attempt_state_check;
alter table platform_stock_control_attempt add constraint platform_stock_control_attempt_state_check check(state in ('running','unknown','succeeded','failed'));
alter table platform_stock_control_attempt add constraint stock_control_attempt_identity unique(bot_ref,source_update_id,attempt_id);
create table platform_stock_control_result (
 bot_ref varchar(64) not null,source_update_id bigint not null,attempt_id uuid not null,
 request_sha256 varchar(64) not null check(request_sha256 ~ '^[a-f0-9]{64}$'),
 outcome text not null check(outcome in ('succeeded','failed')),
 result_sha256 varchar(64) not null check(result_sha256 ~ '^[a-f0-9]{64}$'),
 key_id varchar(80) not null check(key_id ~ '^[A-Za-z0-9_-]{1,80}$'),
 result_enc text not null check(octet_length(result_enc)<=20000),
 recorded_at timestamptz not null default clock_timestamp(),
 primary key(bot_ref,source_update_id),
 foreign key(bot_ref,source_update_id,attempt_id) references platform_stock_control_attempt(bot_ref,source_update_id,attempt_id)
);
create trigger stock_control_result_immutable before update or delete on platform_stock_control_result for each row execute function platform_stock_control_receipt_immutable();
create function stock_control_result_insert_guard() returns trigger language plpgsql as $$
begin
 if not exists(select 1 from platform_stock_control_attempt a where a.bot_ref=new.bot_ref and a.source_update_id=new.source_update_id and a.attempt_id=new.attempt_id and a.request_sha256=new.request_sha256 and a.state='running') then raise exception 'control result attempt mismatch' using errcode='42501'; end if;
 return new;
end $$;
create trigger stock_control_result_insert_guard before insert on platform_stock_control_result for each row execute function stock_control_result_insert_guard();
create or replace function platform_stock_control_attempt_guard() returns trigger language plpgsql as $$
begin
 if TG_OP='DELETE' then raise exception 'control attempt immutable' using errcode='42501'; end if;
 if row(new.bot_ref,new.source_update_id,new.attempt_id,new.receiver_fence,new.token_sha256,new.request_sha256,new.execute_before) is distinct from row(old.bot_ref,old.source_update_id,old.attempt_id,old.receiver_fence,old.token_sha256,old.request_sha256,old.execute_before) or old.state<>'running' or new.state not in ('unknown','succeeded','failed') then raise exception 'control attempt transition denied' using errcode='42501'; end if;
 if new.state in ('succeeded','failed') and not exists(select 1 from platform_stock_control_result r where r.bot_ref=new.bot_ref and r.source_update_id=new.source_update_id and r.attempt_id=new.attempt_id and r.request_sha256=new.request_sha256 and r.outcome=new.state) then raise exception 'control result required' using errcode='42501'; end if;
 return new;
end $$;
create function stock_control_result_terminal_guard() returns trigger language plpgsql as $$
begin
 if not exists(select 1 from platform_stock_control_attempt a where a.bot_ref=new.bot_ref and a.source_update_id=new.source_update_id and a.attempt_id=new.attempt_id and a.state=new.outcome) then raise exception 'control result terminal required' using errcode='42501'; end if;
 return new;
end $$;
create constraint trigger stock_control_result_terminal_guard after insert on platform_stock_control_result deferrable initially deferred for each row execute function stock_control_result_terminal_guard();
