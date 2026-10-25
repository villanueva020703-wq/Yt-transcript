package app.yttranscribe

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.sqrt

data class Segment(val startMs: Long, val endMs: Long, val text: String)

sealed interface TState {
    data object Idle : TState
    data class Working(val stage: String, val progress: Float?) : TState
    data class Done(val title: String) : TState
    data class Failed(val message: String) : TState
}

object Transcriber {
    private val _state = MutableStateFlow<TState>(TState.Idle)
    val state: StateFlow<TState> = _state

    private val _segments = MutableStateFlow<List<Segment>>(emptyList())
    val segments: StateFlow<List<Segment>> = _segments

    private const val SR = 16_000
    private const val WINDOW = 30 * SR
    private val NOISE_TAG = Regex("""^[\[(*♪].*[\])*♪]$""")

    fun fmtTime(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    fun render(list: List<Segment>, timestamps: Boolean): String =
        list.joinToString("\n") { if (timestamps) "[${fmtTime(it.startMs)}] ${it.text}" else it.text }

    suspend fun run(ctx: Context, input: String) = withContext(Dispatchers.Default) {
        val cc = currentCoroutineContext()
        _segments.value = emptyList()
        var whisper = 0L
        try {
            purgeStale(ctx)
            val url = YoutubeAudio.normalize(input)

            _state.value = TState.Working("Preparing speech model", 0f)
            val model = ModelStore.ensure(ctx) { _state.value = TState.Working("Downloading speech model", it) }

            _state.value = TState.Working("Finding audio", null)
            var info = YoutubeAudio.resolve(url)
            val file = File(ctx.cacheDir, "yt_${info.id}.audio")

            var attempt = 0
            while (true) {
                try {
                    _state.value = TState.Working("Downloading audio", 0f)
                    withContext(Dispatchers.IO) {
                        Http.rangeDownload(info.url, file, { cc.ensureActive() }) {
                            _state.value = TState.Working("Downloading audio", it)
                        }
                    }
                    break
                } catch (e: IOException) {          // network drop or expired link: re-resolve and resume
                    if (++attempt >= 4) throw e
                    delay(2000)
                    info = YoutubeAudio.resolve(url)
                }
            }

            _state.value = TState.Working("Loading model", null)
            whisper = WhisperLib.init(model.absolutePath)
            check(whisper != 0L) { "Could not load speech model" }
            val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
            val engine = Engine(whisper, threads)

            _state.value = TState.Working("Transcribing", 0f)
            PcmDecoder.decode(
                file.absolutePath, info.durationSec * 1_000_000L,
                onPcm = { a, n -> cc.ensureActive(); engine.feed(a, n) },
                onProgress = { _state.value = TState.Working("Transcribing", it) }
            )
            engine.finish()

            file.delete()
            _state.value = TState.Done(info.title)
        } catch (e: CancellationException) {
            _state.value = TState.Idle
            throw e
        } catch (e: Throwable) {
            _state.value = TState.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            if (whisper != 0L) WhisperLib.free(whisper)
        }
    }

    private fun purgeStale(ctx: Context) {
        val cutoff = System.currentTimeMillis() - 2L * 24 * 3600 * 1000
        ctx.cacheDir.listFiles { f -> f.name.endsWith(".audio") && f.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    /**
     * Collects PCM into 30 s windows. Each window is cut at the quietest spot in its last
     * 4 seconds so words aren't split; the remainder carries into the next window.
     * Timestamps come from absolute sample offsets, so they never drift.
     */
    private class Engine(val ctx: Long, val threads: Int) {
        private val buf = FloatArray(WINDOW)
        private var len = 0
        private var base = 0L
        private var lang: String? = null

        fun feed(a: FloatArray, n: Int) {
            var off = 0
            while (off < n) {
                val t = minOf(n - off, WINDOW - len)
                System.arraycopy(a, off, buf, len, t)
                len += t; off += t
                if (len == WINDOW) emit(findCut())
            }
        }

        fun finish() { if (len > 0) emit(len) }

        private fun findCut(): Int {
            val step = 1600
            var best = WINDOW - SR
            var bestE = Float.MAX_VALUE
            var s = WINDOW - 4 * SR
            while (s + step <= WINDOW) {
                var e = 0f
                for (i in s until s + step) e += buf[i] * buf[i]
                if (e < bestE) { bestE = e; best = s + step / 2 }
                s += step / 2
            }
            return best
        }

        private fun emit(cut: Int) {
            process(cut)
            System.arraycopy(buf, cut, buf, 0, len - cut)
            len -= cut
            base += cut
        }

        private fun process(n: Int) {
            if (n < SR / 2) return
            var e = 0.0
            for (i in 0 until n) e += buf[i] * buf[i]
            if (sqrt(e / n) < 0.003) return                 // silence: skip, no hallucinations

            val l = lang ?: WhisperLib.detectLanguage(ctx, buf, n, threads)
            val raw = WhisperLib.transcribe(ctx, buf, n, l, threads)
            val baseMs = base * 1000 / SR
            val added = ArrayList<Segment>()
            val all = _segments.value
            for (b in raw) {
                val p = String(b, Charsets.UTF_8).split('\t', limit = 3)
                if (p.size < 3) continue
                val text = p[2].trim()
                if (text.isEmpty() || NOISE_TAG.matches(text)) continue
                val recent = (all.takeLast(2) + added).takeLast(2)
                if (recent.size == 2 && recent.all { it.text == text }) continue   // repetition-loop guard
                added += Segment(baseMs + p[0].toLong() * 10, baseMs + p[1].toLong() * 10, text)
            }
            if (added.isNotEmpty()) {
                lang = l
                _segments.value = all + added
            }
        }
    }
}
