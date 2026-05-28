# Compatibility Dashboard

This page is a quick support matrix for 4ward's main surfaces. It summarizes
the current implementation state; it does not replace the detailed notes in
[LIMITATIONS.md](LIMITATIONS.md), [P4RUNTIME_COMPLIANCE.md](P4RUNTIME_COMPLIANCE.md),
or the user-facing [architecture guide](../userdocs/concepts/architectures.md).

Legend:

| Status | Meaning |
|--------|---------|
| **Supported** | Implemented and covered by focused tests or corpus/E2E coverage. |
| **Partial** | Implemented with documented semantic gaps or surface limitations. |
| **Stub** | Accepted, but behavior is intentionally simplified. |
| **Rejected** | Accepted by the protocol/schema but rejected explicitly at runtime. |
| **Out of scope** | Intentionally not modeled by a reference simulator. |
| **External limitation** | Limitation in an upstream dependency before 4ward-specific behavior runs. |
| **External artifact** | Proven outside `main`, but intentionally not carried in this repository. |
| **Unsupported** | Not implemented; see notes before relying on it. |

## Architecture Support

| Area | v1model | PSA | PNA | Notes |
|------|---------|-----|-----|-------|
| Pipeline execution | Supported | Supported | Supported | v1model, PSA, and PNA all have corpus coverage. |
| Parser select ranges/masks | Supported | Supported | Supported | Covered by parser/interpreter and corpus tests. |
| Table match kinds | Supported | Supported | Supported | Exact, LPM, ternary, optional, and range are supported in the simulator/P4Runtime layers. UI coverage is listed separately below. |
| Action selectors | Supported | Supported | N/A | PSA selector hits produce fork-based trace trees; PNA does not use the same selector model. |
| Clone/mirror | Supported | Supported | Partial | PNA `mirror_packet` uses original pre-parse bytes; DPDK mirrors current packet state. |
| Multicast | Supported | Supported | N/A | v1model forks one branch per configured PRE replica and sets `egress_port`/`egress_rid`. |
| Resubmit | Supported | Supported | N/A | Produces trace tree forks where applicable. |
| Recirculate | Supported | Supported | Supported | PNA recirculate tracks pass count. |
| Registers | Supported | Supported | Supported | Reproducer extraction does not capture register state. |
| Counters | Supported | Supported | Stub | PNA counters are no-op stubs. Counters do not influence forwarding. |
| Meters | Stub | Stub | Stub | Meter externs always return GREEN. |
| Checksums | Supported | Supported | Supported | v1model checksum externs and PSA/PNA `InternetChecksum` are implemented. |
| Hash externs | Supported | Supported | Supported | PSA/PNA support documented hash forms and algorithms. |
| Random externs | Unsupported | Supported | Supported | v1model free-function `random()` is not implemented. |
| Digest externs | Unsupported | Stub | Stub | PSA/PNA `Digest.pack` is a no-op; v1model `digest()` is not implemented. |
| v1model `truncate()` | Supported | N/A | N/A | Caps the emitted packet length after deparser; repeated calls keep the shortest requested length. |
| PNA add-on-miss externs | N/A | N/A | Stub | `add_entry`, `allocate_flow_id`, and timer externs are simplified stubs. |
| User-defined extern functions | Unsupported | Unsupported | Unsupported | Architecture libraries define executable extern semantics. |
| TNA | Unsupported | Unsupported | Unsupported | TNA is not implemented. |

## P4Runtime Support

| Surface | Status | Notes |
|---------|--------|-------|
| Client arbitration | Supported | Per-role primary election, demotion/promotion, and disconnect promotion are tested. |
| Role-based access control | Partial | `@p4runtime_role` is enforced; `Role.config` is rejected with UNIMPLEMENTED. |
| `SetForwardingPipelineConfig` | Supported | Includes VERIFY, VERIFY_AND_COMMIT, VERIFY_AND_SAVE/COMMIT, and compatible reconcile. |
| `GetForwardingPipelineConfig` | Supported | P4Info/device-config/cookie response modes are tested. |
| `Capabilities` | Supported | API version and semver format are tested. |
| `Write` atomicity modes | Supported | `CONTINUE_ON_ERROR`, `ROLLBACK_ON_ERROR`, and `DATAPLANE_ATOMIC` behavior is tested. |
| `Read` | Supported | Wildcard, filtered, default entries, action profiles, registers, and mixed entity reads are tested. |
| Table entries | Supported | Match kinds, defaults, const entries, validation, and direct counter/meter data are tested. |
| Action profile members/groups | Supported | CRUD, max size, empty groups, and selector references are tested. |
| One-shot action selector entries | Supported | Validated and covered by table-store/write-validation tests. |
| Counter entries | Supported | Indirect and direct counter read/write are tested. |
| Meter entries | Partial | Config read/write works; per-color meter behavior is out of scope and meters always return GREEN. |
| Register entries | Supported | MODIFY/read semantics and bounds checks are tested. |
| PRE clone sessions | Supported | CRUD and Read support are tested. |
| PRE multicast groups | Supported | CRUD and Read support are tested. |
| `@p4runtime_translation` | Supported | Action params, match fields, and PacketIO metadata are integrated and tested on SAI P4. |
| `@refers_to` | Supported | Match fields, action params, action profiles, one-shot sets, and multicast group references are validated. |
| p4-constraints | Supported | `@entry_restriction` and `@action_restriction` are enforced on Write. |
| `StreamChannel` PacketIO | Supported | PacketOut/PacketIn flow, ordering, and invalid message handling are tested. |
| DigestEntry configuration | Rejected | Digest configuration is rejected with UNIMPLEMENTED. |
| Digest stream delivery/ack | Out of scope | There are no real packet rates to trigger digest delivery. |
| Idle timeout configuration | Rejected | `idle_timeout_ns` is rejected with UNIMPLEMENTED. |
| Idle timeout notifications | Out of scope | There is no wall-clock time model. |
| `ValueSetEntry` | Partial | `MODIFY` and Read are supported; `INSERT` and `DELETE` are rejected with INVALID_ARGUMENT. |
| `ExternEntry` | Rejected | Dedicated entity types are supported instead. |
| P4Data complex table values | Out of scope | No current program exposes complex P4Data in P4Runtime-visible fields. |

## Web Playground Support

| Capability | Status | Notes |
|------------|--------|-------|
| Compile and load P4 | Supported | Uses the same p4c backend and P4Runtime pipeline load path. |
| Table entry INSERT | Supported | Exact, LPM, ternary, optional, and range match inputs are exposed. |
| Table entry DELETE | Supported | Deletes entries created through the UI. |
| Table entry MODIFY | Unsupported | Delete and re-add the entry instead. |
| Default action changes | Unsupported | Use the gRPC API for default-entry updates. |
| Clone sessions | Partial | UI creates single-replica clone sessions only. |
| Multicast groups | Unsupported | Backend supports PRE multicast groups; UI does not expose them. |
| Counters, meters, registers | Unsupported | Backend/P4Runtime support exists; UI does not expose these entities. |
| Action profiles/selectors | Unsupported | Backend/P4Runtime support exists; UI does not expose members or groups. |
| REST Read API | Partial | `GET /api/read` returns table entries only. |
| Packet injection | Supported | Packets are injected directly into the simulator. |
| PacketIO / `StreamChannel` | Unsupported | The UI uses REST, not P4Runtime `StreamChannel`; controller-bound packets are not surfaced. |
| Trace tree visualization | Supported | Trace tab shows the full fork structure. |
| Output packet panel for forked traces | Partial | Shows outputs from all possible worlds flattened together. |
| Packet history | Unsupported | Only the latest packet result is retained. |
| Persistence | Unsupported | Pipeline, table state, and editor contents are in-memory. |
| Multi-user isolation | Unsupported | All browser sessions share one simulator instance. |
| Compilation timeout | Unsupported | The p4c subprocess currently has no timeout. |
| Offline editor assets | Unsupported | Monaco loads from `cdn.jsdelivr.net`. |

## Test and Packaging Surfaces

| Surface | Status | Notes |
|---------|--------|-------|
| STF corpus | Supported | All 186 BMv2-capable corpus tests pass. |
| p4testgen | Supported | Used for symbolic path coverage on selected corpora and SAI P4. |
| BMv2 data-plane differential tests | Supported | All 186 corpus tests compare bit-for-bit against BMv2. |
| P4Runtime BMv2 differential tests | Partial | Scoped table/action-profile scenarios live in `e2e_tests/p4runtime_diff`; broader RPC coverage remains future work. |
| P4Runtime fuzzing | External artifact | [PR #665](https://github.com/smolkaj/4ward/pull/665) proved sonic-pins fuzzer integration, but it is intentionally not merged to avoid reversing the DVaaS dependency direction. |
| Bazel Central Registry consumers | Supported | Published artifacts are intended for normal 4ward library/CLI consumption; BMv2, PI, and p4testgen are dev/test dependencies and are not part of the BCR consumer surface. |
| Upstream p4c compile performance | External limitation | A small number of pathological programs time out or OOM in p4c before 4ward receives IR. |
