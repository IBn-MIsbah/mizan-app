package com.example.platform.dns

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

data class Ipv4TcpPacket(
  val sourceIp: ByteArray,
  val destinationIp: ByteArray,
  val sourcePort: Int,
  val destinationPort: Int,
  val seqNum: Long,
  val ackNum: Long,
  val flags: Int,
  val payload: ByteArray,
) {
  val isFin: Boolean
    get() = (flags and Ipv4TcpDnsCodec.FLAG_FIN) != 0

  val isSyn: Boolean
    get() = (flags and Ipv4TcpDnsCodec.FLAG_SYN) != 0

  val isRst: Boolean
    get() = (flags and Ipv4TcpDnsCodec.FLAG_RST) != 0

  val isAck: Boolean
    get() = (flags and Ipv4TcpDnsCodec.FLAG_ACK) != 0
}

/**
 * Pure IPv4 + TCP packet codec (DNS-over-TCP port 53 + fast RST for unexpected TCP packets).
 */
object Ipv4TcpDnsCodec {

  const val MIN_IPV4_HEADER = 20
  const val MIN_TCP_HEADER = 20
  const val PROTOCOL_TCP = 6
  const val DNS_PORT = 53
  const val MAX_TCP_SEGMENT_PAYLOAD = 1460 // Fits 1500 MTU: 20 IPv4 + 20 TCP + 1460 = 1500

  const val FLAG_FIN = 0x01
  const val FLAG_SYN = 0x02
  const val FLAG_RST = 0x04
  const val FLAG_PSH = 0x08
  const val FLAG_ACK = 0x10

  fun parseTcpPacket(packet: ByteArray, length: Int = packet.size): Ipv4TcpPacket? {
    if (length < MIN_IPV4_HEADER + MIN_TCP_HEADER || length > packet.size) {
      return null
    }
    val version = (packet[0].toInt() ushr 4) and 0x0F
    if (version != 4) return null

    val ihlBytes = (packet[0].toInt() and 0x0F) * 4
    if (ihlBytes < MIN_IPV4_HEADER || ihlBytes + MIN_TCP_HEADER > length) return null

    val totalLength = DnsWireCodec.readU16(packet, 2)
    if (totalLength < ihlBytes + MIN_TCP_HEADER || totalLength > length) return null

    val fragmentFlagsAndOffset = DnsWireCodec.readU16(packet, 6)
    if ((fragmentFlagsAndOffset and 0x3FFF) != 0) return null

    val protocol = packet[9].toInt() and 0xFF
    if (protocol != PROTOCOL_TCP) return null

    val dataOffsetBytes = ((packet[ihlBytes + 12].toInt() ushr 4) and 0x0F) * 4
    if (dataOffsetBytes < MIN_TCP_HEADER || ihlBytes + dataOffsetBytes > totalLength) return null

    val srcPort = DnsWireCodec.readU16(packet, ihlBytes)
    val dstPort = DnsWireCodec.readU16(packet, ihlBytes + 2)
    val seqNum = readU32(packet, ihlBytes + 4)
    val ackNum = readU32(packet, ihlBytes + 8)
    val flags = packet[ihlBytes + 13].toInt() and 0x3F
    val payload = packet.copyOfRange(ihlBytes + dataOffsetBytes, totalLength)

    return Ipv4TcpPacket(
      sourceIp = packet.copyOfRange(12, 16),
      destinationIp = packet.copyOfRange(16, 20),
      sourcePort = srcPort,
      destinationPort = dstPort,
      seqNum = seqNum,
      ackNum = ackNum,
      flags = flags,
      payload = payload,
    )
  }

  fun buildTcpResponsePacket(
    srcIp: ByteArray,
    dstIp: ByteArray,
    srcPort: Int,
    dstPort: Int,
    seqNum: Long,
    ackNum: Long,
    flags: Int,
    payload: ByteArray = ByteArray(0),
    includeMssOption: Boolean = false,
    windowSize: Int = 65535,
  ): ByteArray {
    val tcpHeaderLen = if (includeMssOption) MIN_TCP_HEADER + 4 else MIN_TCP_HEADER
    val tcpLen = tcpHeaderLen + payload.size
    val totalLen = MIN_IPV4_HEADER + tcpLen
    val out = ByteArray(totalLen)

    // IPv4 header
    out[0] = 0x45.toByte()
    out[1] = 0x00
    DnsWireCodec.writeU16(out, 2, totalLen)
    DnsWireCodec.writeU16(out, 4, 0)
    DnsWireCodec.writeU16(out, 6, 0x4000) // Don't Fragment
    out[8] = 64.toByte()
    out[9] = PROTOCOL_TCP.toByte()
    srcIp.copyInto(out, destinationOffset = 12)
    dstIp.copyInto(out, destinationOffset = 16)
    val ipChecksum = Ipv4UdpDnsPacketCodec.computeOnesComplementChecksum(out, 0, MIN_IPV4_HEADER)
    DnsWireCodec.writeU16(out, 10, ipChecksum)

    // TCP header
    DnsWireCodec.writeU16(out, MIN_IPV4_HEADER, srcPort)
    DnsWireCodec.writeU16(out, MIN_IPV4_HEADER + 2, dstPort)
    writeU32(out, MIN_IPV4_HEADER + 4, seqNum)
    writeU32(out, MIN_IPV4_HEADER + 8, ackNum)
    out[MIN_IPV4_HEADER + 12] = ((tcpHeaderLen / 4) shl 4).toByte()
    out[MIN_IPV4_HEADER + 13] = (flags and 0x3F).toByte()
    DnsWireCodec.writeU16(out, MIN_IPV4_HEADER + 14, windowSize)
    DnsWireCodec.writeU16(out, MIN_IPV4_HEADER + 16, 0) // Checksum placeholder
    DnsWireCodec.writeU16(out, MIN_IPV4_HEADER + 18, 0) // Urgent pointer

    if (includeMssOption) {
      out[MIN_IPV4_HEADER + 20] = 0x02 // Kind: MSS
      out[MIN_IPV4_HEADER + 21] = 0x04 // Length: 4
      DnsWireCodec.writeU16(out, MIN_IPV4_HEADER + 22, MAX_TCP_SEGMENT_PAYLOAD)
    }

    if (payload.isNotEmpty()) {
      payload.copyInto(out, destinationOffset = MIN_IPV4_HEADER + tcpHeaderLen)
    }

    val tcpChecksum =
      computeTcpChecksum(
        srcIp = srcIp,
        dstIp = dstIp,
        tcpSegment = out,
        tcpOffset = MIN_IPV4_HEADER,
        tcpLength = tcpLen,
      )
    DnsWireCodec.writeU16(out, MIN_IPV4_HEADER + 16, tcpChecksum)
    return out
  }

  private fun computeTcpChecksum(
    srcIp: ByteArray,
    dstIp: ByteArray,
    tcpSegment: ByteArray,
    tcpOffset: Int,
    tcpLength: Int,
  ): Int {
    var sum = 0
    sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
    sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
    sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
    sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
    sum += PROTOCOL_TCP
    sum += tcpLength

    var index = tcpOffset
    val end = tcpOffset + tcpLength
    while (index + 1 < end) {
      sum += ((tcpSegment[index].toInt() and 0xFF) shl 8) or (tcpSegment[index + 1].toInt() and 0xFF)
      index += 2
    }
    if (index < end) {
      sum += (tcpSegment[index].toInt() and 0xFF) shl 8
    }
    while ((sum ushr 16) != 0) {
      sum = (sum and 0xFFFF) + (sum ushr 16)
    }
    return sum.inv() and 0xFFFF
  }

  internal fun readU32(bytes: ByteArray, offset: Int): Long =
    ((bytes[offset].toLong() and 0xFFL) shl 24) or
      ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
      ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
      (bytes[offset + 3].toLong() and 0xFFL)

  private fun writeU32(bytes: ByteArray, offset: Int, value: Long) {
    bytes[offset] = ((value ushr 24) and 0xFFL).toByte()
    bytes[offset + 1] = ((value ushr 16) and 0xFFL).toByte()
    bytes[offset + 2] = ((value ushr 8) and 0xFFL).toByte()
    bytes[offset + 3] = (value and 0xFFL).toByte()
  }
}

/**
 * Lightweight userspace TCP-to-DNS session manager for local DNS-over-TCP (port 53) fallback
 * queries arriving on the TUN interface (e.g., when a UDP DNS reply sets TC=1 or exceeds MTU).
 * Never logs or persists queried domains.
 */
class TcpDnsSessionManager(
  private val scope: CoroutineScope,
  private val workerDispatcher: CoroutineDispatcher,
  private val packetWriter: (ByteArray) -> Unit,
  private val nowMillisProvider: () -> Long = { System.currentTimeMillis() },
  private val onDnsQuery: suspend (ByteArray) -> ByteArray?,
) {

  private data class SessionKey(
    val clientIp: Int,
    val clientPort: Int,
    val serverIp: Int,
    val serverPort: Int,
  )

  private class TcpSession(
    val key: SessionKey,
    val clientIpBytes: ByteArray,
    val serverIpBytes: ByteArray,
    val clientPort: Int,
    val serverPort: Int,
    var clientNextSeq: Long,
    var serverNextSeq: Long,
    var lastActivityMs: Long,
    val receiveBuffer: ByteArrayOutputStream = ByteArrayOutputStream(),
    var queryDispatched: Boolean = false,
    var serverFinSent: Boolean = false,
    var clientFinReceived: Boolean = false,
    var closed: Boolean = false,
  )

  private val lock = Any()
  private val sessions = LinkedHashMap<SessionKey, TcpSession>()
  private val nextIsn = AtomicLong(0x10203040L)

  fun handlePacket(packet: ByteArray, length: Int = packet.size): Boolean {
    val tcp = Ipv4TcpDnsCodec.parseTcpPacket(packet, length) ?: return false

    if (tcp.destinationPort != Ipv4TcpDnsCodec.DNS_PORT) {
      if (!tcp.isRst) {
        sendStatelessRst(tcp)
      }
      return true
    }

    val key =
      SessionKey(
        clientIp = ipv4ToInt(tcp.sourceIp),
        clientPort = tcp.sourcePort,
        serverIp = ipv4ToInt(tcp.destinationIp),
        serverPort = tcp.destinationPort,
      )
    val now = nowMillisProvider()

    if (tcp.isRst) {
      synchronized(lock) {
        sessions.remove(key)?.closed = true
      }
      return true
    }

    if (tcp.isSyn && !tcp.isAck) {
      val serverIsn = nextIsn.addAndGet(0x01010101L) and 0xFFFFFFFFL
      val clientNext = (tcp.seqNum + 1L) and 0xFFFFFFFFL
      val serverNext = (serverIsn + 1L) and 0xFFFFFFFFL

      synchronized(lock) {
        pruneExpiredLocked(now)
        if (sessions.size >= MAX_CONCURRENT_SESSIONS) {
          val eldest = sessions.entries.firstOrNull()
          if (eldest != null) {
            eldest.value.closed = true
            sessions.remove(eldest.key)
          }
        }
        sessions[key] =
          TcpSession(
            key = key,
            clientIpBytes = tcp.sourceIp,
            serverIpBytes = tcp.destinationIp,
            clientPort = tcp.sourcePort,
            serverPort = tcp.destinationPort,
            clientNextSeq = clientNext,
            serverNextSeq = serverNext,
            lastActivityMs = now,
          )
      }

      packetWriter(
        Ipv4TcpDnsCodec.buildTcpResponsePacket(
          srcIp = tcp.destinationIp,
          dstIp = tcp.sourceIp,
          srcPort = tcp.destinationPort,
          dstPort = tcp.sourcePort,
          seqNum = serverIsn,
          ackNum = clientNext,
          flags = Ipv4TcpDnsCodec.FLAG_SYN or Ipv4TcpDnsCodec.FLAG_ACK,
          includeMssOption = true,
        )
      )
      return true
    }

    var queryToRun: ByteArray? = null
    var querySession: TcpSession? = null
    val packetsToSend = ArrayList<ByteArray>(2)

    synchronized(lock) {
      val session = sessions[key]
      if (session == null || session.closed) {
        sendStatelessRst(tcp)
        return true
      }

      session.lastActivityMs = now

      if (tcp.payload.isNotEmpty()) {
        val seqDelta = (tcp.seqNum.toInt() - session.clientNextSeq.toInt())
        when {
          seqDelta in -65_536..0 -> {
            val overlap = (-seqDelta).coerceAtMost(tcp.payload.size)
            if (overlap < tcp.payload.size) {
              val newLen = tcp.payload.size - overlap
              if (session.receiveBuffer.size() + newLen <= MAX_RECEIVE_BUFFER_BYTES) {
                session.receiveBuffer.write(tcp.payload, overlap, newLen)
                session.clientNextSeq = (session.clientNextSeq + newLen.toLong()) and 0xFFFFFFFFL
              } else {
                session.closed = true
                sessions.remove(key)
                packetsToSend +=
                  Ipv4TcpDnsCodec.buildTcpResponsePacket(
                    srcIp = session.serverIpBytes,
                    dstIp = session.clientIpBytes,
                    srcPort = session.serverPort,
                    dstPort = session.clientPort,
                    seqNum = session.serverNextSeq,
                    ackNum = session.clientNextSeq,
                    flags = Ipv4TcpDnsCodec.FLAG_RST or Ipv4TcpDnsCodec.FLAG_ACK,
                  )
              }
            }
            if (!session.closed) {
              packetsToSend +=
                Ipv4TcpDnsCodec.buildTcpResponsePacket(
                  srcIp = session.serverIpBytes,
                  dstIp = session.clientIpBytes,
                  srcPort = session.serverPort,
                  dstPort = session.clientPort,
                  seqNum = session.serverNextSeq,
                  ackNum = session.clientNextSeq,
                  flags = Ipv4TcpDnsCodec.FLAG_ACK,
                )
            }
          }
          seqDelta in 1..65_536 -> {
            // Out-of-order segment: send duplicate ACK for expected sequence.
            packetsToSend +=
              Ipv4TcpDnsCodec.buildTcpResponsePacket(
                srcIp = session.serverIpBytes,
                dstIp = session.clientIpBytes,
                srcPort = session.serverPort,
                dstPort = session.clientPort,
                seqNum = session.serverNextSeq,
                ackNum = session.clientNextSeq,
                flags = Ipv4TcpDnsCodec.FLAG_ACK,
              )
          }
          else -> Unit
        }
      }

      if (!session.closed && !session.queryDispatched && session.receiveBuffer.size() >= 2) {
        val buffered = session.receiveBuffer.toByteArray()
        val expectedDnsLen = DnsWireCodec.readU16(buffered, 0)
        if (
          expectedDnsLen < DnsWireCodec.DNS_HEADER_SIZE ||
            expectedDnsLen > DnsWireCodec.MAX_DNS_PACKET_BYTES
        ) {
          session.closed = true
          sessions.remove(key)
          packetsToSend +=
            Ipv4TcpDnsCodec.buildTcpResponsePacket(
              srcIp = session.serverIpBytes,
              dstIp = session.clientIpBytes,
              srcPort = session.serverPort,
              dstPort = session.clientPort,
              seqNum = session.serverNextSeq,
              ackNum = session.clientNextSeq,
              flags = Ipv4TcpDnsCodec.FLAG_RST or Ipv4TcpDnsCodec.FLAG_ACK,
            )
        } else if (buffered.size >= 2 + expectedDnsLen) {
          session.queryDispatched = true
          queryToRun = buffered.copyOfRange(2, 2 + expectedDnsLen)
          querySession = session
        }
      }

      if (!session.closed && tcp.isFin && !session.clientFinReceived) {
        val endSeq =
          (tcp.seqNum + tcp.payload.size.toLong()) and 0xFFFFFFFFL
        if (endSeq == session.clientNextSeq) {
          session.clientFinReceived = true
          session.clientNextSeq = (session.clientNextSeq + 1L) and 0xFFFFFFFFL
          packetsToSend +=
            Ipv4TcpDnsCodec.buildTcpResponsePacket(
              srcIp = session.serverIpBytes,
              dstIp = session.clientIpBytes,
              srcPort = session.serverPort,
              dstPort = session.clientPort,
              seqNum = session.serverNextSeq,
              ackNum = session.clientNextSeq,
              flags = Ipv4TcpDnsCodec.FLAG_ACK,
            )
          if (session.serverFinSent) {
            session.closed = true
            sessions.remove(key)
          }
        }
      }
    }

    packetsToSend.forEach(packetWriter)

    val dnsQuery = queryToRun
    val targetSession = querySession
    if (dnsQuery != null && targetSession != null) {
      scope.launch(workerDispatcher) {
        val dnsResponse = runCatching { onDnsQuery(dnsQuery) }.getOrNull()
        sendSessionResponseAndClose(targetSession, dnsResponse)
      }
    }
    return true
  }

  fun closeAll() {
    synchronized(lock) {
      sessions.values.forEach { it.closed = true }
      sessions.clear()
    }
  }

  private fun sendSessionResponseAndClose(session: TcpSession, dnsResponse: ByteArray?) {
    val outboundPackets = ArrayList<ByteArray>(4)
    synchronized(lock) {
      if (session.closed || sessions[session.key] !== session) {
        return
      }
      if (dnsResponse != null && dnsResponse.size <= DnsWireCodec.MAX_TCP_DNS_PACKET_BYTES) {
        val framed = ByteArray(2 + dnsResponse.size)
        DnsWireCodec.writeU16(framed, 0, dnsResponse.size)
        dnsResponse.copyInto(framed, destinationOffset = 2)

        var offset = 0
        while (offset < framed.size) {
          val end = (offset + Ipv4TcpDnsCodec.MAX_TCP_SEGMENT_PAYLOAD).coerceAtMost(framed.size)
          val chunk = framed.copyOfRange(offset, end)
          outboundPackets +=
            Ipv4TcpDnsCodec.buildTcpResponsePacket(
              srcIp = session.serverIpBytes,
              dstIp = session.clientIpBytes,
              srcPort = session.serverPort,
              dstPort = session.clientPort,
              seqNum = session.serverNextSeq,
              ackNum = session.clientNextSeq,
              flags = Ipv4TcpDnsCodec.FLAG_ACK or Ipv4TcpDnsCodec.FLAG_PSH,
              payload = chunk,
            )
          session.serverNextSeq = (session.serverNextSeq + chunk.size.toLong()) and 0xFFFFFFFFL
          offset = end
        }
      }

      if (!session.serverFinSent) {
        outboundPackets +=
          Ipv4TcpDnsCodec.buildTcpResponsePacket(
            srcIp = session.serverIpBytes,
            dstIp = session.clientIpBytes,
            srcPort = session.serverPort,
            dstPort = session.clientPort,
            seqNum = session.serverNextSeq,
            ackNum = session.clientNextSeq,
            flags = Ipv4TcpDnsCodec.FLAG_FIN or Ipv4TcpDnsCodec.FLAG_ACK,
          )
        session.serverNextSeq = (session.serverNextSeq + 1L) and 0xFFFFFFFFL
        session.serverFinSent = true
        session.lastActivityMs = nowMillisProvider()
      }
    }
    outboundPackets.forEach(packetWriter)
  }

  private fun sendStatelessRst(tcp: Ipv4TcpPacket) {
    val rstPacket =
      if (tcp.isAck) {
        Ipv4TcpDnsCodec.buildTcpResponsePacket(
          srcIp = tcp.destinationIp,
          dstIp = tcp.sourceIp,
          srcPort = tcp.destinationPort,
          dstPort = tcp.sourcePort,
          seqNum = tcp.ackNum,
          ackNum = 0L,
          flags = Ipv4TcpDnsCodec.FLAG_RST,
        )
      } else {
        val ackIncrement =
          tcp.payload.size.toLong() + (if (tcp.isSyn) 1L else 0L) + (if (tcp.isFin) 1L else 0L)
        Ipv4TcpDnsCodec.buildTcpResponsePacket(
          srcIp = tcp.destinationIp,
          dstIp = tcp.sourceIp,
          srcPort = tcp.destinationPort,
          dstPort = tcp.sourcePort,
          seqNum = 0L,
          ackNum = (tcp.seqNum + ackIncrement) and 0xFFFFFFFFL,
          flags = Ipv4TcpDnsCodec.FLAG_RST or Ipv4TcpDnsCodec.FLAG_ACK,
        )
      }
    packetWriter(rstPacket)
  }

  private fun pruneExpiredLocked(nowMs: Long) {
    val iterator = sessions.entries.iterator()
    while (iterator.hasNext()) {
      val entry = iterator.next()
      if (nowMs - entry.value.lastActivityMs > SESSION_IDLE_TIMEOUT_MS) {
        entry.value.closed = true
        iterator.remove()
      }
    }
  }

  private fun ipv4ToInt(bytes: ByteArray): Int =
    ((bytes[0].toInt() and 0xFF) shl 24) or
      ((bytes[1].toInt() and 0xFF) shl 16) or
      ((bytes[2].toInt() and 0xFF) shl 8) or
      (bytes[3].toInt() and 0xFF)

  companion object {
    const val MAX_CONCURRENT_SESSIONS = 64
    const val MAX_RECEIVE_BUFFER_BYTES = 65_540
    const val SESSION_IDLE_TIMEOUT_MS = 15_000L
  }
}
