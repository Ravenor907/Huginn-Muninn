# Natural voices

Read aloud (every message, quick messages, Daily Hávamál) speaks with a natural voice: Female (Alba, the default) or
Male (northern English), picked in Settings, or by a parent on a Kids' Device's page. The speech engine (sherpa-onnx
1.13.8) is built into Huginn and Muninn. Only the chosen voice's data is downloaded, once, from this project's
**voices-1** release, and kept across updates. Until it's ready, the device's own voice reads (only a voice that works
offline; never one that sends the text away).

Settings › Look › Accessibility › Read aloud (1.42) has its own page: a switch to turn Read aloud off (it hides from
every menu), the voice (Female, Male, or **Device**: the device's own voice, and nothing is downloaded), and the
**speed**, 50% to 150% on a smooth slider (80% until changed), with "Hear a sample".

## What the owner does (once)

1. On GitHub: **Actions › Publish voices › Run workflow** (branch beta).
2. When it's done, pull before your next push: the run commits the voices' lines (`code/shared/voices.sha256`) and
   Muninn's library checksums (`code/muninn/gradle/verification-metadata.xml`) to beta, as you.
3. Push as usual. The next build carries the hashes: voices download from then on.

That's all. The workflow publishes voices-1 as a **pre-release**, so the update checks (which only look at version
releases) never pick it, and it fails if GitHub's "latest" release ever stops being a version. If it can't push (the
branch moved three times while it ran), its summary shows the lines and its artifacts hold the checksums file.

Until then, both apps say "Natural voices aren't ready yet" in Settings, offer no voice choice or download, and read
with the device's own voice where there is one (Read aloud is hidden where nothing can speak).

## How it works

- **Files.** `voice-female-1.tar` and `voice-male-1.tar` (about 60 to 65 MB each): `model.onnx`, `tokens.txt`,
  `MODEL_CARD` and `espeak-ng-data` (English only), repacked unchanged from sherpa-onnx's `tts-models` release, plus
  `natural-voices.txt`. Never replaced in place: a fixed voice goes out as voices-2 with new names and new lines.
- **Checked.** Each download's SHA-256 must match its line in `code/shared/voices.sha256`, built into the apps. No line,
  no download. A download that stalls (sleep, another network, a stuck Tor circuit) is cut after a minute with no data
  and resumes from where it stopped.
- **The speech engine.** The sherpa-onnx 1.13.8 files every build bundles (Muninn's AAR, Huginn's Linux library and
  Windows DLLs) have their lines in `code/shared/voices.sha256`. Every build stops when a file doesn't match its line,
  or has none (Gradle's `fetchSherpa`, and the Flatpak and Windows steps in `build.yml`; the Flatpak manifest pins the
  same hash). These hashes are trust on first use: upstream publishes no signatures. They were taken from the GitHub
  release in round 19, and the Linux one matched the Linux review's own download. Huginn loads the library only from
  its own folder (Windows: with its imports from that folder and System32 only), and only when it reports version
  1.13.8, the version `src/sherpatts.h` was copied from. A new engine version means new lines and checking those
  structs again.
- **Untrusted text.** Messages come from other people, and the engine (with its eSpeak NG) is native code inside the
  app. So the text is cleaned twice. First for any voice: no control or format characters, no markup (`< > [ ] { }`
  and backslash), no word longer than 40 letters, at most 4000 characters. Then for the engine: only Latin letters
  (at most two accents each), digits 0 to 9, spaces and `. , ! ? ; : ' " ( ) - / % +`; everything else becomes a
  space. eSpeak's phoneme input (`[[ ]]`) and SSML can't be reached. Text in another script goes to the device's own
  voice. If the engine ever stops the app in mid-sentence twice in a row, the app stops using it until the voices are
  deleted or downloaded again.
- **Library checksums (Muninn).** `code/muninn/gradle/verification-metadata.xml` lists the SHA-256 of every library and
  plugin Muninn's build uses (tor-android included). Gradle stops the build on any that changed or isn't listed. The
  Publish voices workflow writes it, and only ever adds to it: run it again after changing a version on purpose. Until
  it exists, beta builds warn and release builds stop.
- **When.** The first time something is read aloud, in the background, on Wi-Fi (or any network with "Allow on mobile
  data" on, in Settings › Look › Read aloud). Kept in Muninn's private files and Huginn's data folder; "Delete voices"
  frees the space. Switching voices downloads the other one the same way and deletes the first once it's ready.
- **Ultra privacy.** While it's on, the download goes through Tor when Ultra privacy offers a way to; otherwise the app
  waits and says so, and keeps the device's voice. On Muninn it starts only from Settings' "Download the voice now"
  (65 MB through Tor is slow), never from a read.
- **The device's own voice.** Muninn uses only a voice that works offline; the phone's speech app still receives the
  text. Huginn uses Qt's speech (SAPI or WinRT on Windows, speech-dispatcher on Linux), which speak on the computer.
  The Flatpak reaches the host's speech-dispatcher through its folder in the runtime directory, shared read-only
  (`--filesystem=xdg-run/speech-dispatcher:ro`); where that isn't running (and the runtime has no offline engine),
  nothing can speak until the natural voice is in, and Read aloud isn't offered.
- **Kids' Devices** (cap `k15`, protocol.md 7.18): the parent picks the voice on the device's page; the device downloads
  only that one. The speed (cap `k16`) sits right under it; it's sent only when the device and every co-parent's app say
  k16, and a device with none set reads at 80%. Device isn't offered there.
- **Licenses.** `code/shared/licenses/natural-voices.txt`, `sherpa-onnx-Apache-2.0.txt`, `onnxruntime-MIT.txt`; shown
  in About in both apps.
