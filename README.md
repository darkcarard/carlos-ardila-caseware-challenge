# fanout-worker

This is the regional template-publish fan-out worker (Part 2), in plain Java 21. There is no Spring and no AWS SDK: in-memory doubles enforce the DynamoDB condition expressions atomically per row. The design is in [docs/1-design-document.md](docs/1-design-document.md).

```
./mvnw test                  # 179 tests: unit, jqwik properties, races, chaos, capacity
./mvnw verify                # + JaCoCo floors (88% branches / 94% lines)
./mvnw -Pmutation verify     # + PIT, fails under a 92% mutation score (currently 95%)
```

## Two paths, kept apart by types

- **`FanoutChunkProcessor` (publish and withdraw).** It never calls the ~1-minute loader: its constructor does not accept one. Writes are conditional on `state_seq` and `graph_epoch`, and progress is checkpointed per (publish, firm, chunk), so a crash resumes where it stopped.
- **`BackfillWorkerService` (backfill and reconciliation).** It is the only caller of the loader, capped at 10 loads per firm and 100 in total. Firms are served round-robin on virtual threads, behind a kill switch and a circuit breaker.

## Deviations from `.claude/rules`

| Rule | What I did, and why |
| --- | --- |
| §3: backfill writes guard only `state_seq` | **They also guard `graph_epoch`.** A 1-minute load can straddle a withdrawal, and without the guard it re-offers the withdrawn version. On a failed condition the worker re-derives without reloading. |
| §3: set `graph_epoch = incomingGraphEpoch` | **It stores the epoch of the graph snapshot used for the derivation**, not the chunk's. With the chunk's epoch, a stale worker re-offered a withdrawn v7. A surviving mutant exposed this. |
| §4: corrupt data goes to the DLQ | **A corrupt row is quarantined (`unresolvable`); only a corrupt lineage poisons the chunk.** One bad row must not send 499 healthy ones to the DLQ. |
| §2: "latest in topological/version ordering" | **Reachability ignores status, and ties break by `graphSeq`.** A withdrawn v6 must not hide a published v7 behind it, and "latest" must be deterministic across branches. |

**Out of scope:**
- the SQS adapter (sending to the DLQ after N receives);
- the sweep for rows whose `graph_epoch` lags;
- the hook writers;
- real telemetry exporters. `FanoutMetrics` is a port, and `invariant.violation` is the paging signal.

## AI corrections

Built with Claude Code under hard-stop rules ([.claude/rules](.claude/rules)).

**Checked by construction, not trusted:**
- Lock ordering: per-firm before global, covered by an interrupt-without-leak test and a 2,000-task contention test.
- No `save()`: every write method carries its guards.
- `SummaryPair` has 5 fields and cannot hold a `FirmId`, checked by reflection and by scanning the serialized payload.

**Confident claims that were wrong:**
- "No warnings", while the `-q` flag hid them.
- A crash test that passed without the fix it was meant to prove.
- A checkpoint assertion that could never be true.
- Unused helpers.

**A single green run proved nothing about concurrency.** Four races were hiding in the test code:
- a fairness test that released loads faster than the dispatcher refilled them;
- a fake that updated the global count before the per-firm one;
- a kill-switch test that did not wait for the dispatcher to park;
- a spin checker keyed to the wrong signal.

Parallel stress runs and PIT's slower instrumentation exposed them.

**"Redundant" was not proof either.** To kill two mutants, the AI removed `releasePermission()` from the interrupted-load path. That would have leaked the half-open probe; a test caught it and the call was restored. The full triage is in [docs/2-mutation-testing.md](docs/2-mutation-testing.md).

**Guidance for engineers using AI here:**
- Demand pasted test output.
- Measure invariants with an independent oracle, never with the class under test.
- Treat each surviving mutant as a question.
- Never accept a concurrency change on one green run.

Do not trust AI on DynamoDB conditions, residency or lock ordering without a test that fails when they are wrong.
