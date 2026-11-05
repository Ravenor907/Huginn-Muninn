#include "photos.h"

#include <QBuffer>
#include <QFile>
#include <QImage>
#include <QImageReader>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QPainter>
#include <cstdio>

namespace Photos
{
namespace
{
QByteArray encode(QImage img, int limit)
{
    // Best quality that fits the size limit; shrinks further if even low quality doesn't fit.
    while (true) {
        for (int q : {85, 75, 65, 55, 45, 35}) {
            QByteArray out;
            QBuffer buf(&out);
            buf.open(QIODevice::WriteOnly);
            img.save(&buf, "JPEG", q);
            if (out.size() <= limit)
                return out;
        }
        img = img.scaled(qMax(1, int(img.width() * 0.75)), qMax(1, int(img.height() * 0.75)), Qt::KeepAspectRatio, Qt::SmoothTransformation);
    }
}
}

QByteArray prepare(const QString &path, bool hd)
{
    QImageReader r(path);
    r.setAutoTransform(true);   // phones and cameras store some photos sideways; turn them upright
    QImage img = r.read();
    if (img.isNull())
        return {};
    const int side = hd ? 2048 : 640;
    if (qMax(img.width(), img.height()) > side)
        img = img.scaled(side, side, Qt::KeepAspectRatio, Qt::SmoothTransformation);
    img = img.convertToFormat(QImage::Format_RGB32);   // no transparency in a JPEG
    return scrub(encode(img, hd ? 2 * 1024 * 1024 : 60 * 1024));
}

QByteArray prepareImage(const QImage &in, int side, int limit)
{
    if (in.isNull())
        return {};
    QImage img = in;
    if (qMax(img.width(), img.height()) > side)
        img = img.scaled(side, side, Qt::KeepAspectRatio, Qt::SmoothTransformation);
    img = img.convertToFormat(QImage::Format_RGB32);   // no transparency in a JPEG
    return scrub(encode(img, limit));
}

QByteArray scrub(const QByteArray &b)
{
    // Removes EXIF (location, camera, time), XMP, ICC, maker notes (APP1 to APP15) and comments,
    // without touching the picture. Mirrors Photos.scrub in Muninn.
    if (b.size() < 4 || uchar(b[0]) != 0xFF || uchar(b[1]) != 0xD8)
        return {};
    QByteArray out;
    out.reserve(b.size());
    out.append(char(0xFF)).append(char(0xD8));
    int i = 2;
    while (i + 4 <= b.size()) {
        if (uchar(b[i]) != 0xFF)
            return {};
        const int marker = uchar(b[i + 1]);
        if (marker == 0xFF) { ++i; continue; }
        if (marker == 0xDA) { out.append(b.mid(i)); return out; }   // the picture itself, to the end
        if (marker == 0xD9) { out.append(char(0xFF)).append(char(0xD9)); return out; }
        const int len = (uchar(b[i + 2]) << 8) | uchar(b[i + 3]);
        if (len < 2 || i + 2 + len > b.size())
            return {};
        const bool metadata = (marker >= 0xE1 && marker <= 0xEF) || marker == 0xFE;
        if (!metadata)
            out.append(b.mid(i, 2 + len));
        i += 2 + len;
    }
    return {};
}

bool isAdts(const QByteArray &b) { return b.size() >= 7 && b.size() <= 2 * 1024 * 1024 && uchar(b[0]) == 0xFF && (uchar(b[1]) & 0xF0) == 0xF0; }

QList<QByteArray> split(const QByteArray &data)
{
    QList<QByteArray> out;
    for (int i = 0; i < data.size(); i += kPiece)
        out << data.mid(i, kPiece);
    return out;
}

QByteArray join(const QList<QByteArray> &parts)
{
    QByteArray b;
    for (const QByteArray &p : parts)
        b.append(p);
    return b.size() >= 3 && b.size() <= kMaxPieces * kPiece ? scrub(b) : QByteArray();
}

QByteArray joinVoice(const QList<QByteArray> &parts)
{
    QByteArray b;
    for (const QByteArray &p : parts)
        b.append(p);
    return isAdts(b) ? b : QByteArray();
}

bool selfTest()
{
    auto seg = [](int m, const QByteArray &payload) {
        QByteArray s;
        s.append(char(0xFF)).append(char(m)).append(char((payload.size() + 2) >> 8)).append(char((payload.size() + 2) & 0xFF)).append(payload);
        return s;
    };
    QByteArray jpeg;
    jpeg.append(char(0xFF)).append(char(0xD8));
    jpeg += seg(0xE0, "JFIF") + seg(0xE1, "Exif GPS 37.7N") + seg(0xFE, "my comment") + seg(0xDB, QByteArray(65, 1));
    jpeg.append(char(0xFF)).append(char(0xDA)).append(char(0)).append(char(2)).append(QByteArray(50000, 0x11)).append(char(0xFF)).append(char(0xD9));
    const QByteArray clean = scrub(jpeg);
    const bool scrubbed = !clean.isEmpty() && !clean.contains("GPS") && !clean.contains("my comment") && clean.contains("JFIF");
    std::printf("photo metadata removed: %s\n", scrubbed ? "ok" : "FAILED");
    const bool round = join(split(clean)) == clean && split(clean).size() == 4;
    std::printf("photo pieces round trip: %s\n", round ? "ok" : "FAILED");
    const bool notJpeg = join({QByteArray("\x01\x02\x03\x04", 4)}).isEmpty();
    std::printf("non-JPEG rejected: %s\n", notJpeg ? "ok" : "FAILED");
    return scrubbed && round && notJpeg;
}

// ------------------------------------------------------------------ animated GIFs (cap a1, protocol section 25)

bool isGif(const QByteArray &b) { return b.startsWith("GIF87a") || b.startsWith("GIF89a"); }

bool isGifFile(const QString &path)
{
    QFile f(path);
    return f.open(QIODevice::ReadOnly) && isGif(f.read(6));
}

namespace
{
// Skips the sub-blocks from at: where the block after them starts (-1 when cut off), the first two and how many.
qsizetype gifBlocks(const unsigned char *b, qsizetype n, qsizetype at, QList<QByteArray> *firsts, int *count)
{
    while (true) {
        if (at >= n)
            return -1;
        const int len = b[at++];
        if (len == 0)
            return at;
        if (at + len > n)
            return -1;
        if (firsts && firsts->size() < 2)
            firsts->append(QByteArray(reinterpret_cast<const char *>(b + at), len));
        if (count)
            ++*count;
        at += len;
    }
}

// Mirrors Gifs.clean in Muninn and clean() in code/shared/tools/gif_ref.py. Empty with *bad set when refused.
QByteArray cleanGifCore(const QByteArray &in, const char **bad, int *framesOut)
{
    const auto *b = reinterpret_cast<const unsigned char *>(in.constData());
    const qsizetype n = in.size();
    auto refuse = [bad](const char *why) {
        *bad = why;
        return QByteArray();
    };
    if (n > kGifMaxBytes)
        return refuse("big");
    if (!isGif(in) || n < 13)
        return refuse("gif");
    auto u16 = [b](qsizetype i) { return int(b[i]) | (int(b[i + 1]) << 8); };
    const int w = u16(6), h = u16(8);
    if (w == 0 || h == 0)
        return refuse("gif");
    if (w > kGifMaxSide || h > kGifMaxSide)
        return refuse("big");
    qsizetype i = 13;
    if (b[10] & 0x80) {
        i += qsizetype(3) << ((b[10] & 7) + 1);
        if (i > n)
            return refuse("gif");
    }
    QByteArray head("GIF89a");
    head.append(in.constData() + 6, i - 6);
    QByteArray body;
    body.reserve(n);
    QByteArray gce;
    int loop = -1, frames = 0;
    qint64 area = 0;
    while (i < n) {
        const int t = b[i];
        if (t == 0x3B)
            break;
        if (t == 0x2C) {
            if (i + 10 > n)
                return refuse("gif");
            const int x = u16(i + 1), y = u16(i + 3), fw = u16(i + 5), fh = u16(i + 7);
            if (fw == 0 || fh == 0 || x + fw > w || y + fh > h)
                return refuse("gif");
            qsizetype j = i + 10;
            if (b[i + 9] & 0x80)
                j += qsizetype(3) << ((b[i + 9] & 7) + 1);
            if (j >= n || b[j] < 2 || b[j] > 8)
                return refuse("gif");
            const qsizetype end = gifBlocks(b, n, j + 1, nullptr, nullptr);
            if (end < 0)
                return refuse("gif");
            if (++frames > kGifMaxFrames)
                return refuse("frames");
            area += qint64(fw) * fh;
            if (area > kGifMaxArea)
                return refuse("big");
            body.append(gce);
            gce.clear();
            body.append(in.constData() + i, end - i);
            i = end;
        } else if (t == 0x21) {
            if (i + 2 > n)
                return refuse("gif");
            const int label = b[i + 1];
            QList<QByteArray> subs;
            int count = 0;
            const qsizetype end = gifBlocks(b, n, i + 2, &subs, &count);
            if (end < 0)
                return refuse("gif");
            if (label == 0xF9) {
                if (count == 1 && subs.at(0).size() == 4)
                    gce = in.mid(i, end - i);
            } else if (label == 0xFF) {
                if (loop < 0 && subs.size() >= 2 && (subs.at(0) == "NETSCAPE2.0" || subs.at(0) == "ANIMEXTS1.0") && subs.at(1).size() == 3
                    && subs.at(1).at(0) == 1)
                    loop = uchar(subs.at(1).at(1)) | (uchar(subs.at(1).at(2)) << 8);
            } else if (label == 0x01) {
                gce.clear();   // its timing block went with it
            }
            i = end;
        } else {
            return refuse("gif");
        }
    }
    if (frames == 0)
        return refuse("gif");
    QByteArray out = head;
    if (loop >= 0) {
        out.append("\x21\xFF\x0B" "NETSCAPE2.0" "\x03\x01", 16);
        out.append(char(loop & 0xFF)).append(char(loop >> 8)).append(char(0));
    }
    out.append(body);
    out.append(char(0x3B));
    if (framesOut)
        *framesOut = frames;
    return out;
}
}

QByteArray cleanGif(const QByteArray &b, QString *why, int *frames)
{
    if (frames)
        *frames = 0;
    const char *bad = nullptr;
    const QByteArray out = cleanGifCore(b, &bad, frames);
    if (why)
        *why = bad ? QString::fromLatin1(bad) : QString();
    return out;
}

QByteArray readGif(const QString &path, QString *why)
{
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly)) {
        if (why)
            *why = QStringLiteral("gif");
        return {};
    }
    const QByteArray b = f.read(qint64(kGifMaxBytes) + 1);   // anything longer is too big without reading the rest
    return cleanGif(b, why);
}

QByteArray joinGif(const QList<QByteArray> &parts)
{
    qsizetype total = 0;
    for (const QByteArray &p : parts)
        total += p.size();
    if (total > kGifMaxBytes)
        return {};
    QByteArray b;
    b.reserve(total);
    for (const QByteArray &p : parts)
        b.append(p);
    return cleanGif(b);
}

QByteArray stillOfGif(const QByteArray &gif)
{
    // Only a GIF that passed the limits is decoded; its first frame, on white (a JPEG has no transparency).
    const QByteArray clean = cleanGif(gif);
    if (clean.isEmpty())
        return {};
    QBuffer buf;
    buf.setData(clean);
    buf.open(QIODevice::ReadOnly);
    QImageReader r(&buf, "gif");
    const QSize size = r.size();
    if (size.isValid() && qMax(size.width(), size.height()) > 640)
        r.setScaledSize(size.scaled(640, 640, Qt::KeepAspectRatio));
    const QImage frame = r.read();
    if (frame.isNull())
        return {};
    QImage flat(frame.size(), QImage::Format_RGB32);
    flat.fill(Qt::white);
    QPainter(&flat).drawImage(0, 0, frame);
    return prepareImage(flat, 640, 60 * 1024);
}

QString gifWhy(const QString &why)
{
    if (why == QLatin1String("frames"))
        return QStringLiteral("That GIF has more than %1 frames").arg(kGifMaxFrames);
    if (why == QLatin1String("big"))
        return QStringLiteral("That GIF is over 8 MB or larger than %1 px").arg(kGifMaxSide);
    return QStringLiteral("That GIF can't be read");
}

bool gifSelfTest(const QString &vectorsPath)
{
    int pass = 0, fail = 0;
    auto check = [&](bool ok, const QString &what) {
        if (ok) {
            ++pass;
        } else {
            ++fail;
            std::printf("GIF self-test FAILED: %s\n", qPrintable(what));
        }
    };
    QFile f(vectorsPath);
    if (!f.open(QIODevice::ReadOnly)) {
        std::printf("GIF self-test: can't read %s\n", qPrintable(vectorsPath));
        return false;
    }
    const QJsonObject v = QJsonDocument::fromJson(f.readAll()).object();
    for (const QJsonValue &c : v.value(QStringLiteral("good")).toArray()) {
        const QJsonObject o = c.toObject();
        int frames = 0;
        const QByteArray out = cleanGif(QByteArray::fromBase64(o.value(QStringLiteral("in")).toString().toLatin1()), nullptr, &frames);
        check(!out.isEmpty() && out == QByteArray::fromBase64(o.value(QStringLiteral("out")).toString().toLatin1())
                  && frames == o.value(QStringLiteral("frames")).toInt() && cleanGif(out) == out,
              o.value(QStringLiteral("name")).toString());
    }
    for (const QJsonValue &c : v.value(QStringLiteral("refuse")).toArray()) {
        const QJsonObject o = c.toObject();
        QString why;
        const QByteArray out = cleanGif(QByteArray::fromBase64(o.value(QStringLiteral("in")).toString().toLatin1()), &why);
        check(out.isEmpty() && why == o.value(QStringLiteral("why")).toString(), QStringLiteral("refuse ") + o.value(QStringLiteral("name")).toString());
    }
    const QJsonObject l = v.value(QStringLiteral("limits")).toObject();
    check(l.value(QStringLiteral("bytes")).toInt() == kGifMaxBytes && l.value(QStringLiteral("frames")).toInt() == kGifMaxFrames
              && l.value(QStringLiteral("side")).toInt() == kGifMaxSide && qint64(l.value(QStringLiteral("area")).toDouble()) == kGifMaxArea,
          QStringLiteral("limits match the reference"));
    // Not in the vectors: a GIF over 8 MB, and the first frame as a photo.
    QByteArray big("GIF89a\x01\x00\x01\x00\x00\x00\x00", 13);
    big.append(QByteArray(kGifMaxBytes, 0));
    QString why;
    check(cleanGif(big, &why).isEmpty() && why == QLatin1String("big"), QStringLiteral("over 8 MB is refused"));
    const QJsonArray good = v.value(QStringLiteral("good")).toArray();
    if (!good.isEmpty()) {
        const QByteArray still = stillOfGif(QByteArray::fromBase64(good.at(0).toObject().value(QStringLiteral("out")).toString().toLatin1()));
        check(!still.isEmpty() && uchar(still.at(0)) == 0xFF && uchar(still.at(1)) == 0xD8, QStringLiteral("a GIF's first frame as a JPEG"));
    }
    check(pass > 20, QStringLiteral("the vectors have cases"));
    std::printf("GIF self-test: %d passed, %d failed\n", pass, fail);
    return fail == 0;
}
}
