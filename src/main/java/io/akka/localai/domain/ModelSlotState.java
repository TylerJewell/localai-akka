package io.akka.localai.domain;

import java.util.List;

/** SPEC-001 §2 ModelSlot — one per model id. */
public record ModelSlotState(
    String modelId,
    ModelStatus status,
    String backend,
    List<String> groups,
    boolean pinned,
    int activeCalls,
    long lastUsedAtMillis,
    int consecutiveFailures,
    long cooldownUntilMillis) {

  public static ModelSlotState empty(String modelId) {
    return new ModelSlotState(modelId, ModelStatus.NOT_LOADED, null, List.of(), false, 0, 0L, 0, 0L);
  }

  public boolean isEmpty() {
    return status() == ModelStatus.NOT_LOADED && backend() == null;
  }

  public boolean isBusy() {
    return activeCalls > 0;
  }

  public boolean inCooldown(long nowMillis) {
    return cooldownUntilMillis > nowMillis;
  }

  public long cooldownRemainingMillis(long nowMillis) {
    long remaining = cooldownUntilMillis - nowMillis;
    return remaining > 0 ? remaining : 0;
  }

  public ModelSlotState withModelId(String newModelId) {
    return new ModelSlotState(newModelId, status, backend, groups, pinned, activeCalls,
        lastUsedAtMillis, consecutiveFailures, cooldownUntilMillis);
  }

  public ModelSlotState withStatus(ModelStatus newStatus) {
    return new ModelSlotState(modelId, newStatus, backend, groups, pinned, activeCalls,
        lastUsedAtMillis, consecutiveFailures, cooldownUntilMillis);
  }

  public ModelSlotState loading(List<String> requestedGroups) {
    return new ModelSlotState(modelId, ModelStatus.LOADING, backend, requestedGroups, pinned,
        activeCalls, lastUsedAtMillis, consecutiveFailures, cooldownUntilMillis);
  }

  public ModelSlotState loaded(String resolvedBackend, long nowMillis) {
    return new ModelSlotState(modelId, ModelStatus.LOADED, resolvedBackend, groups, pinned,
        activeCalls, nowMillis, 0, 0L);
  }

  /** SPEC-001 §3 rule 3: base * 2^(consecutiveFailures-1), capped. */
  public ModelSlotState failed(long nowMillis, long baseCooldownMillis, long maxCooldownMillis) {
    int nextFailures = consecutiveFailures + 1;
    int shift = Math.min(nextFailures - 1, 20);
    long backoff = baseCooldownMillis <= 0 ? 0 : baseCooldownMillis * (1L << shift);
    if (backoff <= 0 || backoff > maxCooldownMillis) {
      backoff = maxCooldownMillis;
    }
    long cooldownUntil = baseCooldownMillis <= 0 ? 0L : nowMillis + backoff;
    return new ModelSlotState(modelId, ModelStatus.FAILED, backend, groups, pinned, activeCalls,
        lastUsedAtMillis, nextFailures, cooldownUntil);
  }

  public ModelSlotState touched(long nowMillis) {
    return new ModelSlotState(modelId, status, backend, groups, pinned, activeCalls, nowMillis,
        consecutiveFailures, cooldownUntilMillis);
  }

  public ModelSlotState evicted() {
    return new ModelSlotState(modelId, ModelStatus.NOT_LOADED, null, groups, pinned, 0, 0L, 0, 0L);
  }

  public ModelSlotState withPinned(boolean newPinned) {
    return new ModelSlotState(modelId, status, backend, groups, newPinned, activeCalls,
        lastUsedAtMillis, consecutiveFailures, cooldownUntilMillis);
  }

  public ModelSlotState withActiveCallsDelta(int delta) {
    int next = Math.max(0, activeCalls + delta);
    return new ModelSlotState(modelId, status, backend, groups, pinned, next, lastUsedAtMillis,
        consecutiveFailures, cooldownUntilMillis);
  }
}
