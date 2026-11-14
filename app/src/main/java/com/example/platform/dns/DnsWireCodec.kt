package com.example.platform.dns

enum class DnsRecordType(val code: Int) {
  A(1),
  AAAA(28),
  HTTPS(65),
  SVCB(64),
  OTHER(-1);

  companion object {
    fun fromCode(code: Int): DnsRecordType =
      entries.firstOrNull { it.code == code } ?: OTHER
  }
}

enum class DnsParseError {
  EMPTY_OR_TOO_SHORT,
  OVERSIZED,
  NOT_A_QUERY,
  NO_QUESTIONS,
  TRUNCATED_PACKET,
  COMPRESSION_POINTER_LOOP,
  COMPRESSION_OUT_OF_BOUNDS,
  INVALID_LABEL,
  DOMAIN_TOO_LONG,
}

data class ParsedDnsQuery(
  val transactionId: Int,
  val flags: Int,
  val qname: String,
  val qnameLabels: List<String>,
  val qtypeCode: Int,
  val recordType: DnsRecordType,
  val qclassCode: Int,
  val ednsUdpPayloadSize: Int?,
  val questionEndOffset: Int,
) {
  val isInternetClass: Boolean
    get() = qclassCode == DnsWireCodec.QCLASS_IN
}

sealed interface DnsParseResult {
  data class Valid(val query: ParsedDnsQuery) : DnsParseResult

  data class Malformed(val error: DnsParseError, val transactionId: Int? = null, val flags: Int? = null) :
    DnsParseResult
}

/**
 * Bounded RFC 1035 / EDNS0 DNS wire parser & response builder (UDP-only local processor).
 */
object DnsWireCodec {

  const val DNS_HEADER_SIZE = 12
  const val MAX_DNS_PACKET_BYTES = 4096
  const val MAX_COMPRESSION_JUMPS = 16
  const val MAX_QNAME_LENGTH = 253
  const val MAX_LABEL_LENGTH = 63

  const val QCLASS_IN = 1
  const val RR_TYPE_OPT = 41

  const val RCODE_NOERROR = 0
  const val RCODE_FORMERR = 1
  const val RCODE_SERVFAIL = 2
  const val RCODE_NXDOMAIN = 3

  fun parseQuery(packet: ByteArray): DnsParseResult {
    if (packet.size < DNS_HEADER_SIZE) {
      return DnsParseResult.Malformed(DnsParseError.EMPTY_OR_TOO_SHORT)
    }
    if (packet.size > MAX_DNS_PACKET_BYTES) {
      return DnsParseResult.Malformed(DnsParseError.OVERSIZED)
    }

    val txId = readU16(packet, 0)
    val flags = readU16(packet, 2)
    val isResponse = (flags and 0x8000) != 0
    if (isResponse) {
      return DnsParseResult.Malformed(DnsParseError.NOT_A_QUERY, txId, flags)
    }
    val qdCount = readU16(packet, 4)
    if (qdCount < 1) {
      return DnsParseResult.Malformed(DnsParseError.NO_QUESTIONS, txId, flags)
    }
    val anCount = readU16(packet, 6)
    val nsCount = readU16(packet, 8)
    val arCount = readU16(packet, 10)

    val parsedName = parseDomainName(packet, startOffset = DNS_HEADER_SIZE)
    if (parsedName is NameParseResult.Error) {
      return DnsParseResult.Malformed(parsedName.error, txId, flags)
    }
    val nameOk = parsedName as NameParseResult.Success
    val qtypeOffset = nameOk.nextOffset
    if (qtypeOffset + 4 > packet.size) {
      return DnsParseResult.Malformed(DnsParseError.TRUNCATED_PACKET, txId, flags)
    }

    val qtype = readU16(packet, qtypeOffset)
    val qclass = readU16(packet, qtypeOffset + 2)
    val questionEnd = qtypeOffset + 4

    val ednsSize =
      if (qdCount == 1 && anCount == 0 && nsCount == 0 && arCount > 0) {
        tryReadEdns0UdpSize(packet, startOffset = questionEnd, arCount = arCount)
      } else {
        null
      }

    return DnsParseResult.Valid(
      ParsedDnsQuery(
        transactionId = txId,
        flags = flags,
        qname = nameOk.labels.joinToString("."),
        qnameLabels = nameOk.labels,
        qtypeCode = qtype,
        recordType = DnsRecordType.fromCode(qtype),
        qclassCode = qclass,
        ednsUdpPayloadSize = ednsSize,
        questionEndOffset = questionEnd,
      )
    )
  }

  fun buildNxDomainResponse(query: ParsedDnsQuery): ByteArray =
    buildErrorOrEmptyResponse(
      transactionId = query.transactionId,
      requestFlags = query.flags,
      qnameLabels = query.qnameLabels,
      qtypeCode = query.qtypeCode,
      qclassCode = query.qclassCode,
      rcode = RCODE_NXDOMAIN,
    )

  fun buildServFailResponse(query: ParsedDnsQuery): ByteArray =
    buildErrorOrEmptyResponse(
      transactionId = query.transactionId,
      requestFlags = query.flags,
      qnameLabels = query.qnameLabels,
      qtypeCode = query.qtypeCode,
      qclassCode = query.qclassCode,
      rcode = RCODE_SERVFAIL,
    )

  fun buildFormErrResponse(transactionId: Int, requestFlags: Int): ByteArray {
    val out = ByteArray(DNS_HEADER_SIZE)
    writeU16(out, 0, transactionId)
    val responseFlags = buildResponseFlags(requestFlags, RCODE_FORMERR)
    writeU16(out, 2, responseFlags)
    return out
  }

  fun extractTransactionId(packet: ByteArray): Int? =
    if (packet.size >= 2) readU16(packet, 0) else null

  private fun buildErrorOrEmptyResponse(
    transactionId: Int,
    requestFlags: Int,
    qnameLabels: List<String>,
    qtypeCode: Int,
    qclassCode: Int,
    rcode: Int,
  ): ByteArray {
    val encodedLabels = qnameLabels.map { it.encodeToByteArray() }
    val nameByteLen = encodedLabels.sumOf { 1 + it.size } + 1
    val totalSize = DNS_HEADER_SIZE + nameByteLen + 4
    val out = ByteArray(totalSize)

    writeU16(out, 0, transactionId)
    writeU16(out, 2, buildResponseFlags(requestFlags, rcode))
    writeU16(out, 4, 1) // QDCOUNT = 1
    writeU16(out, 6, 0) // ANCOUNT = 0
    writeU16(out, 8, 0) // NSCOUNT = 0
    writeU16(out, 10, 0) // ARCOUNT = 0

    var offset = DNS_HEADER_SIZE
    for (labelBytes in encodedLabels) {
      out[offset++] = labelBytes.size.toByte()
      labelBytes.copyInto(out, destinationOffset = offset)
      offset += labelBytes.size
    }
    out[offset++] = 0
    writeU16(out, offset, qtypeCode)
    writeU16(out, offset + 2, qclassCode)
    return out
  }

  private fun buildResponseFlags(requestFlags: Int, rcode: Int): Int {
    val opcode = requestFlags and 0x7800
    val rd = requestFlags and 0x0100
    val ra = 0x0080
    return 0x8000 or opcode or rd or ra or (rcode and 0x0F)
  }

  private fun tryReadEdns0UdpSize(packet: ByteArray, startOffset: Int, arCount: Int): Int? {
    var cursor = startOffset
    repeat(arCount.coerceAtMost(8)) {
      if (cursor >= packet.size) return null
      // Root domain for OPT is a single 0x00 byte; also skip non-OPT names safely.
      val name = parseDomainName(packet, cursor) as? NameParseResult.Success ?: return null
      cursor = name.nextOffset
      if (cursor + 10 > packet.size) return null
      val type = readU16(packet, cursor)
      val rrClass = readU16(packet, cursor + 2)
      val rdLen = readU16(packet, cursor + 8)
      cursor += 10
      if (cursor + rdLen > packet.size) return null
      if (type == RR_TYPE_OPT) {
        return rrClass.coerceIn(512, MAX_DNS_PACKET_BYTES)
      }
      cursor += rdLen
    }
    return null
  }

  private sealed interface NameParseResult {
    data class Success(val labels: List<String>, val nextOffset: Int) : NameParseResult

    data class Error(val error: DnsParseError) : NameParseResult
  }

  private fun parseDomainName(packet: ByteArray, startOffset: Int): NameParseResult {
    val labels = mutableListOf<String>()
    val visitedPointerTargets = HashSet<Int>()
    var cursor = startOffset
    var nextOffsetAfterName = -1
    var jumped = false
    var jumpCount = 0
    var totalNameLength = 0

    while (true) {
      if (cursor >= packet.size) {
        return NameParseResult.Error(DnsParseError.TRUNCATED_PACKET)
      }
      val lengthByte = packet[cursor].toInt() and 0xFF
      if (lengthByte == 0) {
        if (!jumped) {
          nextOffsetAfterName = cursor + 1
        }
        break
      }

      val topTwoBits = lengthByte and 0xC0
      if (topTwoBits == 0xC0) {
        if (cursor + 1 >= packet.size) {
          return NameParseResult.Error(DnsParseError.TRUNCATED_PACKET)
        }
        val pointerTarget = ((lengthByte and 0x3F) shl 8) or (packet[cursor + 1].toInt() and 0xFF)
        if (pointerTarget < DNS_HEADER_SIZE || pointerTarget >= packet.size) {
          return NameParseResult.Error(DnsParseError.COMPRESSION_OUT_OF_BOUNDS)
        }
        if (!visitedPointerTargets.add(pointerTarget)) {
          return NameParseResult.Error(DnsParseError.COMPRESSION_POINTER_LOOP)
        }
        jumpCount += 1
        if (jumpCount > MAX_COMPRESSION_JUMPS) {
          return NameParseResult.Error(DnsParseError.COMPRESSION_POINTER_LOOP)
        }
        if (!jumped) {
          nextOffsetAfterName = cursor + 2
          jumped = true
        }
        cursor = pointerTarget
        continue
      }

      if (topTwoBits != 0) {
        // Extended/reserved DNS label types (01 / 10) are rejected.
        return NameParseResult.Error(DnsParseError.INVALID_LABEL)
      }

      if (lengthByte > MAX_LABEL_LENGTH) {
        return NameParseResult.Error(DnsParseError.INVALID_LABEL)
      }
      val labelStart = cursor + 1
      val labelEnd = labelStart + lengthByte
      if (labelEnd > packet.size) {
        return NameParseResult.Error(DnsParseError.TRUNCATED_PACKET)
      }

      val labelBytes = packet.copyOfRange(labelStart, labelEnd)
      for (b in labelBytes) {
        val code = b.toInt() and 0xFF
        if (code <= 0x20 || code == 0x7F || code == '.'.code) {
          return NameParseResult.Error(DnsParseError.INVALID_LABEL)
        }
      }
      val labelStr = labelBytes.decodeToString()
      labels.add(labelStr)
      totalNameLength += if (labels.size == 1) labelStr.length else 1 + labelStr.length
      if (totalNameLength > MAX_QNAME_LENGTH) {
        return NameParseResult.Error(DnsParseError.DOMAIN_TOO_LONG)
      }
      cursor = labelEnd
    }

    if (nextOffsetAfterName < 0) {
      return NameParseResult.Error(DnsParseError.TRUNCATED_PACKET)
    }
    return NameParseResult.Success(labels = labels, nextOffset = nextOffsetAfterName)
  }

  internal fun readU16(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

  internal fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = ((value ushr 8) and 0xFF).toByte()
    bytes[offset + 1] = (value and 0xFF).toByte()
  }
}
