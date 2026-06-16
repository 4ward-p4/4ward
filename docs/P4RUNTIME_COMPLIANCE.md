# P4Runtime Compliance Matrix

Systematic mapping of [P4Runtime spec](https://p4lang.github.io/p4runtime/spec/main/P4Runtime-Spec.html)
requirements to test coverage. Living document — update when tests are added
or gaps are closed.

This matrix is self-authored: we wrote both the requirements and the tests.
Requirements we misunderstand or overlook won't appear here. To keep ourselves
honest, uncatalogued spec sections and test depth limitations are documented
explicitly at the end.

Legend: **Y** = tested, **N** = not tested, **R** = rejected with
UNIMPLEMENTED (rejection is tested), **N/A** = out of scope

> Internal requirement IDs (e.g. 7.1, 9.10) are stable identifiers for this
> matrix — they do not correspond to P4Runtime spec section numbers. Spec
> section references appear in each section heading.

---

## Part 1: P4Runtime spec requirements

### Client arbitration (spec §5)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 10.1 | Arbitration establishes master | Y | ConformanceTest #12 |
| 10.2 | Higher election_id becomes primary | Y | ConformanceTest #40-41 |
| 10.3 | Non-primary writes → PERMISSION_DENIED | Y | ConformanceTest #42-44 |
| 10.4 | All controllers may read regardless of role | Y | ConformanceTest #45 |
| 10.5 | Demotion notification to displaced primary | Y | ConformanceTest #73 |
| 10.6 | Automatic promotion on primary disconnect | Y | ConformanceTest #74 |
| 10.7 | Zero election_id: backup semantics (cannot be primary) | Y | ConformanceTest #72 |
| 10.8 | Per-role primary election | Y | ConformanceTest #84, #86 |
| 10.9 | Role-based write access control | Y | ConformanceTest #87, #89 |
| 10.10 | Role-based read access control (specific + wildcard) | Y | ConformanceTest #88, #88a |
| 10.11 | Role.config rejected | R | ConformanceTest #85 |
| 10.12 | No ghost primary after disconnect | Y | ConformanceTest #90 |
| 10.13 | Role change clears old role's primary | Y | ConformanceTest #91 |

### P4Info message (spec §6)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 6.1 | P4Info IDs have consistent type prefixes per entity kind | Y | ConformanceTest #107 |
| 6.2 | Table/action/profile IDs validated on write | Y | WriteValidatorTest, WriteErrorTest |

### Default-valued fields (spec §8.1)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 8.01 | Zero-valued exact match field accepted and round-trips | Y | ConformanceTest #108 |
| 8.02 | Zero-valued action parameter round-trips | Y | ConformanceTest #109 |

### Read-write symmetry (spec §8.2)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 8.10 | Table entry read-write symmetry (match fields, action) | Y | ConformanceTest #110 |
| 8.11 | Action profile member read-write symmetry | Y | ConformanceTest #111 |
| 8.12 | Action profile group read-write symmetry | Y | ConformanceTest #112 |
| 8.13 | Register entry read-write symmetry | Y | ConformanceTest #113 |

### Bytestring encoding (spec §8.3)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 8.1 | Bytestrings must be exactly ceil(bitwidth/8) bytes | Y | WriteValidatorTest |
| 8.2 | Ternary value and mask must have equal byte width | Y | WriteValidatorTest |
| 8.3 | LPM value byte width matches field bitwidth | Y | WriteValidatorTest |
| 8.4 | Range low/high byte width matches field bitwidth | Y | WriteValidatorTest |
| 8.4a | Range low must be ≤ high | Y | WriteValidatorTest |
| 8.5 | Ternary: masked bits must be zero in value | Y | WriteValidatorTest |
| 8.6 | LPM: bits beyond prefix_len must be zero | Y | WriteValidatorTest |
| 8.7 | Read responses zero-pad bytestrings to ceil(bitwidth/8) | Y | ConformanceTest #63 |

### P4Data complex types (spec §8.4)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 8.20 | Struct/header/enum P4Data values in table entries | N/A | No current program uses complex P4Data in P4Runtime-visible fields |

### Table entries (spec §9.1)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 9.1 | Write without pipeline → FAILED_PRECONDITION | Y | WriteErrorTest |
| 9.2 | INSERT succeeds for new entry | Y | ConformanceTest #4 |
| 9.3 | INSERT duplicate → ALREADY_EXISTS | Y | WriteErrorTest |
| 9.4 | MODIFY existing entry succeeds | Y | ConformanceTest #5 |
| 9.5 | MODIFY non-existent → NOT_FOUND | Y | WriteErrorTest |
| 9.6 | DELETE existing entry succeeds | Y | ConformanceTest #6 |
| 9.7 | DELETE non-existent → NOT_FOUND | Y | WriteErrorTest |
| 9.8 | Unknown table_id → NOT_FOUND | Y | WriteErrorTest, WriteValidatorTest |
| 9.9 | Entry lifecycle INSERT→MODIFY→DELETE→DELETE(fail) | Y | WriteErrorTest |
| 9.10 | Unknown action_id → INVALID_ARGUMENT | Y | WriteValidatorTest, WriteErrorTest |
| 9.11 | Action not in table's action_refs → INVALID_ARGUMENT | Y | WriteValidatorTest |
| 9.12 | Wrong param count → INVALID_ARGUMENT | Y | WriteValidatorTest, WriteErrorTest |
| 9.13 | Unknown param_id → INVALID_ARGUMENT | Y | WriteValidatorTest |
| 9.14 | Param byte width mismatch → INVALID_ARGUMENT | Y | WriteValidatorTest |
| 9.15 | Unknown match field_id → INVALID_ARGUMENT | Y | WriteValidatorTest |
| 9.16 | Wrong match kind (e.g. ternary on exact field) → INVALID_ARGUMENT | Y | WriteValidatorTest, WriteErrorTest |
| 9.17 | Match field byte width mismatch → INVALID_ARGUMENT | Y | WriteValidatorTest, WriteErrorTest |
| 9.18 | Missing required exact match field → INVALID_ARGUMENT | Y | WriteValidatorTest, WriteErrorTest |
| 9.19 | Duplicate match field ID → INVALID_ARGUMENT | Y | WriteValidatorTest |
| 9.20 | Priority > 0 required for ternary/range/optional tables | Y | WriteValidatorTest |
| 9.21 | Priority must be 0 for exact-only tables | Y | WriteValidatorTest, WriteErrorTest |
| 9.22 | DELETE skips content validation | Y | WriteValidatorTest |
| 9.23 | Zero-bitwidth params skipped (sdn_string) | Y | WriteValidatorTest |
| 9.24 | Constant table rejects INSERT/MODIFY/DELETE | Y | WriteValidatorTest |
| 9.25 | Default entry: match fields must be absent | Y | WriteValidatorTest |
| 9.26 | Default entry: MODIFY semantics | Y | WriteValidatorTest |
| 9.27 | RESOURCE_EXHAUSTED when table is full | Y | TableStoreTest, ConformanceTest |
| 9.28 | Write batch: updates applied in order | Y | ConformanceTest #39 |
| 9.29 | Direct counter/meter data in table entry reads | Y | ConformanceTest #68-69 |
| 9.30a | Const entries populated at load time and readable | Y | ConformanceTest #65, #115 |
| 9.30b | INSERT into const table → INVALID_ARGUMENT | Y | ConformanceTest #114, WriteValidatorTest |
| 9.30c | MODIFY const table entry → INVALID_ARGUMENT | Y | ConformanceTest #116, WriteValidatorTest |
| 9.30d | idle_timeout_ns rejected (no wall-clock time) | R | ConformanceTest #117, WriteErrorTest |

### Action profiles (spec §9.2)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 9.30 | Insert action profile member succeeds | Y | ConformanceTest #26 |
| 9.31 | Duplicate member → ALREADY_EXISTS | Y | ConformanceTest #27 |
| 9.32 | Delete member succeeds | Y | ConformanceTest #28 |
| 9.33 | Insert action profile group succeeds | Y | ConformanceTest #29 |
| 9.34 | Modify group with different members | Y | ConformanceTest #30 |
| 9.35 | Delete non-existent group → NOT_FOUND | Y | ConformanceTest #31 |
| 9.36 | One-shot action selector | Y | TableStoreTest, WriteValidatorTest |
| 9.37 | Group max_size enforcement | Y | TableStoreTest |
| 9.38 | Unknown action_profile_id on member → NOT_FOUND | Y | WriteValidatorTest, WriteErrorTest |
| 9.39 | Member action_id validated against profile's tables | Y | WriteValidatorTest, WriteErrorTest |
| 9.40 | Member action params validated | Y | WriteValidatorTest |
| 9.41 | Unknown action_profile_id on group → NOT_FOUND | Y | WriteValidatorTest, WriteErrorTest |
| 9.42 | Profile size (total members+groups) enforcement | Y | TableStoreTest |
| 9.43a | Empty group (zero members) accepted | Y | ConformanceTest #118 |
| 9.43b | Group modify replaces member list entirely | Y | ConformanceTest #119 |
| 9.43c | watch_port field | N/A | Not applicable — no physical ports in a reference simulator |

### Counters & meters (spec §9.3, §9.4)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 9.50 | Direct counter read/write | Y | TableStoreTest, ConformanceTest #52-54 |
| 9.51 | Indirect counter read/write | Y | TableStoreTest, ConformanceTest #46-48 |
| 9.52 | Direct meter config | Y | TableStoreTest, ConformanceTest #55-57 |
| 9.53 | Indirect meter config | Y | TableStoreTest, ConformanceTest #49-51 |
| 9.54 | MeterCounterData (per-color packet counts) | N/A | Requires hardware-level metering; reference simulator implements config only |

### Packet replication engine (spec §9.5)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 9.60 | Multicast group CRUD | Y | ConformanceTest (via PRE entries) |
| 9.61 | Clone session CRUD | Y | ConformanceTest (via PRE entries) |
| 9.62 | PRE INSERT existing → ALREADY_EXISTS | Y | TableStoreTest |
| 9.63 | PRE MODIFY non-existent → NOT_FOUND | Y | TableStoreTest |
| 9.64 | PRE DELETE non-existent → NOT_FOUND | Y | TableStoreTest |
| 9.65 | PRE entries readable via Read RPC | Y | TableStoreTest |

### Other entity types (spec §9.6, §9.7, §9.8, §9.9)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 9.40 | MODIFY register entry persists value | Y | TableStoreTest, ConformanceTest #32 |
| 9.41 | INSERT register → INVALID_ARGUMENT | Y | TableStoreTest, ConformanceTest #34 |
| 9.42 | DELETE register → INVALID_ARGUMENT | Y | TableStoreTest |
| 9.43 | Out-of-bounds index → error | Y | TableStoreTest |
| 9.44 | Unknown register_id → NOT_FOUND | Y | TableStoreTest |
| 9.70 | ValueSetEntry | P | MODIFY and Read supported; INSERT/DELETE rejected with INVALID_ARGUMENT; TableStoreTest, ConformanceTest #81 |
| 9.71 | DigestEntry configuration | R | Rejected with UNIMPLEMENTED; ConformanceTest #83 |
| 9.72 | ExternEntry | R | Rejected with UNIMPLEMENTED; ConformanceTest #82 |

### Write RPC (spec §12)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 9.80 | CONTINUE_ON_ERROR: process all updates, per-update errors | Y | WriteErrorTest |
| 9.81 | ROLLBACK_ON_ERROR: undo on failure | Y | WriteErrorTest |
| 9.82 | DATAPLANE_ATOMIC | Y | Handled as ROLLBACK_ON_ERROR; WriteErrorTest |
| 9.83 | Per-update error reporting in WriteResponse | Y | WriteErrorTest |
| 9.90 | device_id validated on Write | Y | ConformanceTest #60 |
| 9.91 | Write allowed without prior arbitration | Y | ConformanceTest (implicit) |
| 9.92 | Per-update error details include canonical_code and message | Y | ConformanceTest #120-121, WriteErrorTest |
| 9.93 | Cross-entity-type batch ordering preserves dependencies | Y | ConformanceTest #122 |

### Read RPC (spec §13)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 11.1 | Read without pipeline → FAILED_PRECONDITION | Y | ConformanceTest #11 |
| 11.2 | Wildcard read returns all table entries | Y | ConformanceTest #9 |
| 11.3 | Read empty table returns empty | Y | ConformanceTest #10 |
| 11.4 | Read with table_id filter | Y | ConformanceTest #20 |
| 11.5 | Empty entity list → no results (spec §13.2) | Y | ConformanceTest #21 |
| 11.6 | Per-entry read with match key | Y | ConformanceTest #23-25 |
| 11.7 | Wildcard read for action profiles | Y | ConformanceTest #35 |
| 11.8 | Wildcard read for registers | Y | TableStoreTest |
| 11.9 | Read unwritten register returns zero | Y | ConformanceTest #33 |
| 11.10 | Default entry included in wildcard table reads | Y | ConformanceTest #64 |
| 11.11 | device_id validated on Read | Y | ConformanceTest #61 |
| 11.12 | Read batch with multiple entity types | Y | ConformanceTest #123-124 |
| 11.13 | Read and write serialized by mutex (atomicity) | Y | P4RuntimeService (by design; no concurrent test) |

### SetForwardingPipelineConfig (spec §7, §14)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 7.1 | Load valid pipeline succeeds | Y | ConformanceTest #1 |
| 7.2 | Pipeline reload replaces previous | Y | ConformanceTest #2 |
| 7.3 | Empty/invalid config → INVALID_ARGUMENT | Y | ConformanceTest #3 |
| 7.4 | Invalid p4_device_config bytes → INVALID_ARGUMENT | Y | ConformanceTest #37 |
| 7.5 | Missing p4_device_config → INVALID_ARGUMENT | Y | ConformanceTest #38 |
| 7.6 | Requires primary controller when arbitration is active | Y | ConformanceTest #66 |
| 7.7 | VERIFY action validates without applying | Y | ConformanceTest #75 |
| 7.8 | VERIFY_AND_COMMIT applies atomically | Y | ConformanceTest #1 |
| 7.9 | VERIFY_AND_SAVE + COMMIT two-phase pipeline load | Y | ConformanceTest #77-78 |
| 7.9a | RECONCILE_AND_COMMIT preserves entries on compatible reload | Y | ConformanceTest #80, #126-130 |
| 7.9b | RECONCILE_AND_COMMIT rejects incompatible populated tables | Y | ConformanceTest #127 |
| 7.10 | Cookie stored and returned | Y | ConformanceTest #59 |
| 7.11 | Pipeline reload clears table entries | Y | ConformanceTest #58 |
| 7.12 | Const table entries populated at load time | Y | ConformanceTest #65 |

### GetForwardingPipelineConfig (spec §15)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 12.1 | Returns loaded p4info | Y | ConformanceTest #16 |
| 12.2 | Without pipeline → FAILED_PRECONDITION | Y | ConformanceTest #17 |
| 12.3 | P4INFO_AND_COOKIE omits device config | Y | ConformanceTest #18 |
| 12.4 | DEVICE_CONFIG_AND_COOKIE omits p4info | Y | ConformanceTest #22 |
| 12.5 | COOKIE_ONLY returns empty config | Y | ConformanceTest #36 |
| 12.6 | device_id validated | Y | ConformanceTest #62 |

### StreamChannel (spec §16)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 14.1 | PacketOut processed, PacketIn returned | Y | ConformanceTest #13 |
| 14.2 | PacketOut with table entries forwards correctly | Y | ConformanceTest #14 |
| 14.3 | Multiple packets preserve ordering | Y | ConformanceTest #15 |
| 14.4 | StreamError on invalid stream message | Y | ConformanceTest #67 |
| 14.5 | Digest delivery | N/A | Out of scope — no control-plane digest queue or stream-delivery model |
| 14.6 | DigestListAck handling | N/A | Digests out of scope |
| 14.7 | Idle timeout notifications | N/A | Out of scope — no wall-clock time in a reference simulator |
| 14.8 | Architecture-specific `other` messages | Y | Rejected with StreamError; ConformanceTest #67 |

### Capabilities (spec §17)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 13.1 | Returns API version | Y | ConformanceTest #19 |
| 13.2 | Version follows semver format (major.minor) | Y | ConformanceTest #125 |

### @p4runtime_translation (spec §8.4.6, §18)

User-defined types with a `@p4runtime_translation` annotation use a
`translated_type` in p4info (`P4NewTypeTranslation` in `p4types.proto`). The
spec defines the annotation and the p4info representation but leaves the actual
mapping mechanism — how SDN values are assigned to data-plane values —
underspecified. 4ward ships a built-in translation engine with explicit,
auto-allocate, and hybrid modes.

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 15.1 | sdn_bitwidth narrowing on write | Y | TranslationTest |
| 15.2 | sdn_bitwidth widening on read | Y | TranslationTest |
| 15.3 | sdn_string encoding/decoding | Y | TranslationTest, SaiP4E2ETest |
| 15.4 | Non-translated fields pass through | Y | TranslationTest |
| 15.5 | End-to-end forwarding with translated ports | Y | TranslationTest |
| 15.6 | sdn_string write/read round-trip with SAI P4 | Y | SaiP4E2ETest |

### Previously uncatalogued sections — now resolved

All 16 spec sections that were previously uncatalogued have been reviewed,
with requirements either added to the main matrix above, marked N/A with
justification, or noted as out-of-scope. Key decisions:

- **§6 P4Info**: ID prefix consistency tested (ConformanceTest #107).
- **§8.1 Default-valued fields**: Zero-valued match/param round-trips tested (#108-109).
- **§8.2 Read-write symmetry**: Explicit round-trip tests for table entries, members, groups, registers (#110-113).
- **§8.4 P4Data complex types**: N/A — no current program uses complex P4Data in P4Runtime-visible fields.
- **§9.1.5 Preinitialized tables**: Const entry load, readback, and immutability tested (#114-116).
- **§9.1.8 Idle timeout**: Rejected with UNIMPLEMENTED; tested (#117).
- **§9.2.2 Action profile groups**: Empty groups and full member replacement tested (#118-119); watch_port N/A.
- **§9.4.3 MeterCounterData**: N/A — per-color counting requires hardware-level metering.
- **§10 Error reporting**: Per-update structured errors tested (#120-121).
- **§11 Atomicity**: Guaranteed by design (single mutex serializes all reads/writes).
- **§12.1 Write batch ordering**: Cross-entity-type dependency ordering tested (#122).
- **§13.3 Read batch**: Multiple entity types in one Read tested (#123-124).
- **§13.4 Read/Write parallelism**: Serialized by mutex; no concurrent test (by design, not a gap).
- **§18 PSA portability**: Covered by existing @p4runtime_translation tests (15.1-15.6); PSA-specific port-as-index guidance N/A (4ward uses explicit translation mappings).
- **§19 Versioning**: Semver format tested (#125); version negotiation not applicable (single version).
- **§20 Non-PSA extensions**: N/A — no architecture-specific extensions implemented yet.

---

## Part 2: project extensions

These are real, valuable capabilities tested end-to-end — but they are not part
of the P4Runtime spec. They are counted separately from spec compliance.

### @refers_to

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 17.1 | Match field referential integrity on INSERT/MODIFY | Y | ReferenceValidatorTest |
| 17.2 | Action param referential integrity | Y | ReferenceValidatorTest |
| 17.3 | Action profile member referential integrity | Y | ReferenceValidatorTest |
| 17.4 | One-shot action set referential integrity | Y | ReferenceValidatorTest |
| 17.5 | builtin::multicast_group_table references | Y | ReferenceValidatorTest |
| 17.6 | DELETE bypasses referential checks | Y | ReferenceValidatorTest |

### p4-constraints

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 18.1 | @entry_restriction validated on Write | Y | ConstraintTest |
| 18.2 | Violation → INVALID_ARGUMENT with constraint text | Y | ConstraintTest |
| 18.3 | DELETE bypasses constraint validation | Y | ConstraintTest |
| 18.4 | Pipeline without constraints works normally | Y | ConstraintTest |

---

## Summary

### P4Runtime spec requirements

| Category | Y | R | N | N/A |
|----------|---|---|---|-----|
| P4Info message (§6) | 2 | 0 | 0 | 0 |
| Default-valued fields (§8.1) | 2 | 0 | 0 | 0 |
| Read-write symmetry (§8.2) | 4 | 0 | 0 | 0 |
| Bytestring encoding (§8.3) | 8 | 0 | 0 | 0 |
| P4Data complex types (§8.4) | 0 | 0 | 0 | 1 |
| Client arbitration (§5) | 12 | 1 | 0 | 0 |
| Table entries (§9.1) | 32 | 1 | 0 | 0 |
| Action profiles (§9.2) | 15 | 0 | 0 | 1 |
| Counters & meters (§9.3, §9.4) | 4 | 0 | 0 | 1 |
| Packet replication engine (§9.5) | 6 | 0 | 0 | 0 |
| Other entity types (§9.6–§9.9) | 5 | 3 | 0 | 0 |
| Write RPC (§10, §12) | 8 | 0 | 0 | 0 |
| Read RPC (§11, §13) | 13 | 0 | 0 | 0 |
| SetForwardingPipelineConfig (§14) | 14 | 0 | 0 | 0 |
| GetForwardingPipelineConfig (§15) | 6 | 0 | 0 | 0 |
| StreamChannel (§16) | 5 | 0 | 0 | 3 |
| Capabilities & versioning (§17, §19) | 2 | 0 | 0 | 0 |
| @p4runtime_translation (§8.4.6, §18) | 6 | 0 | 0 | 0 |
| Non-PSA extensions (§20) | 0 | 0 | 0 | 0 |
| **Spec total** | **144** | **5** | **0** | **6** |

### Project extensions (not in P4Runtime spec)

| Category | Tested |
|----------|--------|
| @refers_to | 6 |
| p4-constraints | 4 |
| **Extensions total** | **10** |

**Grand total: 144 spec implemented + 5 spec rejected + 6 N/A + 10 extensions
= 165 catalogued. All previously uncatalogued spec sections are now resolved.**

---

## Test depth limitations

- **No concurrency testing.** All tests are single-threaded. Concurrent writes,
  reads during pipeline reload, and multi-controller races are untested.
- **Single-table test fixtures.** Most ConformanceTest and WriteErrorTest
  scenarios use `basic_table.p4` (one table, one exact match field, two
  actions). Action profile tests use `action_selector_3.p4`. Multi-table
  programs with different match types are not exercised at the P4Runtime
  level. SAI P4 E2E tests partially compensate for this.
- **Error detail verification is shallow.** Only 1 test (CONTINUE_ON_ERROR)
  inspects the structured `p4.v1.Error` protos in `grpc-status-details-bin`.
  The other ~100 tests check gRPC status codes only, not P4Runtime-specific
  error details.
- **No encoding edge cases.** Untested: `bit<1>`, non-byte-aligned bitwidths
  (`bit<7>`), very wide fields (`bit<256>`), values with leading zero bytes.
