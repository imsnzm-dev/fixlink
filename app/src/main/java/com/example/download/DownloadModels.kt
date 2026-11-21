package com.example.download

import com.example.network.VideoLinkExtractor
import java.util.Locale

enum class DownloadStatus {
    QUEUED,
    CONNECTING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
}

enum class QueueConcurrency(val maxConcurrent: Int, val code: String) {
    ONE(1, "1"),
    TWO(2, "2"),
    THREE(3, "3"),
    ALL(Int.MAX_VALUE, "all");

    companion object {
        fun fromCode(code: String?): QueueConcurrency {
            return entries.firstOrNull { it.code == code } ?: TWO
        }
    }
}

data class DownloadSaveLocation(
    val treeUriString: String? = null,
    val displayPath: String = DEFAULT_DISPLAY_PATH,
) {
    val isCustom: Boolean
        get() = !treeUriString.isNullOrBlank()

    companion object {
        const val DEFAULT_DISPLAY_PATH = "Downloads/FiXLink"
    }
}

data class PendingDownloadRequest(
    val url: String,
    val sourcePlayUrl: String? = null,
    val originalFileName: String,
    val suggestedUniqueFileName: String,
    val folderDisplayName: String,
)

data class DownloadSegment(
    val index: Int,
    val startByte: Long,
    val endByte: Long,
    val downloadedBytes: Long = 0L,
) {
    val totalSegmentBytes: Long
        get() = (endByte - startByte + 1L).coerceAtLeast(0L)

    val currentOffset: Long
        get() = startByte + downloadedBytes

    val isComplete: Boolean
        get() = downloadedBytes >= totalSegmentBytes

    val progressFraction: Float
        get() = if (totalSegmentBytes <= 0L) 0f
        else (downloadedBytes.toDouble() / totalSegmentBytes.toDouble()).toFloat().coerceIn(0f, 1f)
}

data class DownloadTask(
    val id: String,
    val url: String,
    val sourcePlayUrl: String? = null,
    val fileName: String,
    val extension: String? = null,
    val totalBytes: Long = -1L,
    val downloadedBytes: Long = 0L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val supportsRange: Boolean = false,
    val segments: List<DownloadSegment> = emptyList(),
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long? = null,
    val partFilePath: String? = null,
    val localFilePath: String? = null,
    val publicUriString: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val connectionCount: Int = 4,
    val overwriteExisting: Boolean = false,
) {
    val progressFraction: Float
        get() = when {
            status == DownloadStatus.COMPLETED -> 1f
            totalBytes <= 0L -> 0f
            else -> (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        }

    val progressPercent: Int
        get() = (progressFraction * 100f).toInt().coerceIn(0, 100)

    val isActive: Boolean
        get() = status == DownloadStatus.CONNECTING || status == DownloadStatus.DOWNLOADING

    fun formattedDownloaded(): String = VideoLinkExtractor.formatByteSize(downloadedBytes) ?: "0 B"

    fun formattedTotal(): String? = if (totalBytes > 0L) VideoLinkExtractor.formatByteSize(totalBytes) else null

    fun formattedSpeed(): String {
        if (speedBytesPerSec <= 0L) return "0 KB/s"
        val size = VideoLinkExtractor.formatByteSize(speedBytesPerSec) ?: "0 B"
        return "$size/s"
    }

    fun formattedEta(): String? {
        val sec = etaSeconds ?: return null
        if (sec < 0L) return null
        val hours = sec / 3600
        val minutes = (sec % 3600) / 60
        val seconds = sec % 60
        return when {
            hours > 0 -> String.format(Locale.US, "%dh %02dm", hours, minutes)
            minutes > 0 -> String.format(Locale.US, "%dm %02ds", minutes, seconds)
            else -> String.format(Locale.US, "%ds", seconds)
        }
    }
}
