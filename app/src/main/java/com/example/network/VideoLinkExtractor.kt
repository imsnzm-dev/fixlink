package com.example.network

import java.net.URLDecoder
import java.util.Locale

object VideoLinkExtractor {

    val VIDEO_EXTENSIONS = setOf(
        "mkv", "mp4", "avi", "mov", "webm", "flv", "m4v", "ts", "m3u8", "wmv", "3gp"
    )

    private val URL_REGEX = Regex(
        """https?://[^\s"'<>\]\)]+""",
        RegexOption.IGNORE_CASE
    )

    private val REFRESH_URL_REGEX = Regex(
        """url\s*=\s*['"]?([^'";\s]+)['"]?""",
        RegexOption.IGNORE_CASE
    )

    private val FILENAME_HEADER_REGEX = Regex(
        """filename\*?=(?:UTF-8''|")?([^";\r\n]+)"?""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Extracts the target HTTP/HTTPS URL from a raw URL, `vlc://` deep link,
     * or a pasted curl command such as `curl -L -v "http://..."`.
     */
    fun extractInputUrl(rawInput: String): String? {
        val trimmed = normalizeVlcScheme(rawInput.trim())
        if (trimmed.isEmpty()) return null

        val match = URL_REGEX.find(trimmed)?.value ?: return null
        return cleanTrailingPunctuation(match)
    }

    /**
     * Converts `vlc://http://...` or `vlc://example.com/...` into standard http/https URL.
     */
    fun normalizeVlcScheme(raw: String): String {
        if (!raw.startsWith("vlc://", ignoreCase = true)) return raw
        val stripped = raw.substring(6).trimStart('/')
        return if (stripped.startsWith("http://", ignoreCase = true) ||
            stripped.startsWith("https://", ignoreCase = true)
        ) {
            stripped
        } else if (stripped.isNotBlank()) {
            "http://$stripped"
        } else {
            raw
        }
    }

    /**
     * Returns true if the user pasted raw multi-line `curl -L -v` verbose output
     * rather than a single URL or curl command.
     */
    fun looksLikeRawCurlTrace(rawInput: String): Boolean {
        val trimmed = rawInput.trim()
        return trimmed.contains("\n") && (
            trimmed.contains("< HTTP/") ||
                trimmed.contains("* Issue another request") ||
                trimmed.contains("< location:", ignoreCase = true) ||
                trimmed.contains("location:", ignoreCase = true)
            )
    }

    /**
     * Checks whether a URL starts with http:// or https:// and has a video file extension
     * (e.g., .mkv, .mp4, .avi) before any query string or fragment.
     */
    fun isVideoDownloadUrl(url: String): Boolean {
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }
        val withoutFragment = url.substringBefore('#')
        val withoutQuery = withoutFragment.substringBefore('?')
        val lastSegment = withoutQuery.substringAfterLast('/', "")
        if ('.' !in lastSegment) return false
        val extension = lastSegment.substringAfterLast('.', "").lowercase(Locale.US)
        return extension in VIDEO_EXTENSIONS
    }

    /**
     * Scans arbitrary response text, JSON/HTML, or raw `curl -L -v` trace output and returns
     * all distinct video download URLs in order of appearance.
     */
    fun extractVideoUrlsFromText(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val normalized = text
            .replace("\\/", "/")
            .replace("&amp;", "&")

        val results = linkedSetOf<String>()
        for (match in URL_REGEX.findAll(normalized)) {
            val cleaned = cleanTrailingPunctuation(match.value)
            if (isVideoDownloadUrl(cleaned)) {
                results.add(cleaned)
            }
        }
        return results.toList()
    }

    fun extractRefreshRedirectUrl(refreshHeader: String?): String? {
        if (refreshHeader.isNullOrBlank()) return null
        val match = REFRESH_URL_REGEX.find(refreshHeader)?.groupValues?.getOrNull(1)
        return match?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Given a list of candidate video URLs (for example across 302 redirect hops),
     * returns the final direct download URL (last candidate in the redirect chain).
     */
    fun selectBestVideoUrl(candidates: List<String>): String? {
        return candidates.lastOrNull()
    }

    fun extractFilename(url: String, contentDisposition: String? = null): String {
        if (!contentDisposition.isNullOrBlank()) {
            val headerMatch = FILENAME_HEADER_REGEX.find(contentDisposition)
            val rawName = headerMatch?.groupValues?.getOrNull(1)?.trim()
            if (!rawName.isNullOrBlank()) {
                return decodeSafely(rawName)
            }
        }
        val pathOnly = url.substringBefore('#').substringBefore('?')
        val segment = pathOnly.substringAfterLast('/', "").trim()
        return if (segment.isNotEmpty()) decodeSafely(segment) else "video_file"
    }

    fun extractExtension(url: String, filename: String? = null): String? {
        val target = filename?.takeIf { '.' in it }
            ?: url.substringBefore('#').substringBefore('?').substringAfterLast('/', "")
        val ext = target.substringAfterLast('.', "").uppercase(Locale.US)
        return ext.takeIf { it.lowercase(Locale.US) in VIDEO_EXTENSIONS }
    }

    fun formatByteSize(bytes: Long?): String? {
        if (bytes == null || bytes <= 0L) return null
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    private fun cleanTrailingPunctuation(url: String): String {
        return url.trimEnd('\'', '"', ',', ';', '.', ')', ']')
    }

    private fun decodeSafely(value: String): String {
        return try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }
    }
}
