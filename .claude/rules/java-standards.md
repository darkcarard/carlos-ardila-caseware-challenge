# Java 21 Coding Standards & Concurrency Idioms

## 1. Language & Type Design
- **Target Java 21:** Use `record` for immutable domain values, events, commands, and DTOs. Use `sealed interface` for closed outcome hierarchies (`FanoutResult`, `WriteOutcome`, `LoadOutcome`).
- **Null Safety:** Never return `null` from public methods. Use `Optional<T>` for absent values (e.g., `Optional<String> pendingTarget`) and `Objects.requireNonNull` in compact record constructors.
- **Strong Typing:** Avoid primitive obsession where IDs could be confused across plane boundaries (e.g., `FirmId`, `EngagementId`, `ProductId`, `TemplateVersion` value objects or clearly separated records).
- **No Framework Bloat:** Keep `domain` and `application` packages 100% free of AWS SDK annotations, Spring annotations, or reflection-based DI. Wire dependencies via explicit constructor injection.

## 2. Concurrency & Resilience Patterns
- **Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`):** Use virtual threads for I/O-bound fan-out batching and downstream load orchestration, paired with explicit concurrency limiters (`Semaphore` / custom `CapacityLimiter`) rather than unbounded thread pools.
- **Structured Cancellation:** Respect `Thread.currentThread().isInterrupted()` and `CompletableFuture` cancellation when the circuit breaker opens or the kill switch trips.
- **Lock Ordering (Deadlock Prevention):** When acquiring dual capacity permits (`CapacityLimiter` for global + per-firm limits), **ALWAYS acquire the per-firm permit first, then the global permit** (or acquire atomically under a single coordinator lock) and release both in a `try/finally` block in reverse order. Never hold a global slot while blocked waiting for a per-firm slot.
- **Circuit Breaker:** Implement a clean, state-machine circuit breaker (`CLOSED`, `OPEN`, `HALF_OPEN`) driven by an injectable `Clock` (never `System.currentTimeMillis()` directly) so state transitions are deterministically testable.

## 3. Clean Contracts & Error Taxonomy
- Distinguish retryable infrastructure failures from permanent domain/contract errors via sealed exceptions or result types:
  - `TransientInfrastructureException` (throttling, timeouts, 429/503) -> trips circuit breaker, triggers retry/backoff.
  - `OptimisticLockConflictException` (`state_seq` / `graph_epoch` mismatch) -> triggers deterministic re-read & recompute loop (bounded to e.g. 3 attempts before yielding).
  - `NonRetryableDataException` (corrupt payload, unknown product lineage) -> routes directly to DLQ without tripping the downstream circuit breaker.