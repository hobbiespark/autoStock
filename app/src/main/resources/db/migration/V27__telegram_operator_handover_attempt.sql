create table telegram_operator_handover_attempt (
 request_id uuid primary key references telegram_operator_handover_request(request_id),
 attempt_id uuid not null unique,
 token_sha256 varchar(64) not null check(token_sha256 ~ '^[a-f0-9]{64}$'),
 execute_before timestamptz not null,
 claimed_at timestamptz not null default clock_timestamp(),
 unique(request_id,attempt_id)
);
create function telegram_operator_handover_attempt_guard() returns trigger language plpgsql as $$
declare expires timestamptz;
begin
 select expires_at_text::timestamptz into strict expires from telegram_operator_handover_request where request_id=new.request_id;
 if new.execute_before > expires then raise exception 'operator attempt deadline extended' using errcode='23514'; end if;
 return new;
end $$;
create trigger telegram_operator_handover_attempt_guard before insert on telegram_operator_handover_attempt for each row execute function telegram_operator_handover_attempt_guard();
create trigger telegram_operator_handover_attempt_immutable before update or delete on telegram_operator_handover_attempt for each row execute function telegram_operator_handover_request_immutable();
create table telegram_operator_handover_unknown (
 request_id uuid primary key,
 attempt_id uuid not null,
 recorded_at timestamptz not null default clock_timestamp(),
 foreign key(request_id,attempt_id) references telegram_operator_handover_attempt(request_id,attempt_id)
);
create trigger telegram_operator_handover_unknown_immutable before update or delete on telegram_operator_handover_unknown for each row execute function telegram_operator_handover_request_immutable();
