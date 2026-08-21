package io.akka.localai.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rules 1, 2, 3, question-log rows 1, 2. */
public class AdmitDecisionTest {

  @Test
  void loadedModelShortCircuitsToAlreadyLoaded() {
    var state = ModelSlotState.empty("m1").loaded("llama-cpp", 100L);
    var decision = AdmitDecision.decide(state, List.of(), 200L);
    assertThat(decision.result()).isEqualTo(AdmitDecision.Result.ALREADY_LOADED);
    assertThat(decision.nextState()).isEqualTo(state);
  }

  @Test
  void secondAdmitWhileLoadingWaitsRatherThanStartingASecondLoad() {
    var loading = ModelSlotState.empty("m1").loading(List.of());
    var decision = AdmitDecision.decide(loading, List.of(), 100L);
    assertThat(decision.result()).isEqualTo(AdmitDecision.Result.WAIT);
  }

  @Test
  void freshUnloadedModelStartsLoad() {
    var decision = AdmitDecision.decide(ModelSlotState.empty("m1"), List.of("g1"), 100L);
    assertThat(decision.result()).isEqualTo(AdmitDecision.Result.START_LOAD);
    assertThat(decision.nextState().status()).isEqualTo(ModelStatus.LOADING);
    assertThat(decision.nextState().groups()).containsExactly("g1");
  }

  @Test
  void failedModelInCooldownIsRejectedWithRetryAfter() {
    var failed = ModelSlotState.empty("m1").failed(1000L, 10_000L, 300_000L);
    var decision = AdmitDecision.decide(failed, List.of(), 1000L + 5_000L);
    assertThat(decision.result()).isEqualTo(AdmitDecision.Result.IN_COOLDOWN);
    assertThat(decision.retryAfterMillis()).isEqualTo(5_000L);
  }

  @Test
  void failedModelPastCooldownStartsLoadAgain() {
    var failed = ModelSlotState.empty("m1").failed(1000L, 10_000L, 300_000L);
    var decision = AdmitDecision.decide(failed, List.of(), 1000L + 10_000L + 1);
    assertThat(decision.result()).isEqualTo(AdmitDecision.Result.START_LOAD);
  }

  @Test
  void secondConsecutiveFailureDoublesTheBackoff() {
    var first = ModelSlotState.empty("m1").failed(0L, 10_000L, 300_000L);
    assertThat(first.cooldownRemainingMillis(0L)).isEqualTo(10_000L);

    var second = first.failed(0L, 10_000L, 300_000L);
    assertThat(second.cooldownRemainingMillis(0L)).isEqualTo(20_000L);
  }

  @Test
  void backoffIsCappedAtMaxCooldown() {
    var state = ModelSlotState.empty("m1");
    for (int i = 0; i < 10; i++) {
      state = state.failed(0L, 10_000L, 60_000L);
    }
    assertThat(state.cooldownRemainingMillis(0L)).isEqualTo(60_000L);
  }

  @Test
  void successfulLoadClearsFailureState() {
    var failed = ModelSlotState.empty("m1").failed(0L, 10_000L, 300_000L);
    var loaded = failed.loaded("llama-cpp", 1L);
    assertThat(loaded.consecutiveFailures()).isEqualTo(0);
    assertThat(loaded.cooldownUntilMillis()).isEqualTo(0L);
  }

  @Test
  void zeroBaseCooldownDisablesTheCooldownEntirely() {
    var failed = ModelSlotState.empty("m1").failed(1000L, 0L, 300_000L);
    var decision = AdmitDecision.decide(failed, List.of(), 1000L);
    assertThat(decision.result()).isEqualTo(AdmitDecision.Result.START_LOAD);
  }
}
