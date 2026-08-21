package io.akka.localai.domain;

/** SPEC-001 §2. LOADING exists only between a START_LOAD reply and the caller
 * reporting success or failure back — see SPEC-001 §4 decision 1 for why this
 * differs from the source's blocking wait. */
public enum ModelStatus {
  NOT_LOADED,
  LOADING,
  LOADED,
  FAILED
}
