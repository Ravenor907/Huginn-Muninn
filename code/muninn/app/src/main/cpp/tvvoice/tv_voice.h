// Voice processing for the Kids' TV microphone (Muninn, android/.../tv/TvVoice.kt).
// WebRTC Audio Processing (echo canceller AEC3, high-pass filter, AGC2, optional noise suppressor)
// followed by RNNoise, for calls, voice messages and speech to text.
// Plain C API so the host test (tv_voice_test.cc) and the JNI wrapper share it.
#ifndef MUNINN_TV_VOICE_H
#define MUNINN_TV_VOICE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// What to turn on.
#define TVV_AEC     1   // echo canceller: needs tvv_render() with what the speakers play
#define TVV_RNNOISE 2   // RNNoise voice-focused noise suppression
#define TVV_AGC     4   // WebRTC AGC2 adaptive digital gain
#define TVV_NS      8   // WebRTC's own noise suppressor (used when RNNoise is off)

typedef struct TvVoice TvVoice;

// [in_rate]: the microphone's rate (8000, 16000, 32000, 44100 or 48000). [out_rate]: what tvv_capture()
// gives back (16000 for calls and speech to text, 44100 or 48000 for voice messages).
// [render_rate]: the rate tvv_render() is given (the decoded call, 16000). [play_rate]: the rate the
// speaker side wants back from tvv_render(). Null if something failed.
TvVoice *tvv_create(int in_rate, int out_rate, int render_rate, int play_rate, int flags);
void tvv_destroy(TvVoice *v);

// 10 ms of microphone in, processed audio out. [in] has in_rate/100 samples (44100: 441),
// [out] room for out_rate/100. Returns the samples written, or < 0 on an error (then [out] is a copy
// of the input, resampled).
int tvv_capture(TvVoice *v, const int16_t *in, int16_t *out);

// 10 ms of what is about to be played ([render_rate]/100 samples): the echo canceller's reference.
// Writes the same sound at [play_rate] into [out] (room for play_rate/100) and returns its length.
int tvv_render(TvVoice *v, const int16_t *in, int16_t *out);

// A hint for the echo canceller: the delay from tvv_render() to the sound coming back in, in ms.
void tvv_set_delay(TvVoice *v, int ms);

// The last 10 ms of RNNoise's voice probability (0..1), or -1 without RNNoise.
float tvv_voice_probability(TvVoice *v);

#ifdef __cplusplus
}
#endif

#endif
