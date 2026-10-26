-- Local operator legacy polling resume (P2-2d, user decision 2026-10-27 alternative A to VALUNIVO 161 §5-1).
-- One row per authenticated request. Evidence columns are what the operator presented from the platform release; a
-- rejection keeps whatever was presented plus a reason. Only received -> resumed|failed may change, once; no delete.
create table telegram_legacy_resume_request (
 request_id uuid primary key,
 operator_key_id text not null check(operator_key_id ~ '^[A-Za-z0-9_-]{1,80}$'),
 instance_id text not null check(instance_id ~ '^[A-Za-z0-9_-]{1,80}$'),
 request_sha256 text not null check(request_sha256 ~ '^[a-f0-9]{64}$'),
 stop_id uuid,
 platform_run_id uuid,
 platform_stop_request_id uuid,
 platform_run_state text check(platform_run_state ~ '^[a-z_]{1,40}$'),
 platform_lease_returned boolean,
 platform_next_offset bigint check(platform_next_offset>=0 and platform_next_offset<9223372036854775807),
 platform_fence bigint check(platform_fence>=0 and platform_fence<9223372036854775807),
 state text not null check(state in ('received','resumed','failed','rejected')),
 reason text check(reason ~ '^[a-z0-9-]{1,80}$'),
 legacy_next_offset bigint check(legacy_next_offset>=0),
 received_at timestamptz not null default clock_timestamp(),
 decided_at timestamptz,
 check(state<>'received' or (reason is null and legacy_next_offset is null and decided_at is null)),
 check(state<>'resumed' or (reason is null and legacy_next_offset is not null and decided_at is not null and stop_id is not null
       and platform_lease_returned and platform_run_state='released' and platform_next_offset is not null and platform_fence is not null)),
 check(state not in ('failed','rejected') or (reason is not null and legacy_next_offset is null and decided_at is not null))
);
create function telegram_legacy_resume_request_guard() returns trigger language plpgsql as $$
begin
 if tg_op='DELETE' then raise exception 'legacy resume request is append-only' using errcode='42501'; end if;
 if tg_op='INSERT' then
  if new.state not in ('received','rejected') then raise exception 'legacy resume must start received or rejected' using errcode='42501'; end if;
  return new;
 end if;
 if old.state<>'received' or new.state not in ('resumed','failed')
    or (new.request_id,new.operator_key_id,new.instance_id,new.request_sha256,new.stop_id,new.platform_run_id,new.platform_stop_request_id,
        new.platform_run_state,new.platform_lease_returned,new.platform_next_offset,new.platform_fence,new.received_at)
       is distinct from
       (old.request_id,old.operator_key_id,old.instance_id,old.request_sha256,old.stop_id,old.platform_run_id,old.platform_stop_request_id,
        old.platform_run_state,old.platform_lease_returned,old.platform_next_offset,old.platform_fence,old.received_at) then
  raise exception 'legacy resume decision is final' using errcode='42501';
 end if;
 return new;
end $$;
create trigger telegram_legacy_resume_request_guard before insert or update or delete on telegram_legacy_resume_request
 for each row execute function telegram_legacy_resume_request_guard();
