# Acknowledgements

This project is a port of **[mudler/LocalAI](https://github.com/mudler/LocalAI)**.

- **Licence and copyright**: LocalAI is MIT-licensed, copyright (c) 2023-2025 Ettore Di
  Giacinto (mudler@localai.io). MIT permits this port under its own licence.
- **Nothing was copied verbatim.** No prompts, fixtures, schemas, or test corpora from the
  source were reused as-is; every file here is a fresh implementation.
- **Behaviour is derived even where no text was copied.** The domain rules in
  `src/main/java/io/akka/localai/domain/` — the LRU eviction-count arithmetic, the
  failure-backoff formula, the GGUF backend-selection filter — are direct, deliberate
  translations of `pkg/model/watchdog.go`, `pkg/model/loader.go`, and
  `pkg/model/autoload.go`'s logic into Java, cited by line number in
  `specs/SPEC-001-localai.md`. This is the whole point of the port and is not incidental.

## Also used

- Akka
