# Encrypted backups

One file format for Huginn and Muninn, so a backup made on a phone restores on a computer and back. The reference is
`code/shared/tools/backup_ref.py`, which writes `code/shared/vectors/backup-vectors.json` (and the Android test copy).
Huginn checks the vectors in `--chat-selftest` and its own round trip in `--backup-selftest`; Muninn in
`BackupFormatTest`. When this text and the reference disagree, the reference wins.

## 1. What the person sees

**Back up** (Settings › Devices and backup › Back up):

1. What's inside: identity, contacts, chats, groups, folders and settings. Files and pictures (photos, voice and video
   messages) are in it or left out by a checkbox that shows their size.
2. A password, typed twice, at least 12 characters, with a strength hint. It can't be skipped or reset.
3. The Save picker: Qt's file picker on a computer (Documents, `Huginn backup 2026-11-02.hmbak`), the system's Save
   picker on Android (`Muninn backup 2026-11-02.hmbak`; Download is suggested).
4. "Saved to" the full path (on Android, the folder and name the picker gives), "Encrypted with your password", the
   size, and on a computer Show in folder.

**Restore** (Settings › Devices and backup › Restore from a backup, first setup's Welcome page, and Muninn's "can't open your data"
screen):

1. Choose file: any file, any name, from the file picker. Nothing has to be in a set folder or have a set name.
2. Its password. A wrong one says "Wrong password. Try again." and can be tried again.
3. What's in it: when and on which device it was made, in which app, the counts of contacts, chats, groups and folders,
   and whether files and pictures are in it. Restore (replaces what's here) is held to confirm.
4. A copy of what's here is kept first: Huginn copies its chat folder (lock file too) to `chat-kept-<date>` beside it;
   Muninn copies its files (lock file and open key too) to a `kept-<time>` folder. Erase everything removes these
   copies too. Then the backup goes in and the app restarts.

## 2. The file

    "MUNB1"                              5 bytes
    header length                        uint32, big-endian
    header                               UTF-8 JSON, at most 4096 bytes
    nonce                                12 bytes
    ciphertext                           AES-256-GCM
    tag                                  16 bytes

The usual ending is `.hmbak` (older ones were `.mbk`). The name doesn't matter: the first five bytes say what it is.

### 2.1 Header

    {"v":3, "salt":base64(16 bytes), "kdf":"scrypt", "N":65536, "r":8, "p":2, "created":ISO 8601 UTC, "app":"huginn"|"muninn"}

- The key: scrypt(password as UTF-8, salt, N, r, p), 32 bytes. N = 2^16 with r = 8 and p = 2 is as hard to guess
  as N = 2^17 with p = 1 and needs half the memory, which matters on a phone. Version 3 MUST use scrypt (Muninn has no
  Argon2id), so both apps can open every new backup.
- The GCM additional data is every byte before the nonce (magic, length and header). A changed header fails like a
  changed body or a wrong password; the apps can't tell those apart and say "Wrong password. Try again."
- Readers MUST refuse key settings weaker than the apps write (scrypt N below 65536, not a power of two, r below 8,
  PBKDF2 under 100000 rounds) and ones so big they would run out of memory (N r 128 over 1 GiB). The header is read
  before the password is checked.
- Nothing else is readable without the password: not the names, not how many files there are.

### 2.2 Inside

The plaintext is the files one after another:

    name length    uint32, big-endian (1 to 4096)
    name           UTF-8, a relative path: no leading "/", no "..", no "\", no empty parts
    data length    uint32, big-endian
    data           the file's plain contents

The app's own files (as it keeps them, so a backup restores fully in the app that made it) and these entries, whose
names start with "@" and are never written as files:

| Name | What |
|---|---|
| `@about.json` | `{"v":1, "app", "device", "created", "files": true or false}`: which app and device made it, and whether files and pictures are in it. |
| `@move.json` | The shared form of section 18.4 of protocol.md: identity, contacts, ratchets, chats (text), groups, folders and profile. The counts on the Restore screen come from it. |
| `@prefs.json` | Muninn's settings (Muninn only). |

Huginn's settings go in as `huginn-settings.dat`. Files and pictures are `photos/...` and `mirror-media/...`; left out
when the checkbox is off.

### 2.3 Restoring

- Made by this app: its own files go back as they were, settings too.
- Made by the other app: only `@move.json` is used, the same way a move between the two works (protocol.md 18.4).
  Text, contacts, groups, folders and the profile come across; pictures and files stay behind, and the Restore screen
  says so. This device's own settings stay, with the backup's identity and name.

## 3. Older backups

- v2: `{"v":2, salt, "kdf":"scrypt", N, r, p, ...}` (Muninn, and Huginn without Argon2id) or `"kdf":"argon2id", m, t, p`
  (Huginn). No additional data.
- v1: `{"v":1, salt, "rounds":310000, ...}`, PBKDF2-HMAC-SHA256. No additional data.

Both are still restored, in the app that made them; the other app says where to restore it. There never was an
unencrypted backup: every backup either app has made is encrypted with its password.
