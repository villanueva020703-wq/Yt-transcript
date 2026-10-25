package app.yttranscribe

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

object Http {
    const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val CHUNK = 4L * 1024 * 1024

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Resumable download in 4 MB range requests (also avoids YouTube throttling of
     * single huge requests). Continues from dest.length() if the file already exists.
     * Blocking; call from a background dispatcher. [check] should throw on cancellation.
     */
    fun rangeDownload(url: String, dest: File, check: () -> Unit, onProgress: (Float) -> Unit) {
        var have = if (dest.exists()) dest.length() else 0L
        RandomAccessFile(dest, "rw").use { raf ->
            while (true) {
                check()
                var total = -1L
                var read = 0L
                val req = Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Range", "bytes=$have-${have + CHUNK - 1}")
                    .build()
                client.newCall(req).execute().use { r ->
                    if (r.code == 416) { total = have; return@use }
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val body = r.body ?: throw IOException("Empty response")
                    if (r.code == 200) {          // server ignored Range: whole file follows
                        have = 0; raf.setLength(0)
                    } else {
                        total = r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                        if (total < 0) throw IOException("Missing Content-Range")
                    }
                    raf.seek(have)
                    val src = body.byteStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        have += n; read += n
                        check()
                    }
                    if (r.code == 200) total = have
                }
                if (total >= 0 && have >= total) { onProgress(1f); break }
                if (read == 0L) throw IOException("Download stalled")
                onProgress(have.toFloat() / total)
            }
        }
    }
}
