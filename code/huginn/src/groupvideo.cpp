#include "groupvideo.h"
#include "chatwire.h"

#include <QtEndian>
#include <atomic>
#include <cmath>
#include <cstdio>

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
    // The first video track: its size (tkhd), and frames a second from its sample count and length (mdhd, stsz).
    QByteArray type;
    qsizetype b = 0, n = 0, at = moov;
    while (box(mp4, at, moovEnd, &type, &b, &n)) {
        at = n;
        if (type != "trak")
            continue;
        qsizetype md = 0, mdEnd = 0, hd = 0, hdEnd = 0;
        if (!find(mp4, b, n, "mdia", &md, &mdEnd) || !find(mp4, md, mdEnd, "hdlr", &hd, &hdEnd) || hd + 12 > hdEnd || mp4.mid(hd + 8, 4) != "vide")
            continue;
        qsizetype tk = 0, tkEnd = 0;
        if (find(mp4, b, n, "tkhd", &tk, &tkEnd)) {
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
        break;
    }
    return info;
}

bool withinSize(int w, int h, int longSide, int shortSide) { return qMax(w, h) <= longSide && qMin(w, h) <= shortSide; }
bool withinFps(double fps, int most) { return fps <= most + 0.5; }

bool fitsFull(const Info &i)
{
    return i.w > 0 && i.h > 0 && withinSize(i.w, i.h, kFullLong, kFullShort) && withinFps(i.fps, kFullFps) && i.bytes >= 1 && i.bytes <= kFullMaxBytes
        && i.ms >= 1 && i.ms <= kFullMaxMs + kSlackMs;
}

bool needsConvert(const Info &i)
{
    return !(withinSize(i.w, i.h, kFullLong, kFullShort) && withinFps(i.fps, kFullFps) && i.bytes >= 1 && i.bytes <= kFullMaxBytes);
}

bool fitsRelay(const Info &i)
{
    return i.w > 0 && i.h > 0 && withinSize(i.w, i.h, kRelayLong, kRelayShort) && withinFps(i.fps, kFullFps) && relayFits(i.bytes) && i.ms >= 1
        && i.ms <= kRelayMaxMs + kSlackMs;
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
    const int most = int(double(kFullMaxBytes) * 8 * kRelayAim / s - kFullAudioBitrate);
    return qMax(kMinVideoBitrate, qMin(kFullVideoBitrate, most));
}

QString length(int ms)
{
    if (ms <= kRelayMaxMs + kSlackMs)
        return QStringLiteral("short");
    if (ms <= kFullMaxMs + kSlackMs)
        return QStringLiteral("long");
    return QStringLiteral("too long");
}

QString clock(int ms)
{
    const int s = (qMax(ms, 0) + 500) / 1000;
    return QStringLiteral("%1:%2").arg(s / 60).arg(s % 60, 2, 10, QLatin1Char('0'));
}

bool fullOk(const Member &m) { return m.files && m.v3 && !m.kid && !m.relayOnly; }

QString delivery(const Member &m, bool unmetered)
{
    return fullOk(m) && (m.sameWifi || (m.trusted && unmetered)) ? QStringLiteral("full") : QStringLiteral("relay");
}

QString later(const Member &m) { return fullOk(m) && (m.sameWifi || m.trusted) ? QStringLiteral("full") : QStringLiteral("none"); }

QHash<QString, QString> plan(const QList<Member> &members, bool unmetered, bool inFull)
{
    QHash<QString, QString> out;
    for (const Member &m : members)
        out.insert(m.k, inFull ? later(m) : delivery(m, unmetered));
    return out;
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

QString summary(const QHash<QString, QString> &plan, const std::function<QString(const QString &)> &nameOf)
{
    QStringList full, relay, none;
    QStringList keys = plan.keys();
    keys.sort();
    for (const QString &k : keys) {
        const QString how = plan.value(k);
        (how == QLatin1String("full") ? full : how == QLatin1String("relay") ? relay : none) << nameOf(k);
    }
    QStringList out;
    if (!full.isEmpty())
        out << QStringLiteral("Full size (1080p) straight to %1.").arg(names(full));
    if (!relay.isEmpty())
        out << QStringLiteral("A smaller copy (720p, up to 20 seconds) over the internet to %1.").arg(names(relay));
    if (!none.isEmpty())
        out << QStringLiteral("Not to %1: they need the same Wi-Fi or Direct calls and files with you, and the latest app.").arg(names(none));
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
    return d == std::floor(d) && d >= 1 && d <= kFullMaxMs;
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
    check(!fitsFull(info(1920, 1080, 30, 10000, kFullMaxBytes + 1)) && !fitsFull(info(1920, 1080, 30, 61000)), "too big or too long for full size");
    check(fitsRelay(info(1280, 720, 30, 10000, 2000000)) && !fitsRelay(info(1920, 1080, 30, 10000, 2000000)), "720p fits the relay copy, 1080p doesn't");
    check(!fitsRelay(info(1280, 720, 30, 25000, 2000000)) && !fitsRelay(info(1280, 720, 30, 10000, kRelayBytes + 1)), "the relay copy's length and size");
    check(kPiece == 16 * 1024 && kRelayPieces <= 200, "the relay copy fits every app since m2 (200 pieces)");
    // Bitrates, the same numbers as Muninn's.
    const int b20 = relayBitrate(20000);
    check(qint64(b20 + kRelayAudioBitrate) * 20 / 8 <= kRelayBytes && b20 >= 900000, "20 s at about a megabit fits");
    check(relayBitrate(10000) > b20 && relayBitrate(2000) == kMaxRelayVideoBitrate && relayBitrate(600000) == kMinVideoBitrate, "bitrate bounds");
    check(relayBitrate(20000, 130) < b20 && relayBitrate(20000, kRelayPieces, 256000) < b20, "fewer pieces or more sound, less picture");
    check(retryBitrate(b20, kRelayBytes + 400000, 20000) <= int(b20 * kRetryShare), "a copy that ran over goes lower");
    check(fullBitrate(20000) == kFullVideoBitrate && qint64(fullBitrate(60000) + kFullAudioBitrate) * 60 / 8 <= kFullMaxBytes, "full copy bitrate");
    // Lengths.
    check(length(20400) == QLatin1String("short") && length(21000) == QLatin1String("long") && length(60000) == QLatin1String("long")
              && length(61000) == QLatin1String("too long"), "lengths");
    check(clock(65000) == QLatin1String("1:05") && clock(19600) == QLatin1String("0:20"), "clock");
    // Who gets what.
    Member wifi{QStringLiteral("a"), true, false, true, true};
    Member trusted{QStringLiteral("b"), false, true, true, true};
    Member away{QStringLiteral("c"), false, false, true, true};
    Member old{QStringLiteral("d"), true, true, true, false};
    Member kid{QStringLiteral("e"), true, true, true, true, true};
    Member ultra{QStringLiteral("f"), true, true, true, true, false, true};
    check(delivery(wifi, false) == QLatin1String("full") && delivery(trusted, true) == QLatin1String("full"), "full copy close by or trusted on Wi-Fi");
    check(delivery(trusted, false) == QLatin1String("relay") && delivery(away, true) == QLatin1String("relay"), "relay copy over mobile data or far away");
    check(delivery(old, true) == QLatin1String("relay") && delivery(kid, true) == QLatin1String("relay") && delivery(ultra, true) == QLatin1String("relay"),
          "older apps, Kids' Devices and Ultra privacy get the relay copy");
    check(later(trusted) == QLatin1String("full") && later(away) == QLatin1String("none") && later(kid) == QLatin1String("none"), "in full later");
    const QHash<QString, QString> p = plan({wifi, trusted, away}, false, false);
    check(p.value(QStringLiteral("a")) == QLatin1String("full") && p.value(QStringLiteral("b")) == QLatin1String("relay") && p.size() == 3, "plan");
    const QString said = summary(p, [](const QString &k) { return k.toUpper(); });
    check(said.contains(QLatin1String("straight to A")) && said.contains(QLatin1String("internet to B and C")), "summary");
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
    check(vidOk(QJsonValue(QJsonValue::Undefined)) && vidOk(QJsonValue(15000)) && vidOk(QJsonValue(60000)), "vid ok");
    check(!vidOk(QJsonValue(0)) && !vidOk(QJsonValue(60001)) && !vidOk(QJsonValue(1.5)) && !vidOk(QJsonValue(QStringLiteral("15000"))), "vid refused");
    // An MP4's own boxes: the video track (after a sound track), its size, frames a second and length.
    const Info probed = probe(sample(1920, 1080, 300, 10000));
    check(probed.w == 1920 && probed.h == 1080 && probed.ms == 10000 && std::abs(probed.fps - 30.0) < 0.01, "an MP4's size, rate and length");
    check(probe(QByteArray("not a video")).w == 0 && probe(sample(1280, 720, 600, 10000)).fps > 59, "a broken file, a 60 fps one");
    check(QString::fromLatin1(ChatWire::kCaps).split(QLatin1Char(' ')).contains(QLatin1String(kCap)), "caps say v3");
    std::printf("v3 self-test: %d passed, %d failed\n", pass, failed);
    return failed == 0;
}
}
