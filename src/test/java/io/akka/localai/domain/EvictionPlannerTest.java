package io.akka.localai.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.localai.domain.EvictionPlanner.LoadedModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rules 4, 5, question-log rows 3, 4. */
public class EvictionPlannerTest {

  @Test
  void groupConflictsExcludesTheRequestingModelAndNonOverlappingGroups() {
    var loaded = List.of(
        new LoadedModel("a", false, false, 100, List.of("g1")),
        new LoadedModel("b", false, false, 50, List.of("g2")),
        new LoadedModel("requesting", false, false, 10, List.of("g1")));

    var conflicts = EvictionPlanner.groupConflicts(loaded, "requesting", List.of("g1"));

    assertThat(conflicts).extracting(LoadedModel::modelId).containsExactly("a");
  }

  @Test
  void groupConflictsAreOldestLastUsedFirst() {
    var loaded = List.of(
        new LoadedModel("newer", false, false, 200, List.of("g1")),
        new LoadedModel("older", false, false, 50, List.of("g1")));

    var conflicts = EvictionPlanner.groupConflicts(loaded, "requesting", List.of("g1"));

    assertThat(conflicts).extracting(LoadedModel::modelId).containsExactly("older", "newer");
  }

  @Test
  void noRequestedGroupsMeansNoConflicts() {
    var loaded = List.of(new LoadedModel("a", false, false, 100, List.of("g1")));
    assertThat(EvictionPlanner.groupConflicts(loaded, "requesting", List.of())).isEmpty();
  }

  @Test
  void lruEvictionCountAccountsForPendingLoadsAndTheNewOne() {
    // watchdog.go:497 — currentCount - lruLimit + pendingLoads + 1
    assertThat(EvictionPlanner.evictionCountForLruLimit(8, 0, 8)).isEqualTo(1);
    assertThat(EvictionPlanner.evictionCountForLruLimit(5, 2, 8)).isEqualTo(0);
    assertThat(EvictionPlanner.evictionCountForLruLimit(5, 3, 8)).isEqualTo(1);
  }

  @Test
  void zeroOrNegativeLruLimitDisablesEviction() {
    assertThat(EvictionPlanner.evictionCountForLruLimit(100, 0, 0)).isEqualTo(0);
    assertThat(EvictionPlanner.evictionCountForLruLimit(100, 0, -1)).isEqualTo(0);
  }

  @Test
  void oldestFirstOrdersTheFullLoadedSet() {
    var loaded = List.of(
        new LoadedModel("c", false, false, 300, List.of()),
        new LoadedModel("a", false, false, 100, List.of()),
        new LoadedModel("b", false, false, 200, List.of()));

    assertThat(EvictionPlanner.oldestFirst(loaded)).extracting(LoadedModel::modelId)
        .containsExactly("a", "b", "c");
  }
}
