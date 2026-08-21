package io.akka.localai.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * SPEC-001 §3 rule 7 — port of {@code pkg/model/autoload.go}'s {@code SelectAutoLoadBackends}.
 * The source resolves LLM-capability via a package-level callback registered from
 * {@code core/config} to avoid an import cycle (see LocalAI #9287); Java has no such cycle,
 * so this takes the capability set as a plain argument instead.
 */
public final class BackendSelector {

  public static final String PREFERRED_GGUF_BACKEND = "llama-cpp";

  private BackendSelector() {}

  /**
   * available is the set of installed backend names (order not significant). modelFile is
   * the model file name/path (may be null or empty). llmCapableBackends is the set of
   * backend names known to serve text/LLM models; a backend absent from it is treated as
   * not LLM-capable, matching the source's "unknown = not confirmed" stance.
   */
  public static List<String> selectAutoLoadBackends(
      Set<String> available, String modelFile, Set<String> llmCapableBackends) {
    List<String> sorted = new ArrayList<>(new TreeSet<>(available));

    if (!isGgufModelFile(modelFile)) {
      return sorted;
    }

    if (llmCapableBackends == null) {
      // No capability predicate wired: skip filtering rather than risk dropping a
      // valid candidate — matches the source's llmCapableBackend == nil branch.
      return sorted;
    }

    List<String> filtered = new ArrayList<>();
    boolean hasLlama = false;
    for (String backend : sorted) {
      if (backend.equals(PREFERRED_GGUF_BACKEND)) {
        hasLlama = true;
        continue;
      }
      if (llmCapableBackends.contains(backend)) {
        filtered.add(backend);
      }
    }
    if (hasLlama) {
      filtered.add(0, PREFERRED_GGUF_BACKEND);
    }

    if (filtered.isEmpty()) {
      // Conservative fallback: no known LLM-capable backend installed, so rather than
      // refuse to load, try every installed backend in deterministic order.
      return sorted;
    }
    return filtered;
  }

  public static boolean isGgufModelFile(String modelFile) {
    return modelFile != null && modelFile.toLowerCase().endsWith(".gguf");
  }
}
