# Releases and how to check them

Every release has three downloads: `Huginn-Windows.zip`, `Huginn.flatpak` and `Muninn.apk`. For each one there is:

- its SHA-256, shown by GitHub beside the file on the release page.
- its `.sig`: the project's signature of the file.
- its `.vsig`: the project's signature of the file's version, name and SHA-256.
- a GitHub build attestation: GitHub's own record that the file was built by this repository's CI, from a
  given commit.

The SHA-256, `.sig` and `.vsig` of all three are in the signatures block at the end of the release notes, so the
release page lists only the three downloads and GitHub's two source archives.

## The signatures block

The release notes end with a code block like this (one line per download; the signatures are base64):

```
huginn-muninn signatures v1
label 1.3
Huginn-Windows.zip sha256 <64 hex digits> sig <base64, 88 characters> vsig <base64, 88 characters>
Huginn.flatpak sha256 <64 hex digits> sig <base64> vsig <base64>
Muninn.apk sha256 <64 hex digits> sig <base64> vsig <base64>
```

`label` is the release's version (`1.3`), or a pre-release's label (`1.3 beta 12`). Both apps read the block from the
release's `body` in GitHub's API. When the API refuses (it allows each address 60 questions an hour), they read it from
the release's page, `https://github.com/Ravenor907/Huginn-Muninn/releases/tag/<tag>`. They accept it only in exactly
this shape:

- the first line appears exactly once: at the start of a line of the body, or right after a `>` in the page (the start
  of the code element; the page's descriptions and copy button hold the text too, never right after a `>`);
- the block ends at the first `<` or `` ``` `` after it, or at the end of the text, and is at most 2048 characters;
  the body or page is at most 4 MiB;
- then `label <version or label>`, then one to three download lines, each file named once, single spaces, lowercase
  hex, each signature the one canonical base64 spelling of 64 bytes; a `\r` before a line break is ignored, and empty
  lines only at the end.

Anything else means no block. A release without a block has `<file>.sig` and `<file>.vsig` beside each download, and
the apps use those. Every check below is the same either way. `release_sig_ref.py` (`parse_notes`) is the reference,
and the `notes` vectors cover it.

**Moving over.** Apps up to 1.1 only read the separate files. So every release and pre-release made while
`code/VERSION` is 1.2 or lower also carries `<file>.sig`, `<file>.vsig` and (pre-release) `version.txt`; from 1.3 on
the block is the only copy. The rules job's `separate_sigs` output in `.github/workflows/build.yml` decides it.

## The project release key

The project signs every release file with its own Ed25519 key. No outside company or account is involved.

- The public half is in [release-key.pem](release-key.pem) and built into both apps:
  `705d7c9eb0626439b6ab749bbeefc9271876249c701914d60902a6b2b7341d06` (32 bytes, hex).
- The private half is only in the repository's GitHub secrets, as `RELEASE_SIGNING_KEY` (PEM, PKCS#8). It has
  never been committed. The release job signs with it and checks the signature against `release-key.pem`
  before publishing. If the secret is missing or wrong, the release fails and nothing is published.
- `<file>.sig` is 64 bytes: the Ed25519 signature (RFC 8032) of the file's raw bytes, made with
  `openssl pkeyutl -sign -rawin`.
- `<file>.vsig` is 64 bytes: the Ed25519 signature of this text (UTF-8, one item per line, no newline at the end):

  ```
  huginn-muninn release v1
  <version, as in the release tag without the v: 0.97>
  <the file's name: Muninn.apk>
  <the file's SHA-256, lowercase hex>
  ```

  The release job signs both with `code/shared/tools/sign-release.sh` and checks both against `release-key.pem`
  before publishing. The script also writes the signatures block, and the job checks the finished release notes with
  `release_sig_ref.py --check-notes` (the block names all three downloads and every SHA-256 and signature in it
  checks out). If any of the three downloads is missing, or any check fails, nothing is published.

Huginn (Windows and Linux) and Muninn install an update only when all are true:

1. its SHA-256 matches the one in the signatures block (and the one GitHub lists for the file, when the API gives
   it),
2. its `.sig` is a good signature from the project key,
3. its `.vsig` is a good signature from the project key of the text above, built from the tag of the release being
   installed, the file's name and the SHA-256 of the download, and
4. that version is newer than the one installed.

Otherwise they say so and install nothing. Because the version is signed, an older release file can't be posted
again under a newer tag to take anyone back to it. `code/shared/tools/release_sig_ref.py` is the reference and writes
`code/shared/vectors/release-sig-vectors.json` (`versioned`, `newer` and `notes`); Muninn checks it in `ReleaseSigTest`,
Huginn in `--release-sig-selftest`.

## Pre-releases

Stable releases are versions like `0.98` (`code/VERSION`). Every push to the `beta` branch builds a pre-release
labelled `<next> beta <n>`, for example `0.99 beta 12`: `<next>` is `code/BETA` and `<n>` the CI run number.

- **Order.** The number first; for the same number a stable release is newer than any of its betas, and betas
  compare by `<n>`: 0.98 < 0.99 beta 3 < 0.99 beta 7 < 0.99.
  `release_sig_ref.py` (`order`) is the reference; the `newer` vectors include betas.
- **One pre-release.** The beta job replaces one GitHub pre-release with the fixed tag `beta` on every push. It holds
  `Huginn.flatpak`, `Huginn-Windows.zip` and `Muninn.apk`, and its notes end with the signatures block, whose `label`
  line is the label. Each `.vsig` signs the text above with the label as its version line (`0.99 beta 12`). While
  it carries the separate files (see "Moving over"), `version.txt` holds the label, with no newline at the end. The
  beta job signs with the same script, key and checks as the release job, needs the same three
  downloads and the Muninn release keystore, and never deploys the relay. The pre-release is never marked latest.
- **The channel.** In Labs mode, Settings has an update channel: Stable or Pre-release. On Stable an app never looks
  at the `beta` tag. On Pre-release it also reads the pre-release's label (from its signatures block, or from
  `https://github.com/Ravenor907/Huginn-Muninn/releases/download/beta/version.txt` when it has none) and installs
  whichever of the latest release and the pre-release is newest by the order above, with the same four checks.
- **Going back to stable.** Labs has "Go back to stable": after the person confirms, the app installs the latest
  stable release even though it is older than the installed pre-release. It is the only time the newer check is
  skipped, only for the latest stable release, and every other check still applies. It sets the channel back to
  Stable and turns Labs mode off.
- **Android version codes.** A pre-release APK has the same `versionCode` as the stable version in `code/VERSION`
  (Gradle property `betaLabel` sets only its `versionName`), so going back to stable installs over it without
  uninstalling. Stable releases keep their usual `versionCode`.
- **Huginn.** CI passes the label as `-DHUGINN_VERSION_LABEL=<label>` beside `-DHUGINN_VERSION`; empty or missing
  means a stable build.

If the key is ever lost, the apps can't accept updates signed with a new one. Everyone would have to download
the new version by hand once. Keep a copy of the private key somewhere safe and offline.

## Check a download by hand

You need OpenSSL 3 (most Linux systems have it; on Windows it comes with Git for Windows). Put the download and
[release-key.pem](release-key.pem) in one folder, and copy the signatures block from the release notes into a file
there named `signatures.txt`.

The quick way, with Python 3 and `release_sig_ref.py` from this repository, checks every SHA-256 and signature in the
block against the downloads in the folder (all three must be there). For release 1.3:

```
python3 release_sig_ref.py --check-notes signatures.txt 1.3 release-key.pem
```

It prints `The signatures block for 1.3 checks out.` for good files. By hand, for one file: turn its line's `sig` and
`vsig` into files (in Git Bash on Windows too):

```
line=$(grep '^Huginn-Windows.zip ' signatures.txt | tr -d '\r')
echo "$line" | cut -d' ' -f5 | base64 -d > Huginn-Windows.zip.sig
echo "$line" | cut -d' ' -f7 | base64 -d > Huginn-Windows.zip.vsig
```

(A release that has `Huginn-Windows.zip.sig` and `.vsig` beside the download: download those instead.) Then the
signature of the file:

```
openssl pkeyutl -verify -pubin -inkey release-key.pem -rawin -in Huginn-Windows.zip -sigfile Huginn-Windows.zip.sig
```

It prints `Signature Verified Successfully` for a good file. The version signature, for release 1.3:

```
printf 'huginn-muninn release v1\n%s\n%s\n%s' 1.3 Huginn-Windows.zip "$(sha256sum < Huginn-Windows.zip | cut -d' ' -f1)" > version.txt
openssl pkeyutl -verify -pubin -inkey release-key.pem -rawin -in version.txt -sigfile Huginn-Windows.zip.vsig
```

For the SHA-256, compare this with the line's `sha256` and the digest GitHub shows beside the file:

```
sha256sum Huginn-Windows.zip
```

## Check the build attestation

With the GitHub CLI (`gh`):

```
gh attestation verify Huginn-Windows.zip --repo Ravenor907/Huginn-Muninn
```

It shows the workflow and commit that built the file. The same works for `Huginn.flatpak` and `Muninn.apk`.

## Windows warnings

Huginn.exe isn't signed with a paid Windows code-signing certificate, so Windows SmartScreen may say it is
from an unknown publisher the first time. Choose **More info**, then **Run anyway**. The signature and
attestation above are how to check that the file really came from this project. Huginn.exe is built with
Control Flow Guard, CET shadow stacks, ASLR and DEP, and CI fails the build if any of them is missing.
