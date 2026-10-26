alter table telegram_operator_handover_dispatch add constraint telegram_operator_dispatch_pair unique(request_id,attempt_id);
create table telegram_operator_handover_stop (
 request_id uuid primary key,
 attempt_id uuid not null,
 stop_id uuid not null unique,
 stop_kind text not null check(stop_kind='stopped'),
 foreign key(request_id,attempt_id) references telegram_operator_handover_dispatch(request_id,attempt_id),
 foreign key(stop_id,stop_kind) references telegram_receiver_stop(stop_id,kind)
);
create function telegram_operator_handover_stop_guard() returns trigger language plpgsql as $$
begin
 if not exists(select 1 from telegram_operator_handover_request r join telegram_receiver_stop s on s.stop_id=new.stop_id and s.kind=new.stop_kind where r.request_id=new.request_id and r.instance_id=s.instance_id) then
  raise exception 'operator stop instance mismatch' using errcode='23514';
 end if;
 return new;
end $$;
create trigger telegram_operator_handover_stop_guard before insert on telegram_operator_handover_stop for each row execute function telegram_operator_handover_stop_guard();
create trigger telegram_operator_handover_stop_immutable before update or delete on telegram_operator_handover_stop for each row execute function telegram_operator_handover_request_immutable();
