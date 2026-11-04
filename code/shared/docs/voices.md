# Natural voices

Read aloud (every message, quick messages, Daily Hávamál) speaks with a natural voice: Female (Alba, the default) or
Male (northern English), picked in Settings, or by a parent on a Kids' Device's page. The speech engine (sherpa-onnx
1.13.8) is built into Huginn and Muninn. Only the chosen voice's data is downloaded, once, from this project's
**voices-1** release, and kept across updates. Until it's ready, the device's own voice reads (only a voice that works
offline; never one that sends the text away).

## What the owner does (once)

1. On GitHub: **Actions › Publish voices › Run workflow** (branch beta).
2. Open the finished run: its summary shows a block of lines like `3f2a...  voice-female-1.tar`. Paste the whole block
   at the end of `code/shared/voices.sha256` and commit it.
3. Push as usual. The next build carries the hashes: voices download from then on.

That's all. The workflow publishes voices-1 as a **pre-release**, so the update checks (which only look at version
releases) never pick it, and it fails if GitHub's "latest" release ever stops being a version.

## How it works

- **Files.** `voice-female-1.tar` and `voice-male-1.tar` (about 60 to 65 MB each): `model.onnx`, `tokens.txt`,
  `MODEL_CARD` and `espeak-ng-data` (English only), repacked unchanged from sherpa-onnx's `tts-models` release, plus
  `natural-voices.txt`. Never replaced in place: a fixed voice goes out as voices-2 with new names and new lines.
- **Checked.** Each download's SHA-256 must match its line in `code/shared/voices.sha256`, built into the apps. No line,
  no download. The builds check the speech engine they bundle against the same file when its line is there (the
  workflow prints those lines too).
- **When.** The first time something is read aloud, in the background, on Wi-Fi (or any network with "Allow on mobile
  data" on, in Settings › Look › Read aloud). Kept in Muninn's private files and Huginn's data folder; "Delete voices"
  frees the space. Switching voices downloads the other one the same way and deletes the first once it's ready.
- **Ultra privacy.** While it's on, the download goes through Tor when Ultra privacy offers a way to; otherwise the app
  waits and says so, and keeps the device's voice.
- **Kids' Devices** (cap `k15`, protocol.md 7.18): the parent picks the voice on the device's page; the device downloads
  only that one.
- **Licenses.** `code/shared/licenses/natural-voices.txt`, `sherpa-onnx-Apache-2.0.txt`, `onnxruntime-MIT.txt`; shown
  in About in both apps.
