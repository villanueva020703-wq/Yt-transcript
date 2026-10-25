package app.yttranscribe

object WhisperLib {
    init { System.loadLibrary("ytwhisper") }

    external fun init(modelPath: String): Long
    external fun free(ctx: Long)
    external fun detectLanguage(ctx: Long, pcm: FloatArray, n: Int, threads: Int): String

    /** One byte[] per segment: "t0\tt1\ttext" (t0/t1 in centiseconds, UTF-8). */
    external fun transcribe(ctx: Long, pcm: FloatArray, n: Int, lang: String, threads: Int): Array<ByteArray>
}
