package com.example.platform.dns

import com.example.domain.filter.CompiledDomainRuleSet
import com.example.domain.filter.DomainMatchDecision
import com.example.domain.filter.DomainMatchReason
import com.example.domain.filter.DomainRuleMatcher
import com.example.domain.model.Commitment
import com.example.domain.time.TimeProvider

enum class DnsTransport {
  UDP,
  TCP,
}

interface UpstreamDnsForwarder {
  suspend fun forwardQuery(rawDnsQuery: ByteArray): ByteArray?

  suspend fun forwardQueryTcp(rawDnsQuery: ByteArray): ByteArray? = forwardQuery(rawDnsQuery)
}

sealed interface DnsProcessOutcome {
  data class BlockedLocally(val responseBytes: ByteArray, val decision: DomainMatchDecision) :
    DnsProcessOutcome

  data class ForwardedUpstream(
    val responseBytes: ByteArray,
    val decision: DomainMatchDecision,
    val wasTruncatedForMtu: Boolean = false,
  ) : DnsProcessOutcome

  data class UpstreamFailed(val responseBytes: ByteArray) : DnsProcessOutcome

  data class MalformedDropped(val error: DnsParseError, val formErrBytes: ByteArray?) :
    DnsProcessOutcome
}

/**
 * Pure, independently testable DNS query processor bridging wire parsing, domain rule matching,
 * and approved local upstream DNS forwarding without logging or storing DNS query history.
 */
class DnsQueryProcessor(
  private val timeProvider: TimeProvider,
  private val upstreamForwarder: UpstreamDnsForwarder,
) {

  suspend fun processDnsQuery(
    rawDnsQuery: ByteArray,
    commitment: Commitment?,
    compiledRules: CompiledDomainRuleSet,
    transport: DnsTransport = DnsTransport.UDP,
    maxUdpResponseBytes: Int = DnsWireCodec.MAX_TUN_UDP_DNS_PAYLOAD,
  ): DnsProcessOutcome {
    val parsed =
      when (val result = DnsWireCodec.parseQuery(rawDnsQuery)) {
        is DnsParseResult.Malformed -> {
          val formErr =
            if (result.transactionId != null && result.flags != null) {
              DnsWireCodec.buildFormErrResponse(result.transactionId, result.flags)
            } else {
              null
            }
          return DnsProcessOutcome.MalformedDropped(result.error, formErr)
        }
        is DnsParseResult.Valid -> result.query
      }

    val decision =
      DomainRuleMatcher.evaluateCompiled(
        commitment = commitment,
        compiled = compiledRules,
        rawQueryDomain = parsed.qname,
        wallClock = timeProvider.currentWallClock(),
      )

    if (decision.isBlocked) {
      return DnsProcessOutcome.BlockedLocally(
        responseBytes = DnsWireCodec.buildNxDomainResponse(parsed),
        decision = decision,
      )
    }

    val maxAllowedBytes =
      if (transport == DnsTransport.TCP) DnsWireCodec.MAX_TCP_DNS_PACKET_BYTES
      else DnsWireCodec.MAX_DNS_PACKET_BYTES
    val upstreamBytes =
      when (transport) {
        DnsTransport.UDP -> upstreamForwarder.forwardQuery(rawDnsQuery)
        DnsTransport.TCP -> upstreamForwarder.forwardQueryTcp(rawDnsQuery)
      }

    if (
      upstreamBytes == null ||
        upstreamBytes.size < DnsWireCodec.DNS_HEADER_SIZE ||
        upstreamBytes.size > maxAllowedBytes ||
        !DnsWireCodec.isResponsePacket(upstreamBytes) ||
        DnsWireCodec.extractTransactionId(upstreamBytes) != parsed.transactionId
    ) {
      return DnsProcessOutcome.UpstreamFailed(
        responseBytes = DnsWireCodec.buildServFailResponse(parsed)
      )
    }

    if (transport == DnsTransport.UDP && upstreamBytes.size > maxUdpResponseBytes) {
      return DnsProcessOutcome.ForwardedUpstream(
        responseBytes = DnsWireCodec.buildTruncatedResponse(parsed),
        decision = decision,
        wasTruncatedForMtu = true,
      )
    }

    return DnsProcessOutcome.ForwardedUpstream(
      responseBytes = upstreamBytes,
      decision = decision.takeIf { it.reason != DomainMatchReason.NO_ACTIVE_COMMITMENT } ?: decision,
    )
  }
}
