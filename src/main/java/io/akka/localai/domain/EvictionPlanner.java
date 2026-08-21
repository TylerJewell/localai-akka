package io.akka.localai.domain;

import java.util.Comparator;
import java.util.List;

/**
 * SPEC-001 §3 rules 4 and 5 — the pure "which candidates, how many, in what order"
 * decision, factored out of {@code AdmissionService} so it can be unit tested without a
 * running Akka test server. {@code AdmissionService} supplies the current
 * {@link LoadedModel} snapshot (from the view) and applies the plan by calling
 * {@code evict} on each candidate in order.
 */
public final class EvictionPlanner {

  private EvictionPlanner() {}

  public record LoadedModel(String modelId, boolean pinned, boolean busy, long lastUsedAtMillis, List<String> groups) {}

  /** SPEC-001 §3 rule 4: every loaded model sharing a group with requestedGroups,
   * oldest-lastUsed first. Pinned/busy filtering happens at apply time (rule 6's retry
   * needs to re-observe busy state each attempt, not just plan against a stale one). */
  public static List<LoadedModel> groupConflicts(List<LoadedModel> loaded, String requestingModelId,
      List<String> requestedGroups) {
    if (requestedGroups == null || requestedGroups.isEmpty()) {
      return List.of();
    }
    return loaded.stream()
        .filter(m -> !m.modelId().equals(requestingModelId))
        .filter(m -> requestedGroups.stream().anyMatch(m.groups()::contains))
        .sorted(Comparator.comparingLong(LoadedModel::lastUsedAtMillis))
        .toList();
  }

  /** SPEC-001 §3 rule 5: how many of the oldest loaded models must be evicted to bring
   * (existing + pendingLoads + 1) back under lruLimit — mirrors
   * watchdog.go:497's arithmetic exactly. Zero or negative means no eviction needed. */
  public static int evictionCountForLruLimit(int currentLoadedCount, int pendingLoads, int lruLimit) {
    if (lruLimit <= 0) {
      return 0; // LRU disabled
    }
    return currentLoadedCount - lruLimit + pendingLoads + 1;
  }

  /** Oldest-lastUsed-first order, the eviction order both rule 4 (within a conflict set)
   * and rule 5 (within the full loaded set) use. */
  public static List<LoadedModel> oldestFirst(List<LoadedModel> loaded) {
    return loaded.stream().sorted(Comparator.comparingLong(LoadedModel::lastUsedAtMillis)).toList();
  }
}
