#include "groupvideo.h"
#include "chatwire.h"

#include <QBuffer>
#include <QFile>
#include <QtEndian>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstring>

namespace GroupVideo
{
namespace
{
std::atomic<qint64> g_limitedAt{0};

quint32 be32(const QByteArray &d, qsizetype at) { return qFromBigEndian<quint32>(reinterpret_cast<const uchar *>(d.constData() + at)); }
quint64 be64(const QByteArray &d, qsizetype at) { return qFromBigEndian<quint64>(reinterpret_cast<const uchar *>(d.constData() + at)); }

// The box at [at] inside [end]: its type, where its contents start and where the next box starts. False when malformed.
bool box(const QByteArray &d, qsizetype at, qsizetype end, QByteArray *type, qsizetype *body, qsizetype *next)
{
    if (at < 0 || at + 8 > end || end > d.size())
        return false;
    quint64 size = be32(d, at);
    qsizetype head = 8;
    if (size == 1) {
        if (at + 16 > end)
            return false;
        size = be64(d, at + 8);
        head = 16;
    } else if (size == 0) {
        size = quint64(end - at);
    }
    if (size < quint64(head) || size > quint64(end - at))
        return false;
    *type = d.mid(at + 4, 4);
    *body = at + head;
    *next = at + qsizetype(size);
    return true;
}

// The first box of [want] directly inside [at, end): its contents' start and end, or false.
bool find(const QByteArray &d, qsizetype at, qsizetype end, const char *want, qsizetype *body, qsizetype *stop)
{
    QByteArray type;
    qsizetype b = 0, n = 0;
    while (box(d, at, end, &type, &b, &n)) {
        if (type == want) {
            *body = b;
            *stop = n;
            return true;
        }
        at = n;
    }
    return false;
}

quint16 be16(const QByteArray &d, qsizetype at) { return qFromBigEndian<quint16>(reinterpret_cast<const uchar *>(d.constData() + at)); }

// The first sample entry in a track's stsd ([md, mdEnd): its mdia): its 4CC, and where its child boxes are (after the
// entry's [fixed] bytes: 78 for a picture). False when there is none.
bool sampleEntry(const QByteArray &d, qsizetype md, qsizetype mdEnd, QByteArray *type, qsizetype *kids, qsizetype *kidsEnd, qsizetype fixed)
{
    qsizetype mi = 0, miEnd = 0, st = 0, stEnd = 0, sd = 0, sdEnd = 0, b = 0, n = 0;
    if (!find(d, md, mdEnd, "minf", &mi, &miEnd) || !find(d, mi, miEnd, "stbl", &st, &stEnd) || !find(d, st, stEnd, "stsd", &sd, &sdEnd)
        || sd + 8 > sdEnd || !box(d, sd + 8, sdEnd, type, &b, &n))
        return false;
    *kids = qMin(b + fixed, n);
    *kidsEnd = n;
    return true;
}
}

bool plainCodecs(const QByteArray &video, int avcProfile, bool hdr, const QByteArray &sound)
{
    // Baseline, Main, Extended and High: 8 bits. High 10, 4:2:2 and 4:4:4 (and anything unknown) aren't plain.
    const bool eightBit = avcProfile == 66 || avcProfile == 77 || avcProfile == 88 || avcProfile == 100;
    return (video == "avc1" || video == "avc3") && eightBit && !hdr && (sound.isEmpty() || sound == "mp4a");
}

Info probe(const QByteArray &mp4)
{
    Info info;
    info.bytes = mp4.size();
    qsizetype moov = 0, moovEnd = 0;
    if (!find(mp4, 0, mp4.size(), "moov", &moov, &moovEnd))
        return info;
    // The length: mvhd's duration over its time scale.
    qsizetype mv = 0, mvEnd = 0;
    if (find(mp4, moov, moovEnd, "mvhd", &mv, &mvEnd) && mv + 32 <= mvEnd) {
        const bool v1 = uchar(mp4.at(mv)) == 1;
        const quint64 scale = v1 ? be32(mp4, mv + 20) : be32(mp4, mv + 12);
        const quint64 dur = v1 ? be64(mp4, mv + 24) : be32(mp4, mv + 16);
        if (scale > 0 && dur / scale < 24 * 3600)
            info.ms = int(dur * 1000 / scale);
    }
    // The first video track: its size (tkhd), frames a second from its sample count and length (mdhd, stsz), and its
    // codec (stsd: the sample entry, avcC's profile, colr's transfer). The first sound track: its codec.
    QByteArray type;
    qsizetype b = 0, n = 0, at = moov;
    bool video = false, hdr = false;
    int profile = -1;
    while (box(mp4, at, moovEnd, &type, &b, &n)) {
        at = n;
        if (type != "trak")
            continue;
        qsizetype md = 0, mdEnd = 0, hd = 0, hdEnd = 0;
        if (!find(mp4, b, n, "mdia", &md, &mdEnd) || !find(mp4, md, mdEnd, "hdlr", &hd, &hdEnd) || hd + 12 > hdEnd)
            continue;
        const QByteArray handler = mp4.mid(hd + 8, 4);
        if (handler == "soun") {
            if (info.sound.isEmpty()) {
                QByteArray entry;
                qsizetype k = 0, kEnd = 0;
                info.sound = sampleEntry(mp4, md, mdEnd, &entry, &k, &kEnd, 28) ? entry : QByteArray("?");
            }
            continue;
        }
        if (handler != "vide" || video)
            continue;
        video = true;
        qsizetype tk = 0, tkEnd = 0;
        if (find(mp4, b, n, "tkhd", &tk, &tkEnd) && tk < tkEnd) {
            const qsizetype wAt = tk + (uchar(mp4.at(tk)) == 1 ? 88 : 76);
            if (wAt + 8 <= tkEnd) {
                info.w = int(be32(mp4, wAt) >> 16);
                info.h = int(be32(mp4, wAt + 4) >> 16);
            }
        }
        qsizetype mh = 0, mhEnd = 0, mi = 0, miEnd = 0, st = 0, stEnd = 0, sz = 0, szEnd = 0;
        if (find(mp4, md, mdEnd, "mdhd", &mh, &mhEnd) && mh + 24 <= mhEnd && find(mp4, md, mdEnd, "minf", &mi, &miEnd)
            && find(mp4, mi, miEnd, "stbl", &st, &stEnd) && find(mp4, st, stEnd, "stsz", &sz, &szEnd) && sz + 12 <= szEnd) {
            const bool v1 = uchar(mp4.at(mh)) == 1;
            const quint64 scale = v1 ? be32(mp4, mh + 20) : be32(mp4, mh + 12);
            const quint64 dur = v1 ? (mh + 32 <= mhEnd ? be64(mp4, mh + 24) : 0) : be32(mp4, mh + 16);
            const quint32 samples = be32(mp4, sz + 8);
            if (scale > 0 && dur > 0)
                info.fps = double(samples) * double(scale) / double(dur);
        }
        QByteArray entry;
        qsizetype k = 0, kEnd = 0;
        if (sampleEntry(mp4, md, mdEnd, &entry, &k, &kEnd, 78)) {
            info.video = entry;
            qsizetype c = 0, cEnd = 0;
            if (find(mp4, k, kEnd, "avcC", &c, &cEnd) && c + 2 <= cEnd)
                profile = uchar(mp4.at(c + 1));
            // colr "nclx" (or QuickTime's "nclc"): primaries, transfer. BT.2020, PQ or HLG is HDR.
            if (find(mp4, k, kEnd, "colr", &c, &cEnd) && c + 8 <= cEnd && (mp4.mid(c, 4) == "nclx" || mp4.mid(c, 4) == "nclc")) {
                const quint16 primaries = be16(mp4, c + 4), transfer = be16(mp4, c + 6);
                hdr = primaries == 9 || transfer == 16 || transfer == 18;
            }
        }
    }
    info.plain = plainCodecs(info.video, profile, hdr, info.sound);
    return info;
}

Info probeFile(const QString &path, QByteArray *head)
{
    Info info;
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly))
        return info;
    const qint64 size = f.size();
    *head = f.read(12);
    // The top boxes' headers only, then "moov" (its own walk is probe's).
    qint64 at = 0;
    while (at + 8 <= size && f.seek(at)) {
        const QByteArray h = f.read(16);
        if (h.size() < 8)
            break;
        quint64 len = be32(h, 0);
        qint64 headLen = 8;
        if (len == 1) {
            if (h.size() < 16)
                break;
            len = be64(h, 8);
            headLen = 16;
        } else if (len == 0) {
            len = quint64(size - at);
        }
        if (len < quint64(headLen) || len > quint64(size - at))
            break;
        if (h.mid(4, 4) == "moov") {
            if (len > quint64(kMaxMoov) || !f.seek(at))
                break;
            const QByteArray moov = f.read(qint64(len));
            if (moov.size() != qsizetype(len))
                break;
            info = probe(moov);
            break;
        }
        at += qint64(len);
    }
    info.bytes = size;
    return info;
}

bool withinSize(int w, int h, int longSide, int shortSide) { return qMax(w, h) <= longSide && qMin(w, h) <= shortSide; }
bool withinFps(double fps, int most) { return fps <= most + 0.5; }

bool fitsFull(const Info &i)
{
    return i.plain && i.w > 0 && i.h > 0 && withinSize(i.w, i.h, kFullLong, kFullShort) && withinFps(i.fps, kFullFps) && i.bytes >= 1
        && i.bytes <= kFullMaxBytes && i.ms >= 1;
}

bool needsConvert(const Info &i)
{
    return !(i.plain && withinSize(i.w, i.h, kFullLong, kFullShort) && withinFps(i.fps, kFullFps) && i.bytes >= 1 && i.bytes <= kFullMaxBytes);
}

bool fitsSmall(const Info &i)
{
    return i.plain && i.w > 0 && i.h > 0 && withinSize(i.w, i.h, kSmallLong, kSmallShort) && withinFps(i.fps, kFullFps) && i.bytes >= 1
        && i.bytes <= kFullMaxBytes && i.ms >= 1 && i.ms <= kSmallMaxMs + kSlackMs;
}

bool biggerThan720(const Info &i) { return i.w > 0 && i.h > 0 && !withinSize(i.w, i.h, kSmallLong, kSmallShort); }

bool fitsRelay(const Info &i)
{
    return i.plain && i.w > 0 && i.h > 0 && withinSize(i.w, i.h, kRelayLong, kRelayShort) && withinFps(i.fps, kFullFps) && relayFits(i.bytes)
        && i.ms >= 1 && i.ms <= kRelayMaxMs + kSlackMs;
}

bool relayFits(qint64 bytes) { return bytes >= 1 && bytes <= kRelayBytes; }

int relayBitrate(int ms, int pieces, int audioBps)
{
    const double s = qMax(ms, 1000) / 1000.0;
    const double all = double(pieces) * kPiece * 8 * kRelayAim / s;
    const double sound = audioBps > 0 ? audioBps : kRelayAudioBitrate;
    return qBound(kMinVideoBitrate, int(all - sound), kMaxRelayVideoBitrate);
}

int retryBitrate(int was, qint64 gotBytes, int ms)
{
    const double s = qMax(ms, 1000) / 1000.0;
    const double over = (double(gotBytes) - double(kRelayBytes) * kRelayAim) * 8 / s;
    return qMax(kMinVideoBitrate, qMin(int(was * kRetryShare), int(was - over)));
}

int fullBitrate(int ms)
{
    const double s = qMax(ms, 1000) / 1000.0;
    const double most = double(kFullMaxBytes) * 8 * kRelayAim / s - kFullAudioBitrate;
    return qMax(kMinVideoBitrate, most >= kFullVideoBitrate ? kFullVideoBitrate : int(most));
}

QString length(int ms)
{
    if (ms <= kRelayMaxMs + kSlackMs)
        return QStringLiteral("short");
    if (ms <= kSmallMaxMs + kSlackMs)
        return QStringLiteral("long");
    return QStringLiteral("too long");
}

QString clock(int ms)
{
    const int s = int((qint64(qMax(ms, 0)) + 500) / 1000);
    if (s >= 3600)
        return QStringLiteral("%1:%2:%3").arg(s / 3600).arg(s / 60 % 60, 2, 10, QLatin1Char('0')).arg(s % 60, 2, 10, QLatin1Char('0'));
    return QStringLiteral("%1:%2").arg(s / 60).arg(s % 60, 2, 10, QLatin1Char('0'));
}

bool fullOk(const Member &m) { return m.files && m.v3 && !m.kid && !m.relayOnly; }

bool direct(const Member &m, bool unmetered) { return m.sameWifi || (m.trusted && unmetered); }

QString delivery(const Member &m, bool unmetered, int ms)
{
    if (fullOk(m) && m.family)
        return QStringLiteral("full");
    if (fullOk(m) && direct(m, unmetered) && (m.newPath || ms <= kOldFullMaxMs + kSlackMs))
        return QStringLiteral("full");
    if (m.newPath && m.v3 && m.reach)
        return ms <= kSmallMaxMs + kSlackMs ? QStringLiteral("small") : QStringLiteral("none");
    return ms <= kRelayMaxMs + kSlackMs ? QStringLiteral("relay") : QStringLiteral("none");
}

QHash<QString, QString> plan(const QList<Member> &members, bool unmetered, int ms)
{
    QHash<QString, QString> out;
    for (const Member &m : members)
        out.insert(m.k, delivery(m, unmetered, ms));
    return out;
}

bool tooLong(int ms, const QHash<QString, QString> &plan)
{
    if (ms <= kSmallMaxMs + kSlackMs)
        return false;
    for (const QString &v : plan)
        if (v != QLatin1String("full"))
            return true;
    return false;
}

QString names(const QStringList &n)
{
    if (n.isEmpty())
        return QString();
    if (n.size() == 1)
        return n.at(0);
    if (n.size() == 2)
        return n.at(0) + QStringLiteral(" and ") + n.at(1);
    if (n.size() == 3)
        return n.at(0) + QStringLiteral(", ") + n.at(1) + QStringLiteral(" and ") + n.at(2);
    return QStringLiteral("%1, %2 and %3 more").arg(n.at(0), n.at(1)).arg(n.size() - 2);
}

QString summary(const QHash<QString, QString> &plan, const std::function<QString(const QString &)> &nameOf,
                const std::function<bool(const QString &)> &newPath)
{
    QStringList full, small, relay, none, older;
    QStringList keys = plan.keys();
    keys.sort();
    for (const QString &k : keys) {
        const QString how = plan.value(k);
        const bool old = how == QLatin1String("none") && newPath && !newPath(k);
        (how == QLatin1String("full") ? full : how == QLatin1String("small") ? small : how == QLatin1String("relay") ? relay : old ? older : none) << nameOf(k);
    }
    QStringList out;
    if (!full.isEmpty())
        out << QStringLiteral("Full quality to %1.").arg(names(full));
    if (!small.isEmpty())
        out << QStringLiteral("A 720p copy to %1.").arg(names(small));
    if (!relay.isEmpty())
        out << QStringLiteral("A smaller copy (720p, up to 20 seconds) to %1, whose app is older.").arg(names(relay));
    if (!older.isEmpty())
        out << QStringLiteral("Not to %1: their app is older. Ask them to update.").arg(names(older));
    if (!none.isEmpty())
        out << QStringLiteral("Not to %1: no way to reach them with this one now. Try on the same Wi-Fi.").arg(names(none));
    return out.isEmpty() ? QStringLiteral("Nobody else is here yet.") : out.join(QLatin1Char(' '));
}

qint64 Pacer::take(qint64 now)
{
    const qint64 at = qMax(now, qMax(m_next, m_paused));
    m_next = at + gapMs();
    return at;
}

void Pacer::limited(qint64 now) { m_paused = qMax(m_paused, now + kBackoffMs); }

qint64 paceMs(int pieces, int perSecond) { return qint64(pieces) * 1000 / qMax(perSecond, 1); }

bool isRateLimit(const QString &message) { return message.trimmed().startsWith(QLatin1String("rate-limited"), Qt::CaseInsensitive); }

void noteRateLimited(qint64 now) { g_limitedAt.store(now); }
qint64 rateLimitedAt() { return g_limitedAt.load(); }

bool vidOk(const QJsonValue &v)
{
    if (v.isUndefined())
        return true;
    if (!v.isDouble())
        return false;
    const double d = v.toDouble();
    return d == std::floor(d) && d >= 1 && d <= kVidMaxMs;
}

// ------------------------------------------------------------------ location and camera details out (scrub)

namespace
{
struct Box {
    qsizetype at = 0, head = 0, end = 0;
    QByteArray type;
    qsizetype body() const { return at + head; }
};

constexpr qint64 kMaxSamples = 2000000;   // samples zeroed in one dropped track at most
using Runs = QList<QPair<qint64, qint64>>;   // (offset, length) in the file

// The boxes directly inside [start, end) of [d]; false when one is malformed.
bool boxes(const QByteArray &d, qsizetype start, qsizetype end, QList<Box> *out)
{
    qsizetype at = start;
    while (at < end) {
        Box x;
        qsizetype b = 0, n = 0;
        if (!box(d, at, end, &x.type, &b, &n))
            return false;
        x.at = at;
        x.head = b - at;
        x.end = n;
        out->append(x);
        at = n;
    }
    return true;
}

const Box *first(const QList<Box> &list, const char *type)
{
    for (const Box &x : list)
        if (x.type == type)
            return &x;
    return nullptr;
}

bool wiped(const QByteArray &type) { return type == "udta" || type == "meta" || type == "uuid" || type == "free" || type == "skip" || type == "wide"; }

void wipe(QByteArray &d, const Box &x)
{
    std::memcpy(d.data() + x.at + 4, "free", 4);
    std::memset(d.data() + x.body(), 0, size_t(x.end - x.body()));
}

// mvhd, tkhd and mdhd: creation and change times zeroed (version 0: 4 bytes each, version 1: 8).
void zeroTimes(QByteArray &d, const Box &x)
{
    if (x.body() >= x.end)
        return;
    const qsizetype n = uchar(d.at(x.body())) == 1 ? 16 : 8;
    if (x.body() + 4 + n <= x.end)
        std::memset(d.data() + x.body() + 4, 0, size_t(n));
}

// A dropped track's samples in the file, from its stbl. False when its tables don't add up.
bool samples(const QByteArray &d, const Box &stbl, qint64 fileSize, Runs *out)
{
    QList<Box> kids;
    if (!boxes(d, stbl.body(), stbl.end, &kids))
        return false;
    const Box *stsz = first(kids, "stsz");
    if (!stsz)
        return !first(kids, "stz2");   // no samples at all is fine; the compact table isn't read
    const Box *stsc = first(kids, "stsc");
    const Box *co = first(kids, "stco");
    const bool wide = !co;
    if (!co)
        co = first(kids, "co64");
    if (!stsc || !co || stsz->body() + 12 > stsz->end || stsc->body() + 8 > stsc->end || co->body() + 8 > co->end)
        return false;
    const qint64 fixed = be32(d, stsz->body() + 4), count = be32(d, stsz->body() + 8);
    if (count > kMaxSamples || (fixed == 0 && stsz->body() + 12 + count * 4 > stsz->end))
        return false;
    const qint64 entries = be32(d, stsc->body() + 4);
    if (stsc->body() + 8 + entries * 12 > stsc->end)
        return false;
    const qint64 chunks = be32(d, co->body() + 4);
    if (co->body() + 8 + chunks * (wide ? 8 : 4) > co->end)
        return false;
    if (entries == 0)
        return count == 0;
    qint64 sample = 0;
    qint64 e = 0;
    for (qint64 c = 0; c < chunks && sample < count; ++c) {
        while (e + 1 < entries && qint64(be32(d, stsc->body() + 8 + (e + 1) * 12)) <= c + 1)
            ++e;
        const qint64 per = qMin<qint64>(be32(d, stsc->body() + 8 + e * 12 + 4), count - sample);
        const qint64 off = wide ? qint64(be64(d, co->body() + 8 + c * 8)) : qint64(be32(d, co->body() + 8 + c * 4));
        qint64 len = 0;
        if (fixed != 0)
            len = per * fixed;
        else
            for (qint64 s = 0; s < per; ++s)
                len += be32(d, stsz->body() + 12 + (sample + s) * 4);
        sample += per;
        if (off < 0 || len < 0 || off > fileSize || len > fileSize - off)
            return false;
        if (len > 0)
            out->append({off, len});
    }
    return true;
}

// One track: kept and cleaned (picture or sound), or dropped with its samples added to [zero]. 1: a picture track,
// 0: any other, -1: malformed.
int track(QByteArray &d, const Box &trak, qint64 fileSize, Runs *zero)
{
    QList<Box> kids, inMdia;
    if (!boxes(d, trak.body(), trak.end, &kids))
        return -1;
    const Box *mdia = first(kids, "mdia");
    if (!mdia || !boxes(d, mdia->body(), mdia->end, &inMdia))
        return -1;
    const Box *hdlr = first(inMdia, "hdlr");
    if (!hdlr || hdlr->body() + 12 > hdlr->end)
        return -1;
    const QByteArray handler = d.mid(hdlr->body() + 8, 4);
    if (handler != "vide" && handler != "soun") {
        QList<Box> inMinf;
        const Box *minf = first(inMdia, "minf");
        if (minf && boxes(d, minf->body(), minf->end, &inMinf))
            if (const Box *stbl = first(inMinf, "stbl"); stbl && !samples(d, *stbl, fileSize, zero))
                return -1;
        wipe(d, trak);
        return 0;
    }
    for (const Box &k : kids) {
        if (k.type == "tkhd")
            zeroTimes(d, k);
        else if (wiped(k.type))
            wipe(d, k);
    }
    for (const Box &k : inMdia) {
        if (k.type == "mdhd")
            zeroTimes(d, k);
        else if (wiped(k.type))
            wipe(d, k);
    }
    return handler == "vide" ? 1 : 0;
}

bool zeros(QIODevice *dev, qint64 at, qint64 len)
{
    static const QByteArray chunk(64 * 1024, '\0');
    if (!dev->seek(at))
        return false;
    while (len > 0) {
        const qint64 n = qMin<qint64>(chunk.size(), len);
        if (dev->write(chunk.constData(), n) != n)
            return false;
        len -= n;
    }
    return true;
}
}

qint64 scrub(QIODevice *dev, bool write)
{
    if (!dev || !dev->isOpen() || !dev->isReadable() || (write && !dev->isWritable()) || dev->isSequential())
        return -1;
    const qint64 size = dev->size();
    qint64 at = 0, moovAt = -1, moovLen = 0;
    bool mdat = false;
    Runs zero;
    // The top boxes: ftyp, moov and mdat stay; fragments are refused; anything else is zeroed as "free".
    while (at + 8 <= size) {
        if (!dev->seek(at))
            return -1;
        const QByteArray h = dev->read(qMin<qint64>(16, size - at));
        if (h.size() < 8)
            return -1;
        quint64 len = be32(h, 0);
        qint64 head = 8;
        if (len == 1) {
            if (h.size() < 16)
                break;
            len = be64(h, 8);
            head = 16;
        } else if (len == 0) {
            len = quint64(size - at);
        }
        if (len < quint64(head) || len > quint64(size - at))
            break;   // not a box: a maker's trailer, cut off below
        const QByteArray type = h.mid(4, 4);
        if (type == "moov") {
            if (moovAt >= 0 || len > quint64(kMaxMoov))
                return -1;
            moovAt = at;
            moovLen = qint64(len);
        } else if (type == "mdat") {
            mdat = true;
        } else if (type == "moof" || type == "mfra" || type == "sidx" || type == "styp") {
            return -1;
        } else if (type != "ftyp") {
            if (write && (!dev->seek(at + 4) || dev->write("free", 4) != 4))
                return -1;
            zero.append({at + head, qint64(len) - head});
        }
        at += qint64(len);
    }
    const qint64 keep = at;
    if (moovAt < 0 || !mdat || !dev->seek(moovAt))
        return -1;
    QByteArray m = dev->read(moovLen);
    QList<Box> top, kids;
    if (m.size() != moovLen || !boxes(m, 0, m.size(), &top) || top.size() != 1 || !boxes(m, top.at(0).body(), top.at(0).end, &kids)
        || first(kids, "mvex"))
        return -1;
    bool video = false;
    for (const Box &k : kids) {
        if (k.type == "mvhd") {
            zeroTimes(m, k);
        } else if (k.type == "trak") {
            const int t = track(m, k, size, &zero);
            if (t < 0)
                return -1;
            video = video || t == 1;
        } else if (wiped(k.type)) {
            wipe(m, k);
        }
    }
    if (!video)
        return -1;
    // A dropped track's samples must lie before the cut and outside "moov" (written back below).
    for (const auto &r : zero)
        if (r.first + r.second > keep || (r.first < moovAt + moovLen && r.first + r.second > moovAt))
            return -1;
    if (!write)
        return keep;
    if (!dev->seek(moovAt) || dev->write(m) != moovLen)
        return -1;
    for (const auto &r : zero)
        if (!zeros(dev, r.first, r.second))
            return -1;
    return keep;
}

QByteArray scrubbed(const QByteArray &mp4)
{
    QByteArray c = mp4;
    QBuffer buf(&c);
    if (!buf.open(QIODevice::ReadWrite))
        return {};
    const qint64 keep = scrub(&buf);
    buf.close();
    if (keep <= 0)
        return {};
    c.truncate(qsizetype(keep));
    return c;
}

bool scrubFile(const QString &path)
{
    QFile f(path);
    if (!f.open(QIODevice::ReadWrite))
        return false;
    const qint64 keep = scrub(&f);
    if (keep <= 0)
        return false;
    const bool ok = keep >= f.size() || f.resize(keep);
    return f.flush() && ok;
}

namespace
{
QByteArray mkbox(const char *type, const QByteArray &body)
{
    QByteArray b(4, '\0');
    qToBigEndian<quint32>(quint32(8 + body.size()), reinterpret_cast<uchar *>(b.data()));
    return b + QByteArray(type, 4) + body;
}
QByteArray u32(quint32 v)
{
    QByteArray b(4, '\0');
    qToBigEndian<quint32>(v, reinterpret_cast<uchar *>(b.data()));
    return b;
}
// A small MP4 with one video track: w x h, [samples] frames over [ms] (time scale 1000).
QByteArray sample(int w, int h, quint32 samples, quint32 ms)
{
    const QByteArray mvhd = mkbox("mvhd", u32(0) + u32(0) + u32(0) + u32(1000) + u32(ms) + QByteArray(80, '\0'));
    const QByteArray tkhd = mkbox("tkhd", u32(0) + QByteArray(72, '\0') + u32(quint32(w) << 16) + u32(quint32(h) << 16));
    const QByteArray mdhd = mkbox("mdhd", u32(0) + u32(0) + u32(0) + u32(1000) + u32(ms) + u32(0));
    const QByteArray hdlr = mkbox("hdlr", u32(0) + u32(0) + QByteArray("vide") + QByteArray(12, '\0') + QByteArray(1, '\0'));
    const QByteArray stsz = mkbox("stsz", u32(0) + u32(0) + u32(samples));
    const QByteArray minf = mkbox("minf", mkbox("stbl", stsz));
    const QByteArray mdia = mkbox("mdia", mdhd + hdlr + minf);
    const QByteArray sound = mkbox("trak", mkbox("mdia", mkbox("hdlr", u32(0) + u32(0) + QByteArray("soun") + QByteArray(13, '\0'))));
    return mkbox("ftyp", QByteArray("isom") + u32(0)) + mkbox("moov", mvhd + sound + mkbox("trak", tkhd + mdia));
}
// A phone's MP4: an [entry] ("avc1", "hvc1") video track of AVC [profile] whose one sample is in mdat, a GPS metadata
// track, Android's location (udta ©xyz), an iPhone's location key (meta), creation times, XMP (uuid) and a trailer.
QByteArray phone(const char *entry, int profile)
{
    const QByteArray ftyp = mkbox("ftyp", QByteArray("isom") + u32(0));
    const QByteArray frames("VIDEOFRAMES-0123456789"), gps("GPS+37.7749-122.4194");
    const QByteArray mdat = mkbox("mdat", frames + gps);
    const quint32 framesAt = quint32(ftyp.size() + 8), gpsAt = framesAt + quint32(frames.size());
    auto stbl = [](const QByteArray &stsd, quint32 at, quint32 size) {
        return mkbox("stbl", stsd + mkbox("stsz", u32(0) + u32(0) + u32(1) + u32(size)) + mkbox("stsc", u32(0) + u32(1) + u32(1) + u32(1) + u32(1))
                                 + mkbox("stco", u32(0) + u32(1) + u32(at)));
    };
    const QByteArray mvhd = mkbox("mvhd", u32(0) + u32(0x11111111) + u32(0x22222222) + u32(1000) + u32(10000) + QByteArray(80, '\0'));
    const QByteArray tkhd = mkbox("tkhd", u32(0) + u32(0x33333333) + u32(0x44444444) + QByteArray(64, '\0') + u32(1280u << 16) + u32(720u << 16));
    const QByteArray mdhd = mkbox("mdhd", u32(0) + u32(0x55555555) + u32(0x66666666) + u32(1000) + u32(10000) + u32(0));
    QByteArray avcC(4, '\0');
    avcC[0] = 1;
    avcC[1] = char(profile);
    const QByteArray stsd = mkbox("stsd", u32(0) + u32(1) + mkbox(entry, QByteArray(78, '\0') + mkbox("avcC", avcC)));
    const QByteArray vtrak = mkbox("trak", tkhd + mkbox("mdia", mdhd + mkbox("hdlr", u32(0) + u32(0) + QByteArray("vide") + QByteArray(13, '\0'))
                                                         + mkbox("minf", stbl(stsd, framesAt, quint32(frames.size()))))
                                       + mkbox("udta", mkbox("\xa9xyz", "+37.7749-122.4194/")));
    const QByteArray mtrak = mkbox("trak", tkhd + mkbox("mdia", mdhd + mkbox("hdlr", u32(0) + u32(0) + QByteArray("meta") + QByteArray(13, '\0'))
                                                         + mkbox("minf", stbl(QByteArray(), gpsAt, quint32(gps.size())))));
    const QByteArray moov = mkbox("moov", mvhd + vtrak + mtrak + mkbox("udta", mkbox("\xa9xyz", "+37.7749-122.4194/") + mkbox("\xa9mak", "PhoneMaker"))
                                              + mkbox("meta", u32(0) + mkbox("keys", "com.apple.quicktime.location.ISO6709")));
    return ftyp + mdat + moov + mkbox("uuid", QByteArray("XMPXMPXMPXMPXMPX<x:xmpmeta>Model</x:xmpmeta>")) + QByteArray("SEFHjunkSEFT");
}
}

bool selfTest()
{
    int pass = 0, failed = 0;
    auto check = [&](bool ok, const char *what) {
        if (ok)
            ++pass;
        else {
            ++failed;
            std::printf("FAIL v3: %s\n", what);
        }
    };
    auto info = [](int w, int h, double fps = 30, int ms = 10000, qint64 bytes = 5000000) {
        Info i;
        i.w = w; i.h = h; i.fps = fps; i.ms = ms; i.bytes = bytes;
        return i;
    };
    // Sizes.
    check(fitsFull(info(1920, 1080)) && fitsFull(info(1080, 1920)), "1080p either way up goes full size");
    check(!fitsFull(info(3840, 2160)) && needsConvert(info(3840, 2160)), "4K would need converting");
    check(!fitsFull(info(1920, 1080, 60)) && fitsFull(info(1920, 1080, 30.3)), "60 fps would need converting, 29.97 or 30.3 is fine");
    check(!fitsFull(info(1920, 1080, 30, 10000, kFullMaxBytes + 1)) && fitsFull(info(1920, 1080, 30, 30 * 60000, 900000000)),
          "direct: no length cap, only the 4 GB transfer limit");
    check(kFullMaxBytes == 4LL * 1024 * 1024 * 1024, "4 GB, as FileLink");
    check(fitsSmall(info(1280, 720, 30, 5 * 60000)) && fitsSmall(info(720, 1280, 30, 5 * 60000 + 400)) && !fitsSmall(info(1280, 720, 30, 5 * 60000 + 1000))
              && !fitsSmall(info(1920, 1080)) && !fitsSmall(info(1280, 720, 60)),
          "the 720p copy: 720p30 up to 5 minutes as it is");
    check(biggerThan720(info(1920, 1080)) && biggerThan720(info(1080, 1920)) && !biggerThan720(info(1280, 720)) && !biggerThan720(info(720, 1280)), "bigger than 720p");
    check(fitsRelay(info(1280, 720, 30, 10000, 2000000)) && !fitsRelay(info(1920, 1080, 30, 10000, 2000000)), "720p fits the relay copy, 1080p doesn't");
    check(!fitsRelay(info(1280, 720, 30, 25000, 2000000)) && !fitsRelay(info(1280, 720, 30, 10000, kRelayBytes + 1)), "the relay copy's length and size");
    check(kPiece == 16 * 1024 && kRelayPieces <= 200, "the relay copy fits every app since m2 (200 pieces)");
    // Bitrates, the same numbers as Muninn's.
    const int b20 = relayBitrate(20000);
    check(qint64(b20 + kRelayAudioBitrate) * 20 / 8 <= kRelayBytes && b20 >= 900000, "20 s at about a megabit fits");
    check(relayBitrate(10000) > b20 && relayBitrate(2000) == kMaxRelayVideoBitrate && relayBitrate(600000) == kMinVideoBitrate, "bitrate bounds");
    check(relayBitrate(20000, 130) < b20 && relayBitrate(20000, kRelayPieces, 256000) < b20, "fewer pieces or more sound, less picture");
    check(retryBitrate(b20, kRelayBytes + 400000, 20000) <= int(b20 * kRetryShare), "a copy that ran over goes lower");
    check(fullBitrate(20000) == kFullVideoBitrate && fullBitrate(3600000) == kFullVideoBitrate
              && qint64(fullBitrate(60000) + kFullAudioBitrate) * 60 / 8 < 48LL * 1024 * 1024,
          "full copy bitrate (a minute still arrives by itself on an older app)");
    // Lengths.
    check(length(20400) == QLatin1String("short") && length(61000) == QLatin1String("long") && length(5 * 60000) == QLatin1String("long")
              && length(5 * 60000 + 1000) == QLatin1String("too long"), "lengths");
    check(clock(65000) == QLatin1String("1:05") && clock(19600) == QLatin1String("0:20") && clock(3725000) == QLatin1String("1:02:05"), "clock");
    check(QLatin1String(kTooLongText) == QLatin1String("Videos over the internet can be up to 5 minutes."), "the too long wording");
    // Who gets what (the same cases as GroupVideoTest.deliveryChoice).
    auto mem = [](const char *k, bool wifi, bool trusted, bool newPath, bool kid = false, bool v3 = true) {
        Member m;
        m.k = QString::fromLatin1(k);
        m.sameWifi = wifi; m.trusted = trusted; m.files = true; m.v3 = v3; m.kid = kid; m.newPath = newPath;
        return m;
    };
    const int min = 60000;
    const Member wifi = mem("a", true, false, true), trusted = mem("b", false, true, true), far = mem("c", false, false, true);
    const Member kid = mem("e", true, true, true, true), oldNear = mem("d", true, true, false), oldFar = mem("h", false, false, false);
    const Member noV3 = mem("g", true, true, false, false, false);
    check(delivery(wifi, false, 10000) == QLatin1String("full") && delivery(wifi, false, 45 * min) == QLatin1String("full")
              && delivery(trusted, true, 45 * min) == QLatin1String("full"),
          "direct: full size, any length");
    check(delivery(trusted, false, 10000) == QLatin1String("small") && delivery(far, true, 5 * min) == QLatin1String("small")
              && delivery(far, true, 6 * min) == QLatin1String("none"),
          "not direct: the 720p copy up to 5 minutes");
    check(delivery(kid, true, 10000) == QLatin1String("small"), "a kids' device gets the 720p path");
    check(delivery(oldNear, true, min) == QLatin1String("full") && delivery(oldNear, true, 2 * min) == QLatin1String("none")
              && delivery(oldFar, true, 20000) == QLatin1String("relay") && delivery(oldFar, true, 30000) == QLatin1String("none")
              && delivery(noV3, true, 10000) == QLatin1String("relay"),
          "older apps: a minute direct, 20 seconds by relay");
    const QHash<QString, QString> p = plan({wifi, trusted, far, oldFar}, false, 15000);
    check(p.value(QStringLiteral("a")) == QLatin1String("full") && p.value(QStringLiteral("b")) == QLatin1String("small")
              && p.value(QStringLiteral("c")) == QLatin1String("small") && p.value(QStringLiteral("h")) == QLatin1String("relay") && p.size() == 4,
          "plan: each member their best path");
    const QString said = summary(p, [](const QString &k) { return k.toUpper(); });
    check(said.contains(QLatin1String("Full quality to A")) && said.contains(QLatin1String("720p copy to B and C."))
              && said.contains(QLatin1String("to H, whose app is older")),
          "summary");
    check(tooLong(6 * min, plan({wifi, far}, true, 6 * min)) && !tooLong(6 * min, plan({wifi, trusted}, true, 6 * min))
              && !tooLong(5 * min, plan({far}, true, 5 * min)),
          "too long only when someone isn't direct");
    {
        // Not to someone: an older app is asked to update; a new app with no way now is told to try on the same Wi-Fi.
        const Member stuck = [&] { Member m = mem("f", false, false, true); m.reach = false; return m; }();
        const QHash<QString, QString> q = plan({oldFar, stuck}, true, 30000);
        const QString why = summary(q, [](const QString &k) { return k.toUpper(); },
                                    [&](const QString &k) { return k == oldFar.k ? oldFar.newPath : stuck.newPath; });
        check(q.value(QStringLiteral("h")) == QLatin1String("none") && q.value(QStringLiteral("f")) == QLatin1String("none")
                  && why.contains(QLatin1String("Not to H: their app is older. Ask them to update."))
                  && why.contains(QLatin1String("Not to F: no way to reach them with this one now. Try on the same Wi-Fi.")),
              "not to: older apps asked to update, new apps told there's no way now");
    }
    check(names({QStringLiteral("A"), QStringLiteral("B"), QStringLiteral("C"), QStringLiteral("D"), QStringLiteral("E")}) == QLatin1String("A, B and 3 more"), "names");
    // Pacing.
    Pacer pacer(3);
    const qint64 t0 = 1000000;
    qint64 last = pacer.take(t0);
    bool even = last == t0;
    for (int i = 0; i < 8; ++i) {
        const qint64 at = pacer.take(t0);
        even = even && at - last == pacer.gapMs();
        last = at;
    }
    check(even && last - t0 >= 2600, "three a second, evenly");
    pacer.limited(t0 + 5000);
    check(pacer.pausedAt(t0 + 5001) && pacer.take(t0 + 5001) >= t0 + 5000 + kBackoffMs && !pacer.pausedAt(t0 + 5000 + kBackoffMs), "a minute's pause after rate-limited");
    check(paceMs(kRelayPieces * 6) < 15 * 60000, "six members inside the 15 minutes half a video is kept");
    check(isRateLimit(QStringLiteral("rate-limited: slow down")) && !isRateLimit(QStringLiteral("blocked: no")), "rate-limited answers");
    // The offer field.
    check(vidOk(QJsonValue(QJsonValue::Undefined)) && vidOk(QJsonValue(15000)) && vidOk(QJsonValue(60000)) && vidOk(QJsonValue(45 * 60000))
              && vidOk(QJsonValue(kVidMaxMs)),
          "vid ok");
    check(!vidOk(QJsonValue(0)) && !vidOk(QJsonValue(double(kVidMaxMs) + 1)) && !vidOk(QJsonValue(1.5)) && !vidOk(QJsonValue(QStringLiteral("15000"))), "vid refused");
    // An MP4's own boxes: the video track (after a sound track), its size, frames a second and length.
    const Info probed = probe(sample(1920, 1080, 300, 10000));
    check(probed.w == 1920 && probed.h == 1080 && probed.ms == 10000 && std::abs(probed.fps - 30.0) < 0.01, "an MP4's size, rate and length");
    check(probe(QByteArray("not a video")).w == 0 && probe(sample(1280, 720, 600, 10000)).fps > 59, "a broken file, a 60 fps one");
    // Round 19: only plain H.264 (and AAC) goes as it is.
    const Info avc = probe(phone("avc1", 100));
    check(avc.plain && avc.video == "avc1" && avc.sound.isEmpty() && avc.w == 1280 && avc.h == 720, "an 8-bit H.264 video is plain");
    check(!probe(phone("hvc1", 100)).plain && !probe(phone("avc1", 110)).plain && !probe(sample(1280, 720, 300, 10000)).plain,
          "HEVC, 10-bit H.264 or no sample entry isn't");
    check(plainCodecs("avc1", 77, false, "mp4a") && !plainCodecs("avc1", 100, true, QByteArray()) && !plainCodecs("avc3", 100, false, "Opus"),
          "AAC or no sound, and no HDR");
    Info hevc = info(1280, 720, 30, 10000, 2000000);
    hevc.plain = false;
    check(!fitsRelay(hevc) && !fitsFull(hevc) && needsConvert(hevc), "a video that isn't plain goes only as a file");
    // Round 19: location and camera details out, the picture untouched, the trailer cut off.
    const QByteArray raw = phone("avc1", 100), clean = scrubbed(raw);
    check(clean.size() == raw.size() - 12 && raw.left(clean.size()).indexOf("VIDEOFRAMES") == clean.indexOf("VIDEOFRAMES"), "the same size, the picture where it was");
    bool gone = !clean.isEmpty();
    for (const char *s : {"37.7749", "PhoneMaker", "ISO6709", "xmpmeta", "\xa9xyz", "udta", "uuid", "GPS", "SEF", "\x11\x11\x11\x11", "UUUU", "DDDD"})
        gone = gone && !clean.contains(s);
    check(gone, "location, camera, times, XMP and the GPS track's samples are gone");
    check(probe(clean).w == 1280 && probe(clean).plain, "the video track is still there");
    check(scrubbed(QByteArray("not a video")).isEmpty() && scrubbed(raw.left(60)).isEmpty(), "what can't be cleaned is refused");
    {
        QByteArray copy = raw;
        QBuffer readOnly(&copy);
        readOnly.open(QIODevice::ReadOnly);
        check(scrub(&readOnly, false) == raw.size() - 12 && copy == raw, "the check alone changes nothing");
    }
    for (qsizetype n = 0; n <= raw.size(); ++n)
        scrubbed(raw.left(n));   // every cut: no crash
    for (qsizetype i = 0; i < raw.size(); ++i) {
        QByteArray bad = raw;
        bad[i] = char(bad.at(i) + 0x5a);
        scrubbed(bad);   // every damaged byte: no crash
        probe(bad);
    }
    // An empty tkhd as the file's last bytes: no read past it (security 21).
    const QByteArray videoHdlr = mkbox("hdlr", u32(0) + u32(0) + QByteArray("vide") + QByteArray(13, '\0'));
    check(probe(mkbox("ftyp", QByteArray("isom") + u32(0)) + mkbox("moov", mkbox("trak", mkbox("mdia", videoHdlr) + mkbox("tkhd", QByteArray())))).w == 0,
          "an empty tkhd at the end");
    check(QString::fromLatin1(ChatWire::kCaps).split(QLatin1Char(' ')).contains(QLatin1String(kCap)), "caps say v3");
    std::printf("v3 self-test: %d passed, %d failed\n", pass, failed);
    return failed == 0;
}
}
