package com.example.platform.dns

data class Ipv4UdpDnsDatagram(
  val sourceIp: ByteArray,
  val destinationIp: ByteArray,
  val sourcePort: Int,
  val destinationPort: Int,
  val dnsPayload: ByteArray,
)

/**
 * Pure IPv4 + UDP (port 53) TUN packet codec so Android TUN L3 packets can be parsed and serialized
 * safely without touching non-DNS traffic or introducing native dependencies.
 */
object Ipv4UdpDnsPacketCodec {

  private const val MIN_IPV4_HEADER = 20
  private const val UDP_HEADER_SIZE = 8
  private const val PROTOCOL_UDP = 17
  const val DNS_PORT = 53

  fun parseDnsQueryPacket(packet: ByteArray, length: Int = packet.size): Ipv4UdpDnsDatagram? {
    if (length < MIN_IPV4_HEADER + UDP_HEADER_SIZE || length > packet.size) {
      return null
    }
    val version = (packet[0].toInt() ushr 4) and 0x0F
    if (version != 4) return null

    val ihlBytes = (packet[0].toInt() and 0x0F) * 4
    if (ihlBytes < MIN_IPV4_HEADER || ihlBytes + UDP_HEADER_SIZE > length) return null

    val totalLength = DnsWireCodec.readU16(packet, 2)
    if (totalLength < ihlBytes + UDP_HEADER_SIZE || totalLength > length) return null

    val fragmentFlagsAndOffset = DnsWireCodec.readU16(packet, 6)
    if ((fragmentFlagsAndOffset and 0x3FFF) != 0) return null

    val protocol = packet[9].toInt() and 0xFF
    if (protocol != PROTOCOL_UDP) return null

    val srcPort = DnsWireCodec.readU16(packet, ihlBytes)
    val dstPort = DnsWireCodec.readU16(packet, ihlBytes + 2)
    if (dstPort != DNS_PORT) return null

    val udpLength = DnsWireCodec.readU16(packet, ihlBytes + 4)
    if (udpLength < UDP_HEADER_SIZE || ihlBytes + udpLength > totalLength) return null

    val payloadLength = udpLength - UDP_HEADER_SIZE
    if (payloadLength > DnsWireCodec.MAX_DNS_PACKET_BYTES) return null

    val srcIp = packet.copyOfRange(12, 16)
    val dstIp = packet.copyOfRange(16, 20)
    val payload = packet.copyOfRange(ihlBytes + UDP_HEADER_SIZE, ihlBytes + udpLength)

    return Ipv4UdpDnsDatagram(
      sourceIp = srcIp,
      destinationIp = dstIp,
      sourcePort = srcPort,
      destinationPort = dstPort,
      dnsPayload = payload,
    )
  }

  fun buildDnsResponsePacket(request: Ipv4UdpDnsDatagram, dnsResponsePayload: ByteArray): ByteArray {
    val udpLen = UDP_HEADER_SIZE + dnsResponsePayload.size
    val totalLen = MIN_IPV4_HEADER + udpLen
    val out = ByteArray(totalLen)

    // IPv4 header (swap source and destination IPs)
    out[0] = 0x45.toByte()
    out[1] = 0x00
    DnsWireCodec.writeU16(out, 2, totalLen)
    DnsWireCodec.writeU16(out, 4, 0)
    DnsWireCodec.writeU16(out, 6, 0x4000) // Don't Fragment
    out[8] = 64.toByte() // TTL
    out[9] = PROTOCOL_UDP.toByte()
    // bytes 10..11 = IPv4 checksum (computed below)
    request.destinationIp.copyInto(out, destinationOffset = 12)
    request.sourceIp.copyInto(out, destinationOffset = 16)

    val ipChecksum = computeOnesComplementChecksum(out, offset = 0, length = MIN_IPV4_HEADER)
    DnsWireCodec.writeU16(out, 10, ipChecksum)

    // UDP header (swap source and destination ports)
    DnsWireCodec.writeU16(out, MIN_IPV4_HEADER, request.destinationPort)
    DnsWireCodec.writeU16(out, MIN_IPV4_HEADER + 2, request.sourcePort)
    DnsWireCodec.writeU16(out, MIN_IPV4_HEADER + 4, udpLen)
    DnsWireCodec.writeU16(out, MIN_IPV4_HEADER + 6, 0) // UDP checksum placeholder
    dnsResponsePayload.copyInto(out, destinationOffset = MIN_IPV4_HEADER + UDP_HEADER_SIZE)

    val udpChecksum =
      computeUdpChecksum(
        srcIp = out.copyOfRange(12, 16),
        dstIp = out.copyOfRange(16, 20),
        udpSegment = out,
        udpOffset = MIN_IPV4_HEADER,
        udpLength = udpLen,
      )
    DnsWireCodec.writeU16(out, MIN_IPV4_HEADER + 6, if (udpChecksum == 0) 0xFFFF else udpChecksum)

    return out
  }

  internal fun computeOnesComplementChecksum(data: ByteArray, offset: Int, length: Int): Int {
    var sum = 0
    var index = offset
    val end = offset + length
    while (index + 1 < end) {
      sum += ((data[index].toInt() and 0xFF) shl 8) or (data[index + 1].toInt() and 0xFF)
      index += 2
    }
    if (index < end) {
      sum += (data[index].toInt() and 0xFF) shl 8
    }
    while ((sum ushr 16) != 0) {
      sum = (sum and 0xFFFF) + (sum ushr 16)
    }
    return sum.inv() and 0xFFFF
  }

  private fun computeUdpChecksum(
    srcIp: ByteArray,
    dstIp: ByteArray,
    udpSegment: ByteArray,
    udpOffset: Int,
    udpLength: Int,
  ): Int {
    var sum = 0
    sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
    sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
    sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
    sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
    sum += PROTOCOL_UDP
    sum += udpLength

    var index = udpOffset
    val end = udpOffset + udpLength
    while (index + 1 < end) {
      sum += ((udpSegment[index].toInt() and 0xFF) shl 8) or (udpSegment[index + 1].toInt() and 0xFF)
      index += 2
    }
    if (index < end) {
      sum += (udpSegment[index].toInt() and 0xFF) shl 8
    }
    while ((sum ushr 16) != 0) {
      sum = (sum and 0xFFFF) + (sum ushr 16)
    }
    return sum.inv() and 0xFFFF
  }
}
