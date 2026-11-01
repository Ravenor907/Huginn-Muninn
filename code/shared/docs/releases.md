# Releases and how to check them

Every release has three downloads: `Huginn-Windows.zip`, `Huginn.flatpak` and `Muninn.apk`. Next to each one
the release has:

- its SHA-256, shown by GitHub beside the file on the release page.
- `<file>.sig`: the project's signature of the file.
- `<file>.vsig`: the project's signature of the file's version, name and SHA-256.
- a GitHub build attestation: GitHub's own record that the file was built by this repository's CI, from a
  given commit.

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
  before publishing. If any of the three downloads is missing, it fails and nothing is published.

Huginn (Windows and Linux) and Muninn install an update only when all are true:

1. its SHA-256 matches the one GitHub lists for the file,
2. its `.sig` is a good signature from the project key,
3. its `.vsig` is a good signature from the project key of the text above, built from the tag of the release being
   installed, the file's name and the SHA-256 of the download, and
4. that version is newer than the one installed.

Otherwise they say so and install nothing. Because the version is signed, an older release file can't be posted
again under a newer tag to take anyone back to it. Copies that check only the `.sig` keep updating, since every
release still carries it. `code/shared/tools/release_sig_ref.py` is the reference and writes
`code/shared/vectors/release-sig-vectors.json` (`versioned` and `newer`); Muninn checks it in `ReleaseSigTest`, Huginn in
`--release-sig-selftest`.

## Pre-releases

Stable releases are versions like `0.98` (`code/VERSION`). Every push to the `beta` branch builds a pre-release
labelled `<next> beta <n>`, for example `0.99 beta 12`: `<next>` is `code/BETA` and `<n>` the CI run number.

- **Order.** The number first; for the same number a stable release is newer than any of its betas, and betas
  compare by `<n>`: 0.98 < 0.99 beta 3 < 0.99 beta 7 < 0.99.
  `release_sig_ref.py` (`order`) is the reference; the `newer` vectors include betas.
- **One pre-release.** The beta job replaces one GitHub pre-release with the fixed tag `beta` on every push. It holds
  `Huginn.flatpak`, `Huginn-Windows.zip` and `Muninn.apk`, each with `.sig` and `.vsig`, and `version.txt`: the
  label, with no newline at the end. Each `.vsig` signs the text above with the label as its version line
  (`0.99 beta 12`). The beta job signs with the same script, key and checks as the release job, needs the same three
  downloads and the Muninn release keystore, and never deploys the relay. The pre-release is never marked latest.
- **The channel.** In Labs mode, Settings has an update channel: Stable or Pre-release. On Stable an app never looks
  at the `beta` tag. On Pre-release it also reads
  `https://github.com/Ravenor907/Huginn-Muninn/releases/download/beta/version.txt` and installs whichever of the
  latest release and the pre-release is newest by the order above, with the same four checks (the pre-release's
  SHA-256 is the one GitHub lists for its file).
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

You need OpenSSL 3 (most Linux systems have it; on Windows it comes with Git for Windows). In the folder with the
download, its `.sig` and [release-key.pem](release-key.pem):

```
openssl pkeyutl -verify -pubin -inkey release-key.pem -rawin -in Huginn-Windows.zip -sigfile Huginn-Windows.zip.sig
```

It prints `Signature Verified Successfully` for a good file. The version signature, for release 0.97:

```
printf 'huginn-muninn release v1\n%s\n%s\n%s' 0.97 Huginn-Windows.zip "$(sha256sum < Huginn-Windows.zip | cut -d' ' -f1)" > version.txt
openssl pkeyutl -verify -pubin -inkey release-key.pem -rawin -in version.txt -sigfile Huginn-Windows.zip.vsig
```

For the SHA-256, compare this with the digest GitHub shows beside the file:

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
