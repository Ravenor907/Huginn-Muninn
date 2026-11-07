# Dictation

Dictation turns speech into text in Huginn's message box, on Linux (the Flatpak) and Windows. It runs on this
computer only: no outside service hears anything, and nothing you say leaves the computer.

## Turning it on

Settings › Look › Accessibility › **Dictation**, off until turned on. Turning it on asks first: "Download speech to text
(about 125 MB)? It runs on this computer; nothing you say leaves it." Then the model downloads once, with its progress
in the row's line. Turning it off deletes the model and frees the space, so Huginn stays small for everyone who
doesn't use it.

Once it's in, every message box (chats, groups, a linked phone's chats, and the prompt at the top in game mode) shows a
small microphone beside the emoji button:

- **Tap it** (or Ctrl+Shift+S in the box, or Dictate in the box's options on a controller, LT in the prompt at the top).
  It shows it's listening in the accent color, with a ring that pulses.
- **Speak.** After each short pause, the phrase comes in at the cursor, with a space before it when it follows a word.
- **Tap it again** (or Esc) to stop. What was said since the last pause is added too.

Never during a call or voice room: the microphone is greyed out ("Not during a call"), and a call that starts stops it.
If the microphone can't open, Huginn says "The microphone isn't available." The microphone is the one picked in
Settings › Calls › Microphone, or the system's default.

## The download and its checks

- **Files.** `dictation-1.tar` on this project's **dictation-1** pre-release (the update checks never look at it), a
  plain tar with `dictation-1/<file>` entries: Moonshine Tiny int8 (`preprocess.onnx`, `encode.int8.onnx`,
  `uncached_decode.int8.onnx`, `cached_decode.int8.onnx`, `tokens.txt`, `LICENSE-moonshine.txt`) and Silero VAD
  (`silero_vad.onnx`). Never replaced in place: a new model goes out as dictation-2 with its own lines.
- **Checked.** Huginn keeps the download only when every file matches its line in `code/shared/dictation.sha256`,
  built into the app. Only those regular files are unpacked, straight into the model's folder: no links, no other
  names, nothing outside the folder. One that fails its check is deleted and tried again later.
- **Network.** The same rules as the natural voices: it waits on a metered connection unless Read aloud's "Allow on
  mobile data" is on, goes only through Tor while Ultra privacy is on (and waits for Tor), and resumes from where it
  stopped after a cut, a stall of a minute or a network change.
- **Where.** The data folder's `dictation/dictation-1`, beside `voices`. It's unpacked and checked beside its place
  first, so a crash never leaves half a model.

## How it works

- **The engine.** The same sherpa-onnx 1.13.8 library Read aloud loads (from Huginn's own folder, only that version),
  never linked. Its offline recognizer runs Moonshine Tiny; Silero VAD finds where each phrase starts and stops (a pause
  of about 0.4 s ends one; a phrase over 20 s is split).
- **The sound.** The microphone at 16 kHz mono (or the microphone's own format, made 16 kHz mono). It goes to the
  detector and the recognizer on their own thread, never the window's.
- **Memory.** The recognizer starts the first time you dictate and stays until Dictation is turned off or Huginn
  closes, when it's let go of cleanly.

## Privacy

Nothing is recorded to disk and nothing is sent: the sound goes from the microphone to the recognizer in memory, and
only the text comes out, into the box. The text is sent only when you send the message. The download is the only time
Dictation uses the network.

## Credits

Moonshine Tiny by Useful Sensors (MIT, `code/shared/licenses/moonshine-MIT.txt`) and Silero VAD by the Silero Team
(MIT, `code/shared/licenses/silero-vad-MIT.txt`), run by sherpa-onnx (Apache-2.0) with ONNX Runtime (MIT).
