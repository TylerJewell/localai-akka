package io.akka.localai.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.localai.domain.AdmitDecision;
import io.akka.localai.domain.ModelSlotState;
import io.akka.localai.domain.ModelStatus;
import java.util.List;

/**
 * One model, keyed by model id — SPEC-001 §3 rules 1, 2, 3, 8. The entity id serializes
 * every command against a given model the same way the source's per-modelID
 * {@code modelOperationLocks} semaphore does (rule 8): the Akka runtime never runs two
 * commands for the same entity instance concurrently, so a second {@code admit} for a
 * model already being admitted is simply queued behind the first by the runtime, not by
 * application code.
 */
@Component(id = "model-slot")
public class ModelSlotEntity extends KeyValueEntity<ModelSlotState> {

  public record Admit(List<String> requestedGroups, long nowMillis) {}

  public record AdmitReply(AdmitDecision.Result result, ModelSlotState state, long retryAfterMillis) {}

  public record MarkLoaded(String backend, long nowMillis) {}

  public record MarkFailed(long nowMillis, long baseCooldownMillis, long maxCooldownMillis) {}

  public record Touch(long nowMillis) {}

  public record SetPinned(boolean pinned) {}

  public record ActiveCallDelta(int delta) {}

  public enum EvictResult {
    EVICTED,
    SKIPPED_PINNED,
    SKIPPED_BUSY,
    NOT_LOADED
  }

  public record Evict(boolean force) {}

  public record EvictReply(EvictResult result, ModelSlotState state) {}

  @Override
  public ModelSlotState emptyState() {
    // commandContext().entityId() is unavailable here (the SDK only provides a command
    // context while handling a command, confirmed by actually running the service — an
    // IllegalStateException on the first request otherwise). state() below fills
    // in the real id from the command context on first use instead.
    return ModelSlotState.empty(null);
  }

  /** Every handler starts from this instead of state() directly, so a
   * never-before-seen entity's state carries its real id from the first command it
   * receives, not a placeholder from emptyState(). */
  private ModelSlotState state() {
    var state = currentState();
    return state.modelId() == null ? state.withModelId(commandContext().entityId()) : state;
  }

  /** SPEC-001 §3 rules 1, 2, 3 — decision made by {@link AdmitDecision#decide}, unit
   * tested independently of this entity. */
  public Effect<AdmitReply> admit(Admit command) {
    var decision = AdmitDecision.decide(state(), command.requestedGroups(), command.nowMillis());
    var reply = new AdmitReply(decision.result(), decision.nextState(), decision.retryAfterMillis());
    if (decision.result() == AdmitDecision.Result.START_LOAD) {
      return effects().updateState(decision.nextState()).thenReply(reply);
    }
    return effects().reply(reply);
  }

  public Effect<ModelSlotState> markLoaded(MarkLoaded command) {
    var loaded = state().loaded(command.backend(), command.nowMillis());
    return effects().updateState(loaded).thenReply(loaded);
  }

  /** SPEC-001 §3 rule 3: exponential backoff, capped. */
  public Effect<ModelSlotState> markFailed(MarkFailed command) {
    var failed = state()
        .failed(command.nowMillis(), command.baseCooldownMillis(), command.maxCooldownMillis());
    return effects().updateState(failed).thenReply(failed);
  }

  public Effect<ModelSlotState> touch(Touch command) {
    var touched = state().touched(command.nowMillis());
    return effects().updateState(touched).thenReply(touched);
  }

  public Effect<ModelSlotState> setPinned(SetPinned command) {
    var updated = state().withPinned(command.pinned());
    return effects().updateState(updated).thenReply(updated);
  }

  public Effect<ModelSlotState> trackCall(ActiveCallDelta command) {
    var updated = state().withActiveCallsDelta(command.delta());
    return effects().updateState(updated).thenReply(updated);
  }

  /** SPEC-001 §3 rules 4, 5: pinned always skipped; busy skipped unless forced. */
  public Effect<EvictReply> evict(Evict command) {
    var state = state();
    if (state.status() != ModelStatus.LOADED) {
      return effects().reply(new EvictReply(EvictResult.NOT_LOADED, state));
    }
    if (state.pinned()) {
      return effects().reply(new EvictReply(EvictResult.SKIPPED_PINNED, state));
    }
    if (state.isBusy() && !command.force()) {
      return effects().reply(new EvictReply(EvictResult.SKIPPED_BUSY, state));
    }
    var evicted = state.evicted();
    return effects().updateState(evicted).thenReply(new EvictReply(EvictResult.EVICTED, evicted));
  }

  public Effect<ModelSlotState> get() {
    return effects().reply(state());
  }
}
