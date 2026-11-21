package com.example.download

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class DownloadPersistence(private val storageFile: File) {

    private val lock = Any()

    fun loadTasks(): List<DownloadTask> = synchronized(lock) {
        if (!storageFile.exists()) return emptyList()
        return try {
            val raw = storageFile.readText()
            if (raw.isBlank()) return emptyList()
            val array = JSONArray(raw)
            val tasks = ArrayList<DownloadTask>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                tasks.add(parseTask(obj))
            }
            tasks
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveTasks(tasks: List<DownloadTask>) = synchronized(lock) {
        try {
            storageFile.parentFile?.mkdirs()
            val array = JSONArray()
            tasks.forEach { task -> array.put(serializeTask(task)) }
            val tempFile = File(storageFile.parentFile, "${storageFile.name}.tmp")
            tempFile.writeText(array.toString())
            if (!tempFile.renameTo(storageFile)) {
                storageFile.writeText(array.toString())
                tempFile.delete()
            }
        } catch (_: Exception) {
            // Ignore write errors gracefully
        }
    }

    private fun serializeTask(task: DownloadTask): JSONObject {
        val segmentsArr = JSONArray()
        task.segments.forEach { seg ->
            segmentsArr.put(
                JSONObject().apply {
                    put("index", seg.index)
                    put("startByte", seg.startByte)
                    put("endByte", seg.endByte)
                    put("downloadedBytes", seg.downloadedBytes)
                }
            )
        }

        return JSONObject().apply {
            put("id", task.id)
            put("url", task.url)
            put("sourcePlayUrl", task.sourcePlayUrl ?: JSONObject.NULL)
            put("fileName", task.fileName)
            put("extension", task.extension ?: JSONObject.NULL)
            put("totalBytes", task.totalBytes)
            put("downloadedBytes", task.downloadedBytes)
            put("status", task.status.name)
            put("supportsRange", task.supportsRange)
            put("segments", segmentsArr)
            put("partFilePath", task.partFilePath ?: JSONObject.NULL)
            put("localFilePath", task.localFilePath ?: JSONObject.NULL)
            put("publicUriString", task.publicUriString ?: JSONObject.NULL)
            put("errorMessage", task.errorMessage ?: JSONObject.NULL)
            put("createdAt", task.createdAt)
            put("connectionCount", task.connectionCount.coerceIn(1, 8))
            put("overwriteExisting", task.overwriteExisting)
        }
    }

    private fun parseTask(obj: JSONObject): DownloadTask {
        val statusRaw = obj.optString("status", DownloadStatus.PAUSED.name)
        var status = try {
            DownloadStatus.valueOf(statusRaw)
        } catch (_: Exception) {
            DownloadStatus.PAUSED
        }
        // Recover cleanly after an unexpected process termination
        if (status == DownloadStatus.DOWNLOADING || status == DownloadStatus.CONNECTING) {
            status = DownloadStatus.PAUSED
        }

        val segmentsArr = obj.optJSONArray("segments") ?: JSONArray()
        val segments = ArrayList<DownloadSegment>(segmentsArr.length())
        for (i in 0 until segmentsArr.length()) {
            val s = segmentsArr.getJSONObject(i)
            segments.add(
                DownloadSegment(
                    index = s.getInt("index"),
                    startByte = s.getLong("startByte"),
                    endByte = s.getLong("endByte"),
                    downloadedBytes = s.optLong("downloadedBytes", 0L),
                )
            )
        }

        return DownloadTask(
            id = obj.getString("id"),
            url = obj.getString("url"),
            sourcePlayUrl = obj.optNullableString("sourcePlayUrl"),
            fileName = obj.getString("fileName"),
            extension = obj.optNullableString("extension"),
            totalBytes = obj.optLong("totalBytes", -1L),
            downloadedBytes = obj.optLong("downloadedBytes", 0L),
            status = status,
            supportsRange = obj.optBoolean("supportsRange", false),
            segments = segments,
            speedBytesPerSec = 0L,
            etaSeconds = null,
            partFilePath = obj.optNullableString("partFilePath"),
            localFilePath = obj.optNullableString("localFilePath"),
            publicUriString = obj.optNullableString("publicUriString"),
            errorMessage = obj.optNullableString("errorMessage"),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            connectionCount = obj.optInt("connectionCount", 4).coerceIn(1, 8),
            overwriteExisting = obj.optBoolean("overwriteExisting", false),
        )
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotEmpty() }
    }
}
