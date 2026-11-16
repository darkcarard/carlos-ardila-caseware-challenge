# fanout-worker

This is the regional template-publish fan-out worker (Part 2), in plain Java 21. There is no Spring and no AWS SDK: in-memory doubles enforce the DynamoDB condition expressions atomically per row. The design is in [docs/1-design-document.md](docs/1-design-document.md).

```
./mvnw test                  # 179 tests: unit, jqwik properties, races, chaos, capacity
./mvnw verify                # + JaCoCo floors (88% branches / 94% lines)
./mvnw -Pmutation verify     # + PIT, fails under a 92% mutation score (currently 95%)
```

## Two paths, kept apart by types

- **`FanoutChunkProcessor` (publish and withdraw)** runs against the registry only. Its constructor does not accept a `DownstreamEngagementClient`, so a publish makes zero loads by construction.
  - Writes require `state_seq = :seen AND graph_epoch <= :epoch`.
  - When that condition fails because a newer epoch is stored, the chunk acks the row as superseded. When it fails because `state_seq` changed, it re-derives the row and retries, up to 3 times, then returns `PartialRetry`.
  - Each (publish, firm, chunk) has a cursor checkpointed every 50 rows, so a crash at row 250 resumes at row 250.
- **`BackfillWorkerService` (backfill and reconciliation)** is the only caller of the ~1-minute loader.
  - The limiter takes the per-firm permit before the global one: at most 10 per firm and 100 in total.
  - One dispatcher serves firms in round-robin and runs each load on a virtual thread. It blocks only on load completions, never on time.
  - It checks the kill switch before every acquisition and after every completion.
  - When the circuit breaker opens, the remaining items are returned as deferred with a `retryAt`.
  - In a burst of 1,000 loads, ten small firms finish within the first 300 completions, ahead of an 800-item whale.

## Tradeoffs and deviations from the rules

| Decision | Why |
| --- | --- |
| **Backfill writes also guard `graph_epoch`** (the rules guard only `state_seq`) | A 1-minute load can straddle a withdrawal. Without this guard it would re-offer the withdrawn version. On a failed condition it re-derives without reloading. |
| The stored epoch is the **graph snapshot's**, not the chunk's | With the chunk's epoch, a stale worker re-offered a withdrawn v7. A surviving mutant exposed this. |
| A corrupt row is **quarantined**; a corrupt lineage **poisons the chunk** | One bad row must not send 499 healthy ones to the DLQ. Data errors never trip the breaker. |
| Summary pairs are **flushed before each checkpoint** | Otherwise a crash after a checkpoint loses pairs the resumed run never sees. |
| Reachability ignores status; ties break by `graphSeq` | A withdrawn v6 must not hide v7, and "latest" must be deterministic across branches. |
| The kill switch is seen within **one load completion** (under 1 min) | With every slot busy, the dispatcher has no earlier point to check it. In-flight loads are interrupted. |
| A dropped backfill item **pages and is re-delivered** | Every item must get exactly one outcome. Mutation testing showed that a dispatcher bug would otherwise lose one silently. |

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
