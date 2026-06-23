// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#ifndef SIMULATOR_TRACE_SUMMARY_H_
#define SIMULATOR_TRACE_SUMMARY_H_

#include <iosfwd>

#include "simulator/simulator.pb.h"

namespace fourward {

// Prints a compact, human-oriented trace. This is intentionally not the same
// format as TraceTree::DebugString(): callers use it when the full proto would
// bury the dataplane decisions in low-signal execution detail.
void PrintHumanTraceSummary(std::ostream* os, const TraceTree& trace);

}  // namespace fourward

#endif  // SIMULATOR_TRACE_SUMMARY_H_
