package app.yttranscribe

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** whisper "base" multilingual, 5-bit quantized (~60 MB). Downloaded once, then fully offline. */
object ModelStore {
    private const val NAME = "ggml-base-q5_1.bin"
    private const val URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$NAME"
    private const val MIN_SIZE = 50_000_000L

    suspend fun ensure(ctx: Context, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val model = File(ctx.filesDir, NAME)
        if (model.exists() && model.length() > MIN_SIZE) return@withContext model
        val part = File(ctx.filesDir, "$NAME.part")
        val cc = currentCoroutineContext()
        var attempt = 0
        while (true) {
            try {
                Http.rangeDownload(URL, part, { cc.ensureActive() }, onProgress)
                break
            } catch (e: IOException) {
                if (++attempt >= 4) throw e
                delay(2000)
            }
        }
        if (!part.renameTo(model)) throw IOException("Could not save model")
        model
    }
}
