// JNI wrapper around libopus for Muninn's calls: 16 kHz mono VOIP, 16 kbps VBR, in-band FEC, DTX.
// Kotlin side: io.github.ravenor907.muninn.net.call.Opus
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <opus.h>

#define EXPORT __attribute__((visibility("default")))

EXPORT JNIEXPORT jlong JNICALL
Java_io_github_ravenor907_muninn_net_call_Opus_encoderNew(JNIEnv *env, jclass cls, jint rate, jint bitrate, jint loss, jint complexity) {
    int err = 0;
    OpusEncoder *e = opus_encoder_create(rate, 1, OPUS_APPLICATION_VOIP, &err);
    if (err != OPUS_OK || e == NULL) return 0;
    opus_encoder_ctl(e, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(e, OPUS_SET_VBR(1));
    opus_encoder_ctl(e, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(e, OPUS_SET_PACKET_LOSS_PERC(loss));
    opus_encoder_ctl(e, OPUS_SET_DTX(1));
    opus_encoder_ctl(e, OPUS_SET_COMPLEXITY(complexity));
    opus_encoder_ctl(e, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(e, OPUS_SET_MAX_BANDWIDTH(OPUS_BANDWIDTH_WIDEBAND));
    return (jlong)(intptr_t)e;
}

// Encodes one frame of 16-bit PCM. Returns the packet length (1 or 2 bytes means DTX silence), or < 0 on error.
EXPORT JNIEXPORT jint JNICALL
Java_io_github_ravenor907_muninn_net_call_Opus_encode(JNIEnv *env, jclass cls, jlong h, jshortArray pcm, jint frame, jbyteArray out) {
    OpusEncoder *e = (OpusEncoder *)(intptr_t)h;
    if (e == NULL || (*env)->GetArrayLength(env, pcm) < frame) return OPUS_BAD_ARG;
    jshort *in = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    int n = opus_encode(e, (const opus_int16 *)in, frame, (unsigned char *)o, (*env)->GetArrayLength(env, out));
    (*env)->ReleaseByteArrayElements(env, out, o, 0);
    (*env)->ReleaseShortArrayElements(env, pcm, in, JNI_ABORT);
    return n;
}

// Between two c2 apps (shared/docs/protocol.md 5.8.4): the bitrate and the loss the other side reports.
EXPORT JNIEXPORT void JNICALL
Java_io_github_ravenor907_muninn_net_call_Opus_encoderTune(JNIEnv *env, jclass cls, jlong h, jint bitrate, jint loss) {
    OpusEncoder *e = (OpusEncoder *)(intptr_t)h;
    if (e == NULL) return;
    opus_encoder_ctl(e, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(e, OPUS_SET_PACKET_LOSS_PERC(loss));
}

EXPORT JNIEXPORT void JNICALL
Java_io_github_ravenor907_muninn_net_call_Opus_encoderFree(JNIEnv *env, jclass cls, jlong h) {
    if (h) opus_encoder_destroy((OpusEncoder *)(intptr_t)h);
}

EXPORT JNIEXPORT jlong JNICALL
Java_io_github_ravenor907_muninn_net_call_Opus_decoderNew(JNIEnv *env, jclass cls, jint rate) {
    int err = 0;
    OpusDecoder *d = opus_decoder_create(rate, 1, &err);
    return (err != OPUS_OK || d == NULL) ? 0 : (jlong)(intptr_t)d;
}

// Decodes [data] (null: packet loss concealment). fec != 0 rebuilds the frame before [data] from its FEC.
// Returns samples written into pcm, or < 0 on error.
EXPORT JNIEXPORT jint JNICALL
Java_io_github_ravenor907_muninn_net_call_Opus_decode(JNIEnv *env, jclass cls, jlong h, jbyteArray data, jshortArray pcm, jint frame, jint fec) {
    OpusDecoder *d = (OpusDecoder *)(intptr_t)h;
    if (d == NULL || (*env)->GetArrayLength(env, pcm) < frame) return OPUS_BAD_ARG;
    jshort *o = (*env)->GetShortArrayElements(env, pcm, NULL);
    int n;
    if (data == NULL) {
        n = opus_decode(d, NULL, 0, (opus_int16 *)o, frame, 0);
    } else {
        jsize len = (*env)->GetArrayLength(env, data);
        jbyte *in = (*env)->GetByteArrayElements(env, data, NULL);
        n = opus_decode(d, (const unsigned char *)in, len, (opus_int16 *)o, frame, fec ? 1 : 0);
        (*env)->ReleaseByteArrayElements(env, data, in, JNI_ABORT);
    }
    (*env)->ReleaseShortArrayElements(env, pcm, o, 0);
    return n;
}

EXPORT JNIEXPORT void JNICALL
Java_io_github_ravenor907_muninn_net_call_Opus_decoderFree(JNIEnv *env, jclass cls, jlong h) {
    if (h) opus_decoder_destroy((OpusDecoder *)(intptr_t)h);
}
