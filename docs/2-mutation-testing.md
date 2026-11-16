# Mutation Testing Triage

`./mvnw -Pmutation verify` runs PIT (`STRONGER` mutators) over `domain`, `application` and `infrastructure.resilience`, and fails the build below a **92%** mutation score or **95%** line coverage. `./mvnw verify` enforces the JaCoCo floors (bundle: 88% branches, 94% lines; 85% branches in each logic package).

| Run | Mutants | Killed | Score |
| --- | --- | --- | --- |
| First measurement (end of step 4) | 414 | 325 | 79% |
| After triage | 398 | 379 | **95%** |

Deleting `FanoutResilienceTest` drops the score to 91% and fails the build, so the gate catches lost tests.

## What the survivors found

Every survivor was put in one of three buckets.

Of the 89 first-run survivors, 70 are gone. Most were killed by new tests; the rest disappeared with the code that produced them.

**(a) Missing tests: new tests kill them.**

- **A HALF_OPEN probe that ends without a transient failure must close the breaker.** This covers a poison chunk, a duplicate chunk, a lagging graph projection and a conflict yield. If any of these paths dropped its `recordSuccess()`, the single probe permission would be held forever and every later chunk would come back `Throttled`. Removing the call left every test green.
- **A breaker-denied item that is the last in its firm's queue must go back into the round-robin ring.** Without that, `run()` ends with the item missing from the report: it is lost, not deferred.
- **Interrupting the dispatcher must return the probe permission.** The new test also caught a regression I introduced during this triage (below).
- **Dispatcher behaviour while the probe is in flight.** The dispatcher must wait for the probe's verdict, not busy-spin on the breaker or defer the rest. A wrapper breaker flags any re-ask that happens before a verdict.
- **Row-level checks:** checkpoint cadence (no double checkpoint at the end of a chunk), no empty summary batches, each flush publishes only new pairs, repair of a drifted target at the current epoch, and per-item retry jitter (no thundering herd).
- **Metrics:** counters carry the chunk correlation fields.

**(c) Dead or redundant code: removed or simplified.**

- `Ids.requireNonBlank` no longer returns a value; no caller used it.
- `FairCapacityLimiter`: the `maxGlobal < 1` check is implied by `1 <= maxPerFirm <= maxGlobal`.
- `ClockedCircuitBreaker.recordFailure` is now a switch on state, and `releasePermission` clears the probe flag unconditionally. Two guards were unobservable.
- `FanoutChunkProcessor`: checkpoints are row-aligned (`done % N`), so the `- start` subtraction is gone.
- `BackfillWorkerService`: the dispatcher no longer checks `!aborted` before dispatching, because a drained queue dispatches nothing.

**(b) Equivalent or defensive: accepted (19).**

| Where | Why it survives |
| --- | --- |
| `RetryPolicy.mix` (6), `delayFor` (3) | Any deterministic hash, and any jitter within `[exp/2, exp]`, meets the contract. The range is checked by a property test and the spread statistically. |
| `FairCapacityLimiter.granted` (3) | Invariant-violation alarm. It is unreachable while the semaphores are correct, and it exists for the day they are not. |
| `BackfillWorkerService.settle` (3) | Backstops for a future cancelled before its task started. This is a scheduling race with no deterministic trigger, and the operations are idempotent. |
| `BackfillWorkerService.seed` `orElseThrow` (1) | The backfill condition cannot fail on an absent row. |
| `ClockedCircuitBreaker.retryAfter` state check (1) | Outside OPEN the remaining window is already in the past, so the clamp returns zero anyway. Kept for readability. |
| `FairQueue.drain` `byFirm.clear` (1) | The queue is never used after a drain. |
| `PendingTargetResolver.descendantsOf` visited check (1) | Performance only: it avoids re-walking diamonds. The result is the same set. |

## Mistakes the triage exposed

- **A regression I introduced and the new tests caught.** To kill two "redundant" mutants I removed `breaker.releasePermission()` from the interrupted-load path. `settle()` does not run when the dispatcher itself is interrupted, so the probe permission would have leaked. I restored the call; `interruptingTheDispatcherCancelsLoadsAndGivesBackSlotsAndTheProbe` covers it.
- **Three flaky tests: one existing test, one new test and one test fake, all racing in the test code, not in production.**
  - The kill-switch test flipped the switch before the dispatcher was parked. PIT's slower instrumentation exposed this.
  - The fake downstream bumped its global counter before the per-firm one, so a waiter could see 100 global and a stale 9 for the whale. This showed up as 1 failure in 36 stressed runs.
  - The new spin checker used client exits as its signal, but a client exit comes before the breaker hears the verdict.

  All three are fixed. The suite then ran 75 times on 3 parallel workers with no failures.
