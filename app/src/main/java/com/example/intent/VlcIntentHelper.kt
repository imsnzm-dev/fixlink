package com.example.intent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.example.network.VideoLinkExtractor

object VlcIntentHelper {

    private const val ALIAS_CLASS_NAME = "com.example.VlcLinkReceiverAlias"
    const val VLC_PACKAGE_NAME = "org.videolan.vlc"

    fun isVlcInterceptorEnabled(context: Context): Boolean {
        val component = ComponentName(context.packageName, ALIAS_CLASS_NAME)
        val state = context.packageManager.getComponentEnabledSetting(component)
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }

    fun setVlcInterceptorEnabled(context: Context, enabled: Boolean) {
        val component = ComponentName(context.packageName, ALIAS_CLASS_NAME)
        val newState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        context.packageManager.setComponentEnabledSetting(
            component,
            newState,
            PackageManager.DONT_KILL_APP,
        )
    }

    @Suppress("DEPRECATION")
    fun isVlcInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(VLC_PACKAGE_NAME, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun openVlcAppSettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$VLC_PACKAGE_NAME")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Extracts a stream or video URL from incoming external-player (VLC-targeted),
     * intent:// wrapped, or share Intents by inspecting dataString, clipData, and all extras.
     */
    fun extractUrlFromIntent(intent: Intent?): String? {
        if (intent == null) return null

        val action = intent.action
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) {
            return null
        }

        val candidates = mutableListOf<String>()

        intent.dataString?.let { raw ->
            candidates.add(raw)
            if (raw.startsWith("intent://", ignoreCase = true)) {
                unwrapIntentScheme(raw)?.let { candidates.add(it) }
            }
        }

        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { candidates.add(it) }
        intent.getStringExtra("url")?.let { candidates.add(it) }
        intent.getStringExtra("video_url")?.let { candidates.add(it) }
        intent.getStringExtra("stream_url")?.let { candidates.add(it) }
        intent.getStringExtra("link")?.let { candidates.add(it) }

        intent.extras?.let { bundle ->
            @Suppress("DEPRECATION")
            for (key in bundle.keySet()) {
                val value = bundle.get(key)
                if (value is String && value.isNotBlank()) {
                    candidates.add(value)
                } else if (value is Uri) {
                    candidates.add(value.toString())
                }
            }
        }

        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).text?.toString()?.let { candidates.add(it) }
                clip.getItemAt(i).uri?.toString()?.let { candidates.add(it) }
            }
        }

        for (candidate in candidates) {
            val extracted = VideoLinkExtractor.extractInputUrl(candidate)
            if (extracted != null) {
                return extracted
            }
        }
        return null
    }

    private fun unwrapIntentScheme(intentUri: String): String? {
        return try {
            val parsed = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME)
            parsed.dataString ?: parsed.getStringExtra("S.browser_fallback_url")
        } catch (_: Exception) {
            null
        }
    }
}
