package io.akka.localai.domain;

import java.util.Set;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * bench/REPORT.md §2 — same-operation timing for {@link BackendSelector#selectAutoLoadBackends}
 * against the Go source's {@code BenchmarkSelectAutoLoadBackends} (pkg/model/autoload.go),
 * the one function on both sides with no I/O, so JIT/GC noise is the only confound. Disabled
 * by default (a timing loop is not a correctness test); run explicitly for the benchmark.
 */
public class BackendSelectorBenchmark {

  @Test
  @Disabled("run explicitly for bench/REPORT.md; not a correctness check")
  void timeSelectAutoLoadBackends() {
    Set<String> available = Set.of("opus", "vllm", "llama-cpp", "whisper", "diffusers", "stablediffusion");
    Set<String> llmCapable = Set.of("llama-cpp", "vllm");

    // warmup
    for (int i = 0; i < 2_000_000; i++) {
      BackendSelector.selectAutoLoadBackends(available, "model.gguf", llmCapable);
    }

    int n = 12_000_000;
    long start = System.nanoTime();
    for (int i = 0; i < n; i++) {
      BackendSelector.selectAutoLoadBackends(available, "model.gguf", llmCapable);
    }
    long elapsed = System.nanoTime() - start;
    System.out.printf("BackendSelector.selectAutoLoadBackends: %.1f ns/op (n=%d)%n", (double) elapsed / n, n);
  }
}
