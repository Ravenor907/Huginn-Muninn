// Host check for tv_voice (not part of the app): a made-up room where the TV's speakers play the far
// end and the microphone hears it back 150 ms later, plus a steady hiss. Checks that the echo canceller
// takes most of the echo out, that RNNoise takes the hiss down, that the speaker gets the far end at
// full level, and that every rate pairing runs.
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <random>
#include <vector>

#include "tv_voice.h"

static double energy(const std::vector<int16_t> &v, size_t from, size_t to) {
  double e = 0;
  for (size_t i = from; i < to && i < v.size(); i++) e += double(v[i]) * v[i];
  return e / double(to - from) + 1e-9;
}

// Speech-like: a few harmonics that glide, switched on and off like syllables.
static std::vector<int16_t> talk(int rate, int seconds, double base, unsigned seed) {
  std::mt19937 rng(seed);
  std::uniform_real_distribution<double> u(-1, 1);
  std::vector<int16_t> out(size_t(rate) * seconds);
  double ph = 0;
  for (size_t i = 0; i < out.size(); i++) {
    double t = double(i) / rate;
    double f = base * (1 + 0.15 * std::sin(2 * M_PI * 0.7 * t));
    ph += 2 * M_PI * f / rate;
    double env = std::sin(2 * M_PI * 3.1 * t) > -0.2 ? 1.0 : 0.05;
    double s = 0;
    for (int h = 1; h <= 6; h++) s += std::sin(ph * h) / h;
    out[i] = int16_t(std::lrint(env * 6000 * s + 300 * u(rng)));
  }
  return out;
}

static int check_echo() {
  const int rate = 16000, secs = 12, delay = rate * 150 / 1000;
  auto far = talk(rate, secs, 180, 1);
  auto mic = std::vector<int16_t>(far.size());
  // The room: delayed, a little reverb, quieter.
  for (size_t i = 0; i < mic.size(); i++) {
    double s = 0;
    if (i >= size_t(delay)) s += 0.5 * far[i - delay];
    if (i >= size_t(delay + 160)) s += 0.2 * far[i - delay - 160];
    if (i >= size_t(delay + 400)) s += 0.08 * far[i - delay - 400];
    mic[i] = int16_t(std::lrint(s));
  }
  TvVoice *v = tvv_create(rate, rate, rate, 48000, TVV_AEC);
  if (!v) { std::puts("create failed"); return 1; }
  tvv_set_delay(v, 150);
  std::vector<int16_t> out(mic.size()), play(480);
  for (size_t f = 0; f + 160 <= mic.size(); f += 160) {
    if (tvv_render(v, &far[f], play.data()) != 480) { std::puts("render length"); return 1; }
    if (tvv_capture(v, &mic[f], &out[f]) != 160) { std::puts("capture length"); return 1; }
  }
  tvv_destroy(v);
  // The last 4 seconds, once the canceller has settled.
  size_t a = size_t(rate) * 8, b = mic.size();
  double erle = 10 * std::log10(energy(mic, a, b) / energy(out, a, b));
  std::printf("echo: removed %.1f dB\n", erle);
  return erle >= 15 ? 0 : 1;
}

static int check_noise() {
  const int rate = 48000, secs = 6;
  std::mt19937 rng(7);
  std::normal_distribution<double> n(0, 1500);
  std::vector<int16_t> mic(size_t(rate) * secs), out(size_t(16000) * secs);
  for (auto &s : mic) s = int16_t(std::lrint(n(rng)));
  TvVoice *v = tvv_create(rate, 16000, 16000, 16000, TVV_RNNOISE);
  if (!v) { std::puts("create failed"); return 1; }
  for (size_t f = 0, o = 0; f + 480 <= mic.size(); f += 480, o += 160)
    if (tvv_capture(v, &mic[f], &out[o]) != 160) { std::puts("capture length (noise)"); return 1; }
  tvv_destroy(v);
  // Energy per sample, input at 48 kHz vs output at 16 kHz: both are averages, so they compare.
  double cut = 10 * std::log10(energy(mic, size_t(rate) * 2, mic.size()) / energy(out, 16000 * 2, out.size()));
  std::printf("hiss: down %.1f dB\n", cut);
  return cut >= 10 ? 0 : 1;
}

// The speaker gets the call: what goes into tvv_render comes out at the speaker's rate, as loud.
static int check_render() {
  for (int flags : {TVV_AEC | TVV_RNNOISE | TVV_AGC, 0}) for (int play_rate : {16000, 48000}) {
    TvVoice *v = tvv_create(48000, 16000, 16000, play_rate, flags);
    if (!v) { std::puts("create failed (render)"); return 1; }
    auto far = talk(16000, 2, 300, 9);
    std::vector<int16_t> play(far.size() * size_t(play_rate) / 16000), mic(480, 0), o(160);
    const size_t step = size_t(play_rate / 100);
    for (size_t f = 0, p = 0; f + 160 <= far.size(); f += 160, p += step) {
      if (tvv_render(v, &far[f], &play[p]) != int(step)) { std::puts("render length"); return 1; }
      tvv_capture(v, mic.data(), o.data());
    }
    tvv_destroy(v);
    double in = energy(far, 1600, far.size()), out = energy(play, step * 10, play.size());
    double db = 10 * std::log10(out / in);
    if (std::fabs(db) > 1.0) { std::printf("render flags %d at %d Hz: speaker level %.1f dB off\n", flags, play_rate, db); return 1; }
  }
  std::puts("render: the speaker gets the call");
  return 0;
}

static int check_rates() {
  const int ins[] = {16000, 44100, 48000};
  const int outs[] = {16000, 44100, 48000};
  for (int in : ins) for (int out : outs) for (int flags : {TVV_AEC | TVV_RNNOISE | TVV_AGC, TVV_AGC | TVV_NS}) {
    TvVoice *v = tvv_create(in, out, 16000, 48000, flags);
    if (!v) { std::printf("create %d -> %d failed\n", in, out); return 1; }
    auto sig = talk(in, 2, 220, 3);
    std::vector<int16_t> o(out / 100), p(480), r(160, 0);
    for (size_t f = 0; f + size_t(in / 100) <= sig.size(); f += size_t(in / 100)) {
      tvv_render(v, r.data(), p.data());
      if (tvv_capture(v, &sig[f], o.data()) != out / 100) { std::printf("capture %d -> %d flags %d: bad length\n", in, out, flags); return 1; }
    }
    tvv_destroy(v);
  }
  std::puts("rates: all ran");
  return 0;
}

// How long a minute of a call (48 kHz microphone, everything on) takes to process here.
static void bench(int in_rate, int flags) {
  TvVoice *v = tvv_create(in_rate, 16000, 16000, 48000, flags);
  auto sig = talk(in_rate, 60, 200, 5);
  std::vector<int16_t> o(160), p(480), r(160, 100);
  const size_t step = size_t(in_rate / 100);
  auto t0 = std::chrono::steady_clock::now();
  for (size_t f = 0; f + step <= sig.size(); f += step) { tvv_render(v, r.data(), p.data()); tvv_capture(v, &sig[f], o.data()); }
  double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
  tvv_destroy(v);
  std::printf("a minute of a call at %d Hz, flags %d: %.0f ms (%.1f%% of one core)\n", in_rate, flags, ms, ms / 600.0);
}

int main() {
  if (std::getenv("TVV_BENCH")) {
    for (int r : {16000, 48000}) for (int f : {TVV_AEC, TVV_RNNOISE, TVV_AGC, TVV_AEC | TVV_RNNOISE | TVV_AGC}) bench(r, f);
    return 0;
  }
  int bad = check_rates();
  bad |= check_render();
  bad |= check_echo();
  bad |= check_noise();
  std::puts(bad ? "FAILED" : "ok");
  return bad;
}
