#pragma once

// Read aloud's speech engine (code/shared/docs/voices.md): the part of sherpa-onnx 1.13.8's C API (Apache-2.0,
// code/shared/licenses/sherpa-onnx-Apache-2.0.txt) Huginn uses. The structs are exactly as in that version's
// sherpa-onnx/c-api/c-api.h, field for field, so the library Huginn bundles reads them right; the functions are found
// at run time (QLibrary, src/readaloud.cpp), never linked, and only from Huginn's own folder after
// SherpaOnnxGetVersionStr() says 1.13.8. Changing the bundled version means checking these again. The Linux build was
// checked against the binary (round 19: every offset matches); readaloud.cpp also hands the config over in a zeroed
// buffer 4 KB larger than the struct, so a bigger struct in a library could never read past it.

#include <cstdint>

extern "C" {

typedef struct SherpaOnnxOfflineTtsVitsModelConfig {
  const char *model;
  const char *lexicon;
  const char *tokens;
  const char *data_dir;
  float noise_scale;
  float noise_scale_w;
  float length_scale;
  const char *dict_dir;
} SherpaOnnxOfflineTtsVitsModelConfig;

typedef struct SherpaOnnxOfflineTtsMatchaModelConfig {
  const char *acoustic_model;
  const char *vocoder;
  const char *lexicon;
  const char *tokens;
  const char *data_dir;
  float noise_scale;
  float length_scale;
  const char *dict_dir;
} SherpaOnnxOfflineTtsMatchaModelConfig;

typedef struct SherpaOnnxOfflineTtsKokoroModelConfig {
  const char *model;
  const char *voices;
  const char *tokens;
  const char *data_dir;
  float length_scale;
  const char *dict_dir;
  const char *lexicon;
  const char *lang;
} SherpaOnnxOfflineTtsKokoroModelConfig;

typedef struct SherpaOnnxOfflineTtsKittenModelConfig {
  const char *model;
  const char *voices;
  const char *tokens;
  const char *data_dir;
  float length_scale;
} SherpaOnnxOfflineTtsKittenModelConfig;

typedef struct SherpaOnnxOfflineTtsZipvoiceModelConfig {
  const char *tokens;
  const char *encoder;
  const char *decoder;
  const char *vocoder;
  const char *data_dir;
  const char *lexicon;
  float feat_scale;
  float t_shift;
  float target_rms;
  float guidance_scale;
} SherpaOnnxOfflineTtsZipvoiceModelConfig;

typedef struct SherpaOnnxOfflineTtsPocketModelConfig {
  const char *lm_flow;
  const char *lm_main;
  const char *encoder;
  const char *decoder;
  const char *text_conditioner;
  const char *vocab_json;
  const char *token_scores_json;
  int32_t voice_embedding_cache_capacity;
} SherpaOnnxOfflineTtsPocketModelConfig;

typedef struct SherpaOnnxOfflineTtsSupertonicModelConfig {
  const char *duration_predictor;
  const char *text_encoder;
  const char *vector_estimator;
  const char *vocoder;
  const char *tts_json;
  const char *unicode_indexer;
  const char *voice_style;
} SherpaOnnxOfflineTtsSupertonicModelConfig;

typedef struct SherpaOnnxOfflineTtsModelConfig {
  SherpaOnnxOfflineTtsVitsModelConfig vits;
  int32_t num_threads;
  int32_t debug;
  const char *provider;
  SherpaOnnxOfflineTtsMatchaModelConfig matcha;
  SherpaOnnxOfflineTtsKokoroModelConfig kokoro;
  SherpaOnnxOfflineTtsKittenModelConfig kitten;
  SherpaOnnxOfflineTtsZipvoiceModelConfig zipvoice;
  SherpaOnnxOfflineTtsPocketModelConfig pocket;
  SherpaOnnxOfflineTtsSupertonicModelConfig supertonic;
} SherpaOnnxOfflineTtsModelConfig;

typedef struct SherpaOnnxOfflineTtsConfig {
  SherpaOnnxOfflineTtsModelConfig model;
  const char *rule_fsts;
  int32_t max_num_sentences;
  const char *rule_fars;
  float silence_scale;
} SherpaOnnxOfflineTtsConfig;

typedef struct SherpaOnnxGeneratedAudio {
  const float *samples;
  int32_t n;
  int32_t sample_rate;
} SherpaOnnxGeneratedAudio;

typedef struct SherpaOnnxOfflineTts SherpaOnnxOfflineTts;

typedef const SherpaOnnxOfflineTts *(*SherpaOnnxCreateOfflineTtsFn)(const SherpaOnnxOfflineTtsConfig *config);
typedef void (*SherpaOnnxDestroyOfflineTtsFn)(const SherpaOnnxOfflineTts *tts);
typedef const SherpaOnnxGeneratedAudio *(*SherpaOnnxOfflineTtsGenerateFn)(const SherpaOnnxOfflineTts *tts, const char *text,
                                                                         int32_t sid, float speed);
typedef void (*SherpaOnnxDestroyOfflineTtsGeneratedAudioFn)(const SherpaOnnxGeneratedAudio *audio);
// "1.13.8": Huginn uses the library only when it is exactly the version these structs were copied from.
typedef const char *(*SherpaOnnxGetVersionStrFn)(void);

}

// The version the structs above match.
#define HUGINN_SHERPA_ONNX_VERSION "1.13.8"
