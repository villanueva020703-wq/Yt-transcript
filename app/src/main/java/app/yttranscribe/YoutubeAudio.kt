package app.yttranscribe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType

object YoutubeAudio {
    class Info(val id: String, val title: String, val url: String, val durationSec: Long)

    private var initialised = false

    private class NpDownloader(private val client: OkHttpClient) : Downloader() {
        override fun execute(request: Request): Response {
            val body = request.dataToSend()?.toRequestBody()
            val b = okhttp3.Request.Builder()
                .method(request.httpMethod(), body)
                .url(request.url())
                .header("User-Agent", Http.UA)
            request.headers().forEach { (name, values) ->
                b.removeHeader(name)
                values.forEach { b.addHeader(name, it) }
            }
            client.newCall(b.build()).execute().use { r ->
                if (r.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
                return Response(
                    r.code, r.message, r.headers.toMultimap(),
                    r.body?.string(), r.request.url.toString()
                )
            }
        }
    }

    @Synchronized
    private fun ensureInit() {
        if (!initialised) {
            NewPipe.init(NpDownloader(Http.client))
            initialised = true
        }
    }

    /** Accepts a full link, shared text containing a link, or a bare 11-char video id. */
    fun normalize(input: String): String {
        val t = input.trim()
        Regex("""https?://\S+""").find(t)?.let { return it.value }
        if (Regex("""^[\w-]{11}$""").matches(t)) return "https://www.youtube.com/watch?v=$t"
        throw IllegalArgumentException("Enter a valid YouTube link")
    }

    suspend fun resolve(url: String): Info = withContext(Dispatchers.IO) {
        ensureInit()
        val si = StreamInfo.getInfo(url)
        if (si.streamType == StreamType.LIVE_STREAM || si.streamType == StreamType.AUDIO_LIVE_STREAM) {
            error("Live streams aren't supported")
        }
        val candidates = si.audioStreams.filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                (it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL)
        }
        // Smallest file that still has a known bitrate: speech needs nothing more.
        val pick = candidates.filter { it.averageBitrate > 0 }.minByOrNull { it.averageBitrate }
            ?: candidates.firstOrNull()
            ?: error("No downloadable audio found (private, age-restricted or region-locked?)")
        Info(si.id, si.name, pick.content, si.duration)
    }
}
