#include <jni.h>
#include <string>
#include <vector>
#include "whisper.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_yttranscribe_WhisperLib_init(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_app_yttranscribe_WhisperLib_free(JNIEnv *, jobject, jlong h) {
    if (h) whisper_free(reinterpret_cast<whisper_context *>(h));
}

JNIEXPORT jstring JNICALL
Java_app_yttranscribe_WhisperLib_detectLanguage(JNIEnv *env, jobject, jlong h,
                                                jfloatArray pcm, jint n, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(h);
    jfloat *d = env->GetFloatArrayElements(pcm, nullptr);
    std::string out = "en";
    if (whisper_pcm_to_mel(ctx, d, n, threads) == 0) {
        std::vector<float> probs(whisper_lang_max_id() + 1, 0.0f);
        int id = whisper_lang_auto_detect(ctx, 0, threads, probs.data());
        if (id >= 0) out = whisper_lang_str(id);
    }
    env->ReleaseFloatArrayElements(pcm, d, JNI_ABORT);
    return env->NewStringUTF(out.c_str());
}

// Returns one byte[] per segment: "<t0 centisec>\t<t1 centisec>\t<utf8 text>".
// Bytes (not jstring) because whisper can split multibyte characters across tokens.
JNIEXPORT jobjectArray JNICALL
Java_app_yttranscribe_WhisperLib_transcribe(JNIEnv *env, jobject, jlong h, jfloatArray pcm,
                                            jint n, jstring jlang, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(h);
    const char *l = env->GetStringUTFChars(jlang, nullptr);
    std::string lang(l);
    env->ReleaseStringUTFChars(jlang, l);

    jfloat *d = env->GetFloatArrayElements(pcm, nullptr);

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_timestamps = false;
    wp.print_special = false;
    wp.translate = false;
    wp.language = lang.c_str();
    wp.detect_language = false;
    wp.n_threads = threads;
    wp.no_context = true;          // avoids runaway repetition across chunks
    wp.suppress_blank = true;
    wp.no_speech_thold = 0.6f;
    wp.greedy.best_of = 1;

    int rc = whisper_full(ctx, wp, d, n);
    env->ReleaseFloatArrayElements(pcm, d, JNI_ABORT);

    int cnt = rc == 0 ? whisper_full_n_segments(ctx) : 0;
    jclass bytesCls = env->FindClass("[B");
    jobjectArray arr = env->NewObjectArray(cnt, bytesCls, nullptr);
    for (int i = 0; i < cnt; i++) {
        const char *t = whisper_full_get_segment_text(ctx, i);
        std::string s = std::to_string(whisper_full_get_segment_t0(ctx, i)) + "\t" +
                        std::to_string(whisper_full_get_segment_t1(ctx, i)) + "\t" +
                        (t ? t : "");
        jbyteArray b = env->NewByteArray(static_cast<jsize>(s.size()));
        env->SetByteArrayRegion(b, 0, static_cast<jsize>(s.size()),
                                reinterpret_cast<const jbyte *>(s.data()));
        env->SetObjectArrayElement(arr, i, b);
        env->DeleteLocalRef(b);
    }
    return arr;
}

}
