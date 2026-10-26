package com.autostock.monitor;

import com.autostock.monitor.control.HandoverOperatorGate;
import com.autostock.monitor.control.HandoverOperatorRequestStore;
import com.autostock.monitor.control.HandoverOperatorAttemptStore;
import java.util.Objects;
import java.util.Optional;

/** Explicit source composition only. No bean, listener, key loader, retry or resume endpoint. */
public final class OperatorHandoverCoordinator {
 public record Observation(String state,HandoverOperatorRequestStore.Receipt receipt,
        Optional<HandoverOperatorAttemptStore.Observation> attempt,
        Optional<TelegramReceiverStopStore.OperatorStop> stop) {
  @Override public String toString(){return "OperatorHandoverObservation[state="+state+"]";}
 }
 private final HandoverOperatorGate gate;
 private final HandoverOperatorRequestStore requests;
 private final HandoverOperatorAttemptStore attempts;
 private final TelegramReceiverStopStore stops;
 private final TelegramCommandPoller poller;
 public OperatorHandoverCoordinator(HandoverOperatorGate gate,HandoverOperatorRequestStore requests,
         HandoverOperatorAttemptStore attempts,TelegramReceiverStopStore stops,TelegramCommandPoller poller){
  this.gate=Objects.requireNonNull(gate);this.requests=Objects.requireNonNull(requests);this.attempts=Objects.requireNonNull(attempts);this.stops=Objects.requireNonNull(stops);this.poller=Objects.requireNonNull(poller);
 }
 public TelegramReceiverStopStore.Stop stop(HandoverOperatorGate.Verified value){
  gate.requireVerified(value);var receipt=requests.receive(value);
  if(receipt.duplicate())throw new IllegalStateException("operator-request-already-received-use-observe");
  var claim=attempts.begin(value);
  try {
   var dispatch=attempts.dispatch(claim,value);
   return poller.stopForHandover(value.request().instanceId(),()->attempts.enter(dispatch,value),
           (offset,dropped)->attempts.recordStopped(dispatch,stops,offset,dropped));
  }catch(RuntimeException failed){
   try{attempts.markUnknown(claim);}catch(RuntimeException unavailable){/* Durable reservation still prevents replay. */}
   throw new IllegalStateException("operator-stop-outcome-unconfirmed-use-observe");
  }
 }
 /** Metadata recovery does not renew stop time, confirm an offset or resume a receiver. */
 public Optional<Observation> observe(HandoverOperatorGate.Verified value){
  gate.requireVerified(value);var receipt=requests.observe(value);if(receipt.isEmpty())return Optional.empty();
  var attempt=attempts.observe(value);var stop=stops.operatorStop(value.request().targetRequestId());
  gate.requireVerified(value);
  String state=stop.map(s->s.active()?"stopped":"withdrawn").orElseGet(()->attempt.map(HandoverOperatorAttemptStore.Observation::state).orElse("received"));
  return Optional.of(new Observation(state,receipt.orElseThrow(),attempt,stop));
 }
}
