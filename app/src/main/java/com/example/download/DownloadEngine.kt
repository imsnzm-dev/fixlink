package com.example.download

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
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
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
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

    private val downloadDispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 32
    }

    private val downloadClient = OkHttpClient.Builder()
        .dispatcher(downloadDispatcher)
        .connectionPool(ConnectionPool(24, 5, TimeUnit.MINUTES))
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _tasks = MutableStateFlow(persistence.loadTasks())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _concurrencyLimit = MutableStateFlow(
        QueueConcurrency.fromCode(prefs.getString(KEY_CONCURRENCY, QueueConcurrency.TWO.code))
    )
    val concurrencyLimit: StateFlow<QueueConcurrency> = _concurrencyLimit.asStateFlow()

    private val _connectionsPerDownload = MutableStateFlow(
        prefs.getInt(KEY_THREADS, 4).coerceIn(MIN_PARTS, MAX_PARTS)
    )
    val connectionsPerDownload: StateFlow<Int> = _connectionsPerDownload.asStateFlow()

    private val _saveLocation = MutableStateFlow(loadSavedLocation())
    val saveLocation: StateFlow<DownloadSaveLocation> = _saveLocation.asStateFlow()

    private val activeJobs = ConcurrentHashMap<String, Job>()

    private fun loadSavedLocation(): DownloadSaveLocation {
        val uriStr = prefs.getString(KEY_SAVE_TREE_URI, null)?.takeIf { it.isNotBlank() }
        val display = prefs.getString(KEY_SAVE_DISPLAY_PATH, null)?.takeIf { it.isNotBlank() }
            ?: DownloadSaveLocation.DEFAULT_DISPLAY_PATH
        return DownloadSaveLocation(treeUriString = uriStr, displayPath = display)
    }

    fun setCustomSaveLocation(treeUri: Uri) {
        val display = resolveTreeDisplayName(treeUri)
        prefs.edit()
            .putString(KEY_SAVE_TREE_URI, treeUri.toString())
            .putString(KEY_SAVE_DISPLAY_PATH, display)
            .apply()
        _saveLocation.value = DownloadSaveLocation(
            treeUriString = treeUri.toString(),
            displayPath = display,
        )
    }

    fun resetSaveLocationToDefault() {
        prefs.edit()
            .remove(KEY_SAVE_TREE_URI)
            .remove(KEY_SAVE_DISPLAY_PATH)
            .apply()
        _saveLocation.value = DownloadSaveLocation()
    }

    private fun resolveTreeDisplayName(treeUri: Uri): String {
        runCatching {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            appContext.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        val rawSeg = Uri.decode(treeUri.lastPathSegment ?: "")
        return rawSeg.substringAfter(':', rawSeg).ifBlank { "Custom Folder" }
    }

    fun setConcurrencyLimit(limit: QueueConcurrency) {
        prefs.edit().putString(KEY_CONCURRENCY, limit.code).apply()
        _concurrencyLimit.value = limit
        scheduleQueue()
    }

    fun setConnectionsPerDownload(threads: Int) {
        val clamped = threads.coerceIn(MIN_PARTS, MAX_PARTS)
        prefs.edit().putInt(KEY_THREADS, clamped).apply()
        _connectionsPerDownload.value = clamped
    }

    fun resolveCandidateFileName(url: String, suggestedFileName: String? = null): String {
        val rawName = suggestedFileName?.takeIf { it.isNotBlank() }
            ?: VideoLinkExtractor.extractFilename(url.trim())
        return sanitizeFileName(rawName)
    }

    fun hasFileConflict(fileName: String): Boolean {
        val cleanName = sanitizeFileName(fileName)
        if (_tasks.value.any { it.fileName.equals(cleanName, ignoreCase = true) }) {
            return true
        }
        return existsInSaveDestination(cleanName)
    }

    fun suggestUniqueFileName(fileName: String): String {
        val clean = sanitizeFileName(fileName)
        if (!hasFileConflict(clean)) return clean
        val base = clean.substringBeforeLast('.', clean)
        val ext = clean.substringAfterLast('.', "")
        var index = 1
        while (index <= 999) {
            val candidate = if (ext.isNotEmpty()) "$base ($index).$ext" else "$base ($index)"
            if (!hasFileConflict(candidate)) return candidate
            index++
        }
        return "${base}_${System.currentTimeMillis()}" + if (ext.isNotEmpty()) ".$ext" else ""
    }

    private fun existsInSaveDestination(fileName: String): Boolean {
        val customTreeStr = _saveLocation.value.treeUriString
        if (!customTreeStr.isNullOrBlank()) {
            val treeUri = runCatching { Uri.parse(customTreeStr) }.getOrNull()
            if (treeUri != null) {
                val found = findSafDocumentUris(treeUri, fileName).isNotEmpty()
                if (found) return true
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val foundInMediaStore = runCatching {
                val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
                val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
                val args = arrayOf(fileName, "%FiXLink%")
                appContext.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    args,
                    null,
                )?.use { cursor ->
                    cursor.count > 0
                } ?: false
            }.getOrDefault(false)
            if (foundInMediaStore) return true
        }

        val fallbackDir = File(
            appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir,
            "FiXLink",
        )
        return File(fallbackDir, fileName).exists()
    }

    fun enqueue(
        url: String,
        sourcePlayUrl: String? = null,
        suggestedFileName: String? = null,
        overwriteExisting: Boolean = false,
    ): DownloadTask {
        val cleanUrl = url.trim()
        val safeName = resolveCandidateFileName(cleanUrl, suggestedFileName)
        val ext = VideoLinkExtractor.extractExtension(cleanUrl, safeName)

        if (overwriteExisting) {
            val conflictingTasks = _tasks.value.filter { it.fileName.equals(safeName, ignoreCase = true) }
            conflictingTasks.forEach { remove(it.id, deleteFiles = true) }
        }

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
            connectionCount = _connectionsPerDownload.value.coerceIn(MIN_PARTS, MAX_PARTS),
            overwriteExisting = overwriteExisting,
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

        var activeUrl = task.url
        val probeRequest = Request.Builder()
            .url(activeUrl)
            .get()
            .header("User-Agent", "curl/8.19.0")
            .header("Accept-Encoding", "identity")
            .header("Range", "bytes=0-")
            .build()

        var probeResponse = runCatching { downloadClient.newCall(probeRequest).execute() }.getOrNull()

        // Automatic expired-link refresh via original play URL on 401/403/410
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
                    .header("Accept-Encoding", "identity")
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
            val acceptRanges = resp.header("Accept-Ranges")
            supportsRange = resp.code == 206 || acceptRanges.equals("bytes", ignoreCase = true)

            serverTotalBytes = parseTotalBytesFromHeaders(
                code = resp.code,
                contentRange = resp.header("Content-Range"),
                contentLength = resp.header("Content-Length")?.toLongOrNull(),
            )

            val existingSegments = task.segments
            val canResumeExisting = supportsRange &&
                existingSegments.isNotEmpty() &&
                partFile.exists() &&
                serverTotalBytes > 0L &&
                existingSegments.last().endByte == serverTotalBytes - 1L

            val requestedThreads = _connectionsPerDownload.value.coerceIn(MIN_PARTS, MAX_PARTS)
            val threads = if (canResumeExisting) task.connectionCount.coerceIn(MIN_PARTS, MAX_PARTS) else requestedThreads
            val shouldUseMultiSegment = supportsRange && serverTotalBytes > MIN_MULTI_SEGMENT_BYTES && threads > 1

            if (!canResumeExisting && !shouldUseMultiSegment && (!supportsRange || task.downloadedBytes == 0L)) {
                useFirstResponseDirectly = true
                if (partFile.exists()) partFile.delete()
                mutateTask(taskId) {
                    it.copy(
                        fileName = resolvedFileName,
                        totalBytes = serverTotalBytes,
                        downloadedBytes = 0L,
                        supportsRange = supportsRange,
                        segments = emptyList(),
                        connectionCount = 1,
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
            finalizeCompletedFile(taskId, partFile, resolvedFileName, mimeType, task.overwriteExisting)
            return
        }

        task = _tasks.value.firstOrNull { it.id == taskId } ?: return
        val existingSegmentsValid = supportsRange &&
            task.segments.isNotEmpty() &&
            partFile.exists() &&
            serverTotalBytes > 0L &&
            task.segments.last().endByte == serverTotalBytes - 1L

        val desiredParts = if (existingSegmentsValid) {
            task.segments.size
        } else {
            _connectionsPerDownload.value.coerceIn(MIN_PARTS, MAX_PARTS)
        }

        val segments = if (existingSegmentsValid) {
            task.segments
        } else {
            if (partFile.exists()) partFile.delete()
            if (serverTotalBytes > 0L) {
                RandomAccessFile(partFile, "rw").use { it.setLength(serverTotalBytes) }
                buildSegments(serverTotalBytes, if (supportsRange) desiredParts else 1)
            } else {
                emptyList()
            }
        }

        if (segments.isEmpty()) {
            val req = Request.Builder()
                .url(activeUrl)
                .get()
                .header("User-Agent", "curl/8.19.0")
                .header("Accept-Encoding", "identity")
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
                        connectionCount = 1,
                        status = DownloadStatus.DOWNLOADING,
                    )
                }
                streamSingleResponse(taskId, resp, partFile, 0L, -1L)
            }
            finalizeCompletedFile(taskId, partFile, resolvedFileName, mimeType, task.overwriteExisting)
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
                connectionCount = segments.size,
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

        finalizeCompletedFile(taskId, partFile, resolvedFileName, mimeType, task.overwriteExisting)
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
                    var currentSeg = segment
                    var attempts = 0
                    var lastError: Throwable? = null

                    while (isActive && !currentSeg.isComplete && attempts < MAX_SEGMENT_RETRIES) {
                        attempts++
                        try {
                            val req = Request.Builder()
                                .url(url)
                                .get()
                                .header("User-Agent", "curl/8.19.0")
                                .header("Accept-Encoding", "identity")
                                .header("Range", "bytes=${currentSeg.currentOffset}-${currentSeg.endByte}")
                                .build()

                            downloadClient.newCall(req).execute().use { resp ->
                                if (resp.code != 206 && resp.code != 200) {
                                    throw IllegalStateException("HTTP ${resp.code} on part ${segment.index + 1}")
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
                            }

                            if (!currentSeg.isComplete && isActive) {
                                throw IllegalStateException("Part ${segment.index + 1} interrupted")
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            lastError = e
                            if (!currentSeg.isComplete && attempts < MAX_SEGMENT_RETRIES) {
                                delay(450L * attempts)
                            }
                        }
                    }

                    if (!currentSeg.isComplete) {
                        throw (lastError ?: IllegalStateException("Part ${segment.index + 1} failed"))
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
            delay(350)
            val now = System.currentTimeMillis()
            val currentBytes = downloadedCounter.get()
            val elapsedMs = (now - prevTime).coerceAtLeast(1L)
            val instantSpeed = ((currentBytes - prevBytes).coerceAtLeast(0L) * 1000L) / elapsedMs
            smoothedSpeed = if (smoothedSpeed == 0L) {
                instantSpeed
            } else {
                ((smoothedSpeed * 5L) + (instantSpeed * 5L)) / 10L
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
        overwriteExisting: Boolean,
    ) {
        val resolvedMime = mimeType?.substringBefore(';')?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: guessMimeType(desiredName)

        var publicUriStr: String? = null
        var localPathStr: String? = null

        // 1. Custom SAF Folder selected by user
        val customTreeStr = _saveLocation.value.treeUriString
        if (!customTreeStr.isNullOrBlank()) {
            val treeUri = runCatching { Uri.parse(customTreeStr) }.getOrNull()
            if (treeUri != null) {
                publicUriStr = saveToSafTree(
                    treeUri = treeUri,
                    partFile = partFile,
                    desiredName = desiredName,
                    mimeType = resolvedMime,
                    overwriteExisting = overwriteExisting,
                )
            }
        }

        // 2. Default MediaStore.Downloads/FiXLink (API 29+)
        if (publicUriStr == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = appContext.contentResolver
            if (overwriteExisting) {
                deleteExistingMediaStoreEntries(desiredName)
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, desiredName)
                put(MediaStore.Downloads.MIME_TYPE, resolvedMime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/FiXLink")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
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

        // 3. Fallback / API 24-28: app external Downloads/FiXLink
        if (publicUriStr == null) {
            val targetDir = File(
                appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir,
                "FiXLink",
            ).apply { mkdirs() }
            val destFile = if (overwriteExisting) {
                File(targetDir, desiredName).apply { if (exists()) delete() }
            } else {
                uniqueDestinationFile(targetDir, desiredName)
            }
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

    private fun saveToSafTree(
        treeUri: Uri,
        partFile: File,
        desiredName: String,
        mimeType: String,
        overwriteExisting: Boolean,
    ): String? {
        return try {
            val contentResolver = appContext.contentResolver
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val dirDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)

            val existingMatches = findSafDocumentUris(treeUri, desiredName)
            var finalName = desiredName
            if (existingMatches.isNotEmpty()) {
                if (overwriteExisting) {
                    existingMatches.forEach { docUri ->
                        runCatching { DocumentsContract.deleteDocument(contentResolver, docUri) }
                    }
                } else {
                    finalName = uniqueSafName(treeUri, desiredName)
                }
            }

            val createdUri = DocumentsContract.createDocument(
                contentResolver,
                dirDocUri,
                mimeType,
                finalName,
            ) ?: return null

            contentResolver.openOutputStream(createdUri)?.use { out ->
                FileInputStream(partFile).use { input ->
                    input.copyTo(out, bufferSize = DEFAULT_IO_BUFFER_SIZE)
                }
            } ?: return null

            partFile.delete()
            createdUri.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun uniqueSafName(treeUri: Uri, desiredName: String): String {
        val base = desiredName.substringBeforeLast('.', desiredName)
        val ext = desiredName.substringAfterLast('.', "")
        var index = 1
        while (index <= 999) {
            val candidate = if (ext.isNotEmpty()) "$base ($index).$ext" else "$base ($index)"
            if (findSafDocumentUris(treeUri, candidate).isEmpty()) return candidate
            index++
        }
        return "${base}_${System.currentTimeMillis()}" + if (ext.isNotEmpty()) ".$ext" else ""
    }

    private fun findSafDocumentUris(treeUri: Uri, fileName: String): List<Uri> {
        return runCatching {
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
            val matches = mutableListOf<Uri>()
            appContext.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val docId = if (idIdx >= 0) cursor.getString(idIdx) else continue
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) else continue
                    if (name.equals(fileName, ignoreCase = true)) {
                        matches.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId))
                    }
                }
            }
            matches
        }.getOrDefault(emptyList())
    }

    private fun deleteExistingMediaStoreEntries(fileName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val resolver = appContext.contentResolver
            val projection = arrayOf(MediaStore.Downloads._ID)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val args = arrayOf(fileName, "%FiXLink%")
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.Downloads._ID)
                while (cursor.moveToNext()) {
                    if (idIdx >= 0) {
                        val id = cursor.getLong(idIdx)
                        val itemUri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                        runCatching { resolver.delete(itemUri, null, null) }
                    }
                }
            }
        }
    }

    private fun buildSegments(totalBytes: Long, count: Int): List<DownloadSegment> {
        val safeCount = if (totalBytes < MIN_MULTI_SEGMENT_BYTES) 1 else count.coerceIn(MIN_PARTS, MAX_PARTS)
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
        private const val KEY_SAVE_TREE_URI = "custom_save_tree_uri"
        private const val KEY_SAVE_DISPLAY_PATH = "custom_save_display_path"
        const val MIN_PARTS = 1
        const val MAX_PARTS = 8
        private const val MIN_MULTI_SEGMENT_BYTES = 1L * 1024L * 1024L
        private const val DEFAULT_IO_BUFFER_SIZE = 256 * 1024
        private const val MAX_SEGMENT_RETRIES = 3
    }
}
