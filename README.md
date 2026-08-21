# localai-akka

Decides whether a request for a model is served from memory, waits for a load already
under way, starts a new load, or forces something else out of memory first.

A port of [mudler/LocalAI](https://github.com/mudler/LocalAI) onto **Akka**, built with
**Akka Specify**.

---

## Where it came from

LocalAI is a self-hosted server that runs open models behind an OpenAI-compatible API. It
was ported to derive a specification format precise enough to regenerate a system on a
different stack — the port is the vehicle, the specification is the deliverable.

The specifications the port was generated from are in
[TylerJewell/akka-specify-harness](https://github.com/TylerJewell/akka-specify-harness)
under `localai-port/`.

---

## mudler/LocalAI → this port

📉 518 Go lines (in-scope slice) → **479 Java lines**<br>
📁 5 Go files → **9 Java files**<br>
🖥️ one process holding every loaded model → **one process per model entity, held by the runtime**<br>
⚡ 195.1 ns/op (backend selection, no I/O) → **131.2 ns/op**<br>
🎯 5 backend-selection cases checked against the source's own regression test → **5 of 5 agree**

Full method and the numbers that did not make this list:
[`bench/REPORT.md`](../localai-port/bench/REPORT.md).

---

## What it took to build

⏱️ **0.7 hours** from the first command to the published repository, **0.7** of them active<br>
💬 **431** exchanges with the model<br>
✍️ **185,791** tokens written by the model, **87,209,357** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **25** tests

```bash
python toolkit/tokens.py --port localai    # turns, tokens, elapsed and active time
```

The record of every question, and where the time went, is in
[`port-log/`](https://github.com/TylerJewell/akka-specify-harness/tree/main/port-log).

---

## What it does

From the specification:

- **Two admits for the same unloaded model never both start a load.** The second is told
  to wait rather than spawning a duplicate backend.
- **A failed load backs off before it is retried.** Each consecutive failure doubles the
  wait, up to a cap, so a broken model is not retried on every request.
- **Loading a model that would exceed the configured cap evicts the oldest ones first.**
  A model currently serving a request, or marked never to evict, is skipped.
- **Two models declared mutually exclusive are never both loaded.** Loading one evicts
  the other first.
- **The backend chosen for a model with none configured is always the same one**, for the
  same installed set, regardless of the order backends were installed in.

---

## Design decisions

**Reply-and-retry instead of blocking.** The original holds the caller's request open
until the in-progress load finishes. A component here cannot hold a request open without
blocking everything else waiting on it, so the caller is told to wait and ask again a
moment later instead. This makes the caller respond sooner, at the cost of the caller now
doing the asking-again itself.

**One entity per model.** Every request for the same model is handled one at a time
automatically, in the order it arrived, without writing any locking code — the runtime
already guarantees that for anything sharing one identity. The original reaches the same
guarantee with a hand-written table of locks, one per model, cleaned up when nothing is
waiting on it any more.

**Eviction reads a list, then acts on it.** Deciding which models to remove needs to see
every loaded model at once, sorted by how long ago each was last used, which is not
something a single model can answer about itself. That list is kept up to date
automatically as models load and unload, and eviction is a plain read of it followed by
one instruction per model chosen.

---

## Running it — the short path

You do not need Java, Maven, or the Akka CLI installed. Akka Specify installs them for you.

**1. Install Akka Specify** in Claude Code:

```
/plugin marketplace add akka/ai-marketplace
/plugin install akka@akka-ai-marketplace
```

Restart Claude Code when it asks.

**2. Give it this prompt:**

> Clone https://github.com/TylerJewell/localai-akka into a new directory and open it.
> Then run /akka:setup to install everything this project needs, and /akka:build to
> compile it, run the tests, and start it locally.

**3. Send a request** — see below.

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once

### Start the service

```bash
mvn compile
akka local run
```

The service starts on **port 9037**.

### Try it

```bash
curl -X POST http://localhost:9037/models/my-model/load -H "Content-Type: application/json" -d '{"groups":[]}'
curl -X POST http://localhost:9037/models/my-model/loaded -H "Content-Type: application/json" -d '"llama-cpp"'
curl http://localhost:9037/models/my-model
```

---

## Configuration

Everything in this slice is fixed at construction in `ModelEndpoint` rather than read from
the environment — the cap on how many models may be loaded at once, and the retry count
and interval used while waiting for something busy to become free — because the source's
own equivalents (`lruEvictionMaxRetries`, `lruEvictionRetryInterval`) are set the same way,
as constructor arguments on the object that owns them, not environment variables.

| Setting | Default | Where |
|---|---|---|
| Loaded-model cap | 8 | `ModelEndpoint` constructor |
| Eviction retry count | 30 | `ModelEndpoint` constructor |
| Eviction retry interval | 1000 ms | `ModelEndpoint` constructor |
| Load-failure cooldown, first failure | 10,000 ms | `ModelEndpoint` constants |
| Load-failure cooldown, cap | 300,000 ms | `ModelEndpoint` constants |

---

## Where it differs from mudler/LocalAI

Everything not listed here behaves the same way on purpose, including the parts that
look like mistakes.

- **A second request for a model already loading is told to wait, rather than having its
  own request held open.** The original blocks the caller's own request until the
  in-progress load finishes and only then answers. This port replies immediately with a
  wait instruction and expects the caller to ask again, because a component here cannot
  hold a request open without blocking every other request waiting on the same model.
- **The cap on loaded models, and the retry count and interval used while waiting for a
  busy model to free up, are fixed in code rather than read from configuration at
  startup.** The original reads equivalent settings once at startup too, so this is not
  checked against runtime reconfiguration on either side — `not checked`.
- **Memory-pressure eviction and the idle/busy background sweeps are not ported.** The
  original runs these as periodic timers over the same eviction machinery already in
  scope here; they were left out as a scope decision (see the specification), not
  discovered to behave differently.
- **Distributed, multi-node routing is not ported.** The original's remote-node behaviour
  (health-check policy on a timeout versus a connection error, cluster-wide eviction) was
  read but never run — `not checked` — and is out of scope for this single-node slice.

---

## Licence

mudler/LocalAI is MIT licensed, © 2023-2025 Ettore Di Giacinto. This port reimplements the
behaviour described in its specification without copied source; see `ACKNOWLEDGEMENTS.md`.
