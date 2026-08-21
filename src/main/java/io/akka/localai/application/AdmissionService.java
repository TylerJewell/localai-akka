package io.akka.localai.application;

import akka.javasdk.client.ComponentClient;
import io.akka.localai.application.ModelSlotEntity.Admit;
import io.akka.localai.application.ModelSlotEntity.AdmitReply;
import io.akka.localai.application.ModelSlotEntity.Evict;
import io.akka.localai.application.ModelSlotEntity.EvictReply;
import io.akka.localai.application.ModelSlotEntity.EvictResult;
import io.akka.localai.application.LoadedModelsView.Row;
import io.akka.localai.domain.AdmitDecision;
import io.akka.localai.domain.EvictionPlanner;
import io.akka.localai.domain.EvictionPlanner.LoadedModel;
import java.util.List;

/**
 * SPEC-001 §3 rules 4, 5, 6 — the cross-entity part of admission that a single
 * {@link ModelSlotEntity} cannot do on its own: finding eviction candidates (via
 * {@link LoadedModelsView}) and evicting them before the requested model is allowed to
 * start loading. Not a component itself; called from {@code ModelEndpoint}, matching
 * the source's {@code ModelLoader.Load} orchestrating {@code enforceGroupExclusivity}
 * then {@code enforceLRULimit} before the actual load (rule 4 runs before rule 5).
 */
public class AdmissionService {

  private final ComponentClient componentClient;
  private final int lruLimit;
  private final int maxRetries;
  private final long retryIntervalMillis;

  public AdmissionService(ComponentClient componentClient, int lruLimit, int maxRetries,
      long retryIntervalMillis) {
    this.componentClient = componentClient;
    this.lruLimit = lruLimit;
    this.maxRetries = maxRetries;
    this.retryIntervalMillis = retryIntervalMillis;
  }

  public record AdmissionOutcome(AdmitDecision.Result result, long retryAfterMillis) {}

  /**
   * SPEC-001 §3 rules 4, 5, 6, in that order: group exclusivity first (so LRU does not
   * evict something group-exclusivity would have reclaimed anyway), then the LRU cap,
   * each retried up to maxRetries times against busy candidates before proceeding
   * regardless — then the actual admit.
   */
  public AdmissionOutcome admit(String modelId, List<String> requestedGroups, long nowMillis) {
    enforceWithRetry(() -> enforceGroupExclusivity(modelId, requestedGroups));
    enforceWithRetry(() -> enforceLruLimit());

    AdmitReply reply = componentClient.forKeyValueEntity(modelId)
        .method(ModelSlotEntity::admit)
        .invoke(new Admit(requestedGroups, nowMillis));
    return new AdmissionOutcome(reply.result(), reply.retryAfterMillis());
  }

  private record EnforceResult(boolean needMore) {}

  private void enforceWithRetry(java.util.function.Supplier<EnforceResult> fn) {
    for (int attempt = 0; attempt < maxRetries; attempt++) {
      EnforceResult result = fn.get();
      if (!result.needMore()) {
        return;
      }
      if (attempt < maxRetries - 1) {
        sleep(retryIntervalMillis);
      }
      // Last attempt: give up and let the load proceed anyway (rule 6).
    }
  }

  /** SPEC-001 §3 rule 4. */
  private EnforceResult enforceGroupExclusivity(String modelId, List<String> requestedGroups) {
    List<LoadedModel> loaded = fetchLoaded();
    List<LoadedModel> conflicts = EvictionPlanner.groupConflicts(loaded, modelId, requestedGroups);
    if (conflicts.isEmpty()) {
      return new EnforceResult(false);
    }
    return evictAll(conflicts, conflicts.size());
  }

  /** SPEC-001 §3 rule 5. */
  private EnforceResult enforceLruLimit() {
    List<LoadedModel> loaded = fetchLoaded();
    int toEvict = EvictionPlanner.evictionCountForLruLimit(loaded.size(), 0, lruLimit);
    if (toEvict <= 0) {
      return new EnforceResult(false);
    }
    return evictAll(EvictionPlanner.oldestFirst(loaded), toEvict);
  }

  private List<LoadedModel> fetchLoaded() {
    List<Row> rows = componentClient.forView().method(LoadedModelsView::loadedOldestFirst).invoke().models();
    return rows.stream()
        .map(r -> new LoadedModel(r.modelId(), r.pinned(), r.activeCalls() > 0, r.lastUsedAtMillis(), csvToList(r.groupsCsv())))
        .toList();
  }

  private EnforceResult evictAll(List<LoadedModel> candidates, int maxToEvict) {
    int evicted = 0;
    int skippedBusy = 0;
    for (LoadedModel candidate : candidates) {
      if (evicted >= maxToEvict) {
        break;
      }
      if (candidate.pinned()) {
        continue;
      }
      EvictReply reply = componentClient.forKeyValueEntity(candidate.modelId())
          .method(ModelSlotEntity::evict)
          .invoke(new Evict(false));
      if (reply.result() == EvictResult.EVICTED) {
        evicted++;
      } else if (reply.result() == EvictResult.SKIPPED_BUSY) {
        skippedBusy++;
      }
    }
    boolean needMore = evicted < maxToEvict && skippedBusy > 0;
    return new EnforceResult(needMore);
  }

  private static List<String> csvToList(String csv) {
    if (csv == null || csv.isEmpty()) {
      return List.of();
    }
    return List.of(csv.split(","));
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
