// See tv_voice.h. One TvVoice per recording or call; tvv_capture() and tvv_render() may run on two
// threads at once (the APM locks its capture and render sides separately), each side on one thread only.
#include "tv_voice.h"

#include <atomic>
#include <cmath>
#include <memory>
#include <new>
#include <vector>

#include "common_audio/resampler/include/push_resampler.h"
#include "modules/audio_processing/include/audio_processing.h"

extern "C" {
#include "rnnoise.h"
}

namespace {
constexpr int kRnRate = 48000;
constexpr int kRnFrame = 480;  // RNNoise: 10 ms at 48 kHz
}  // namespace

struct TvVoice {
  std::unique_ptr<webrtc::AudioProcessing> apm;
  DenoiseState *rn = nullptr;
  int in_rate = 0, out_rate = 0, render_rate = 0, play_rate = 0, flags = 0;
  webrtc::StreamConfig in_cfg, mid_cfg, render_cfg;
  webrtc::PushResampler<int16_t> down;    // RNNoise's 48 kHz to out_rate
  webrtc::PushResampler<int16_t> up;      // render_rate to play_rate
  webrtc::PushResampler<int16_t> plain;   // in_rate to out_rate, when the APM fails
  std::vector<int16_t> mid, rendered, reverse;  // reverse: the canceller's own output, never played
  std::vector<float> fin, fout;
  std::atomic<int> delay{0};
  std::atomic<float> vad{-1.f};

  ~TvVoice() {
    if (rn) rnnoise_destroy(rn);
  }
};

static bool rate_ok(int r) { return r == 8000 || r == 16000 || r == 32000 || r == 44100 || r == 48000; }

extern "C" TvVoice *tvv_create(int in_rate, int out_rate, int render_rate, int play_rate, int flags) {
  if (!rate_ok(in_rate) || !rate_ok(out_rate) || !rate_ok(render_rate) || !rate_ok(play_rate)) return nullptr;
  std::unique_ptr<TvVoice> v(new (std::nothrow) TvVoice());
  if (!v) return nullptr;
  v->in_rate = in_rate; v->out_rate = out_rate; v->render_rate = render_rate; v->play_rate = play_rate; v->flags = flags;
  const bool rn = (flags & TVV_RNNOISE) != 0;

  webrtc::AudioProcessing::Config c;
  c.pipeline.maximum_internal_processing_rate = 48000;
  c.high_pass_filter.enabled = true;
  c.echo_canceller.enabled = (flags & TVV_AEC) != 0;
  c.echo_canceller.mobile_mode = false;  // AEC3, not the mobile canceller (AECM)
  // Standard (WebRTC) and Strong (RNNoise) can run together: WebRTC first, then RNNoise.
  c.noise_suppression.enabled = (flags & TVV_NS) != 0;
  c.noise_suppression.level = webrtc::AudioProcessing::Config::NoiseSuppression::kHigh;
  c.transient_suppression.enabled = false;
  c.voice_detection.enabled = false;
  c.residual_echo_detector.enabled = false;
  c.level_estimation.enabled = false;
  c.gain_controller1.enabled = false;
  c.gain_controller2.enabled = (flags & TVV_AGC) != 0;
  c.gain_controller2.fixed_digital.gain_db = 0.f;
  c.gain_controller2.adaptive_digital.enabled = (flags & TVV_AGC) != 0;
  // A quiet TV mic across the room: let the gain climb a little faster than the 3 dB/s default.
  c.gain_controller2.adaptive_digital.max_gain_change_db_per_second = 6.f;
  v->apm.reset(webrtc::AudioProcessingBuilder().Create());
  if (!v->apm) return nullptr;
  v->apm->ApplyConfig(c);

  v->in_cfg = webrtc::StreamConfig(in_rate, 1);
  v->mid_cfg = webrtc::StreamConfig(rn ? kRnRate : out_rate, 1);
  v->render_cfg = webrtc::StreamConfig(render_rate, 1);
  webrtc::ProcessingConfig pc = {{v->in_cfg, v->mid_cfg, v->render_cfg, v->render_cfg}};
  if (v->apm->Initialize(pc) != webrtc::AudioProcessing::kNoError) return nullptr;

  if (rn) {
    v->rn = rnnoise_create(nullptr);
    if (!v->rn) return nullptr;
    v->down.InitializeIfNeeded(kRnRate, out_rate, 1);
    v->fin.resize(kRnFrame);
    v->fout.resize(kRnFrame);
  }
  v->up.InitializeIfNeeded(render_rate, play_rate, 1);
  v->plain.InitializeIfNeeded(in_rate, out_rate, 1);
  v->mid.resize(v->mid_cfg.num_frames());
  v->rendered.resize(v->render_cfg.num_frames());
  v->reverse.resize(v->render_cfg.num_frames());
  return v.release();
}

extern "C" void tvv_destroy(TvVoice *v) { delete v; }

extern "C" int tvv_capture(TvVoice *v, const int16_t *in, int16_t *out) {
  if (!v) return -1;
  const size_t nin = v->in_cfg.num_frames();
  const size_t nout = static_cast<size_t>(v->out_rate / 100);
  v->apm->set_stream_delay_ms(v->delay.load(std::memory_order_relaxed));
  if (v->apm->ProcessStream(in, v->in_cfg, v->mid_cfg, v->mid.data()) != webrtc::AudioProcessing::kNoError) {
    v->plain.Resample(in, nin, out, nout);
    return -1;
  }
  if (!v->rn) {
    for (size_t i = 0; i < nout; i++) out[i] = v->mid[i];
    return static_cast<int>(nout);
  }
  // RNNoise takes and gives floats on the 16-bit scale.
  for (int i = 0; i < kRnFrame; i++) v->fin[i] = v->mid[i];
  v->vad.store(rnnoise_process_frame(v->rn, v->fout.data(), v->fin.data()), std::memory_order_relaxed);
  for (int i = 0; i < kRnFrame; i++) {
    float s = std::nearbyint(v->fout[i]);
    v->mid[i] = static_cast<int16_t>(s > 32767.f ? 32767.f : s < -32768.f ? -32768.f : s);
  }
  if (v->out_rate == kRnRate) {
    for (size_t i = 0; i < nout; i++) out[i] = v->mid[i];
    return static_cast<int>(nout);
  }
  return v->down.Resample(v->mid.data(), kRnFrame, out, nout);
}

extern "C" int tvv_render(TvVoice *v, const int16_t *in, int16_t *out) {
  if (!v) return -1;
  const size_t n = v->render_cfg.num_frames();
  const size_t nplay = static_cast<size_t>(v->play_rate / 100);
  // The speaker plays the call as it came; the echo canceller only listens. ProcessReverseStream writes
  // its output only when a render pre-processor runs (none does here), so it gets a buffer of its own.
  for (size_t i = 0; i < n; i++) v->rendered[i] = in[i];
  if (v->flags & TVV_AEC) v->apm->ProcessReverseStream(in, v->render_cfg, v->render_cfg, v->reverse.data());
  if (v->play_rate == v->render_rate) {
    for (size_t i = 0; i < n; i++) out[i] = v->rendered[i];
    return static_cast<int>(n);
  }
  return v->up.Resample(v->rendered.data(), n, out, nplay);
}

extern "C" void tvv_set_delay(TvVoice *v, int ms) {
  if (v) v->delay.store(ms < 0 ? 0 : ms > 500 ? 500 : ms, std::memory_order_relaxed);
}

extern "C" float tvv_voice_probability(TvVoice *v) { return v ? v->vad.load(std::memory_order_relaxed) : -1.f; }
