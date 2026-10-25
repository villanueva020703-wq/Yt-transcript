package app.yttranscribe

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder

/** Streams any Android-decodable audio file out as 16 kHz mono float PCM in small buffers. */
object PcmDecoder {

    private class Resampler(rate: Int) {
        private val step = rate / 16000.0
        private var acc = 0f
        private var cnt = 0
        private var inIdx = 0L
        private var outIdx = 0L
        var value = 0f

        /** Box-filter decimation. Returns true when [value] holds a new 16 kHz sample. */
        fun push(x: Float): Boolean {
            acc += x; cnt++; inIdx++
            if (inIdx >= (outIdx + 1) * step) {
                value = acc / cnt; acc = 0f; cnt = 0; outIdx++
                return true
            }
            return false
        }
    }

    fun decode(
        path: String,
        durationHintUs: Long,
        onPcm: (FloatArray, Int) -> Unit,
        onProgress: (Float) -> Unit
    ) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(path)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track in downloaded file")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION)
            else durationHintUs

            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rs = Resampler(fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE))

            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()

            val info = MediaCodec.BufferInfo()
            val out = FloatArray(16000)
            var n = 0
            var inDone = false
            var outDone = false
            var lastPts = 0L
            var lastReport = 0L

            while (!outDone) {
                if (!inDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            lastPts = ex.sampleTime
                            c.queueInputBuffer(i, 0, size, lastPts, 0)
                            ex.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        rs = Resampler(f.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    }
                    o >= 0 -> {
                        val buf = c.getOutputBuffer(o)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                        while (sb.remaining() >= channels) {
                            var s = 0f
                            for (k in 0 until channels) s += sb.get()
                            if (rs.push(s / channels / 32768f)) {
                                out[n++] = rs.value
                                if (n == out.size) { onPcm(out, n); n = 0 }
                            }
                        }
                        c.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true

                        val now = System.nanoTime()
                        if (durUs > 0 && now - lastReport > 500_000_000L) {
                            lastReport = now
                            onProgress((lastPts.toFloat() / durUs).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            if (n > 0) onPcm(out, n)
            onProgress(1f)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release()
            ex.release()
        }
    }
}
