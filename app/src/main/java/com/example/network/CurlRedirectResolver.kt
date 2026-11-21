package com.example.network

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

data class RedirectHop(
    val step: Int,
    val requestUrl: String,
    val statusCode: Int,
    val statusMessage: String,
    val locationHeader: String?,
    val contentType: String?,
    val contentLength: Long?,
)

data class CurlResolutionResult(
    val sourceUrl: String,
    val directVideoUrl: String?,
    val candidateVideoUrls: List<String>,
    val filename: String?,
    val extension: String?,
    val fileSizeFormatted: String?,
    val finalStatusCode: Int,
    val hops: List<RedirectHop>,
    val verboseLog: String,
)

class CurlRedirectResolver(
    private val client: OkHttpClient = defaultClient(),
) {

    private val fastProbeClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        private const val MAX_REDIRECTS = 15
        private const val MAX_TEXT_BODY_BYTES = 256L * 1024L
        private const val CURL_USER_AGENT = "curl/8.19.0"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(18, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .writeTimeout(18, TimeUnit.SECONDS)
            .build()
    }

    suspend fun resolve(
        inputUrl: String,
        onLogUpdate: (traceSoFar: String, currentHop: Int) -> Unit = { _, _ -> },
    ): CurlResolutionResult = withContext(Dispatchers.IO) {
        val logBuilder = StringBuilder()
        fun appendLine(line: String) {
            logBuilder.appendLine(line)
        }

        appendLine("$ curl -L -v \"$inputUrl\"")

        val hops = mutableListOf<RedirectHop>()
        val candidateVideoUrls = linkedSetOf<String>()
        val visitedUrls = hashSetOf<String>()
        var currentUrl = inputUrl
        var finalStatusCode = 0
        var finalContentType: String? = null
        var finalContentLength: Long? = null
        var finalContentDisposition: String? = null

        if (VideoLinkExtractor.isVideoDownloadUrl(currentUrl)) {
            candidateVideoUrls.add(currentUrl)
        }

        for (hopIndex in 0..MAX_REDIRECTS) {
            currentCoroutineContext().ensureActive()
            if (!visitedUrls.add(currentUrl)) {
                appendLine("* Redirect loop detected; using captured download link.")
                break
            }

            val parsedUrl = currentUrl.toHttpUrlOrNull()
                ?: if (candidateVideoUrls.isNotEmpty()) {
                    appendLine("* Reached non-standard redirect target; returning captured link.")
                    break
                } else {
                    throw IllegalArgumentException("Invalid URL: $currentUrl")
                }

            val host = parsedUrl.host
            val port = parsedUrl.port
            val hostWithPort = if (
                (parsedUrl.scheme == "http" && port == 80) ||
                (parsedUrl.scheme == "https" && port == 443)
            ) {
                host
            } else {
                "$host:$port"
            }

            val resolvedIp = try {
                val addresses = Dns.SYSTEM.lookup(host)
                val ipv4 = addresses.filterIsInstance<Inet4Address>().firstOrNull()
                val ipv6 = addresses.filterIsInstance<Inet6Address>().firstOrNull()
                appendLine("* Host $host:$port was resolved.")
                appendLine("* IPv6: ${ipv6?.hostAddress ?: "(none)"}")
                appendLine("* IPv4: ${ipv4?.hostAddress ?: "(none)"}")
                (ipv4 ?: ipv6)?.hostAddress
            } catch (_: Exception) {
                null
            }

            if (resolvedIp != null) {
                appendLine("*   Trying $resolvedIp:$port...")
                appendLine("* Established connection to $host ($resolvedIp port $port)")
            }

            val pathAndQuery = buildString {
                append(parsedUrl.encodedPath)
                parsedUrl.encodedQuery?.let {
                    append('?')
                    append(it)
                }
            }

            appendLine("* using HTTP/1.x")
            appendLine("> GET $pathAndQuery HTTP/1.1")
            appendLine("> Host: $hostWithPort")
            appendLine("> User-Agent: $CURL_USER_AGENT")
            appendLine("> Accept: */*")
            appendLine(">")
            appendLine("* Request completely sent off")
            onLogUpdate(logBuilder.toString(), hopIndex + 1)

            val request = Request.Builder()
                .url(parsedUrl)
                .get()
                .header("User-Agent", CURL_USER_AGENT)
                .header("Accept", "*/*")
                .header("Connection", "keep-alive")
                .build()

            // Once we already captured a video link from a previous Location header,
            // use a short probe timeout and gracefully finish if the final host fails or drops.
            val activeClient = if (candidateVideoUrls.isNotEmpty()) fastProbeClient else client
            val call = activeClient.newCall(request)
            val response = try {
                call.execute()
            } catch (e: CancellationException) {
                call.cancel()
                throw e
            } catch (e: IOException) {
                if (candidateVideoUrls.isNotEmpty()) {
                    appendLine("* Follow-up probe interrupted (${e.message ?: "timeout"}); download link already found in redirect structure.")
                    onLogUpdate(logBuilder.toString(), hopIndex + 1)
                    break
                }
                appendLine("* Connection error: ${e.message ?: "Network failure"}")
                onLogUpdate(logBuilder.toString(), hopIndex + 1)
                throw e
            }

            var shouldContinue = false
            response.use { resp ->
                val statusCode = resp.code
                val statusMsg = resp.message.ifBlank { defaultStatusText(statusCode) }
                finalStatusCode = statusCode

                appendLine("< HTTP/1.1 $statusCode $statusMsg")
                for (i in 0 until resp.headers.size) {
                    val headerName = resp.headers.name(i)
                    val headerVal = resp.headers.value(i)
                    appendLine("< $headerName: $headerVal")
                    // Catch any video URL appearing inside any header value (Location, Content-Location, etc.)
                    candidateVideoUrls.addAll(VideoLinkExtractor.extractVideoUrlsFromText(headerVal))
                }
                appendLine("<")

                val locationHeader = resp.header("Location")
                    ?: resp.header("location")
                    ?: VideoLinkExtractor.extractRefreshRedirectUrl(resp.header("Refresh"))
                val contentType = resp.header("Content-Type")
                val contentLength = resp.header("Content-Length")?.toLongOrNull()
                val contentDisposition = resp.header("Content-Disposition")

                finalContentType = contentType
                finalContentLength = contentLength
                finalContentDisposition = contentDisposition

                if (VideoLinkExtractor.isVideoDownloadUrl(currentUrl)) {
                    candidateVideoUrls.add(currentUrl)
                }

                hops.add(
                    RedirectHop(
                        step = hopIndex + 1,
                        requestUrl = currentUrl,
                        statusCode = statusCode,
                        statusMessage = statusMsg,
                        locationHeader = locationHeader,
                        contentType = contentType,
                        contentLength = contentLength,
                    )
                )

                val isRedirect = statusCode in setOf(301, 302, 303, 307, 308)
                if (isRedirect && !locationHeader.isNullOrBlank()) {
                    val resolvedNextUrl = parsedUrl.resolve(locationHeader.trim())?.toString()
                        ?: locationHeader.trim()

                    if (VideoLinkExtractor.isVideoDownloadUrl(resolvedNextUrl)) {
                        candidateVideoUrls.add(resolvedNextUrl)
                    }

                    appendLine("* Ignoring the response-body")
                    appendLine("* Connection #$hopIndex to host $host:$port left intact")
                    appendLine("* Issue another request to this URL: '$resolvedNextUrl'")
                    onLogUpdate(logBuilder.toString(), hopIndex + 1)

                    currentUrl = resolvedNextUrl
                    shouldContinue = true
                } else {
                    // Non-redirect response: do NOT download full video payload!
                    val isBinaryOrVideo = isBinaryOrVideoContent(contentType, currentUrl)
                    if (isBinaryOrVideo) {
                        appendLine("* Video/binary response detected (${VideoLinkExtractor.formatByteSize(contentLength) ?: "stream"}). Closing connection without downloading payload.")
                        appendLine("* closing connection #$hopIndex")
                    } else {
                        // Inspect small text/HTML/JSON bodies for embedded video links
                        val bodySnippet = try {
                            val source = resp.body?.source()
                            if (source != null) {
                                source.request(MAX_TEXT_BODY_BYTES)
                                val buffer = source.buffer.clone()
                                val bytesToRead = minOf(buffer.size, MAX_TEXT_BODY_BYTES)
                                buffer.readUtf8(bytesToRead)
                            } else {
                                ""
                            }
                        } catch (_: Exception) {
                            ""
                        }
                        if (bodySnippet.isNotBlank()) {
                            val foundInBody = VideoLinkExtractor.extractVideoUrlsFromText(bodySnippet)
                            candidateVideoUrls.addAll(foundInBody)
                        }
                        appendLine("* Connection #$hopIndex to host $host:$port left intact")
                    }
                    onLogUpdate(logBuilder.toString(), hopIndex + 1)
                }
            }

            if (!shouldContinue) break
        }

        val directUrl = VideoLinkExtractor.selectBestVideoUrl(candidateVideoUrls.toList())
            ?: if (isBinaryOrVideoContent(finalContentType, currentUrl) && finalStatusCode in 200..299) {
                currentUrl
            } else {
                null
            }

        val filename = directUrl?.let {
            VideoLinkExtractor.extractFilename(it, finalContentDisposition)
        }
        val extension = directUrl?.let {
            VideoLinkExtractor.extractExtension(it, filename)
        }
        val formattedSize = VideoLinkExtractor.formatByteSize(finalContentLength)

        CurlResolutionResult(
            sourceUrl = inputUrl,
            directVideoUrl = directUrl,
            candidateVideoUrls = candidateVideoUrls.toList(),
            filename = filename,
            extension = extension,
            fileSizeFormatted = formattedSize,
            finalStatusCode = finalStatusCode,
            hops = hops,
            verboseLog = logBuilder.toString().trimEnd(),
        )
    }

    fun resolveFromRawTrace(rawTrace: String): CurlResolutionResult {
        val candidates = VideoLinkExtractor.extractVideoUrlsFromText(rawTrace)
        val best = VideoLinkExtractor.selectBestVideoUrl(candidates)
        val filename = best?.let { VideoLinkExtractor.extractFilename(it) }
        val extension = best?.let { VideoLinkExtractor.extractExtension(it, filename) }

        val lengthRegex = Regex("""Content-Length:\s*(\d+)""", RegexOption.IGNORE_CASE)
        val lastLength = lengthRegex.findAll(rawTrace).lastOrNull()?.groupValues?.getOrNull(1)?.toLongOrNull()

        return CurlResolutionResult(
            sourceUrl = VideoLinkExtractor.extractInputUrl(rawTrace) ?: "",
            directVideoUrl = best,
            candidateVideoUrls = candidates,
            filename = filename,
            extension = extension,
            fileSizeFormatted = VideoLinkExtractor.formatByteSize(lastLength),
            finalStatusCode = if (rawTrace.contains("200 OK")) 200 else 0,
            hops = emptyList(),
            verboseLog = rawTrace.trim(),
        )
    }

    private fun isBinaryOrVideoContent(contentType: String?, url: String): Boolean {
        if (VideoLinkExtractor.isVideoDownloadUrl(url)) return true
        val ct = contentType?.lowercase() ?: return false
        return ct.startsWith("video/") || ct.contains("application/octet-stream")
    }

    private fun defaultStatusText(code: Int): String = when (code) {
        200 -> "OK"
        206 -> "Partial Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        303 -> "See Other"
        307 -> "Temporary Redirect"
        308 -> "Permanent Redirect"
        403 -> "Forbidden"
        404 -> "Not Found"
        else -> ""
    }
}
