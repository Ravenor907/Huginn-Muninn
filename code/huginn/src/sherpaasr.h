#pragma once

// Dictation's speech to text (code/shared/docs/dictation.md): the part of sherpa-onnx 1.13.8's C API (Apache-2.0,
// code/shared/licenses/sherpa-onnx-Apache-2.0.txt) Huginn uses for it. The structs are exactly as in that version's
// sherpa-onnx/c-api/c-api.h, field for field and in the same order, every model type's struct included (even the ones
// Huginn never fills), so the library Huginn bundles reads them right. The functions are found at run time (QLibrary,
// src/dictation.cpp), never linked, from the same library Read aloud loads (ReadAloud::engineLibrary, only from
// Huginn's own folder and only after SherpaOnnxGetVersionStr() says 1.13.8, HUGINN_SHERPA_ONNX_VERSION in
// src/sherpatts.h). Changing the bundled version means checking these again. dictation.cpp hands each config over in
// a zeroed buffer 4 KB larger than the struct, so a bigger struct in a library could never read past it.

#include <cstdint>

extern "C" {

// ---- the recognizer (offline, one finished piece of speech at a time)

typedef struct SherpaOnnxFeatureConfig {
  int32_t sample_rate;
  int32_t feature_dim;
} SherpaOnnxFeatureConfig;

typedef struct SherpaOnnxHomophoneReplacerConfig {
  const char *dict_dir;
  const char *lexicon;
  const char *rule_fsts;
} SherpaOnnxHomophoneReplacerConfig;

typedef struct SherpaOnnxOfflineTransducerModelConfig {
  const char *encoder;
  const char *decoder;
  const char *joiner;
} SherpaOnnxOfflineTransducerModelConfig;

typedef struct SherpaOnnxOfflineParaformerModelConfig {
  const char *model;
} SherpaOnnxOfflineParaformerModelConfig;

typedef struct SherpaOnnxOfflineNemoEncDecCtcModelConfig {
  const char *model;
} SherpaOnnxOfflineNemoEncDecCtcModelConfig;

typedef struct SherpaOnnxOfflineWhisperModelConfig {
  const char *encoder;
  const char *decoder;
  const char *language;
  const char *task;
  int32_t tail_paddings;
  int32_t enable_token_timestamps;
  int32_t enable_segment_timestamps;
} SherpaOnnxOfflineWhisperModelConfig;

typedef struct SherpaOnnxOfflineCanaryModelConfig {
  const char *encoder;
  const char *decoder;
  const char *src_lang;
  const char *tgt_lang;
  int32_t use_pnc;
} SherpaOnnxOfflineCanaryModelConfig;

typedef struct SherpaOnnxOfflineCohereTranscribeModelConfig {
  const char *encoder;
  const char *decoder;
  const char *language;
  int32_t use_punct;
  int32_t use_itn;
} SherpaOnnxOfflineCohereTranscribeModelConfig;

typedef struct SherpaOnnxOfflineFireRedAsrModelConfig {
  const char *encoder;
  const char *decoder;
} SherpaOnnxOfflineFireRedAsrModelConfig;

typedef struct SherpaOnnxOfflineFireRedAsrCtcModelConfig {
  const char *model;
} SherpaOnnxOfflineFireRedAsrCtcModelConfig;

// The one Huginn fills: Moonshine Tiny (preprocessor, encoder, uncached and cached decoder; no merged decoder).
typedef struct SherpaOnnxOfflineMoonshineModelConfig {
  const char *preprocessor;
  const char *encoder;
  const char *uncached_decoder;
  const char *cached_decoder;
  const char *merged_decoder;
} SherpaOnnxOfflineMoonshineModelConfig;

typedef struct SherpaOnnxOfflineTdnnModelConfig {
  const char *model;
} SherpaOnnxOfflineTdnnModelConfig;

typedef struct SherpaOnnxOfflineLMConfig {
  const char *model;
  float scale;
} SherpaOnnxOfflineLMConfig;

typedef struct SherpaOnnxOfflineSenseVoiceModelConfig {
  const char *model;
  const char *language;
  int32_t use_itn;
} SherpaOnnxOfflineSenseVoiceModelConfig;

typedef struct SherpaOnnxOfflineDolphinModelConfig {
  const char *model;
} SherpaOnnxOfflineDolphinModelConfig;

typedef struct SherpaOnnxOfflineZipformerCtcModelConfig {
  const char *model;
} SherpaOnnxOfflineZipformerCtcModelConfig;

typedef struct SherpaOnnxOfflineWenetCtcModelConfig {
  const char *model;
} SherpaOnnxOfflineWenetCtcModelConfig;

typedef struct SherpaOnnxOfflineOmnilingualAsrCtcModelConfig {
  const char *model;
} SherpaOnnxOfflineOmnilingualAsrCtcModelConfig;

typedef struct SherpaOnnxOfflineFunASRNanoModelConfig {
  const char *encoder_adaptor;
  const char *llm;
  const char *embedding;
  const char *tokenizer;
  const char *system_prompt;
  const char *user_prompt;
  int32_t max_new_tokens;
  float temperature;
  float top_p;
  int32_t seed;
  const char *language;
  int32_t itn;
  const char *hotwords;
} SherpaOnnxOfflineFunASRNanoModelConfig;

typedef struct SherpaOnnxOfflineQwen3ASRModelConfig {
  const char *conv_frontend;
  const char *encoder;
  const char *decoder;
  const char *tokenizer;
  int32_t max_total_len;
  int32_t max_new_tokens;
  float temperature;
  float top_p;
  int32_t seed;
  const char *hotwords;
} SherpaOnnxOfflineQwen3ASRModelConfig;

typedef struct SherpaOnnxOfflineMedAsrCtcModelConfig {
  const char *model;
} SherpaOnnxOfflineMedAsrCtcModelConfig;

typedef struct SherpaOnnxOfflineModelConfig {
  SherpaOnnxOfflineTransducerModelConfig transducer;
  SherpaOnnxOfflineParaformerModelConfig paraformer;
  SherpaOnnxOfflineNemoEncDecCtcModelConfig nemo_ctc;
  SherpaOnnxOfflineWhisperModelConfig whisper;
  SherpaOnnxOfflineTdnnModelConfig tdnn;

  const char *tokens;
  int32_t num_threads;
  int32_t debug;
  const char *provider;
  const char *model_type;
  const char *modeling_unit;
  const char *bpe_vocab;
  const char *telespeech_ctc;
  SherpaOnnxOfflineSenseVoiceModelConfig sense_voice;
  SherpaOnnxOfflineMoonshineModelConfig moonshine;
  SherpaOnnxOfflineFireRedAsrModelConfig fire_red_asr;
  SherpaOnnxOfflineDolphinModelConfig dolphin;
  SherpaOnnxOfflineZipformerCtcModelConfig zipformer_ctc;
  SherpaOnnxOfflineCanaryModelConfig canary;
  SherpaOnnxOfflineWenetCtcModelConfig wenet_ctc;
  SherpaOnnxOfflineOmnilingualAsrCtcModelConfig omnilingual;
  SherpaOnnxOfflineMedAsrCtcModelConfig medasr;
  SherpaOnnxOfflineFunASRNanoModelConfig funasr_nano;
  SherpaOnnxOfflineFireRedAsrCtcModelConfig fire_red_asr_ctc;
  SherpaOnnxOfflineQwen3ASRModelConfig qwen3_asr;
  SherpaOnnxOfflineCohereTranscribeModelConfig cohere_transcribe;
} SherpaOnnxOfflineModelConfig;

typedef struct SherpaOnnxOfflineRecognizerConfig {
  SherpaOnnxFeatureConfig feat_config;
  SherpaOnnxOfflineModelConfig model_config;
  SherpaOnnxOfflineLMConfig lm_config;

  const char *decoding_method;
  int32_t max_active_paths;

  const char *hotwords_file;

  float hotwords_score;
  const char *rule_fsts;
  const char *rule_fars;
  float blank_penalty;

  SherpaOnnxHomophoneReplacerConfig hr;
} SherpaOnnxOfflineRecognizerConfig;

typedef struct SherpaOnnxOfflineRecognizer SherpaOnnxOfflineRecognizer;
typedef struct SherpaOnnxOfflineStream SherpaOnnxOfflineStream;

// Huginn reads only [text]; the rest is here so the layout matches.
typedef struct SherpaOnnxOfflineRecognizerResult {
  const char *text;
  float *timestamps;
  int32_t count;
  const char *tokens;
  const char *const *tokens_arr;
  const char *json;
  const char *lang;
  const char *emotion;
  const char *event;
  float *durations;
  float *ys_log_probs;
  const float *segment_timestamps;
  const float *segment_durations;
  const char *segment_texts;
  const char *const *segment_texts_arr;
  int32_t segment_count;
} SherpaOnnxOfflineRecognizerResult;

typedef const SherpaOnnxOfflineRecognizer *(*SherpaOnnxCreateOfflineRecognizerFn)(const SherpaOnnxOfflineRecognizerConfig *config);
typedef void (*SherpaOnnxDestroyOfflineRecognizerFn)(const SherpaOnnxOfflineRecognizer *recognizer);
typedef const SherpaOnnxOfflineStream *(*SherpaOnnxCreateOfflineStreamFn)(const SherpaOnnxOfflineRecognizer *recognizer);
typedef void (*SherpaOnnxDestroyOfflineStreamFn)(const SherpaOnnxOfflineStream *stream);
// Once per stream: the whole piece of speech, mono floats from -1 to 1 at [sample_rate].
typedef void (*SherpaOnnxAcceptWaveformOfflineFn)(const SherpaOnnxOfflineStream *stream, int32_t sample_rate,
                                                  const float *samples, int32_t n);
typedef void (*SherpaOnnxDecodeOfflineStreamFn)(const SherpaOnnxOfflineRecognizer *recognizer, const SherpaOnnxOfflineStream *stream);
typedef const SherpaOnnxOfflineRecognizerResult *(*SherpaOnnxGetOfflineStreamResultFn)(const SherpaOnnxOfflineStream *stream);
typedef void (*SherpaOnnxDestroyOfflineRecognizerResultFn)(const SherpaOnnxOfflineRecognizerResult *r);

// ---- the voice activity detector (Silero VAD): finds where speech starts and stops

typedef struct SherpaOnnxSileroVadModelConfig {
  const char *model;
  float threshold;
  float min_silence_duration;
  float min_speech_duration;
  int32_t window_size;
  float max_speech_duration;
} SherpaOnnxSileroVadModelConfig;

typedef struct SherpaOnnxTenVadModelConfig {
  const char *model;
  float threshold;
  float min_silence_duration;
  float min_speech_duration;
  int32_t window_size;
  float max_speech_duration;
} SherpaOnnxTenVadModelConfig;

typedef struct SherpaOnnxVadModelConfig {
  SherpaOnnxSileroVadModelConfig silero_vad;
  int32_t sample_rate;
  int32_t num_threads;
  const char *provider;
  int32_t debug;
  SherpaOnnxTenVadModelConfig ten_vad;
} SherpaOnnxVadModelConfig;

typedef struct SherpaOnnxSpeechSegment {
  int32_t start;
  float *samples;
  int32_t n;
} SherpaOnnxSpeechSegment;

typedef struct SherpaOnnxVoiceActivityDetector SherpaOnnxVoiceActivityDetector;

typedef const SherpaOnnxVoiceActivityDetector *(*SherpaOnnxCreateVoiceActivityDetectorFn)(const SherpaOnnxVadModelConfig *config,
                                                                                      float buffer_size_in_seconds);
typedef void (*SherpaOnnxDestroyVoiceActivityDetectorFn)(const SherpaOnnxVoiceActivityDetector *p);
typedef void (*SherpaOnnxVoiceActivityDetectorAcceptWaveformFn)(const SherpaOnnxVoiceActivityDetector *p, const float *samples, int32_t n);
// 1 when no finished piece of speech is waiting.
typedef int32_t (*SherpaOnnxVoiceActivityDetectorEmptyFn)(const SherpaOnnxVoiceActivityDetector *p);
typedef void (*SherpaOnnxVoiceActivityDetectorPopFn)(const SherpaOnnxVoiceActivityDetector *p);
// Drops every finished piece still waiting.
typedef void (*SherpaOnnxVoiceActivityDetectorClearFn)(const SherpaOnnxVoiceActivityDetector *p);
typedef const SherpaOnnxSpeechSegment *(*SherpaOnnxVoiceActivityDetectorFrontFn)(const SherpaOnnxVoiceActivityDetector *p);
typedef void (*SherpaOnnxDestroySpeechSegmentFn)(const SherpaOnnxSpeechSegment *p);
typedef void (*SherpaOnnxVoiceActivityDetectorResetFn)(const SherpaOnnxVoiceActivityDetector *p);
// What is still held (speech without its pause yet) becomes a finished piece.
typedef void (*SherpaOnnxVoiceActivityDetectorFlushFn)(const SherpaOnnxVoiceActivityDetector *p);

}
