package io.akka.localai.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import io.akka.localai.application.AdmissionService;
import io.akka.localai.application.AdmissionService.AdmissionOutcome;
import io.akka.localai.application.ModelSlotEntity;
import io.akka.localai.application.ModelSlotEntity.ActiveCallDelta;
import io.akka.localai.application.ModelSlotEntity.MarkFailed;
import io.akka.localai.application.ModelSlotEntity.MarkLoaded;
import io.akka.localai.application.ModelSlotEntity.SetPinned;
import io.akka.localai.application.ModelSlotEntity.Touch;
import io.akka.localai.domain.BackendSelector;
import io.akka.localai.domain.ModelSlotState;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * HTTP surface for SPEC-001's admit/load/evict decision procedure. This is the reachable
 * caller PIPELINE.md's step d asks for separately from tests — see
 * {@code gui/manifest.json} for why this port has no rendered surface.
 */
@HttpEndpoint("/models")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class ModelEndpoint {

  private final ComponentClient componentClient;
  private final AdmissionService admissionService;
  private static final long FAILURE_BASE_COOLDOWN_MILLIS = 10_000;
  private static final long FAILURE_MAX_COOLDOWN_MILLIS = 5 * 60_000;

  public ModelEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
    // SPEC-001 §3 rule 5: lruLimit, maxRetries, retryInterval match the source's
    // documented defaults (loader.go:147-148: 30 retries, 1s interval).
    this.admissionService = new AdmissionService(componentClient, 8, 30, 1000);
  }

  public record LoadRequest(List<String> groups) {}

  public record LoadResponse(String status, ModelSlotState state, long retryAfterMillis) {}

  /** SPEC-001 §3 rules 1-6: the full admit path, group + LRU eviction included. */
  @Post("/{modelId}/load")
  public LoadResponse load(String modelId, LoadRequest request) {
    long now = Instant.now().toEpochMilli();
    List<String> groups = request == null || request.groups() == null ? List.of() : request.groups();
    AdmissionOutcome outcome = admissionService.admit(modelId, groups, now);

    ModelSlotState state = componentClient.forKeyValueEntity(modelId)
        .method(ModelSlotEntity::get)
        .invoke();

    return new LoadResponse(outcome.result().name(), state, outcome.retryAfterMillis());
  }

  /** Reports the outcome of the out-of-band load the caller performed after START_LOAD. */
  @Post("/{modelId}/loaded")
  public ModelSlotState markLoaded(String modelId, String backend) {
    return componentClient.forKeyValueEntity(modelId)
        .method(ModelSlotEntity::markLoaded)
        .invoke(new MarkLoaded(backend, Instant.now().toEpochMilli()));
  }

  /** SPEC-001 §3 rule 3. */
  @Post("/{modelId}/failed")
  public ModelSlotState markFailed(String modelId) {
    return componentClient.forKeyValueEntity(modelId)
        .method(ModelSlotEntity::markFailed)
        .invoke(new MarkFailed(Instant.now().toEpochMilli(), FAILURE_BASE_COOLDOWN_MILLIS,
            FAILURE_MAX_COOLDOWN_MILLIS));
  }

  @Post("/{modelId}/touch")
  public ModelSlotState touch(String modelId) {
    return componentClient.forKeyValueEntity(modelId)
        .method(ModelSlotEntity::touch)
        .invoke(new Touch(Instant.now().toEpochMilli()));
  }

  @Post("/{modelId}/pin")
  public ModelSlotState pin(String modelId, boolean pinned) {
    return componentClient.forKeyValueEntity(modelId)
        .method(ModelSlotEntity::setPinned)
        .invoke(new SetPinned(pinned));
  }

  @Post("/{modelId}/call-started")
  public ModelSlotState callStarted(String modelId) {
    return componentClient.forKeyValueEntity(modelId)
        .method(ModelSlotEntity::trackCall)
        .invoke(new ActiveCallDelta(1));
  }

  @Post("/{modelId}/call-finished")
  public ModelSlotState callFinished(String modelId) {
    return componentClient.forKeyValueEntity(modelId)
        .method(ModelSlotEntity::trackCall)
        .invoke(new ActiveCallDelta(-1));
  }

  @Get("/{modelId}")
  public ModelSlotState get(String modelId) {
    return componentClient.forKeyValueEntity(modelId).method(ModelSlotEntity::get).invoke();
  }

  public record ResolveBackendRequest(List<String> available, String modelFile, List<String> llmCapableBackends) {}

  /** SPEC-001 §3 rule 7. */
  @Post("/resolve-backend")
  public List<String> resolveBackend(ResolveBackendRequest request) {
    return BackendSelector.selectAutoLoadBackends(
        Set.copyOf(request.available()),
        request.modelFile(),
        request.llmCapableBackends() == null ? null : Set.copyOf(request.llmCapableBackends()));
  }
}
