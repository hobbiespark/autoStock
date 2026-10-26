-- A reservation is not a successful Telegram stop; never reuse or reconstruct it.
create table telegram_operator_handover_dispatch (
 request_id uuid primary key,
 attempt_id uuid not null,
 recorded_at timestamptz not null default clock_timestamp(),
 foreign key(request_id,attempt_id) references telegram_operator_handover_attempt(request_id,attempt_id)
);
create trigger telegram_operator_handover_dispatch_immutable before update or delete on telegram_operator_handover_dispatch for each row execute function telegram_operator_handover_request_immutable();
