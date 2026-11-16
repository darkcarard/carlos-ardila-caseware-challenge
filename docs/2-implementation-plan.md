# Implementation Plan: fanout-worker (Part 2)

This plan was agreed before any code was written. Each step ends with a green `./mvnw test` and its own commit on `feat/fanout-worker`. The sources of truth are [1-design-document.md](1-design-document.md) (section 2, appendices A, E and F) and [`.claude/rules/architecture-invariants.md`](../.claude/rules/architecture-invariants.md).

| Step | Scope | Commit | Status |
| --- | --- | --- | --- |
| 0 | Install JDK 21 and Maven, generate `./mvnw` (committed with step 1) | — | Done |
| 1 | Maven project, immutable models, ports | `eb0fb12` | Done |
| 2 | Pure `PendingTargetResolver`, property and edge-case tests | `8eb13bc` | Done |
| 3 | `FanoutChunkProcessor`: conditional writes, checkpoints, resilience | `49d7930` | Done |
| 4 | `BackfillWorkerService`: 100 global / 10 per-firm caps, circuit breaker | `772b7a1` | Done |
| 5 | Mutation testing (PIT) and coverage floors | `828544f`, `e4101f5` | Done |
| — | README | `d903bda`…`2b9f666` | Done |

---

## Step 1: Maven project, immutable models and ports

**Build.** Java 21, JUnit 5, AssertJ, Mockito, jqwik. Surefire runs with `-ea` so that invariant `assert`s fire under test.
- *Deviation:* no AWS SDK. No package needs it, and `java-standards.md` §1 forbids it in `domain` and `application`.

**`domain/model`.** Records validate their inputs in compact constructors and make defensive copies.
- Typed IDs: `FirmId`, `EngagementId`, `ProductId`, `Market`, `TemplateVersion`, `PublishId`, `LocaleCode`.
- `EngagementRecord` carries the row invariants: `locked` implies no pending target, the target and `pending_since` travel together, and the target is never the base.
- `VersionNode` and `VersionGraph`. A graph is validated on construction: one lineage, known parents, unique `graphSeq`, and acyclic (checked with Kahn's algorithm). Any violation throws `NonRetryableDataException`.
- `FanoutChunk` (at most 500 ids), `ChunkKey`, `ChunkCursor`.
- `SummaryPair` has **exactly five fields**: product, market, from, to, locale.

**`domain/port`.**
- `RegistryRepository` exposes `get`, `conditionalFanoutWrite` and `conditionalBackfillWrite`. There is **no `save()`**, so every write carries its guards in its signature. Writes return the sealed `WriteOutcome { Applied, ConditionFailed(current) }`.
- `VersionGraphStore`; `CheckpointStore`, whose `advance` is monotonic; `DownstreamEngagementClient`; `SummaryWarmupPublisher`, which accepts only `Set<SummaryPair>`; `CapacityLimiter`; `KillSwitch`; `FanoutMetrics` with `invariantViolation`; `TelemetryContext`; `CircuitBreaker`.

**Errors.** A sealed `FanoutException` hierarchy:
- `TransientInfrastructureException`, with registry-throttled, downstream-rate-limit and downstream-unavailable subtypes;
- `OptimisticLockConflictException`;
- `NonRetryableDataException`.

**Tests.** Constructor validation, plus the structural half of `ResidencyBoundaryTest`: a reflection walk that fails if any type reachable from the global publisher holds a `FirmId` or `EngagementId`.

## Step 2: pure `PendingTargetResolver`

`resolve(EngagementRecord, VersionGraph) → Optional<TemplateVersion>` is static, with no I/O and no clock.
1. If the row is `locked`, return empty.
2. If the graph's lineage is foreign, or the base version is unknown, throw `NonRetryableDataException`.
3. Find the **descendants** of `baseVersion` by BFS. Reachability **ignores status**, so a withdrawn v6 does not hide v7.
4. Keep only `PUBLISHED` versions that are not declined and are not the base.
5. Return the one with the maximum `graphSeq`, which keeps the result deterministic across branches.

`ChunkPlanner` deduplicates and sorts a firm's ids, then slices them into chunks of at most 500.

**Tests.**
- Fixed cases from `testing-and-ai-audit.md` §1.1: linear, multi-hop, declined pinning, withdrawal fallback, EU/CA isolation, locked.
- Seven jqwik properties over generated DAGs, checked against an independent ancestor-closure oracle.

## Step 3: `FanoutChunkProcessor` (registry only)

The constructor **does not accept a `DownstreamEngagementClient`**, so "zero loads per publish" holds by construction.

`process(chunk)`:
1. If the breaker is open, return `Throttled`.
2. If the cursor is already completed, return `Completed(ZERO)`. This makes SQS duplicates no-ops.
3. If the graph snapshot's epoch is lower than the chunk's, return `PartialRetry`: the projection is lagging.
4. For each row from the cursor offset: missing, superseded, locked (left untouched), unresolvable (quarantined), unchanged, or a conditional write with `state_seq = :seen AND graph_epoch <= :epoch`.
   - When the condition fails because a newer epoch is stored, the row is superseded and is acked.
   - When it fails because `state_seq` changed, the row is re-derived and retried, at most 3 times. After that the chunk yields with `PartialRetry`.
5. Every N rows, flush the summary pairs **and then** advance the checkpoint.
6. Map exceptions to outcomes:
   - transient → `recordFailure` + `PartialRetry`;
   - conflict → `PartialRetry`;
   - non-retryable → `PoisonMessage`.

   The last two count as `recordSuccess`: the registry answered, so they never trip the breaker.

The **epoch written is the graph snapshot's**, not the chunk's.

`ClockedCircuitBreaker` is a CLOSED / OPEN / HALF_OPEN state machine. It runs on an injected `Clock` and admits a single probe while HALF_OPEN.

**Test doubles.**
- `InMemoryRegistryRepository` applies the exact DynamoDB conditions atomically through `ConcurrentHashMap.compute`, and can inject throttling and races.
- In-memory checkpoint and graph stores, a recording summary publisher, and a `MutableClock`.

**Tests.**
- `FanoutChunkProcessorTest`.
- `FanoutRaceConditionTest`: a user acting mid-flight, and a publish racing a withdrawal in both orders.
- `FanoutResilienceTest`:
  - convergence under duplicates, reordering and DLQ replay;
  - a crash mid-chunk resuming from the checkpoint;
  - the breaker opening under throttling;
  - poison versus transient errors.
- The runtime half of `ResidencyBoundaryTest`: the serialized payload contains no firm or engagement id.

## Step 4: `BackfillWorkerService` (the ~1-minute load path)

**`FairCapacityLimiter`.**
- Fair semaphores: 100 global and 10 per firm.
- Acquires **per-firm first, then global**, so it never holds a global slot while waiting for a firm slot.
- Releases in reverse order, exactly once.
- Counter overruns raise `invariant.violation` and fail an `assert`.

**`BackfillWorkerService`.**
- A single dispatcher keeps a queue per firm and serves firms **round-robin**. Each load runs on a virtual thread.
- The dispatcher blocks only on completions, never on time.
- The kill switch is checked before every acquisition and after every completion; in-flight loads are interrupted.
- When the breaker is open, the remaining items come back as `Deferred` with a `retryAt`.
- `RetryPolicy` computes exponential backoff with deterministic jitter. It only computes delays and never sleeps. Once the attempts are exhausted, the item ends `Failed`.

**Gap closed in the rules (§3).** The backfill write now also guards `graph_epoch`:

```
(not_exists OR state_seq < :loaded) AND (not_exists OR graph_epoch <= :epoch)
```

When the epoch guard fails, the worker re-derives against a fresh graph **without reloading**.

**Tests.**
- `DownstreamCapacityTest`: a burst of 1,000 loads, 800 from one whale firm and 20 from each of ten small firms. Concurrency is measured by the fake downstream as an independent oracle, never by the limiter under test, and the burst is drained in lock-step. It checks:
  - the global peak is exactly 100;
  - the per-firm peak is exactly 10;
  - the small firms finish within the first 300 completions.
- Kill switch, 429 opening the breaker, stale backfill versus a newer hook, and the epoch gap.

## Step 5: mutation testing and coverage floors

This automates the manual mutation checks done in step 3.
- **`./mvnw verify`** runs the JaCoCo floors: 88% branches and 94% lines for the whole bundle, and 85% branches for each logic package.
- **`./mvnw -Pmutation verify`** runs PIT with `pitest-junit5-plugin`, which also runs jqwik. It mutates `domain`, `application` and `infrastructure.resilience` with `STRONGER` mutators. The build fails below a 92% mutation score or 95% line coverage.

**Survivor triage.** Each surviving mutant is classified as one of:
- (a) a missing test, so a test was added;
- (b) an equivalent mutant, so it is documented;
- (c) dead code, so the code was removed.

The score went from 79% to 95%. Details are in [3-mutation-testing.md](3-mutation-testing.md).

**Follow-up hardening.** `run()` now checks that every backfill item gets exactly one outcome. A missing item pages through `invariant.violation` and is re-delivered as `Deferred`.

---

## Validation against `architecture-invariants.md`

| Invariant | Enforced in | Proven by |
| --- | --- | --- |
| §1 No firm or engagement id in `SummaryPair` or cross-region DTOs | Step 1: five-field record; the publisher port accepts only `SummaryPair` | `ResidencyBoundaryTest` (reflection and payload scan) |
| §1 Regional access keyed by firm | Step 1: the `RegistryRepository` signatures require `FirmId` | Structural |
| §2.1 Locked ⇒ empty | Steps 2 and 3 (no write at all) | Resolver and processor tests |
| §2.2–2.5 Lineage, descendants, PUBLISHED only, declined pinning, latest | Step 2 | Fixed cases and jqwik properties |
| §2.6 Withdrawal falls back or clears | Steps 2 and 3 (new epoch, re-run) | Withdrawal and race tests |
| §3 Fan-out `state_seq = AND graph_epoch <=`; conflict handling | Steps 1 and 3 | `FanoutRaceConditionTest` |
| §3 `pending_since` policy | Steps 1 and 3 | Model and processor tests |
| §3 Backfill `not_exists OR <` (plus the epoch guard) | Step 4 | Stale-backfill and epoch-gap tests |
| §4 At most 500 per chunk; cursor; idempotency; poison versus transient | Steps 1–3 | Model, planner and `FanoutResilienceTest` |
| §5 At most 100 global and 10 per firm; fairness; kill switch | Step 4 | `DownstreamCapacityTest`, kill-switch tests |
| CLAUDE.md: zero loads on publish; no `Thread.sleep`; no unconditional writes | All steps | Constructor reflection test; grep checks at every step |

**Points the validation made explicit**, because the rules do not state them literally:
1. The stored epoch is the graph snapshot's.
2. Reachability ignores status.
3. Ties between branches break by `graphSeq`.
4. The backfill write also guards the epoch.
5. The locale comes from the region's configuration, because the registry does not store it.

## Verification at each step

- `./mvnw test` is green, with no compiler warnings (checked without `-q`).
- `grep -rn "Thread.sleep\|System.currentTimeMillis\|\.save(" src/` returns nothing.
- `grep -rn "software.amazon\|org.springframework" src/main` returns nothing.
- From step 4 on, concurrency tests are stress-run on parallel workers before committing.
