#!/usr/bin/env python3
"""Per-voice synthesis check: each natural voice reads known sentences, a speech recognizer writes down what it heard, and
the words must match closely. Usage: voice_check.py <voice folder> <asr folder> <name>. Exits 1 when a voice doesn't pass."""
import difflib
import re
import sys
import numpy as np
import sherpa_onnx

SENTENCES = [
    "I regret not going with you to the lake last summer.",
    "Dinner is at six, and the children want to call you before bed.",
    "The weather is cold tonight, so bring a warm coat and good boots.",
    "Messages are delayed, but they will send automatically.",
    "A man should never be parted from his weapons in the field.",
]
# (noise scale, noise width): preset A and the two ends of the Labs presets.
TUNES = [(0.667, 0.8), (0.33, 0.4), (1.0, 1.2)]
PASS = 0.70


def words(s):
    return re.sub(r"[^a-z' ]", " ", s.lower()).split()


def main():
    voice, asr, name = sys.argv[1], sys.argv[2], sys.argv[3]
    rec = sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=f"{asr}/tiny.en-encoder.int8.onnx", decoder=f"{asr}/tiny.en-decoder.int8.onnx", tokens=f"{asr}/tiny.en-tokens.txt",
        language="en", task="transcribe", num_threads=2)
    failed = 0
    wave_of = {}
    for tune in TUNES:
        cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=f"{voice}/model.onnx", tokens=f"{voice}/tokens.txt", data_dir=f"{voice}/espeak-ng-data",
                                                       noise_scale=tune[0], noise_scale_w=tune[1], length_scale=1.0),
            num_threads=2, provider="cpu"), max_num_sentences=1)
        tts = sherpa_onnx.OfflineTts(cfg)
        scores = []
        for s in SENTENCES:
            a = tts.generate(s, sid=0, speed=1.0)
            pcm = np.array(a.samples, dtype=np.float32)
            wave_of.setdefault(s, {})[tune] = pcm
            st = rec.create_stream()
            st.accept_waveform(a.sample_rate, pcm)
            rec.decode_stream(st)
            heard = st.result.text
            r = difflib.SequenceMatcher(None, words(s), words(heard)).ratio()
            scores.append(r)
            print(f"{name} noise {tune[0]} width {tune[1]}: {r:.2f} {len(pcm) / a.sample_rate:.1f}s said: {s}  heard: {heard}", flush=True)
        mean = sum(scores) / len(scores)
        print(f"{name} noise {tune[0]} width {tune[1]}: mean {mean:.2f}", flush=True)
        if tune == TUNES[0] and mean < PASS:
            failed = 1
    # The presets must reach the engine: the same sentence with other settings isn't the same sound.
    s = SENTENCES[0]
    a, b = wave_of[s][TUNES[0]], wave_of[s][TUNES[1]]
    n = min(len(a), len(b))
    differs = float(np.mean(np.abs(a[:n] - b[:n])))
    print(f"{name}: mean difference between presets {differs:.4f}, lengths {len(a)} {len(b)}", flush=True)
    if differs < 1e-4:
        print(f"::error title=Voice {name}::the presets make no difference")
        failed = 1
    if failed:
        print(f"::error title=Voice {name}::what it says doesn't match the text")
    return failed


if __name__ == "__main__":
    sys.exit(main())
