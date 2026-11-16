# Testing Strategy, Observability & AI Governance

## 1. Required Test Coverage (Aligned with Design Doc Appendix E)
Every PR/implementation step must include fast, deterministic JUnit 5 tests covering:
1. **Version Graph Property & Edge-Case Tests (`PendingTargetResolverTest`):**
   - Linear upgrade (`v1 -> v2`), multi-hop accumulation (`v4 -> v5 -> v7`), declined version pinning (`v5` declined, `v7` offered), withdrawal rollback (`v7` withdrawn -> falls back to `v5` if not declined, or `v4` -> empty), market isolation (EU vs CA branches), and finalized/locked immutability.
2. **Race Condition & Conditional Write Tests (`FanoutRaceConditionTest`):**
   - User accepts or declines mid-fan-out (`state_seq` advances -> worker detects conflict, re-reads, recomputes, and does NOT clobber user decision).
   - Publish racing a withdrawal (`graph_epoch` advances -> stale publish fan-out is rejected by `graph_epoch <= :epoch`).
3. **Chaos, Idempotency & Partial Chunk Failure Tests (`FanoutResilienceTest`):**
   - Duplicate SQS message delivery and out-of-order chunk replay converge to identical registry state.
   - Worker failure mid-500-row chunk resumes from checkpointed cursor without duplicating side effects.
   - Registry throttling / downstream 429s open the circuit breaker and halt further pulls.
4. **Capacity Cap & Fairness Tests (`DownstreamCapacityTest`):**
   - Burst of 1,000 engagements across a whale firm (800 items) and 10 small firms (20 items each) proves:
     - Active global in-flight loads **never exceed 100** (hard invariant).
     - Active per-firm in-flight loads **never exceed 10** (hard invariant).
     - Small firms make progress alongside the whale firm without starvation.
5. **Data Residency Guardrail Test (`ResidencyBoundaryTest`):**
   - Assert that `SummaryWarmupPublisher` receives deduplicated `(product, market, from, to, locale)` tuples and serialized payloads contain zero `firmId` or `engagementId` strings.

## 2. Telemetry & Hard-Invariant Observability
- Emit structured span/metric hooks (`FanoutMetrics` / `Tracer` port) carrying required correlation fields: `publish_id`, `product_id`, `template_version`, `firm_id`, `chunk_id`.
- **Never** log engagement workpaper content or sensitive user data.
- Assert hard invariants in code (e.g., `assert inFlightGlobal <= globalCap && inFlightFirm <= perFirmCap`) and emit a high-severity metric (`invariant.violation`) if breached.

## 3. AI Usage & Review Guardrails (For Live Architecture Review)
When using AI to generate code in this repo, enforce these guardrails and document any corrections in `README.md`:
- **Never trust AI on concurrency lock ordering:** Always verify that global + per-firm permits cannot deadlock under contention.
- **Never trust AI with unconditional upserts:** AI frequently defaults to `repository.save(entity)`. Reject any persistence method that lacks `expectedStateSeq` and `graphEpoch` preconditions.
- **Never trust AI to keep cross-plane DTOs clean:** Verify that AI does not add `firmId` to `SummaryPair` "for convenience in logging".
