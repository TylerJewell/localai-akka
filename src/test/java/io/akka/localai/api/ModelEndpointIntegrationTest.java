package io.akka.localai.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.localai.domain.ModelStatus;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 §5 — the admit/load/evict decision procedure reachable over HTTP, run against a
 * real local service (Awaitility for the view's eventual-consistency window, per O3).
 */
public class ModelEndpointIntegrationTest extends TestKitSupport {

  private String freshId(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  private ModelEndpoint.LoadResponse load(String modelId, List<String> groups) {
    return httpClient.POST("/models/" + modelId + "/load")
        .withRequestBody(new ModelEndpoint.LoadRequest(groups))
        .responseBodyAs(ModelEndpoint.LoadResponse.class)
        .invoke()
        .body();
  }

  private void markLoaded(String modelId) {
    httpClient.POST("/models/" + modelId + "/loaded").withRequestBody("llama-cpp").invoke();
  }

  /** SPEC-001 §3 rules 1, 2: first admit starts a load; once loaded, a second admit
   * short-circuits to ALREADY_LOADED without touching eviction. */
  @Test
  void secondAdmitAfterLoadReturnsAlreadyLoaded() {
    String modelId = freshId("m");

    var first = load(modelId, List.of());
    assertThat(first.status()).isEqualTo("START_LOAD");
    assertThat(first.state().status()).isEqualTo(ModelStatus.LOADING);

    markLoaded(modelId);

    var second = load(modelId, List.of());
    assertThat(second.status()).isEqualTo("ALREADY_LOADED");
    assertThat(second.state().backend()).isEqualTo("llama-cpp");
  }

  /** SPEC-001 §3 rule 3: a reported failure sets a cooldown that a fresh admit respects. */
  @Test
  void admitAfterFailureIsInCooldown() {
    String modelId = freshId("m");
    load(modelId, List.of());
    httpClient.POST("/models/" + modelId + "/failed").invoke();

    var retry = load(modelId, List.of());
    assertThat(retry.status()).isEqualTo("IN_COOLDOWN");
    assertThat(retry.retryAfterMillis()).isGreaterThan(0);
  }

  /** SPEC-001 §3 rule 5: loading past the configured LRU cap evicts the oldest loaded
   * model to make room. Waits for the view (Awaitility, per O3) since eviction reads the
   * view's eventually-consistent projection. */
  @Test
  void loadingPastTheLruCapEvictsTheOldestModel() {
    String prefix = freshId("lru");
    String oldest = prefix + "-0";

    for (int i = 0; i < 8; i++) {
      String id = prefix + "-" + i;
      load(id, List.of());
      markLoaded(id);
    }

    Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
      load(prefix + "-9th", List.of());
      var oldestState = httpClient.GET("/models/" + oldest)
          .responseBodyAs(io.akka.localai.domain.ModelSlotState.class)
          .invoke()
          .body();
      assertThat(oldestState.status()).isEqualTo(ModelStatus.NOT_LOADED);
    });
  }

  /** SPEC-001 §3 rule 7. */
  @Test
  void resolveBackendFiltersToLlmCapableForGgufModels() {
    var got = httpClient.POST("/models/resolve-backend")
        .withRequestBody(new ModelEndpoint.ResolveBackendRequest(
            List.of("opus", "llama-cpp"), "model.gguf", List.of("llama-cpp", "vllm")))
        .responseBodyAs(String[].class)
        .invoke()
        .body();
    assertThat(got).containsExactly("llama-cpp");
  }
}
