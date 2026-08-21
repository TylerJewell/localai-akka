package io.akka.localai.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rule 7, question-log row 5. Mirrors the source's own regression cases in
 * pkg/model/autoload_test.go (#9287), run against the real Go package in the source
 * clone as part of step c's evidence. */
public class BackendSelectorTest {

  private static final Set<String> LLM_CAPABLE = Set.of("llama-cpp", "vllm");

  @Test
  void excludesIncompatibleBackendsForGgufModel() {
    var got = BackendSelector.selectAutoLoadBackends(
        Set.of("opus", "llama-cpp"), "Qwen3.5-9b.gguf", LLM_CAPABLE);
    assertThat(got).doesNotContain("opus");
    assertThat(got).contains("llama-cpp");
  }

  @Test
  void placesLlamaCppFirstForGgufModel() {
    var got = BackendSelector.selectAutoLoadBackends(
        Set.of("vllm", "opus", "llama-cpp"), "model.gguf", LLM_CAPABLE);
    assertThat(got).isNotEmpty();
    assertThat(got.get(0)).isEqualTo("llama-cpp");
  }

  @Test
  void isDeterministicRegardlessOfInputOrdering() {
    var a = BackendSelector.selectAutoLoadBackends(
        Set.of("opus", "vllm", "llama-cpp", "whisper"), "m.gguf", LLM_CAPABLE);
    var b = BackendSelector.selectAutoLoadBackends(
        Set.of("whisper", "llama-cpp", "vllm", "opus"), "m.gguf", LLM_CAPABLE);
    assertThat(a).isEqualTo(b);
  }

  @Test
  void fallsBackToFullSortedSetWhenFilteringLeavesNoCandidate() {
    var got = BackendSelector.selectAutoLoadBackends(Set.of("opus"), "model.gguf", LLM_CAPABLE);
    assertThat(got).isEqualTo(List.of("opus"));
  }

  @Test
  void nonGgufModelReturnsDeterministicUnfilteredSet() {
    var got = BackendSelector.selectAutoLoadBackends(
        Set.of("opus", "llama-cpp", "diffusers"), "model-dir", LLM_CAPABLE);
    assertThat(got).isEqualTo(List.of("diffusers", "llama-cpp", "opus"));
  }

  @Test
  void nullCapabilitySetSkipsFiltering() {
    var got = BackendSelector.selectAutoLoadBackends(Set.of("opus", "llama-cpp"), "model.gguf", null);
    assertThat(got).isEqualTo(List.of("llama-cpp", "opus"));
  }
}
