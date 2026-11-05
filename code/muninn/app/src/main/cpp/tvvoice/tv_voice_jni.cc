// JNI for io.github.ravenor907.muninn.tv.TvVoice (see tv_voice.h).
#include <jni.h>
#include <stdint.h>

#include "tv_voice.h"

#define EXPORT __attribute__((visibility("default")))

static TvVoice *of(jlong h) { return reinterpret_cast<TvVoice *>(static_cast<intptr_t>(h)); }

extern "C" {

EXPORT JNIEXPORT jlong JNICALL
Java_io_github_ravenor907_muninn_tv_TvVoice_create(JNIEnv *, jclass, jint in_rate, jint out_rate, jint render_rate, jint play_rate, jint flags) {
  return static_cast<jlong>(reinterpret_cast<intptr_t>(tvv_create(in_rate, out_rate, render_rate, play_rate, flags)));
}

EXPORT JNIEXPORT void JNICALL
Java_io_github_ravenor907_muninn_tv_TvVoice_destroy(JNIEnv *, jclass, jlong h) { tvv_destroy(of(h)); }

// [in]: in_rate/100 samples from [inOff]; [out]: room for out_rate/100 from [outOff]. The arrays are
// checked by the Kotlin side.
EXPORT JNIEXPORT jint JNICALL
Java_io_github_ravenor907_muninn_tv_TvVoice_capture(JNIEnv *env, jclass, jlong h, jshortArray in, jint inOff, jshortArray out, jint outOff) {
  jshort *i = static_cast<jshort *>(env->GetPrimitiveArrayCritical(in, nullptr));
  if (!i) return -1;
  jshort *o = static_cast<jshort *>(env->GetPrimitiveArrayCritical(out, nullptr));
  if (!o) { env->ReleasePrimitiveArrayCritical(in, i, JNI_ABORT); return -1; }
  int n = tvv_capture(of(h), reinterpret_cast<const int16_t *>(i + inOff), reinterpret_cast<int16_t *>(o + outOff));
  env->ReleasePrimitiveArrayCritical(out, o, 0);
  env->ReleasePrimitiveArrayCritical(in, i, JNI_ABORT);
  return n;
}

EXPORT JNIEXPORT jint JNICALL
Java_io_github_ravenor907_muninn_tv_TvVoice_render(JNIEnv *env, jclass, jlong h, jshortArray in, jint inOff, jshortArray out, jint outOff) {
  jshort *i = static_cast<jshort *>(env->GetPrimitiveArrayCritical(in, nullptr));
  if (!i) return -1;
  jshort *o = static_cast<jshort *>(env->GetPrimitiveArrayCritical(out, nullptr));
  if (!o) { env->ReleasePrimitiveArrayCritical(in, i, JNI_ABORT); return -1; }
  int n = tvv_render(of(h), reinterpret_cast<const int16_t *>(i + inOff), reinterpret_cast<int16_t *>(o + outOff));
  env->ReleasePrimitiveArrayCritical(out, o, 0);
  env->ReleasePrimitiveArrayCritical(in, i, JNI_ABORT);
  return n;
}

EXPORT JNIEXPORT void JNICALL
Java_io_github_ravenor907_muninn_tv_TvVoice_setDelay(JNIEnv *, jclass, jlong h, jint ms) { tvv_set_delay(of(h), ms); }

EXPORT JNIEXPORT jfloat JNICALL
Java_io_github_ravenor907_muninn_tv_TvVoice_voiceProbability(JNIEnv *, jclass, jlong h) { return tvv_voice_probability(of(h)); }

}  // extern "C"
