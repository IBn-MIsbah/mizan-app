package com.example.data.filter

import com.example.core.common.DispatcherProvider
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

sealed interface FeedFetchResult {
  data class Success(val bytes: ByteArray) : FeedFetchResult

  data class NetworkError(val reason: String) : FeedFetchResult

  data class HttpError(val statusCode: Int, val reason: String) : FeedFetchResult

  data class InvalidRequestOrLimit(val reason: String) : FeedFetchResult
}

fun interface FeedHttpFetcher {
  suspend fun fetchBytes(url: String, maxBytes: Int): FeedFetchResult
}

/**
 * Privacy-preserving bounded HTTPS fetcher built on [HttpURLConnection].
 *
 * Guarantees:
 * - HTTPS only (rejects cleartext HTTP and non-HTTPS schemes).
 * - Disables redirects and HTTP disk caches so untrusted hosts cannot intercept downloads.
 * - Sends zero cookies, device identifiers, user accounts, or browsing history.
 * - Enforces strict [maxBytes] download caps while streaming.
 */
@Singleton
class HttpUrlConnectionFeedFetcher
@Inject
constructor(
  private val dispatcherProvider: DispatcherProvider,
) : FeedHttpFetcher {

  override suspend fun fetchBytes(url: String, maxBytes: Int): FeedFetchResult =
    withContext(dispatcherProvider.io) {
      val trimmed = url.trim()
      if (!trimmed.startsWith("https://", ignoreCase = true)) {
        return@withContext FeedFetchResult.InvalidRequestOrLimit(
          "Dataset feed URL must use HTTPS."
        )
      }
      if (maxBytes <= 0) {
        return@withContext FeedFetchResult.InvalidRequestOrLimit("Invalid download size limit.")
      }

      val parsedUrl =
        try {
          URL(trimmed)
        } catch (_: MalformedURLException) {
          return@withContext FeedFetchResult.InvalidRequestOrLimit("Malformed dataset feed URL.")
        }

      var connection: HttpURLConnection? = null
      try {
        connection =
          (parsedUrl.openConnection() as? HttpURLConnection)
            ?: return@withContext FeedFetchResult.InvalidRequestOrLimit(
              "Unsupported network connection handler."
            )
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.useCaches = false
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json, text/plain")
        connection.setRequestProperty("User-Agent", "Mizan-FilterUpdater/1.0")
        connection.connect()

        val statusCode = connection.responseCode
        if (statusCode !in 200..299) {
          return@withContext FeedFetchResult.HttpError(
            statusCode = statusCode,
            reason = "Dataset feed returned HTTP $statusCode.",
          )
        }

        val declaredLength = connection.contentLengthLong
        if (declaredLength > maxBytes.toLong()) {
          return@withContext FeedFetchResult.InvalidRequestOrLimit(
            "Dataset download exceeds maximum allowed size of $maxBytes bytes."
          )
        }

        val buffer = ByteArray(8192)
        val output = ByteArrayOutputStream()
        connection.inputStream.use { input ->
          var totalRead = 0
          while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            totalRead += read
            if (totalRead > maxBytes) {
              return@withContext FeedFetchResult.InvalidRequestOrLimit(
                "Dataset stream exceeded maximum allowed size of $maxBytes bytes."
              )
            }
            output.write(buffer, 0, read)
          }
        }

        FeedFetchResult.Success(output.toByteArray())
      } catch (io: IOException) {
        FeedFetchResult.NetworkError(io.message ?: "Network error while fetching dataset feed.")
      } finally {
        connection?.disconnect()
      }
    }

  companion object {
    const val CONNECT_TIMEOUT_MILLIS = 10_000
    const val READ_TIMEOUT_MILLIS = 15_000
  }
}
