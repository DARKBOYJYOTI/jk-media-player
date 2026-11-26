package com.jk.mediaplayer.data

import okhttp3.OkHttpClient
import okhttp3.Request as OkHttpRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * NewPipeExtractor transport backed by OkHttp.
 *
 * The extractor only speaks through this class, so every request it makes shares one
 * connection pool and one timeout policy. Nothing is logged and no credentials are added:
 * the headers come from the extractor itself.
 */
class YouTubeDownloader private constructor(
    private val client: OkHttpClient
) : Downloader() {

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val url = request.url()
        if (url.isBlank()) throw IOException("Empty request URL")
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw IOException("Unsupported request scheme")
        }

        val body = request.dataToSend()?.toRequestBody()
        // googlevideo answers 403 when the player user agent differs from the one that produced
        // the URL, so remember exactly what the extractor sent.
        for ((name, values) in request.headers()) {
            if (name.equals("User-Agent", ignoreCase = true)) {
                values.firstOrNull { it.isNotBlank() }?.let(lastUserAgent::set)
                break
            }
        }
        val builder = OkHttpRequest.Builder()
            .url(url)
            .method(request.httpMethod(), body)

        for ((name, values) in request.headers()) {
            // OkHttp sets the length itself; a copied value breaks chunked uploads.
            if (name.equals("Content-Length", ignoreCase = true)) continue
            for (value in values) builder.addHeader(name, value)
        }

        client.newCall(builder.build()).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (response.code == 429) {
                throw ReCaptchaException("Request was rate limited", url)
            }
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                responseBody,
                response.request.url.toString()
            )
        }
    }

    class Builder {
        fun build(): YouTubeDownloader = shared
    }

    companion object {
        /** The user agent that produced the most recent extraction request, if any. */
        fun rememberedUserAgent(): String? = lastUserAgent.get()

        private val lastUserAgent = java.util.concurrent.atomic.AtomicReference<String?>(null)

        private val shared: YouTubeDownloader by lazy {
            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .build()
            YouTubeDownloader(client)
        }
    }
}
