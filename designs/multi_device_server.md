# Multi-Device Server

**Status: implemented in PR #788**

Revision note: PR #788 implements the first version of this design. It keeps
per-device state independent, adds the management API, routes P4Runtime by
`device_id`, and keeps single-device use on the default device.

## Problem

Run thousands of independent 4ward devices on one large machine without making
the normal one-device case harder.

Target workload: 1k devices first, 10k as a stretch target, mostly idle, with
roughly 1k-10k entries per device and 100-200 GB of memory available.

## Requirements

1. **Scale.** Support the target workload on one large host.

2. **Equivalence.** Behave like N independent 4ward instances, except for
   shared host resources and one management endpoint.

3. **Simple default.** Starting one 4ward device should not require using the
   management API or thinking about device IDs.

4. **P4Runtime compliance.** Multi-device P4Runtime behavior must follow the
   P4Runtime spec.

5. **Dynamic lifecycle.** Create or delete devices after startup, including 10k
   devices in one RPC.

6. **Simplicity.** Keep the design as simple as the requirements allow.

## Scale Validation

PR #788 validated the main scale assumptions with `MultiDeviceScaleBenchmark`.

| Workload | Result |
|----------|--------|
| 10k devices, SAI middleblock loaded, no table entries | Passed; 8.3 GiB incremental heap, about 0.88 MiB/device. |
| 1k devices, SAI middleblock loaded, 1k IPv6 routes/device | Passed; 1.2 GiB incremental heap, about 1.3 MiB/device. |
| 1k devices, SAI middleblock loaded, 10k IPv6 routes/device | Passed; 8.4 GiB populated heap, about 8.6 MiB/device, populated in 25s. |

These measurements support the first target and the 10k mostly-idle stretch
target. The benchmark uses real P4Runtime writes against SAI P4 and installs
IPv6 LPM route entries plus the prerequisite SAI objects required by
`@refers_to`. We did not run the full extrapolated 10k devices x 10k routes
case because it would install 100 million entries; the measured heap/device
stays well inside the 100-200 GB memory target.

Remaining design assumptions:

- The naive design, N independent 4ward processes with one JVM each, does not
  meet the target workload.
- Contiguous device IDs are natural for the target network-emulation workflow.
- Most multi-device runs use homogeneous server-level options. Per-device CPU
  port, drop port, or validation settings are not needed in the first API.
- Creating empty device contexts is cheap enough that bulk create can be an
  ordinary unary RPC rather than a long-running operation.

Back-of-the-envelope: if one idle 4ward server process costs even 100 MB of
resident memory, 1k devices cost 100 GB before table entries; 10k devices cost
1 TB before table entries. At 200 MB per process, the same math is 200 GB for
1k devices and 2 TB for 10k. That is enough to treat one-JVM-per-device as the
baseline to beat, not the design target.

## P4Runtime constraints

P4Runtime already has the routing key we need:

- Core P4Runtime requests use `uint64 device_id`.
- A multi-device P4Runtime server maintains stream arbitration independently
  per `(device_id, role)`.
- The first `MasterArbitrationUpdate` on a stream selects the device for that
  stream. PacketIn and PacketOut then use the stream's device context.
- Unknown device IDs should fail with `NOT_FOUND`.
- For normal control-plane RPCs, `device_id = 0` is the proto3 unset value, not
  a valid device. The `Capabilities` RPC has special server-wide behavior for
  `device_id = 0`; that exception should not leak into the rest of 4ward.

So 4ward should use P4Runtime `device_id` directly and choose `1` as the
canonical default device.

## Design decisions

The requirements above imply a small set of explicit design decisions. When a
decision is forced by a requirement or by the P4Runtime spec, this table says
so.

| Pressure | Decision | Why |
|----------|----------|-----|
| Scale | Use one JVM and one gRPC server. | Chosen to avoid per-device process and server overhead. |
| Equivalence | Give every live device its own complete `DeviceContext`. | Forced by equivalence to N independent 4ward instances. |
| P4Runtime compliance | Route P4Runtime by standard `uint64 device_id`. | Forced by the P4Runtime spec. |
| P4Runtime compliance | Reject `device_id = 0` on normal P4Runtime RPCs; use `NOT_FOUND` for unknown nonzero IDs. | Forced by the P4Runtime spec. |
| Simple default | Create device `1` at startup and keep existing single-device APIs defaulting to it. | Chosen to preserve the one-device user experience while using a valid P4Runtime device ID. |
| Dynamic lifecycle | Add 4ward-native bulk `CreateDevices` and `DeleteDevices` RPCs. | Forced by dynamic lifecycle; P4Runtime assumes devices already exist. |
| 10k in one RPC | Represent the initial bulk API as `first_device_id + count`. | Chosen as the simplest shape for the target workload. |
| One programming path | Keep pipeline loading in P4Runtime `SetForwardingPipelineConfig`. | Forced by P4Runtime compliance and simplicity. |
| Simplicity | Do not share state, including immutable pipeline artifacts, in the first design. | Chosen to avoid coupling until measurements justify it. |

The two most important simplifying choices are no state sharing and contiguous
bulk lifecycle requests. Both trade future flexibility for a smaller first
implementation with fewer invariants.

## Design

The server owns a `DeviceRegistry`. Each live device is represented by one
`DeviceContext`.

```
gRPC server
  ├─ P4RuntimeService ──┐
  ├─ DataplaneService ──┼── DeviceRegistry
  └─ ManagementService ─┘       │
                                ├─ device 1  ── DeviceContext
                                ├─ device 2  ── DeviceContext
                                └─ device N  ── DeviceContext
```

`DeviceContext` owns one complete per-device 4ward stack: simulator state,
loaded pipeline, packet broker, P4Runtime stream/arbitration state, and
validation state. There is no state sharing between device contexts in the
first design, including immutable pipeline artifacts.

### Default device

Startup creates device `1` by default. Single-device mode is not a separate
implementation; it is the multi-device implementation with exactly one live
device.

### Device lifecycle

Devices have a binary lifecycle: live or absent. There is no disabled,
half-created, or partially initialized state.

Creating a device constructs an empty `DeviceContext`. It does not load a P4
pipeline. The controller programs the new device through the standard
P4Runtime `SetForwardingPipelineConfig` RPC using that device's ID.

Deleting a device atomically removes it from `DeviceRegistry` and releases its
per-device state once existing users drop their references. Future RPCs for that
ID fail with `NOT_FOUND`; active streams for that device close with `NOT_FOUND`
and a message naming the deleted device. In-flight unary RPCs that already
resolved the `DeviceContext` may complete normally.

### P4Runtime routing

Every P4Runtime RPC resolves `device_id` at the service boundary and then
operates on the selected `DeviceContext`. `StreamChannel` follows the P4Runtime
arbitration model: the first arbitration update binds the stream to a device,
and later stream messages use that device context.

### Dataplane routing

The Dataplane service is 4ward-native, so it may keep the single-device default
ergonomic. Dataplane requests should gain an optional `uint64 device_id` field
with this explicit meaning:

- unset or `0`: use the server's default device, currently `1`
- nonzero: use that exact device

This is not a P4Runtime rule and should not be copied into P4Runtime handling.
It is a 4ward-native convenience to preserve the existing single-device API.
Unknown nonzero IDs fail with `NOT_FOUND`.

## Management API

Device creation and deletion are 4ward-native operations. P4Runtime assumes
devices already exist; it should not be stretched into a device-management API.

The management API is bulk-first but deliberately contiguous-only:

```proto
package fourward;

service FourwardManagement {
  rpc CreateDevices(CreateDevicesRequest) returns (CreateDevicesResponse);
  rpc DeleteDevices(DeleteDevicesRequest) returns (DeleteDevicesResponse);
  rpc ListDevices(ListDevicesRequest) returns (ListDevicesResponse);
}

message CreateDevicesRequest {
  uint64 first_device_id = 1;
  uint32 count = 2;
}

message CreateDevicesResponse {}

message DeleteDevicesRequest {
  uint64 first_device_id = 1;
  uint32 count = 2;
}

message DeleteDevicesResponse {}

message ListDevicesRequest {}

message ListDevicesResponse {
  repeated uint64 device_ids = 1;
}
```

Rules: ranges must start at a nonzero device ID, be valid, avoid `uint64`
overflow after widening `count`, and stay within configured per-RPC and
server-wide device limits. Create/delete are atomic at the registry level: if
any requested device cannot be created or deleted, the whole request fails and
the registry is unchanged.

The API intentionally does not include per-device options. New devices inherit
the server's startup defaults for CPU port behavior, drop port behavior, and
validation policy. We should add per-device options only when we have a real
need to run heterogeneous logical devices inside the same server.

The API also intentionally does not include P4 pipeline config. Pipeline loading
belongs to P4Runtime:

1. `CreateDevices(first_device_id = 1, count = 10000)`
2. `SetForwardingPipelineConfig(device_id = 1, ...)`
3. ...
4. `SetForwardingPipelineConfig(device_id = 10000, ...)`

That gives one way to create devices and one way to program them.

## C++ API

The C++ embedding API should wrap the management gRPC service, not reimplement
device lifecycle locally. It should expose the same create/delete/list surface
as the gRPC API. `FourwardServer::DeviceId()` continues to return the default
device ID, `1`, even when more devices are created later.

An ergonomic wrapper mirrors the gRPC API:

```cc
class ManagementClient {
 public:
  struct DeviceRange {
    uint64_t first_device_id;
    uint32_t count;
  };

  explicit ManagementClient(const FourwardServer& server);

  absl::Status CreateDevices(DeviceRange devices);
  absl::Status DeleteDevices(DeviceRange devices);
  absl::StatusOr<std::vector<uint64_t>> ListDevices();
};
```

For dataplane traffic, prefer a device-scoped client so multi-device callers set
the target once:

```cc
DataplaneClient default_dataplane(server);      // uses server.DeviceId()
DataplaneClient device_42(server, 42);          // sets device_id on requests
```

That keeps single-device tests terse while making multi-device tests explicit.

## Concurrency

`DeviceRegistry` synchronization protects lifecycle operations and device
lookup. Once an RPC resolves a `DeviceContext`, work proceeds through that
device's existing synchronization; independent devices should not share a global
packet-processing or write lock.

## Error model

Errors should be strict and actionable:

| Case | Status |
|------|--------|
| `device_id = 0` in P4Runtime control RPC | `INVALID_ARGUMENT` |
| unknown nonzero P4Runtime `device_id` | `NOT_FOUND` |
| stream closed because its device was deleted | `NOT_FOUND` |
| management lifecycle conflict | `ALREADY_EXISTS` or `NOT_FOUND` |
| malformed management range | `INVALID_ARGUMENT` |
| request exceeds per-RPC or server-wide device limit | `RESOURCE_EXHAUSTED` |

Messages should name the device ID or range and the operation that failed.

## Testing

Tests should cover the design contracts, not every implementation detail:

- default device creation and single-device compatibility
- management range validation, atomicity, and lifecycle errors
- P4Runtime routing and error behavior by `device_id`
- Dataplane default-device and explicit-device routing
- deletion behavior for active and future streams
- a scale benchmark that creates 10k devices and installs SAI IPv6 route entries
  through P4Runtime

## Future work

If measurements show memory is the limiting factor, the next optimization is
sharing immutable pipeline artifacts between devices loaded with byte-identical
P4Info and `p4_device_config`. That should be a measured second step, not part
of the initial design.

Other possible extensions:

- arbitrary repeated device IDs for sparse bulk operations
- per-device creation options
- paginated or range-compressed `ListDevices`
