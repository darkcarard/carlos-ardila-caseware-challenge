# Architecture Invariants & Domain Semantics

These rules encode the non-negotiable invariants from the Design Document. Any violation is a Sev-1 bug.

## 1. Data Residency & Cross-Plane Isolation
- **Strict Boundary:** `firm_id` and `engagement_id` MUST NEVER appear in `SummaryPair`, cross-region events, or global telemetry payloads.
- **Enforced by Types:** Cross-region DTOs (e.g., `SummaryWarmupRequest`) must only accept `(productId, market, fromVersion, toVersion, locale)`. Do not pass domain entities containing `firmId` into global publisher ports.
- **Regional Access:** `firm_id` is always the DynamoDB Partition Key (`PK`). Queries are strictly scoped per firm or per sharded `product_id#market#shard(0..15)` (`GSI-2`).

## 2. Version Graph & `pending_target` Derivation
`PendingTargetResolver.resolve(EngagementRecord, VersionGraph)` must be a **pure function** implementing these exact rules:
1. **Finalized / Locked:** If `record.locked()` is `true`, return `Optional.empty()` (never offer updates to signed-off engagements).
2. **Market Lineage:** Only consider versions belonging to the engagement's `(productId, market)` lineage that are reachable descendants of `record.baseVersion()`.
3. **Status Filtering:** Only versions with `status == PUBLISHED` are eligible. `WITHDRAWN` versions are never offered.
4. **Declined Pinning:** Exclude any version present in `record.declinedVersions()`. Declining `v5` keeps `base_version = v4`; when `v7` (a descendant of `v5`) is published, `pending_target` resolves to `v7`.
5. **Latest Selection:** Pick the latest `PUBLISHED` non-declined descendant in the topological/version ordering. If none exists (or the latest equals `base_version`), return `Optional.empty()`.
6. **Withdrawals:** When a version is withdrawn, `graph_epoch` increments and fan-out re-runs. `PendingTargetResolver` naturally falls back to the previous eligible `PUBLISHED` descendant or clears `pending_target` (retracting the notification).

## 3. DynamoDB Conditional Write Semantics
Every repository write must simulate and enforce these exact optimistic concurrency guards:
- **Fan-out Worker Write:**
  - Condition: `state_seq == expectedStateSeq AND graph_epoch <= incomingGraphEpoch`
  - Effect: Updates `pending_target`, `pending_since` (preserved if already pending on the same target, set if newly pending, removed if empty), and sets `graph_epoch = incomingGraphEpoch`.
  - On `ConditionalCheckFailedException`: Do NOT treat as a fatal error. Re-read the row:
    - If `current.graphEpoch() > incomingGraphEpoch`, drop/ack as superseded (a newer withdrawal/publish already won).
    - If `current.stateSeq() != expectedStateSeq`, a user action (`accepted`/`declined`/`finalized`) occurred mid-flight; recompute `pending_target` against the fresh row and retry the conditional write.
- **Backfill / Reconciliation Write (after ~1 min load):**
  - Condition: `attribute_not_exists(state_seq) OR state_seq < loadedStateSeq`
  - Sets `source = BACKFILL` (or `RECONCILED`), `base_version`, `state_seq`, and derives `pending_target` against the current `VersionGraph`.

## 4. Chunking, Checkpointing & Failure Recovery
- **Chunk Size:** Maximum **500 engagements** per SQS fan-out message (`FanoutChunk`).
- **Per-`(publish_id, firm_id)` Cursor:** Workers checkpoint progress per chunk/offset so a 40,000-engagement firm (80 chunks) resumes from the last uncompleted chunk on worker crash or throttling without redoing completed work.
- **Idempotency:** Duplicate SQS deliveries, out-of-order chunks, and DLQ replays must converge to the exact same registry state as a clean run.
- **Poison vs. Transient Failures:**
  - Transient errors (`RegistryThrottledException`, `DownstreamRateLimitException` 429, `5xx`) trigger the circuit breaker and return `PartialRetry` / nack with backoff (never poison-pill healthy data).
  - Unrecoverable schema/graph corruption goes to DLQ after max attempts with structured context (`publishId`, `firmId`, `chunkId`).

## 5. Downstream Capacity Caps (~1-Minute Load Path)
For operations that invoke `DownstreamEngagementClient.loadEngagement(...)` (~1 min/file):
- **Global Concurrency Cap:** Never exceed `maxGlobalConcurrentLoads` (default `100`).
- **Per-Firm Concurrency Cap:** Never exceed `maxPerFirmConcurrentLoads` (default `10`) so a 40,000-file firm cannot starve median 40-file firms.
- **Fair Scheduling:** Use weighted round-robin or fair per-firm queuing across active firms.
- **Kill Switch:** Check an injectable `KillSwitch` supplier before acquiring downstream slots; abort gracefully if tripped.