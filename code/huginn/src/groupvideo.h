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
// checks those, selfTest() these):
//  - Full size: at most 1080p either way up, 30 frames a second, a minute and kFullMaxBytes. It goes straight (FileLink,
//    a file offer with "vid") to members on the same Wi-Fi, or with Direct calls and files on for each other.
//  - Everyone else: a relay copy of at most 720p, kRelayMaxMs and kRelayPieces pieces, its pieces paced.
//  - Huginn converts nothing (Qt 6.6 can't turn a file into another file): a video goes full size only if it's already
//    plain H.264 within the limits, and as the relay copy only if it already fits that (29.6). It does take out the
//    location and camera details (scrub), which needs no converting.
namespace GroupVideo
{
inline const char kCap[] = "v3";

constexpr int kFullLong = 1920;
constexpr int kFullShort = 1080;
constexpr int kFullFps = 30;
constexpr int kFullMaxMs = 60000;
constexpr qint64 kFullMaxBytes = 48LL * 1024 * 1024;   // under FileLink::kAutoBytes: arrives by itself
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
bool fitsRelay(const Info &i);
bool relayFits(qint64 bytes);
int relayBitrate(int ms, int pieces = kRelayPieces, int audioBps = 0);
int retryBitrate(int was, qint64 gotBytes, int ms);
int fullBitrate(int ms);

// "short" (everyone), "long" (trim, or in full later when direct), "too long" (trim only).
QString length(int ms);
QString clock(int ms);

// One member as this computer sees them now (see GroupVideo.kt's Member).
struct Member {
    QString k;
    bool sameWifi = false, trusted = false, files = false, v3 = false, kid = false, relayOnly = false;
};
bool fullOk(const Member &m);
// "full" or "relay" now; later: "full" or "none" for a long video sent in full.
QString delivery(const Member &m, bool unmetered);
QString later(const Member &m);
QHash<QString, QString> plan(const QList<Member> &members, bool unmetered, bool inFull);
QString names(const QStringList &n);
QString summary(const QHash<QString, QString> &plan, const std::function<QString(const QString &)> &nameOf);

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

// A file offer's "vid": absent, or a whole number 1 to kFullMaxMs.
bool vidOk(const QJsonValue &v);

// --chat-selftest (with the m2 checks): the rules above, an MP4's boxes, and the cap in ChatWire::kCaps.
bool selfTest();
}
