package fourward.grpc

import com.google.protobuf.ByteString
import fourward.Link
import fourward.NetworkPort
import io.grpc.Status

class NetworkTopology(private val registry: DeviceRegistry) {
  private val peers = mutableMapOf<Endpoint, Endpoint>()

  @Synchronized
  internal fun addLinks(links: List<Link>) {
    val additions = links.map(::validatedLink)
    val newEndpoints = mutableSetOf<Endpoint>()
    for ((a, b) in additions) {
      if (!newEndpoints.add(a)) duplicateEndpoint(a)
      if (!newEndpoints.add(b)) duplicateEndpoint(b)
      if (peers.containsKey(a)) alreadyLinked(a)
      if (peers.containsKey(b)) alreadyLinked(b)
    }
    for ((a, b) in additions) {
      peers[a] = b
      peers[b] = a
    }
  }

  @Synchronized
  internal fun removeLinks(links: List<Link>) {
    val removals = links.map(::validatedLink)
    val removedEndpoints = mutableSetOf<Endpoint>()
    for ((a, b) in removals) {
      if (!removedEndpoints.add(a)) duplicateEndpoint(a)
      if (!removedEndpoints.add(b)) duplicateEndpoint(b)
      val actualA = peers[a] ?: throw notFound(a)
      val actualB = peers[b] ?: throw notFound(b)
      if (actualA != b || actualB != a) {
        throw Status.INVALID_ARGUMENT.withDescription(
            "link ${a.describe()} <-> ${b.describe()} does not match configured topology"
          )
          .asException()
      }
    }
    for ((a, b) in removals) {
      peers.remove(a)
      peers.remove(b)
    }
  }

  @Synchronized
  internal fun listLinks(): List<Link> {
    val seen = mutableSetOf<Endpoint>()
    val links = mutableListOf<Link>()
    for ((a, b) in peers) {
      if (a in seen || b in seen) continue
      seen.add(a)
      seen.add(b)
      links.add(Link.newBuilder().setA(a.toProto()).setB(b.toProto()).build())
    }
    return links
  }

  @Synchronized internal fun snapshot(): TopologySnapshot = TopologySnapshot(peers.toMap())

  private fun validatedLink(link: Link): Pair<Endpoint, Endpoint> {
    val a = validatedEndpoint(link.a)
    val b = validatedEndpoint(link.b)
    if (a == b) {
      throw Status.INVALID_ARGUMENT.withDescription("link endpoint ${a.describe()} is repeated")
        .asException()
    }
    return a to b
  }

  private fun validatedEndpoint(port: NetworkPort): Endpoint {
    val endpoint = Endpoint.fromProto(port)
    registry.get(endpoint.deviceId)
    return endpoint
  }

  private fun alreadyLinked(endpoint: Endpoint): Nothing {
    throw Status.ALREADY_EXISTS.withDescription("endpoint ${endpoint.describe()} is already linked")
      .asException()
  }

  private fun duplicateEndpoint(endpoint: Endpoint): Nothing {
    throw Status.INVALID_ARGUMENT.withDescription(
        "endpoint ${endpoint.describe()} appears more than once in request"
      )
      .asException()
  }

  private fun notFound(endpoint: Endpoint) =
    Status.NOT_FOUND.withDescription("endpoint ${endpoint.describe()} is not linked").asException()
}

internal class TopologySnapshot internal constructor(private val peers: Map<Endpoint, Endpoint>) {
  fun peer(endpoint: Endpoint): Endpoint? = peers[endpoint]
}

internal data class Endpoint(val deviceId: Long, val port: PortKey) {
  fun toProto(): NetworkPort {
    val builder = NetworkPort.newBuilder().setDeviceId(deviceId)
    when (port) {
      is PortKey.Dataplane -> builder.setDataplanePort(port.value)
      is PortKey.P4Runtime -> builder.setP4RtPort(port.value)
    }
    return builder.build()
  }

  fun describe(): String =
    when (port) {
      is PortKey.Dataplane -> "device_id $deviceId dataplane_port ${port.unsignedValue()}"
      is PortKey.P4Runtime -> "device_id $deviceId p4rt_port 0x${port.value.toHex()}"
    }

  companion object {
    fun fromProto(port: NetworkPort): Endpoint {
      if (port.deviceId == 0L) {
        throw Status.INVALID_ARGUMENT.withDescription("device_id must be nonzero").asException()
      }
      val key =
        when (port.portCase) {
          NetworkPort.PortCase.DATAPLANE_PORT -> PortKey.Dataplane(port.dataplanePort)
          NetworkPort.PortCase.P4RT_PORT -> {
            if (port.p4RtPort.isEmpty) {
              throw Status.INVALID_ARGUMENT.withDescription("p4rt_port must be nonempty")
                .asException()
            }
            PortKey.P4Runtime(port.p4RtPort)
          }
          NetworkPort.PortCase.PORT_NOT_SET,
          null ->
            throw Status.INVALID_ARGUMENT.withDescription("NetworkPort must set a port")
              .asException()
        }
      return Endpoint(port.deviceId, key)
    }
  }
}

internal sealed interface PortKey {
  data class Dataplane(val value: Int) : PortKey {
    fun unsignedValue(): Long = value.toLong() and 0xFFFF_FFFFL
  }

  data class P4Runtime(val value: ByteString) : PortKey
}
