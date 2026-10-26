-- Handover stop signal for the single Telegram receiver. Append-only: a withdrawal is a new row.
create table telegram_receiver_stop (
 seq bigserial primary key,
 stop_id uuid not null,
 kind text not null check(kind in ('stopped','withdrawn')),
 confirmed_next_offset bigint check(confirmed_next_offset>=0 and confirmed_next_offset<9223372036854775807),
 pending_resume_dropped boolean,
 instance_id text not null check(instance_id ~ '^[A-Za-z0-9_.:-]{1,80}$'),
 recorded_at timestamptz not null default clock_timestamp(),
 unique(stop_id,kind),
 check((kind='stopped')=(confirmed_next_offset is not null)),
 check((kind='stopped')=(pending_resume_dropped is not null))
);
create trigger telegram_receiver_stop_immutable before update or delete on telegram_receiver_stop for each row execute function platform_stock_control_receipt_immutable();
create function telegram_receiver_stop_insert_guard() returns trigger language plpgsql as $$
begin
 perform pg_advisory_xact_lock(hashtextextended('autostock-telegram-receiver-stop',0));
 if new.kind='stopped' and exists(select 1 from telegram_receiver_stop s where s.kind='stopped' and not exists(select 1 from telegram_receiver_stop w where w.stop_id=s.stop_id and w.kind='withdrawn')) then
  raise exception 'telegram receiver already stopped' using errcode='42501'; end if;
 if new.kind='withdrawn' and not exists(select 1 from telegram_receiver_stop s where s.stop_id=new.stop_id and s.kind='stopped') then
  raise exception 'telegram receiver stop not found' using errcode='42501'; end if;
 return new;
end $$;
create trigger telegram_receiver_stop_insert_guard before insert on telegram_receiver_stop for each row execute function telegram_receiver_stop_insert_guard();
