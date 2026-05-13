# P4Runtime Differential Testing

Cross-validates 4ward's P4Runtime gRPC server against BMv2's
`simple_switch_grpc` on a corpus of write/read scenarios. Design
rationale and findings: [`designs/p4runtime_diff.md`](../../designs/p4runtime_diff.md).

## Running

The diff suite is tagged `heavy` so default test runs skip it. To run:

```sh
bazel test //e2e_tests/p4runtime_diff/...
```

No additional system dependencies beyond `libgmp-dev` and `libpcap-dev`
(already installed for BMv2). `dev_dependency = True` in `MODULE.bazel`
keeps the diff suite's deps out of the BCR consumer view.

## What's here

Top-level targets:

- `:simple_switch_grpc` — BMv2's P4Runtime gRPC binary, built from
  upstream source under Bazel. Links `bm_sim` + `libbmpi` + PI's
  `piserver` / `pifeproto`. No Apache Thrift in the runtime.
- `:libbmpi` — bmv2's Thrift-free PI integration adapter
  (sources from `@behavioral_model//:libbmpi_srcs`).
- `:basic_table_fourward` / `:basic_table_bmv2_json` — `basic_table.p4`
  compiled for both backends (scenarios 1-7).
- `:action_selector_fourward` / `:action_selector_bmv2_json` —
  `action_selector_3.p4` compiled for both backends (scenarios 8-10).

Test targets:

- `:ResponseDiffTest` — unit tests for the canonicalize-and-diff
  helpers ([`ResponseDiff.kt`](ResponseDiff.kt)). Always runnable.
- `:P4RuntimeDiffSmokeTest` — spawns both servers, exercises
  `Capabilities` RPC. Skipped via `Assume` if the binary isn't built.
- `:P4RuntimeDiffScenariosTest` — table entry scenarios (encoding,
  error semantics, wildcard reads, default actions).
- `:P4RuntimeDiffActionProfileTest` — action profile scenarios
  (member CRUD, groups, table entries referencing groups).
