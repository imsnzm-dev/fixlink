package com.example.network

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

sealed interface GitHubUpdateResult {
    data class UpdateAvailable(
        val currentVersion: String,
        val latestVersion: String,
        val releaseName: String,
        val releaseNotes: String,
        val releaseUrl: String,
        val apkDownloadUrl: String?,
        val apkFileName: String?,
    ) : GitHubUpdateResult

    data class UpToDate(
        val currentVersion: String,
        val latestVersion: String,
        val repoUrl: String,
    ) : GitHubUpdateResult

    data class NoReleasesYet(
        val currentVersion: String,
        val repoUrl: String,
    ) : GitHubUpdateResult

    data class Error(val message: String) : GitHubUpdateResult
}

class GitHubUpdateChecker(
    private val client: OkHttpClient = defaultClient(),
) {
    companion object {
        const val REPO_URL = "https://github.com/imsnzm-dev/fixlink"
        private const val LATEST_RELEASE_API = "https://api.github.com/repos/imsnzm-dev/fixlink/releases/latest"
        private const val RELEASES_LIST_API = "https://api.github.com/repos/imsnzm-dev/fixlink/releases?per_page=1"
        private const val TAGS_LIST_API = "https://api.github.com/repos/imsnzm-dev/fixlink/tags?per_page=1"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()

        fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
            val remoteParts = parseVersionParts(remoteVersion)
            val currentParts = parseVersionParts(currentVersion)
            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r != c) return r > c
            }
            return false
        }

        private fun parseVersionParts(raw: String): List<Int> {
            val cleaned = raw.trim().removePrefix("v").removePrefix("V")
                .substringBefore('-')
                .substringBefore('+')
            return cleaned.split('.').mapNotNull { part ->
                part.filter { it.isDigit() }.toIntOrNull()
            }
        }
    }

    suspend fun checkForUpdates(currentVersion: String): GitHubUpdateResult = withContext(Dispatchers.IO) {
        try {
            val latestJson = fetchJsonObject(LATEST_RELEASE_API)
            if (latestJson != null) {
                return@withContext evaluateReleaseJson(latestJson, currentVersion)
            }

            // Fallback: check releases list (handles pre-releases)
            val releasesArray = fetchJsonArray(RELEASES_LIST_API)
            if (releasesArray != null && releasesArray.length() > 0) {
                return@withContext evaluateReleaseJson(releasesArray.getJSONObject(0), currentVersion)
            }

            // Fallback: check git tags
            val tagsArray = fetchJsonArray(TAGS_LIST_API)
            if (tagsArray != null && tagsArray.length() > 0) {
                val tagObj = tagsArray.getJSONObject(0)
                val tagName = tagObj.optString("name", "").trim()
                if (tagName.isNotEmpty()) {
                    return@withContext if (isNewerVersion(tagName, currentVersion)) {
                        GitHubUpdateResult.UpdateAvailable(
                            currentVersion = currentVersion,
                            latestVersion = tagName,
                            releaseName = tagName,
                            releaseNotes = "",
                            releaseUrl = "$REPO_URL/releases/tag/$tagName",
                            apkDownloadUrl = null,
                            apkFileName = null,
                        )
                    } else {
                        GitHubUpdateResult.UpToDate(
                            currentVersion = currentVersion,
                            latestVersion = tagName,
                            repoUrl = REPO_URL,
                        )
                    }
                }
            }

            GitHubUpdateResult.NoReleasesYet(
                currentVersion = currentVersion,
                repoUrl = REPO_URL,
            )
        } catch (e: Exception) {
            GitHubUpdateResult.Error(e.localizedMessage ?: "GitHub connection failed")
        }
    }

    private fun evaluateReleaseJson(obj: JSONObject, currentVersion: String): GitHubUpdateResult {
        val tagName = obj.optString("tag_name", "").ifBlank { obj.optString("name", "") }.trim()
        val releaseName = obj.optString("name", tagName).ifBlank { tagName }
        val body = obj.optString("body", "").trim()
        val htmlUrl = obj.optString("html_url", "$REPO_URL/releases").ifBlank { "$REPO_URL/releases" }

        var apkUrl: String? = null
        var apkName: String? = null
        val assets = obj.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val url = asset.optString("browser_download_url", "")
                if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                    apkUrl = url
                    apkName = name
                    break
                }
            }
        }

        return if (tagName.isNotEmpty() && isNewerVersion(tagName, currentVersion)) {
            GitHubUpdateResult.UpdateAvailable(
                currentVersion = currentVersion,
                latestVersion = tagName,
                releaseName = releaseName,
                releaseNotes = body,
                releaseUrl = htmlUrl,
                apkDownloadUrl = apkUrl,
                apkFileName = apkName,
            )
        } else {
            GitHubUpdateResult.UpToDate(
                currentVersion = currentVersion,
                latestVersion = tagName.ifBlank { currentVersion },
                repoUrl = REPO_URL,
            )
        }
    }

    private fun fetchJsonObject(url: String): JSONObject? {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "FiXLink-Android")
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw IllegalStateException("GitHub API HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            return if (body.isBlank()) null else JSONObject(body)
        }
    }

    private fun fetchJsonArray(url: String): JSONArray? {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "FiXLink-Android")
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw IllegalStateException("GitHub API HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            return if (body.isBlank()) null else JSONArray(body)
        }
    }
}
