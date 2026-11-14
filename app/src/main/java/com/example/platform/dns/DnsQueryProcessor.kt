package com.example.platform.dns

import com.example.domain.filter.CompiledDomainRuleSet
import com.example.domain.filter.DomainMatchDecision
import com.example.domain.filter.DomainMatchReason
import com.example.domain.filter.DomainRuleMatcher
import com.example.domain.model.Commitment
import com.example.domain.time.TimeProvider

interface UpstreamDnsForwarder {
  suspend fun forwardQuery(rawDnsQuery: ByteArray): ByteArray?
}

sealed interface DnsProcessOutcome {
  data class BlockedLocally(val responseBytes: ByteArray, val decision: DomainMatchDecision) :
    DnsProcessOutcome

  data class ForwardedUpstream(val responseBytes: ByteArray, val decision: DomainMatchDecision) :
    DnsProcessOutcome

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

    val upstreamBytes = upstreamForwarder.forwardQuery(rawDnsQuery)
    if (
      upstreamBytes == null ||
        upstreamBytes.size < DnsWireCodec.DNS_HEADER_SIZE ||
        upstreamBytes.size > DnsWireCodec.MAX_DNS_PACKET_BYTES ||
        DnsWireCodec.extractTransactionId(upstreamBytes) != parsed.transactionId
    ) {
      return DnsProcessOutcome.UpstreamFailed(
        responseBytes = DnsWireCodec.buildServFailResponse(parsed)
      )
    }

    return DnsProcessOutcome.ForwardedUpstream(
      responseBytes = upstreamBytes,
      decision = decision.takeIf { it.reason != DomainMatchReason.NO_ACTIVE_COMMITMENT } ?: decision,
    )
  }
}
