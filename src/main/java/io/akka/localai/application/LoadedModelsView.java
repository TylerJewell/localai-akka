package io.akka.localai.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.localai.domain.ModelSlotState;
import io.akka.localai.domain.ModelStatus;
import java.util.List;

/**
 * The read side {@link AdmissionService} needs to find eviction candidates —
 * SPEC-001 §3 rules 4 and 5 both start from "every currently loaded model, oldest
 * {@code lastUsedAt} first." Sourced from {@link ModelSlotEntity}; a model that evicts
 * itself back to NOT_LOADED simply stops matching {@code status = 'LOADED'} on the next
 * update, no delete handler needed.
 */
@Component(id = "loaded-models-view")
public class LoadedModelsView extends View {

  public record Row(
      String modelId,
      String status,
      boolean pinned,
      int activeCalls,
      long lastUsedAtMillis,
      String groupsCsv) {}

  public record Rows(List<Row> models) {}

  @Consume.FromKeyValueEntity(ModelSlotEntity.class)
  public static class Updater extends TableUpdater<Row> {
    public Effect<Row> onUpdate(ModelSlotState state) {
      return effects().updateRow(new Row(
          state.modelId(),
          state.status().name(),
          state.pinned(),
          state.activeCalls(),
          state.lastUsedAtMillis(),
          String.join(",", state.groups())));
    }
  }

  /** SPEC-001 §3 rules 4 and 5 candidate list: every loaded model, oldest first. */
  @Query("SELECT * AS models FROM loaded_models_view WHERE status = 'LOADED' ORDER BY lastUsedAtMillis ASC")
  public QueryEffect<Rows> loadedOldestFirst() {
    return queryResult();
  }
}
