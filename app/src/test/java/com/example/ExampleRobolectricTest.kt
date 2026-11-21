package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.download.DownloadEngine
import com.example.download.DownloadPersistence
import com.example.download.DownloadSegment
import com.example.download.DownloadStatus
import com.example.download.DownloadTask
import com.example.download.QueueConcurrency
import com.example.intent.VlcIntentHelper
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name and unique package id from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("FiXLink", appName)
        assertEquals("com.aistudio.fixlink.qvrtpm", context.packageName)
    }

    @Test
    fun `toggle vlc interceptor alias updates component state`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(VlcIntentHelper.isVlcInterceptorEnabled(context))

        VlcIntentHelper.setVlcInterceptorEnabled(context, true)
        assertTrue(VlcIntentHelper.isVlcInterceptorEnabled(context))

        VlcIntentHelper.setVlcInterceptorEnabled(context, false)
        assertFalse(VlcIntentHelper.isVlcInterceptorEnabled(context))
    }

    @Test
    fun `extracts incoming url from vlc view and share intents`() {
        val playUrl = "http://googfilmazappfordownfilmmedis.xyz/play-link/play.php?t=serie&i=575432"

        val viewVideoIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(playUrl), "video/*")
        }
        assertEquals(playUrl, VlcIntentHelper.extractUrlFromIntent(viewVideoIntent))

        val broadHttpIntent = Intent(Intent.ACTION_VIEW, Uri.parse(playUrl))
        assertEquals(playUrl, VlcIntentHelper.extractUrlFromIntent(broadHttpIntent))

        val vlcSchemeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vlc://$playUrl"))
        assertEquals(playUrl, VlcIntentHelper.extractUrlFromIntent(vlcSchemeIntent))

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, playUrl)
        }
        assertEquals(playUrl, VlcIntentHelper.extractUrlFromIntent(shareIntent))

        val doubleQueryPlayUrl =
            "http://googfilmazappfordownfilmmedis.xyz/play-link/play.php?play-link/play.php?&t=movie&i=72648&m=09210076896&ap=Fj?&t=movie&i=72648&m=09210076896&s=vcp"
        val intentScheme = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("intent://googfilmazappfordownfilmmedis.xyz/play-link/play.php?play-link/play.php?&t=movie&i=72648&m=09210076896&ap=Fj?&t=movie&i=72648&m=09210076896&s=vcp#Intent;scheme=http;package=org.videolan.vlc;end"),
        )
        assertEquals(doubleQueryPlayUrl, VlcIntentHelper.extractUrlFromIntent(intentScheme))
    }

    @Test
    fun `download persistence saves segments and normalizes active tasks to paused on reload`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storageFile = File(context.cacheDir, "test_downloads_persistence.json").apply { delete() }
        val persistence = DownloadPersistence(storageFile)

        val activeTask = DownloadTask(
            id = "task-1",
            url = "http://cdn.example.com/movie.mkv?expire=999",
            sourcePlayUrl = "http://example.com/play.php?i=1",
            fileName = "movie.mkv",
            extension = "MKV",
            totalBytes = 10_000L,
            downloadedBytes = 4_000L,
            status = DownloadStatus.DOWNLOADING,
            supportsRange = true,
            segments = listOf(
                DownloadSegment(index = 0, startByte = 0L, endByte = 4_999L, downloadedBytes = 3_000L),
                DownloadSegment(index = 1, startByte = 5_000L, endByte = 9_999L, downloadedBytes = 1_000L),
            ),
            partFilePath = "/tmp/task-1.part",
            connectionCount = 2,
        )
        val completedTask = DownloadTask(
            id = "task-2",
            url = "http://cdn.example.com/done.mp4",
            fileName = "done.mp4",
            extension = "MP4",
            totalBytes = 5_000L,
            downloadedBytes = 5_000L,
            status = DownloadStatus.COMPLETED,
            localFilePath = "/tmp/done.mp4",
        )

        persistence.saveTasks(listOf(activeTask, completedTask))
        val reloaded = persistence.loadTasks()

        assertEquals(2, reloaded.size)
        assertEquals(DownloadStatus.PAUSED, reloaded[0].status)
        assertEquals(2, reloaded[0].segments.size)
        assertEquals(3_000L, reloaded[0].segments[0].downloadedBytes)
        assertEquals("http://example.com/play.php?i=1", reloaded[0].sourcePlayUrl)
        assertEquals(DownloadStatus.COMPLETED, reloaded[1].status)
        assertEquals("/tmp/done.mp4", reloaded[1].localFilePath)

        storageFile.delete()
    }

    @Test
    fun `download engine updates queue concurrency threads pause move to top and remove`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = DownloadEngine(context)

        engine.setConcurrencyLimit(QueueConcurrency.ONE)
        engine.setConnectionsPerDownload(4)
        assertEquals(QueueConcurrency.ONE, engine.concurrencyLimit.value)
        assertEquals(4, engine.connectionsPerDownload.value)

        val first = engine.enqueue("http://127.0.0.1:1/video1.mkv", suggestedFileName = "video1.mkv")
        val second = engine.enqueue("http://127.0.0.1:1/video2.mp4", suggestedFileName = "video2.mp4")

        engine.pause(first.id)
        engine.pause(second.id)

        val pausedIds = engine.tasks.value.associateBy { it.id }
        assertEquals(DownloadStatus.PAUSED, pausedIds[first.id]?.status)
        assertEquals(DownloadStatus.PAUSED, pausedIds[second.id]?.status)

        engine.moveToTop(first.id)
        assertEquals(first.id, engine.tasks.value.first().id)

        engine.remove(first.id)
        engine.remove(second.id)
        assertTrue(engine.tasks.value.none { it.id == first.id || it.id == second.id })
    }
}
