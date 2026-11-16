# Template-Publish Fan-Out Worker (Caseware Staff Java Challenge)

## 1. Project Context & Scope
This repository implements **Part 2** of the Staff Java Developer Take-Home Exercise: the **regional template-publish fan-out worker** (`fanout-worker`).

When a product template is `published` or `withdrawn` globally, regional workers evaluate affected firm engagements and update the **Engagement State Registry** (DynamoDB).

**CRITICAL ARCHITECTURAL CONSTRAINT:**
- **Steady-state publish fan-out NEVER calls the ~1-minute downstream engagement loader** (0 downstream loads per publish). It operates exclusively against the regional DynamoDB registry and local version-graph projection.
- **Downstream capacity protection (100 global slots / 10 per-firm cap)** applies to **backfill**, **reconciliation**, and **user accept surges**, which share the same worker runtime/rate-limiting primitives or run alongside fan-out workers under shared circuit breakers and downstream throttlers. Both paths must be cleanly modeled so the code demonstrates mastery of both registry-only fan-out and downstream-capped execution.

## 2. Architecture & Residency Boundaries
- **Global Plane:** Product Template DB, Event Bus, Diff + Summary Service (S3 Object Lock). **Zero firm or engagement IDs.**
- **Regional Plane (`eu-central-1`, `ca-central-1`):** SQS + DLQ, Fan-Out Workers, Version-Graph Projection, Engagement State Registry (DynamoDB), Downstream Engagement Loader (~1 min/file). **All firm/engagement IDs stay strictly in-region.**
- **Cross-Plane Contract:**
  - Inbound to region: `(product_id, market, version, parents, status, graph_seq, graph_epoch)`.
  - Outbound from region: unique `(product_id, market, from_version, to_version, locale)` pairs for summary pre-warming — **stripped of any firm or engagement identifiers**.

## 3. Repository & Package Map
```text
├── CLAUDE.md
├── README.md                          # Concise architectural & implementation tradeoffs (< 1 page)
├── pom.xml                            # Java 21, JUnit 5, AssertJ, Mockito, jqwik; JaCoCo floors; PIT in -Pmutation (no AWS SDK)
├── docs/                              # 0 challenge, 1 design, 2 implementation plan, 3 mutation-testing triage
├── .claude/
│   └── rules/
│       ├── architecture-invariants.md # Residency, DynamoDB conditions, graph semantics, capacity caps
│       ├── java-standards.md          # Java 21 idioms, virtual threads, immutability, error taxonomy
│       └── testing-and-ai-audit.md    # Property tests, race/chaos tests, observability, AI guardrails
└── src/
    ├── main/java/com/caseware/fanout/
    │   ├── domain/
    │   │   ├── model/                 # Records: EngagementRecord, VersionNode, VersionGraph, FanoutChunk, SummaryPair
    │   │   ├── service/               # Pure functions: PendingTargetResolver, ChunkPlanner
    │   │   ├── port/                  # Interfaces: RegistryRepository, VersionGraphStore, DownstreamEngagementClient, SummaryWarmupPublisher, CapacityLimiter, CircuitBreaker, FanoutMetrics (telemetry + invariant.violation)
    │   │   └── error/                 # Sealed FanoutException: Transient / OptimisticLockConflict / NonRetryableData
    │   ├── application/
    │   │   ├── FanoutChunkProcessor.java      # Orchestrates chunk evaluation, checkpointing, conditional writes
    │   │   ├── BackfillWorkerService.java     # Demonstrates ~1m downstream load with global/per-firm caps & circuit breaker
    │   │   ├── FanoutResult.java              # Sealed outcome hierarchy (Completed, PartialRetry, Throttled, PoisonMessage)
    │   │   └── RetryPolicy.java               # Exponential backoff with deterministic jitter (computes delays, never sleeps)
    │   └── infrastructure/
    │       └── resilience/            # FairCapacityLimiter (per-firm then global semaphores), ClockedCircuitBreaker
    └── test/java/com/caseware/fanout/
        ├── domain/                    # Pure unit & property-based graph resolution tests
        ├── application/               # Concurrency, race conditions, idempotency, DLQ & cap enforcement tests
        └── doubles/                   # Deterministic in-memory fakes (simulating DynamoDB conditional writes & 429s)
```
### 3.1 Challenge Reference
- @docs/0-architecture-design-exercise.md

### 3.2 Architectural Source of Truth
- @docs/1-design-document.md

## 4. Build & Verification Commands
- Compile & run all tests: `./mvnw test`
- Run specific test suite: `./mvnw test -Dtest=FanoutChunkProcessorTest`
- Tests + coverage floors: `./mvnw verify`
- Mutation testing (slow, ~1 min): `./mvnw -Pmutation verify`

## 5. Implementation Priorities (Time-Boxed Scope)
1. **Interfaces & Contracts over Boilerplate:** Define crisp ports (`RegistryRepository`, `DownstreamEngagementClient`, `CapacityLimiter`). Do NOT pull in heavy Spring Boot starters or real AWS network clients; use clean Java 21 + in-memory test doubles that faithfully enforce DynamoDB conditional expressions.
2. **Purity in Domain:** `PendingTargetResolver` must be a 100% pure, deterministic function with zero I/O.
3. **Explicit Race & Failure Handling:** Model `state_seq`, `graph_epoch`, per-`(publish_id, firm_id, chunk_index)` cursors, partial batch failures, and circuit-breaking explicitly in types and tests.
4. **README Tradeoffs:** Keep `README.md` sharp and opinionated, matching the Design Document's voice.

## 6. Hard Stop Rules for AI
- **NEVER** leak `firm_id` or `engagement_id` into `SummaryPair`, global metrics, or cross-region DTOs.
- **NEVER** overwrite a registry row unconditionally; every mutation requires `state_seq` and/or `graph_epoch` guards.
- **NEVER** use `Thread.sleep()` in production concurrency or tests; use `Clock`, `ScheduledExecutorService`, or deterministic virtual-thread latches.
- **ALWAYS** keep `locked == true` (`finalized`) engagements untouched (`pending_target = null`).