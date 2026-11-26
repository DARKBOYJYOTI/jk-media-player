package com.jk.mediaplayer.data.potoken

import android.util.Log
import android.webkit.WebView
import com.jk.mediaplayer.JKApplication
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * Supplies BotGuard `poToken`s to NewPipeExtractor.
 *
 * YouTube answers a subset of videos with no playable audio stream unless the request carries a
 * proof-of-origin token. The token is minted inside a WebView that runs YouTube's own BotGuard
 * JavaScript; the extractor asks this provider for one token per video id.
 *
 * Everything degrades to null when the device WebView is missing or broken: the extractor then
 * falls back to its best effort, exactly like before this class existed.
 */
object PoTokenProviderImpl : PoTokenProvider {

    private const val TAG = "PoTokenProvider"

    private val webViewSupported by lazy { WebViewSupport.isSupported }
    @Volatile
    private var webViewBadImpl = false

    private val lock = Any()
    private var visitorData: String? = null
    private var streamingPot: String? = null
    private var generator: PoTokenGenerator? = null

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        if (!webViewSupported || webViewBadImpl) return null
        return try {
            mint(videoId, forceRecreate = false)
        } catch (e: RuntimeException) {
            // RxJava wraps errors; unwrap so a broken WebView disables the provider for good.
            when (val cause = e.cause) {
                is BadWebViewException -> {
                    Log.e(TAG, "poToken disabled, WebView is broken", e)
                    webViewBadImpl = true
                    null
                }

                null -> throw e
                else -> throw cause
            }
        } catch (e: PoTokenException) {
            Log.w(TAG, "poToken mint failed for $videoId", e)
            null
        }
    }

    private fun mint(videoId: String, forceRecreate: Boolean): PoTokenResult {
        data class Snapshot(
            val generator: PoTokenGenerator,
            val visitorData: String,
            val streamingPot: String,
            val recreated: Boolean
        )

        val snapshot = synchronized(lock) {
            val shouldRecreate = generator == null || forceRecreate || generator!!.isExpired()
            if (shouldRecreate) {
                val requestInfo = InnertubeClientRequestInfo.ofWebClient()
                requestInfo.clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion()
                visitorData = YoutubeParsingHelper.getVisitorDataFromInnertube(
                    requestInfo,
                    Localization("en"),
                    ContentCountry("US"),
                    YoutubeParsingHelper.getYouTubeHeaders(),
                    YoutubeParsingHelper.YOUTUBEI_V1_URL,
                    null,
                    false
                )
                generator?.let { old -> WebViewSupport.runOnMain { old.close() } }
                generator = PoTokenWebView.newPoTokenGenerator(JKApplication.instance).blockingGet()
                // The streaming token must be generated before any per-video token.
                streamingPot = generator!!.generatePoToken(visitorData!!).blockingGet()
            }
            Snapshot(generator!!, visitorData!!, streamingPot!!, shouldRecreate)
        }

        val playerPot = try {
            snapshot.generator.generatePoToken(videoId).blockingGet()
        } catch (t: Throwable) {
            if (snapshot.recreated) throw t
            Log.e(TAG, "poToken failed, recreating the generator", t)
            return mint(videoId, forceRecreate = true)
        }

        if (com.jk.mediaplayer.BuildConfig.DEBUG) {
            Log.d(TAG, "poToken minted for $videoId")
        }
        return PoTokenResult(snapshot.visitorData, playerPot, snapshot.streamingPot)
    }

    /** Frees the WebView. Called when the app goes to the background for a long time. */
    fun release() {
        synchronized(lock) {
            generator?.let { old -> WebViewSupport.runOnMain { old.close() } }
            generator = null
            visitorData = null
            streamingPot = null
        }
    }
}

/** Small WebView capability check used before the BotGuard page is loaded. */
internal object WebViewSupport {
    val isSupported: Boolean by lazy {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                WebView.getCurrentWebViewPackage() != null
            } else {
                // The lookup API is O+; older devices keep the historical best-effort behaviour.
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun runOnMain(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { block() }
    }
}
