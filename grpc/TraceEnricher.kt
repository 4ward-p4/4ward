package fourward.grpc

import fourward.TraceEvent
import fourward.TraceTree
import p4.v1.P4RuntimeOuterClass.Entity

/**
 * Enriches a [TraceTree] with P4Runtime representations alongside the raw dataplane values produced
 * by the simulator.
 *
 * The simulator is P4RT-agnostic — it emits only dataplane values (numeric ports, untranslated
 * bytes). This enricher walks the tree and populates the optional `p4rt_*` fields using a
 * [TypeTranslator], so consumers (gRPC clients, viewers) can display human-readable names like
 * `"Ethernet0"` instead of raw port numbers.
 *
 * Called at the service boundary ([DataplaneService]) after the simulator produces the trace.
 */
object TraceEnricher {

  /** Returns an enriched copy of [trace], or [trace] itself if no enrichment applies. */
  fun enrich(trace: TraceTree, translator: TypeTranslator): TraceTree {
    val pt = translator.portTranslator
    if (pt == null && !translator.hasTranslations) return trace
    return enrichTree(trace, pt, translator)
  }

  private fun enrichTree(
    tree: TraceTree,
    pt: PortTranslator?,
    translator: TypeTranslator,
  ): TraceTree {
    // Defer toBuilder() until the first enrichment is confirmed, avoiding a deep copy
    // when the trace has no enrichable events (common for pipelines with translations
    // but traces that only hit non-translated tables).
    var builder: TraceTree.Builder? = null
    fun lazyBuilder(): TraceTree.Builder {
      if (builder == null) builder = tree.toBuilder()
      return builder!!
    }

    for (i in 0 until tree.eventsCount) {
      val enriched = enrichEvent(tree.getEvents(i), pt, translator)
      if (enriched != null) {
        lazyBuilder().setEvents(i, enriched)
      }
    }

    when (tree.outcomeCase) {
      TraceTree.OutcomeCase.FORK_OUTCOME -> {
        val fork = tree.forkOutcome
        for (i in 0 until fork.branchesCount) {
          val branch = fork.getBranches(i)
          val enrichedSubtree = enrichTree(branch.subtree, pt, translator)
          if (enrichedSubtree !== branch.subtree) {
            lazyBuilder()
              .forkOutcomeBuilder
              .setBranches(i, branch.toBuilder().setSubtree(enrichedSubtree))
          }
        }
      }
      TraceTree.OutcomeCase.PACKET_OUTCOME -> {
        if (pt != null && tree.packetOutcome.hasOutput()) {
          val output = tree.packetOutcome.output
          val p4rtPort = pt.dataplaneToP4rt(output.dataplaneEgressPort)
          if (p4rtPort != null) {
            lazyBuilder().packetOutcomeBuilder.outputBuilder.setP4RtEgressPort(p4rtPort)
          }
        }
      }
      TraceTree.OutcomeCase.OUTCOME_NOT_SET,
      null -> {}
    }

    return builder?.build() ?: tree
  }

  private fun enrichEvent(
    event: TraceEvent,
    pt: PortTranslator?,
    translator: TypeTranslator,
  ): TraceEvent? {
    if (event.hasPacketIngress() && pt != null) {
      val ingress = event.packetIngress
      val p4rtPort = pt.dataplaneToP4rt(ingress.dataplaneIngressPort) ?: return null
      return event
        .toBuilder()
        .setPacketIngress(ingress.toBuilder().setP4RtIngressPort(p4rtPort))
        .build()
    }

    if (event.hasCloneSessionLookup() && pt != null) {
      val csl = event.cloneSessionLookup
      if (!csl.sessionFound) return null
      val p4rtPort = pt.dataplaneToP4rt(csl.dataplaneEgressPort) ?: return null
      return event
        .toBuilder()
        .setCloneSessionLookup(csl.toBuilder().setP4RtEgressPort(p4rtPort))
        .build()
    }

    if (event.hasTableLookup() && translator.hasTranslations) {
      val tl = event.tableLookup
      if (!tl.hit || !tl.hasMatchedEntry()) return null
      val entity = Entity.newBuilder().setTableEntry(tl.matchedEntry).build()
      val translated = translator.translateForRead(entity)
      if (translated === entity) return null
      return event
        .toBuilder()
        .setTableLookup(tl.toBuilder().setP4RtMatchedEntry(translated.tableEntry))
        .build()
    }

    return null
  }
}
