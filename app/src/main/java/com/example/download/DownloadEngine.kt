package com.example.download

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.network.CurlRedirectResolver
import com.example.network.VideoLinkExtractor
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

object DownloadManagerHolder {
    @Volatile
    private var instance: DownloadEngine? = null

    fun get(context: Context): DownloadEngine {
        return instance ?: synchronized(this) {
            instance ?: DownloadEngine(context.applicationContext).also { instance = it }
        }
    }
}

class DownloadEngine(
    private val appContext: Context,
    private val resolver: CurlRedirectResolver = CurlRedirectResolver(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val persistence = DownloadPersistence(File(appContext.filesDir, "fixlink_downloads.json"))
    private val stagingDir = File(appContext.filesDir, "downloads_part").apply { mkdirs() }

    private val downloadClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _tasks = MutableStateFlow(persistence.loadTasks())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _concurrencyLimit = MutableStateFlow(
        QueueConcurrency.fromCode(prefs.getString(KEY_CONCURRENCY, QueueConcurrency.TWO.code))
    )
    val concurrencyLimit: StateFlow<QueueConcurrency> = _concurrencyLimit.asStateFlow()

    private val _connectionsPerDownload = MutableStateFlow(
        prefs.getInt(KEY_THREADS, 2).coerceIn(1, 4)
    )
    val connectionsPerDownload: StateFlow<Int> = _connectionsPerDownload.asStateFlow()

    private val activeJobs = ConcurrentHashMap<String, Job>()

    fun setConcurrencyLimit(limit: QueueConcurrency) {
        prefs.edit().putString(KEY_CONCURRENCY, limit.code).apply()
        _concurrencyLimit.value = limit
        scheduleQueue()
    }

    fun setConnectionsPerDownload(threads: Int) {
        val clamped = threads.coerceIn(1, 4)
        prefs.edit().putInt(KEY_THREADS, clamped).apply()
        _connectionsPerDownload.value = clamped
    }

    fun enqueue(
        url: String,
        sourcePlayUrl: String? = null,
        suggestedFileName: String? = null,
    ): DownloadTask {
        val cleanUrl = url.trim()
        val rawName = suggestedFileName?.takeIf { it.isNotBlank() }
            ?: VideoLinkExtractor.extractFilename(cleanUrl)
        val safeName = sanitizeFileName(rawName)
        val ext = VideoLinkExtractor.extractExtension(cleanUrl, safeName)
        val id = UUID.randomUUID().toString()
        val partFile = File(stagingDir, "$id.part")

        val newTask = DownloadTask(
            id = id,
            url = cleanUrl,
            sourcePlayUrl = sourcePlayUrl?.takeIf { it != cleanUrl },
            fileName = safeName,
            extension = ext,
            status = DownloadStatus.QUEUED,
            partFilePath = partFile.absolutePath,
            createdAt = System.currentTimeMillis(),
            connectionCount = _connectionsPerDownload.value,
        )

        mutateTasks { current -> listOf(newTask) + current }
        scheduleQueue()
        return newTask
    }

    fun pause(id: String) {
        activeJobs.remove(id)?.cancel()
        mutateTask(id) { task ->
            if (task.status == DownloadStatus.COMPLETED) task
            else task.copy(
                status = DownloadStatus.PAUSED,
                speedBytesPerSec = 0L,
                etaSeconds = null,
            )
        }
        scheduleQueue()
    }

    fun resume(id: String) {
        mutateTask(id) { task ->
            if (task.status == DownloadStatus.COMPLETED || task.isActive) task
            else task.copy(
                status = DownloadStatus.QUEUED,
                errorMessage = null,
                speedBytesPerSec = 0L,
                etaSeconds = null,
            )
        }
        scheduleQueue()
    }

    fun moveToTop(id: String) {
        mutateTasks { list ->
            val target = list.firstOrNull { it.id == id } ?: return@mutateTasks list
            listOf(target) + list.filterNot { it.id == id }
        }
        scheduleQueue()
    }

    fun pauseAll() {
        val activeIds = activeJobs.keys.toList()
        activeIds.forEach { activeJobs.remove(it)?.cancel() }
        mutateTasks { list ->
            list.map { task ->
                if (task.isActive || task.status == DownloadStatus.QUEUED) {
                    task.copy(
                        status = DownloadStatus.PAUSED,
                        speedBytesPerSec = 0L,
                        etaSeconds = null,
                    )
                } else {
                    task
                }
            }
        }
    }

    fun resumeAll() {
        mutateTasks { list ->
            list.map { task ->
                if (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.FAILED) {
                    task.copy(
                        status = DownloadStatus.QUEUED,
                        errorMessage = null,
                    )
                } else {
                    task
                }
            }
        }
        scheduleQueue()
    }

    fun clearCompleted() {
        mutateTasks { list ->
            list.filterNot { it.status == DownloadStatus.COMPLETED }
        }
    }

    fun remove(id: String, deleteFiles: Boolean = true) {
        activeJobs.remove(id)?.cancel()
        val removed = _tasks.value.firstOrNull { it.id == id }
        mutateTasks { list -> list.filterNot { it.id == id } }
        if (deleteFiles && removed != null) {
            removed.partFilePath?.let { runCatching { File(it).delete() } }
            if (removed.status == DownloadStatus.COMPLETED) {
                removed.localFilePath?.let { runCatching { File(it).delete() } }
            }
        }
        scheduleQueue()
    }

    fun getOpenableUri(task: DownloadTask): Uri? {
        task.publicUriString?.let { raw ->
            runCatching { return Uri.parse(raw) }
        }
        task.localFilePath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                return runCatching {
                    FileProvider.getUriForFile(
                        appContext,
                        "${appContext.packageName}.fileprovider",
                        file,
                    )
                }.getOrNull()
            }
        }
        return null
    }

    fun scheduleQueue() {
        val currentTasks = _tasks.value
        val activeCount = currentTasks.count { it.isActive }
        val maxAllowed = _concurrencyLimit.value.maxConcurrent
        val availableSlots = (maxAllowed - activeCount).coerceAtLeast(0)

        if (availableSlots > 0) {
            // Pick oldest queued items first (or prioritized order in list)
            val queued = currentTasks.filter { it.status == DownloadStatus.QUEUED }.reversed()
            queued.take(availableSlots).forEach { nextTask ->
                startTaskInternal(nextTask.id)
            }
        }

        val hasRunning = _tasks.value.any { it.isActive }
        if (hasRunning) {
            startForegroundServiceIfNeeded()
        }
    }

    private fun startForegroundServiceIfNeeded() {
        try {
            val intent = Intent(appContext, DownloadForegroundService::class.java)
            ContextCompat.startForegroundService(appContext, intent)
        } catch (_: Exception) {
            // Service launch may fail in Robolectric or restricted contexts
        }
    }

    private fun startTaskInternal(taskId: String) {
        if (activeJobs.containsKey(taskId)) return
        mutateTask(taskId) {
            it.copy(
                status = DownloadStatus.CONNECTING,
                errorMessage = null,
            )
        }

        val job = scope.launch {
            try {
                executeDownload(taskId)
            } catch (_: CancellationException) {
                // Paused or cancelled cleanly
            } catch (e: Exception) {
                mutateTask(taskId) {
                    if (it.status == DownloadStatus.PAUSED) it
                    else it.copy(
                        status = DownloadStatus.FAILED,
                        speedBytesPerSec = 0L,
                        etaSeconds = null,
                        errorMessage = e.localizedMessage ?: "Download error",
                    )
                }
            } finally {
                activeJobs.remove(taskId)
                scheduleQueue()
            }
        }
        activeJobs[taskId] = job
    }

    private suspend fun executeDownload(taskId: String) {
        var task = _tasks.value.firstOrNull { it.id == taskId } ?: return
        val partFile = File(task.partFilePath ?: File(stagingDir, "${task.id}.part").absolutePath)
        partFile.parentFile?.mkdirs()

        // Step 1: Probe URL & server Range capability (with automatic expired-link refresh!)
        var activeUrl = task.url
        val probeRequest = Request.Builder()
            .url(activeUrl)
            .get()
            .header("User-Agent", "curl/8.19.0")
            .header("Range", "bytes=0-")
            .build()

        var probeResponse = runCatching { downloadClient.newCall(probeRequest).execute() }.getOrNull()

        // If expired (403/410) or unreachable and we have the original play.php source URL, refresh!
        if ((probeResponse == null || probeResponse.code in setOf(401, 403, 410)) && !task.sourcePlayUrl.isNullOrBlank()) {
            probeResponse?.close()
            val refreshed = runCatching { resolver.resolve(task.sourcePlayUrl!!) }.getOrNull()
            val newDirect = refreshed?.directVideoUrl
            if (!newDirect.isNullOrBlank()) {
                activeUrl = newDirect
                mutateTask(taskId) { it.copy(url = activeUrl) }
                val retryProbe = Request.Builder()
                    .url(activeUrl)
                    .get()
                    .header("User-Agent", "curl/8.19.0")
                    .header("Range", "bytes=0-")
                    .build()
                probeResponse = downloadClient.newCall(retryProbe).execute()
            }
        }

        val response = probeResponse ?: throw IllegalStateException("Unable to connect to download server")
        var serverTotalBytes = -1L
        var supportsRange = false
        var resolvedFileName = task.fileName
        var mimeType: String? = null

        var useFirstResponseDirectly = false
        response.use { resp ->
            if (!resp.isSuccessful && resp.code != 206) {
                throw IllegalStateException("HTTP ${resp.code} ${resp.message}")
            }

            mimeType = resp.header("Content-Type")
            val disposition = resp.header("Content-Disposition")
            if (!disposition.isNullOrBlank()) {
                resolvedFileName = sanitizeFileName(VideoLinkExtractor.extractFilename(activeUrl, disposition))
            }

            val acceptRanges = resp.header("Accept-Ranges")
            supportsRange = resp.code == 206 || acceptRanges.equals("bytes", ignoreCase = true)

            serverTotalBytes = parseTotalBytesFromHeaders(
                code = resp.code,
                contentRange = resp.header("Content-Range"),
                contentLength = resp.header("Content-Length")?.toLongOrNull(),
            )

            // Decide if we can stream directly from this response (non-resumable or small file or single connection)
            val existingSegments = task.segments
            val canResumeExisting = supportsRange &&
                existingSegments.isNotEmpty() &&
                partFile.exists() &&
                serverTotalBytes > 0L &&
                existingSegments.last().endByte == serverTotalBytes - 1L

            val threads = task.connectionCount.coerceIn(1, 4)
            val shouldUseMultiSegment = supportsRange && serverTotalBytes > MIN_MULTI_SEGMENT_BYTES && threads > 1

            if (!canResumeExisting && !shouldUseMultiSegment && (!supportsRange || task.downloadedBytes == 0L)) {
                // Single-stream directly from this already-open response so we don't make a duplicate HTTP call!
                useFirstResponseDirectly = true
                val initialBytes = 0L
                if (partFile.exists()) partFile.delete()
                mutateTask(taskId) {
                    it.copy(
                        fileName = resolvedFileName,
                        totalBytes = serverTotalBytes,
                        downloadedBytes = initialBytes,
                        supportsRange = supportsRange,
                        segments = emptyList(),
                        status = DownloadStatus.DOWNLOADING,
                    )
                }

                streamSingleResponse(
                    taskId = taskId,
                    response = resp,
                    partFile = partFile,
                    startOffset = 0L,
                    totalBytes = serverTotalBytes,
                )
            }
        }

        if (useFirstResponseDirectly) {
            finalizeCompletedFile(taskId, partFile, resolvedFileName, mimeType)
            return
        }

        // Step 2: Resumable multi-segment or single-segment resume
        task = _tasks.value.firstOrNull { it.id == taskId } ?: return
        val existingSegmentsValid = supportsRange &&
            task.segments.isNotEmpty() &&
            partFile.exists() &&
            serverTotalBytes > 0L &&
            task.segments.last().endByte == serverTotalBytes - 1L

        val segments = if (existingSegmentsValid) {
            task.segments
        } else {
            if (partFile.exists()) partFile.delete()
            if (serverTotalBytes > 0L) {
                RandomAccessFile(partFile, "rw").use { it.setLength(serverTotalBytes) }
                buildSegments(serverTotalBytes, if (supportsRange) task.connectionCount else 1)
            } else {
                emptyList()
            }
        }

        if (segments.isEmpty()) {
            // Unknown total length single stream
            val req = Request.Builder()
                .url(activeUrl)
                .get()
                .header("User-Agent", "curl/8.19.0")
                .build()
            downloadClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                mutateTask(taskId) {
                    it.copy(
                        fileName = resolvedFileName,
                        totalBytes = -1L,
                        downloadedBytes = 0L,
                        supportsRange = false,
                        segments = emptyList(),
                        status = DownloadStatus.DOWNLOADING,
                    )
                }
                streamSingleResponse(taskId, resp, partFile, 0L, -1L)
            }
            finalizeCompletedFile(taskId, partFile, resolvedFileName, mimeType)
            return
        }

        val initialTotalDownloaded = segments.sumOf { it.downloadedBytes }
        mutateTask(taskId) {
            it.copy(
                fileName = resolvedFileName,
                totalBytes = serverTotalBytes,
                downloadedBytes = initialTotalDownloaded,
                supportsRange = supportsRange,
                segments = segments,
                status = DownloadStatus.DOWNLOADING,
            )
        }

        downloadSegmentsInParallel(
            taskId = taskId,
            url = activeUrl,
            partFile = partFile,
            initialSegments = segments,
            totalBytes = serverTotalBytes,
        )

        finalizeCompletedFile(taskId, partFile, resolvedFileName, mimeType)
    }

    private suspend fun streamSingleResponse(
        taskId: String,
        response: okhttp3.Response,
        partFile: File,
        startOffset: Long,
        totalBytes: Long,
    ) = coroutineScope {
        val body = response.body ?: throw IllegalStateException("Empty response body")
        val source = body.byteStream()
        val downloadedCounter = AtomicLong(startOffset)

        val meterJob = launch {
            monitorProgress(taskId, downloadedCounter, totalBytes) { emptyList() }
        }

        try {
            RandomAccessFile(partFile, "rw").use { raf ->
                raf.seek(startOffset)
                val buffer = ByteArray(DEFAULT_IO_BUFFER_SIZE)
                while (isActive) {
                    val read = source.read(buffer)
                    if (read == -1) break
                    raf.write(buffer, 0, read)
                    downloadedCounter.addAndGet(read.toLong())
                }
            }
        } finally {
            meterJob.cancel()
        }

        val finalCount = downloadedCounter.get()
        mutateTask(taskId) {
            it.copy(
                downloadedBytes = finalCount,
                totalBytes = if (totalBytes > 0L) totalBytes else finalCount,
                speedBytesPerSec = 0L,
                etaSeconds = null,
            )
        }
    }

    private suspend fun downloadSegmentsInParallel(
        taskId: String,
        url: String,
        partFile: File,
        initialSegments: List<DownloadSegment>,
        totalBytes: Long,
    ) = coroutineScope {
        val segmentStates = initialSegments.toMutableList()
        val totalDownloaded = AtomicLong(segmentStates.sumOf { it.downloadedBytes })
        val segmentLock = Any()

        val meterJob = launch {
            monitorProgress(taskId, totalDownloaded, totalBytes) {
                synchronized(segmentLock) { segmentStates.toList() }
            }
        }

        try {
            val jobs = segmentStates.mapIndexed { listIdx, segment ->
                async(Dispatchers.IO) {
                    if (segment.isComplete) return@async
                    var currentSeg = segment
                    val req = Request.Builder()
                        .url(url)
                        .get()
                        .header("User-Agent", "curl/8.19.0")
                        .header("Range", "bytes=${currentSeg.currentOffset}-${currentSeg.endByte}")
                        .build()

                    val call = downloadClient.newCall(req)
                    call.execute().use { resp ->
                        if (resp.code != 206 && resp.code != 200) {
                            throw IllegalStateException("HTTP ${resp.code} on segment ${segment.index}")
                        }
                        val stream = resp.body?.byteStream() ?: throw IllegalStateException("Empty stream")
                        val buf = ByteArray(DEFAULT_IO_BUFFER_SIZE)

                        RandomAccessFile(partFile, "rw").channel.use { channel ->
                            var writePos = currentSeg.currentOffset
                            val remainingMax = currentSeg.endByte - writePos + 1L
                            var writtenInThisRun = 0L

                            while (isActive && writtenInThisRun < remainingMax) {
                                val toRead = minOf(buf.size.toLong(), remainingMax - writtenInThisRun).toInt()
                                val read = stream.read(buf, 0, toRead)
                                if (read == -1) break

                                val byteBuf = ByteBuffer.wrap(buf, 0, read)
                                while (byteBuf.hasRemaining()) {
                                    writePos += channel.write(byteBuf, writePos).toLong()
                                }

                                writtenInThisRun += read.toLong()
                                totalDownloaded.addAndGet(read.toLong())
                                currentSeg = currentSeg.copy(
                                    downloadedBytes = currentSeg.downloadedBytes + read.toLong()
                                )
                                synchronized(segmentLock) {
                                    segmentStates[listIdx] = currentSeg
                                }
                            }
                        }

                        if (!currentSeg.isComplete) {
                            throw IllegalStateException("Segment ${segment.index} ended early")
                        }
                    }
                }
            }
            jobs.awaitAll()
        } finally {
            meterJob.cancel()
            val snapshotSegments = synchronized(segmentLock) { segmentStates.toList() }
            mutateTask(taskId) {
                it.copy(
                    segments = snapshotSegments,
                    downloadedBytes = totalDownloaded.get(),
                    speedBytesPerSec = 0L,
                    etaSeconds = null,
                )
            }
        }
    }

    private suspend fun CoroutineScope.monitorProgress(
        taskId: String,
        downloadedCounter: AtomicLong,
        totalBytes: Long,
        segmentsProvider: () -> List<DownloadSegment>,
    ) {
        var prevBytes = downloadedCounter.get()
        var prevTime = System.currentTimeMillis()
        var smoothedSpeed = 0L
        var lastSaveTime = prevTime

        while (isActive) {
            delay(450)
            val now = System.currentTimeMillis()
            val currentBytes = downloadedCounter.get()
            val elapsedMs = (now - prevTime).coerceAtLeast(1L)
            val instantSpeed = ((currentBytes - prevBytes).coerceAtLeast(0L) * 1000L) / elapsedMs
            smoothedSpeed = if (smoothedSpeed == 0L) {
                instantSpeed
            } else {
                ((smoothedSpeed * 6L) + (instantSpeed * 4L)) / 10L
            }

            val eta = if (totalBytes > 0L && smoothedSpeed > 0L) {
                ((totalBytes - currentBytes).coerceAtLeast(0L)) / smoothedSpeed
            } else {
                null
            }

            val shouldPersist = (now - lastSaveTime) >= 1500L
            if (shouldPersist) lastSaveTime = now

            updateTaskInMemory(
                id = taskId,
                persist = shouldPersist,
            ) {
                if (!it.isActive) it
                else it.copy(
                    downloadedBytes = currentBytes,
                    segments = segmentsProvider().ifEmpty { it.segments },
                    speedBytesPerSec = smoothedSpeed,
                    etaSeconds = eta,
                )
            }

            prevBytes = currentBytes
            prevTime = now
        }
    }

    private fun finalizeCompletedFile(
        taskId: String,
        partFile: File,
        desiredName: String,
        mimeType: String?,
    ) {
        val resolvedMime = mimeType?.substringBefore(';')?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: guessMimeType(desiredName)

        var publicUriStr: String? = null
        var localPathStr: String? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, desiredName)
                put(MediaStore.Downloads.MIME_TYPE, resolvedMime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/FiXLink")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = appContext.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(partFile).use { input ->
                            input.copyTo(out, bufferSize = DEFAULT_IO_BUFFER_SIZE)
                        }
                    }
                    val finishValues = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    resolver.update(uri, finishValues, null, null)
                    publicUriStr = uri.toString()
                    partFile.delete()
                } catch (_: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                }
            }
        }

        // Fallback / API 24-28: move to app's external Downloads/FiXLink folder
        if (publicUriStr == null) {
            val targetDir = File(
                appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir,
                "FiXLink",
            ).apply { mkdirs() }
            val destFile = uniqueDestinationFile(targetDir, desiredName)
            if (!partFile.renameTo(destFile)) {
                partFile.copyTo(destFile, overwrite = true)
                partFile.delete()
            }
            localPathStr = destFile.absolutePath
        }

        val finalSize = _tasks.value.firstOrNull { it.id == taskId }?.let {
            maxOf(it.totalBytes, it.downloadedBytes)
        } ?: 0L

        mutateTask(taskId) {
            it.copy(
                fileName = desiredName,
                status = DownloadStatus.COMPLETED,
                totalBytes = finalSize,
                downloadedBytes = finalSize,
                speedBytesPerSec = 0L,
                etaSeconds = 0L,
                localFilePath = localPathStr,
                publicUriString = publicUriStr,
                partFilePath = null,
                errorMessage = null,
            )
        }
    }

    private fun buildSegments(totalBytes: Long, count: Int): List<DownloadSegment> {
        val safeCount = if (totalBytes < MIN_MULTI_SEGMENT_BYTES) 1 else count.coerceIn(1, 4)
        val chunkSize = totalBytes / safeCount
        val segments = ArrayList<DownloadSegment>(safeCount)
        var start = 0L
        for (i in 0 until safeCount) {
            val end = if (i == safeCount - 1) totalBytes - 1L else (start + chunkSize - 1L)
            segments.add(DownloadSegment(index = i, startByte = start, endByte = end, downloadedBytes = 0L))
            start = end + 1L
        }
        return segments
    }

    private fun parseTotalBytesFromHeaders(code: Int, contentRange: String?, contentLength: Long?): Long {
        if (code == 206 && !contentRange.isNullOrBlank()) {
            val totalPart = contentRange.substringAfterLast('/', "").trim()
            val parsed = totalPart.toLongOrNull()
            if (parsed != null && parsed > 0L) return parsed
        }
        return contentLength?.takeIf { it > 0L } ?: -1L
    }

    private fun sanitizeFileName(raw: String): String {
        val cleaned = raw.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
        return cleaned.ifBlank { "video_${System.currentTimeMillis()}.mp4" }
    }

    private fun uniqueDestinationFile(dir: File, fileName: String): File {
        var candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")
        var index = 1
        while (candidate.exists()) {
            val nextName = if (ext.isNotEmpty()) "$base ($index).$ext" else "$base ($index)"
            candidate = File(dir, nextName)
            index++
        }
        return candidate
    }

    private fun guessMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "mkv" -> "video/x-matroska"
            "mp4", "m4v" -> "video/mp4"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "m3u8" -> "application/x-mpegURL"
            else -> "video/*"
        }
    }

    private fun mutateTasks(transform: (List<DownloadTask>) -> List<DownloadTask>) {
        _tasks.update(transform)
        persistence.saveTasks(_tasks.value)
    }

    private fun mutateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        updateTaskInMemory(id, persist = true, transform = transform)
    }

    private fun updateTaskInMemory(
        id: String,
        persist: Boolean,
        transform: (DownloadTask) -> DownloadTask,
    ) {
        _tasks.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
        if (persist) {
            persistence.saveTasks(_tasks.value)
        }
    }

    companion object {
        private const val PREFS_NAME = "fixlink_download_prefs"
        private const val KEY_CONCURRENCY = "queue_concurrency"
        private const val KEY_THREADS = "connections_per_download"
        private const val MIN_MULTI_SEGMENT_BYTES = 2L * 1024L * 1024L
        private const val DEFAULT_IO_BUFFER_SIZE = 32 * 1024
    }
}
