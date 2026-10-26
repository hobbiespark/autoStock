create table platform_stock_control_attempt (
 bot_ref varchar(64) not null,source_update_id bigint not null,
 attempt_id uuid not null unique,receiver_fence bigint not null check(receiver_fence>0),
 token_sha256 varchar(64) not null check(token_sha256 ~ '^[a-f0-9]{64}$'),
 request_sha256 varchar(64) not null check(request_sha256 ~ '^[a-f0-9]{64}$'),
 state text not null check(state in ('running','unknown')),
 execute_before timestamptz not null,
 primary key(bot_ref,source_update_id),
 foreign key(bot_ref,source_update_id) references platform_stock_control_receipt(bot_ref,source_update_id)
);
create function platform_stock_control_attempt_guard() returns trigger language plpgsql as $$
begin
 if TG_OP='DELETE' then raise exception 'control attempt immutable' using errcode='42501'; end if;
 if row(new.bot_ref,new.source_update_id,new.attempt_id,new.receiver_fence,new.token_sha256,new.request_sha256,new.execute_before)
   is distinct from row(old.bot_ref,old.source_update_id,old.attempt_id,old.receiver_fence,old.token_sha256,old.request_sha256,old.execute_before)
   or old.state<>'running' or new.state<>'unknown' then raise exception 'control attempt transition denied' using errcode='42501'; end if;
 return new;
end $$;
create trigger platform_stock_control_attempt_guard before update or delete on platform_stock_control_attempt for each row execute function platform_stock_control_attempt_guard();
