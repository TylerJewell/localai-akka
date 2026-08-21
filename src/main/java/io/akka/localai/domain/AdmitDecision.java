package io.akka.localai.domain;

/** SPEC-001 §2 AdmitResult, plus the state admit() should transition to (if any). */
public record AdmitDecision(Result result, ModelSlotState nextState, long retryAfterMillis) {

  public enum Result {
    ALREADY_LOADED,
    START_LOAD,
    WAIT,
    IN_COOLDOWN
  }

  /** SPEC-001 §3 rules 1, 2, 3 — pure decision, independent of the entity/runtime so it
   * can be unit tested directly. */
  public static AdmitDecision decide(ModelSlotState state, java.util.List<String> requestedGroups, long nowMillis) {
    if (state.status() == ModelStatus.LOADED) {
      return new AdmitDecision(Result.ALREADY_LOADED, state, 0);
    }
    if (state.status() == ModelStatus.LOADING) {
      return new AdmitDecision(Result.WAIT, state, 0);
    }
    if (state.status() == ModelStatus.FAILED && state.inCooldown(nowMillis)) {
      return new AdmitDecision(Result.IN_COOLDOWN, state, state.cooldownRemainingMillis(nowMillis));
    }
    var loading = state.loading(requestedGroups);
    return new AdmitDecision(Result.START_LOAD, loading, 0);
  }
}
