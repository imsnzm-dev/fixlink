package com.example

import com.example.download.DownloadStatus
import com.example.download.DownloadTask
import com.example.download.QueueConcurrency
import com.example.network.CurlRedirectResolver
import com.example.network.VideoLinkExtractor
import com.example.ui.AppDestination
import com.example.ui.AppLanguage
import com.example.ui.DownloadFilter
import com.example.ui.LinkExtractorViewModel
import com.example.ui.filterDownloadTasks
import com.example.ui.stringsFor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLinkExtractorTest {

    @Test
    fun `default viewmodel state starts in English on resolver tab`() {
        val vm = LinkExtractorViewModel()
        assertEquals(AppLanguage.EN, vm.screenState.value.language)
        assertEquals(AppDestination.RESOLVER, vm.screenState.value.destination)
        assertFalse(vm.screenState.value.isVlcInterceptorEnabled)
        assertFalse(vm.screenState.value.isDeveloperMode)
    }

    @Test
    fun `extractInputUrl extracts url from curl command or vlc scheme or plain url`() {
        val plain = "http://googfilmazappfordownfilmmedis.xyz/play-link/play.php?play-link/play.php?&t=serie&i=575432&m=09210076896&ap=Fj&s=vcp"
        assertEquals(plain, VideoLinkExtractor.extractInputUrl(plain))

        val cmd = """curl -L -v "$plain""""
        assertEquals(plain, VideoLinkExtractor.extractInputUrl(cmd))

        val vlcUrl = "vlc://$plain"
        assertEquals(plain, VideoLinkExtractor.extractInputUrl(vlcUrl))

        val doubleQuery =
            "http://googfilmazappfordownfilmmedis.xyz/play-link/play.php?play-link/play.php?&t=movie&i=72648&m=09210076896&ap=Fj?&t=movie&i=72648&m=09210076896&s=vcp"
        assertEquals(doubleQuery, VideoLinkExtractor.extractInputUrl(doubleQuery))
        assertTrue(doubleQuery.toHttpUrlOrNull() != null)
    }

    @Test
    fun `isVideoDownloadUrl detects mkv mp4 avi with query params`() {
        assertTrue(
            VideoLinkExtractor.isVideoDownloadUrl(
                "http://dl10.tdmm2.ir/movies/sere/05/We.Are.All.Trying.Here/fasl1dub/We.Are.All.Trying.Here.E12.720p.NF.WEB-DL.Golchindl.DUBLE.mkv?expire=1795266368&hash=1qREDmRrIguQTdhLkw2YDw"
            )
        )
        assertTrue(VideoLinkExtractor.isVideoDownloadUrl("https://cdn.example.com/video/sample.MP4"))
        assertTrue(VideoLinkExtractor.isVideoDownloadUrl("http://cdn.example.com/movie.avi?token=123"))
        assertFalse(VideoLinkExtractor.isVideoDownloadUrl("http://example.com/play-link/play.php?t=serie"))
    }

    @Test
    fun `developer mode unlocks only with exact password ali3973`() {
        val vm = LinkExtractorViewModel()
        assertFalse(vm.screenState.value.isDeveloperMode)

        assertFalse(vm.tryUnlockDeveloperMode("wrong"))
        assertFalse(vm.tryUnlockDeveloperMode("ALI3973"))
        assertFalse(vm.screenState.value.isDeveloperMode)

        assertTrue(vm.tryUnlockDeveloperMode("ali3973"))
        assertTrue(vm.screenState.value.isDeveloperMode)

        vm.disableDeveloperMode()
        assertFalse(vm.screenState.value.isDeveloperMode)
    }

    @Test
    fun `normal mode strings do not mention curl in either English or Persian`() {
        for (lang in AppLanguage.entries) {
            val s = stringsFor(lang)
            val normalTexts = listOf(
                s.appName,
                s.appSubtitle,
                s.inputCardTitle,
                s.inputPlaceholder,
                s.inputHelper,
                s.actionExtract,
                s.loadingNormalMessage,
                s.resultCardTitle,
                s.emptyTitle,
                s.emptyDesc,
                s.errorNoVideoFound,
                s.downloadsTitle,
                s.downloadsEmptyTitle,
                s.downloadsEmptyDesc,
            )
            for (text in normalTexts) {
                assertFalse("Unexpected curl mention in: $text", text.contains("curl", ignoreCase = true))
            }
        }
    }

    @Test
    fun `resolveFromRawTrace returns final dl10 tdmm2 ir mkv url from user curl trace`() {
        val trace = """
            curl -L -v "http://googfilmazappfordownfilmmedis.xyz/play-link/play.php?play-link/play.php?&t=serie&i=575432&m=09210076896&ap=Fj&s=vcp"
            < HTTP/1.1 302 Found
            < location: http://dl10-tdmm.cloudcdn.site/movies/sere/05/We.Are.All.Trying.Here/fasl1dub/We.Are.All.Trying.Here.E12.720p.NF.WEB-DL.Golchindl.DUBLE.mkv?expire=1795266368&hash=1qREDmRrIguQTdhLkw2YDw
            * Issue another request to this URL: 'http://dl10-tdmm.cloudcdn.site/movies/sere/05/We.Are.All.Trying.Here/fasl1dub/We.Are.All.Trying.Here.E12.720p.NF.WEB-DL.Golchindl.DUBLE.mkv?expire=1795266368&hash=1qREDmRrIguQTdhLkw2YDw'
            < HTTP/1.1 302 Found
            < Location: http://dl10.tdmm2.ir/movies/sere/05/We.Are.All.Trying.Here/fasl1dub/We.Are.All.Trying.Here.E12.720p.NF.WEB-DL.Golchindl.DUBLE.mkv?expire=1795266368&hash=1qREDmRrIguQTdhLkw2YDw
            * Issue another request to this URL: 'http://dl10.tdmm2.ir/movies/sere/05/We.Are.All.Trying.Here/fasl1dub/We.Are.All.Trying.Here.E12.720p.NF.WEB-DL.Golchindl.DUBLE.mkv?expire=1795266368&hash=1qREDmRrIguQTdhLkw2YDw'
            < HTTP/1.1 200 OK
            < Content-Type: application/octet-stream
            < Content-Length: 755072553
        """.trimIndent()

        val resolver = CurlRedirectResolver()
        val result = resolver.resolveFromRawTrace(trace)

        assertEquals(
            "http://dl10.tdmm2.ir/movies/sere/05/We.Are.All.Trying.Here/fasl1dub/We.Are.All.Trying.Here.E12.720p.NF.WEB-DL.Golchindl.DUBLE.mkv?expire=1795266368&hash=1qREDmRrIguQTdhLkw2YDw",
            result.directVideoUrl,
        )
        assertEquals(
            "We.Are.All.Trying.Here.E12.720p.NF.WEB-DL.Golchindl.DUBLE.mkv",
            result.filename,
        )
        assertEquals("MKV", result.extension)
        assertEquals("720.1 MB", result.fileSizeFormatted)
        assertEquals(200, result.finalStatusCode)
    }

    @Test
    fun `download task formats progress eta speed and concurrency filters cleanly`() {
        val active = DownloadTask(
            id = "1",
            url = "http://example.com/a.mkv",
            fileName = "a.mkv",
            totalBytes = 100L * 1024L * 1024L,
            downloadedBytes = 25L * 1024L * 1024L,
            status = DownloadStatus.DOWNLOADING,
            speedBytesPerSec = 2L * 1024L * 1024L,
            etaSeconds = 95L,
        )
        val queued = DownloadTask(
            id = "2",
            url = "http://example.com/b.mp4",
            fileName = "b.mp4",
            status = DownloadStatus.QUEUED,
        )
        val completed = DownloadTask(
            id = "3",
            url = "http://example.com/c.avi",
            fileName = "c.avi",
            status = DownloadStatus.COMPLETED,
        )

        assertEquals(25, active.progressPercent)
        assertEquals("2.0 MB/s", active.formattedSpeed())
        assertEquals("1m 35s", active.formattedEta())

        assertEquals(QueueConcurrency.ONE, QueueConcurrency.fromCode("1"))
        assertEquals(QueueConcurrency.TWO, QueueConcurrency.fromCode("invalid"))
        assertEquals(QueueConcurrency.ALL, QueueConcurrency.fromCode("all"))

        val allTasks = listOf(active, queued, completed)
        assertEquals(3, filterDownloadTasks(allTasks, DownloadFilter.ALL).size)
        assertEquals(listOf(active), filterDownloadTasks(allTasks, DownloadFilter.ACTIVE))
        assertEquals(listOf(queued), filterDownloadTasks(allTasks, DownloadFilter.QUEUED))
        assertEquals(listOf(completed), filterDownloadTasks(allTasks, DownloadFilter.COMPLETED))
    }
}
