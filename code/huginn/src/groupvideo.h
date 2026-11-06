#pragma once

#include <QByteArray>
#include <QIODevice>
#include <QHash>
#include <QJsonValue>
#include <QList>
#include <QString>
#include <QStringList>
#include <functional>

// Picked videos (cap v3, shared/docs/protocol.md section 29), the same rules as Muninn's GroupVideo.kt (GroupVideoTest
// checks those, selfTest() these). 1.42 (owner, 2026-11-04): a video is never cut or trimmed.
//  - Direct (the same Wi-Fi, or Trusted direct on for each other): full size, at most 1080p either way up and
//    30 frames a second, with no length or size cap beyond FileLink's 4 GB. A file offer with "vid".
//  - Everyone else whose app has the new file paths (kNewPathCap): a 720p copy of up to 5 minutes through FileLink,
//    which uses Tor when there's no direct path. Never through the relays.
//  - Older apps only: the relay copy (at most 720p, kRelayMaxMs and kRelayPieces pieces, paced), or a full copy of at
//    most kOldFullMaxMs straight.
//  - Over 5 minutes with no direct path for someone: "Video too long" (it can go as a file).
//  - Huginn converts nothing (Qt 6.6 can't turn a file into another file): it sends a 720p-or-smaller plain H.264/AAC
//    MP4 of up to 5 minutes as it is, and full size only a plain 1080p30 one (29.6); anything else can go as a file. It
//    does take out the location and camera details (scrub), which needs no converting.
namespace GroupVideo
{
inline const char kCap[] = "v3";
// The cap of apps with FileLink's new paths (Nearby, trusted direct, then Tor): f4, as Muninn's TorFiles.CAP. Without
// it a member counts as an older app (relay copy).
inline const char kNewPathCap[] = "f4";

constexpr int kFullLong = 1920;
constexpr int kFullShort = 1080;
constexpr int kFullFps = 30;
constexpr qint64 kFullMaxBytes = 4LL * 1024 * 1024 * 1024;   // FileLink::kMaxBytes: the only ceiling when direct
constexpr int kOldFullMaxMs = 60000;                    // an older app takes a "vid" of at most a minute
constexpr int kVidMaxMs = 24 * 3600 * 1000;             // what a new app takes as a "vid"
constexpr int kFullMaxMs = kVidMaxMs;                   // FileLink's "vid" bound (an older app only gets a minute: plan)
constexpr int kSmallLong = 1280;
constexpr int kSmallShort = 720;
constexpr int kSmallMaxMs = 5 * 60000;                  // the 720p copy for those not direct
constexpr int kFullVideoBitrate = 4500000;
constexpr int kFullAudioBitrate = 128000;

constexpr int kRelayLong = 1280;
constexpr int kRelayShort = 720;
constexpr int kRelayMaxMs = 20000;
constexpr int kPiece = 16 * 1024;                       // Photos::kPiece
constexpr int kRelayPieces = 192;                       // 3 MB: every app since m2 takes up to 200
constexpr qint64 kRelayBytes = qint64(kRelayPieces) * kPiece;
constexpr int kRelayAudioBitrate = 128000;
constexpr int kMinVideoBitrate = 300000;
constexpr int kMaxRelayVideoBitrate = 1500000;
constexpr double kRelayAim = 0.90;
constexpr double kRetryShare = 0.85;
constexpr int kSlackMs = 500;

constexpr int kPacePerSecond = 3;                       // relay pieces of videos, from this computer in all
constexpr qint64 kBackoffMs = 60000;                    // after a relay's "rate-limited"
constexpr qint64 kMaxMoov = 16LL * 1024 * 1024;         // a bigger "moov" isn't read (a minute's is well under 1 MB)

// What an MP4 is, read from its own boxes (no decoding): the video track's picture, frames a second, length, size, and
// its codecs: [video] and [sound] are the first sample entries' 4CCs ("avc1", "hvc1", "mp4a"; sound empty when none).
// [plain]: H.264 in 8 bits and not HDR, with AAC or no sound (29.1), so every app plays it as it is; Huginn sends
// nothing else as a video (it can't convert), only as a file.
struct Info {
    int w = 0, h = 0, ms = 0;
    double fps = 0;
    qint64 bytes = 0;
    QByteArray video, sound;
    bool plain = true;
};
Info probe(const QByteArray &mp4);
// The same from a file, reading only its top boxes' headers and "moov" (fast on a slow card or a network share).
// [head]: set to the file's first 12 bytes (for MoreWire::isVideo).
Info probeFile(const QString &path, QByteArray *head);
bool plainCodecs(const QByteArray &video, int avcProfile, bool hdr, const QByteArray &sound);

// Round 19: location and camera details out of a video before it goes (as Photos does for pictures), changed in place
// so the picture, the sound and every sample offset stay exactly as they are (Muninn's Mp4Scrub does the same):
// "udta", "meta" and "uuid" boxes (Android's location, an iPhone's location keys, XMP, make and model) at the top, in
// "moov" and in each track become "free" and are zeroed; tracks other than picture and sound (GPS and other timed
// metadata) become "free" and their samples are zeroed; creation times are zeroed; anything after the last whole box is
// cut off. A file it can't do that to safely (fragmented, malformed) is refused: then it only goes as a file.
// scrub: in place on [d] (open for reading and writing); the length to cut it to, or -1. [write] false: only checks
// that it could be done (reads the top boxes' headers and "moov"; [d] may be read-only).
qint64 scrub(QIODevice *d, bool write = true);
QByteArray scrubbed(const QByteArray &mp4);            // a cleaned copy, or empty
bool scrubFile(const QString &path);                     // in place (and cut); false: don't send it as a video

bool withinSize(int w, int h, int longSide, int shortSide);
bool withinFps(double fps, int most);
bool fitsFull(const Info &i);
bool needsConvert(const Info &i);
bool fitsSmall(const Info &i);
bool biggerThan720(const Info &i);
bool fitsRelay(const Info &i);
bool relayFits(qint64 bytes);
int relayBitrate(int ms, int pieces = kRelayPieces, int audioBps = 0);
int retryBitrate(int was, qint64 gotBytes, int ms);
int fullBitrate(int ms);

// "short" (everyone, older apps' relay copy too), "long" (up to 5 minutes), "too long" (direct only, or as a file).
QString length(int ms);
QString clock(int ms);

// One member as this computer sees them now (see GroupVideo.kt's Member).
struct Member {
    QString k;
    bool sameWifi = false, trusted = false, files = false, v3 = false, kid = false, relayOnly = false, newPath = false;
    bool family = false;   // Family or trusted, and the call relay can carry it when direct doesn't work
    bool reach = true;     // FileLink has a way to them now (Tor only under Ultra privacy)
};
bool fullOk(const Member &m);
bool direct(const Member &m, bool unmetered);
// "full", "small" (720p copy through FileLink), "relay" (older apps) or "none" for a video [ms] long.
QString delivery(const Member &m, bool unmetered, int ms);
QHash<QString, QString> plan(const QList<Member> &members, bool unmetered, int ms);
// Over 5 minutes and someone isn't direct: "Video too long".
bool tooLong(int ms, const QHash<QString, QString> &plan);
inline const char kTooLongTitle[] = "Video too long";
inline const char kTooLongText[] = "Videos over the internet can be up to 5 minutes.";
inline const char kNote[] = "Full size close by. Others get up to 5 minutes.";
QString names(const QStringList &n);
// newPath: whether a member's app has the new paths (Member::newPath). A member it can't go to says "their app is
// older" without them, and "no way to reach them" with them (none given: every member has them).
QString summary(const QHash<QString, QString> &plan, const std::function<QString(const QString &)> &nameOf,
                const std::function<bool(const QString &)> &newPath = {});

// When each relay piece may go: kPacePerSecond a second, nothing while a "rate-limited" answer is under kBackoffMs old.
class Pacer
{
public:
    explicit Pacer(int perSecond = kPacePerSecond) : m_per(perSecond < 1 ? 1 : perSecond) {}
    qint64 gapMs() const { return 1000 / m_per; }
    qint64 take(qint64 now);
    void limited(qint64 now);
    bool pausedAt(qint64 now) const { return now < m_paused; }

private:
    int m_per;
    qint64 m_next = 0, m_paused = 0;
};
qint64 paceMs(int pieces, int perSecond = kPacePerSecond);
bool isRateLimit(const QString &message);
// A relay said "rate-limited" (noted on the relay thread, read on the window's thread).
void noteRateLimited(qint64 now);
qint64 rateLimitedAt();

// A file offer's "vid": absent, or a whole number 1 to kVidMaxMs (apps before 1.42 took up to a minute).
bool vidOk(const QJsonValue &v);

// --chat-selftest (with the m2 checks): the rules above, an MP4's boxes, and the cap in ChatWire::kCaps.
bool selfTest();
}
