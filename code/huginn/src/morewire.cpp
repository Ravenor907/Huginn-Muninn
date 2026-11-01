#include "morewire.h"
#include "groupwire.h"
#include "photos.h"
#include "richwire.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QHash>
#include <QJsonArray>
#include <QJsonDocument>
#include <QRegularExpression>
#include <QSet>
#include <QUrl>
#include <algorithm>
#include <cmath>
#include <cstdio>

const char MoreWire::kCap[] = "m2";

namespace
{
bool intIn(const QJsonValue &v, int lo, int hi)
{
    if (!v.isDouble())
        return false;
    const double d = v.toDouble();
    return std::floor(d) == d && d >= lo && d <= hi;
}

// lo..hi code points, not only spaces (when lo > 0), no control characters but a newline.
bool clean(const QJsonValue &v, int lo, int hi)
{
    if (!v.isString())
        return false;
    const QString s = v.toString();
    const int n = GroupWire::length(s);
    if (n < lo || n > hi || (lo > 0 && s.trimmed().isEmpty()))
        return false;
    for (const QChar c : s)
        if ((c.unicode() < 0x20 && c != QLatin1Char('\n')) || c.unicode() == 0x7F)
            return false;
    return true;
}

bool oneLine(const QJsonValue &v, int lo, int hi) { return clean(v, lo, hi) && !v.toString().contains(QLatin1Char('\n')); }

bool pollOk(const QJsonValue &pv)
{
    if (!pv.isObject())
        return false;
    const QJsonObject poll = pv.toObject();
    if (!oneLine(poll.value(QStringLiteral("q")), 1, MoreWire::kQuestionMax))
        return false;
    const QJsonValue av = poll.value(QStringLiteral("a"));
    if (!av.isArray())
        return false;
    const QJsonArray a = av.toArray();
    if (a.size() < MoreWire::kOptionsMin || a.size() > MoreWire::kOptionsMax)
        return false;
    QSet<QString> seen;
    for (const QJsonValue &x : a) {
        if (!oneLine(x, 1, MoreWire::kOptionMax))
            return false;
        const QString k = x.toString().trimmed().toLower();
        if (seen.contains(k))
            return false;
        seen.insert(k);
    }
    return !poll.contains(QStringLiteral("m")) || poll.value(QStringLiteral("m")).isBool();
}

bool previewOk(const QJsonValue &lv)
{
    if (!lv.isObject())
        return false;
    const QJsonObject lp = lv.toObject();
    const QJsonValue uv = lp.value(QStringLiteral("u"));
    if (!uv.isString())
        return false;
    const QString u = uv.toString();
    if (u.size() < 8 || u.size() > MoreWire::kUrlMax || !(u.startsWith(QLatin1String("https://")) || u.startsWith(QLatin1String("http://"))))
        return false;
    for (const QChar c : u)
        if (c.unicode() <= 0x20 || c.unicode() == 0x7F)
            return false;
    if (!oneLine(lp.value(QStringLiteral("t")), 1, MoreWire::kTitleMax))
        return false;
    if (lp.contains(QStringLiteral("d")) && !clean(lp.value(QStringLiteral("d")), 0, MoreWire::kDescMax))
        return false;
    if (lp.contains(QStringLiteral("i"))) {
        const QJsonValue iv = lp.value(QStringLiteral("i"));
        if (!iv.isString())
            return false;
        const QByteArray i = iv.toString().toLatin1();
        if (i.size() < 4 || i.size() > MoreWire::kThumbMax)
            return false;
        const auto r = QByteArray::fromBase64Encoding(i, QByteArray::AbortOnBase64DecodingErrors);
        if (!r || r.decoded.size() < 3 || uchar(r.decoded[0]) != 0xFF || uchar(r.decoded[1]) != 0xD8 || uchar(r.decoded[2]) != 0xFF)
            return false;
    }
    return true;
}

bool votesOk(const QJsonObject &p)
{
    const QJsonValue vv = p.value(QStringLiteral("v"));
    if (!vv.isArray())
        return false;
    const QJsonArray v = vv.toArray();
    if (v.size() > MoreWire::kOptionsMax)
        return false;
    int last = -1;
    for (const QJsonValue &x : v) {
        if (!intIn(x, 0, MoreWire::kOptionsMax - 1))
            return false;
        const int n = x.toInt();
        if (n <= last)
            return false;
        last = n;
    }
    return true;
}

bool logical(const QJsonValue &v) { return v.isString() && GroupWire::isLogicalId(v.toString()); }

quint32 be32(const QByteArray &b, qsizetype at)
{
    return (quint32(uchar(b[at])) << 24) | (quint32(uchar(b[at + 1])) << 16) | (quint32(uchar(b[at + 2])) << 8) | quint32(uchar(b[at + 3]));
}

// The boxes directly inside [from, to): type -> (payload start, payload end), first of each.
QHash<QByteArray, QPair<qsizetype, qsizetype>> boxes(const QByteArray &b, qsizetype from, qsizetype to)
{
    QHash<QByteArray, QPair<qsizetype, qsizetype>> out;
    qsizetype i = from;
    while (i + 8 <= to) {
        quint64 size = be32(b, i);
        qsizetype head = 8;
        if (size == 1) {
            if (i + 16 > to)
                break;
            size = (quint64(be32(b, i + 8)) << 32) | be32(b, i + 12);
            head = 16;
        } else if (size == 0) {
            size = quint64(to - i);
        }
        if (size < quint64(head) || quint64(i) + size > quint64(to))
            break;
        const QByteArray type = b.mid(i + 4, 4);
        if (!out.contains(type))
            out.insert(type, qMakePair(i + head, qsizetype(i + qsizetype(size))));
        i += qsizetype(size);
    }
    return out;
}
}

bool MoreWire::ok(const QString &room, const QJsonObject &p)
{
    const QString t = p.value(QStringLiteral("t")).toString();
    if (room == QLatin1String("rich") || room == QLatin1String("gmsg")) {
        // The text itself follows m1 (11.1); m2 adds only the fields below.
        if (room == QLatin1String("rich") && !RichWire::textOk(p.value(QStringLiteral("text"))))
            return false;
        if (p.contains(QStringLiteral("poll")) && !pollOk(p.value(QStringLiteral("poll"))))
            return false;
        return !p.contains(QStringLiteral("lp")) || previewOk(p.value(QStringLiteral("lp")));
    }
    if (room == QLatin1String("mx"))
        return t == QLatin1String("vote") && RichWire::isMsgId(p.value(QStringLiteral("msg"))) && RichWire::isTs(p.value(QStringLiteral("ts"))) && votesOk(p);
    if (room == QLatin1String("group")) {
        if (t != QLatin1String("vote"))
            return false;
        const QJsonValue g = p.value(QStringLiteral("g"));
        if (!g.isString() || g.toString().isEmpty() || g.toString().size() > 40)
            return false;
        return logical(p.value(QStringLiteral("id"))) && logical(p.value(QStringLiteral("msg"))) && RichWire::isTs(p.value(QStringLiteral("ts"))) && votesOk(p);
    }
    if (room == QLatin1String("piece")) {
        const bool album = p.contains(QStringLiteral("al")) || p.contains(QStringLiteral("ai")) || p.contains(QStringLiteral("an"));
        if (album) {
            if (!RichWire::isMsgId(p.value(QStringLiteral("al"))) || !intIn(p.value(QStringLiteral("an")), 1, kAlbumMax)
                || !intIn(p.value(QStringLiteral("ai")), 0, kAlbumMax - 1) || p.value(QStringLiteral("ai")).toInt() >= p.value(QStringLiteral("an")).toInt())
                return false;
        }
        const QString kind = p.contains(QStringLiteral("kind")) ? p.value(QStringLiteral("kind")).toString() : QStringLiteral("photo");
        if (kind == QLatin1String("video"))
            return intIn(p.value(QStringLiteral("ms")), 1, kVideoMaxMs) && !p.contains(QStringLiteral("al"));
        if (kind == QLatin1String("file")) {
            // Cap f1 (section 14): a small file over the relays, with its name.
            const QJsonValue fv = p.value(QStringLiteral("fn"));
            if (!fv.isString())
                return false;
            const QString fn = fv.toString();
            if (fn.trimmed().isEmpty() || fn.size() > 200)
                return false;
            for (const QChar c : fn)
                if (c.unicode() < 0x20 || c == QLatin1Char('/') || c == QLatin1Char('\\'))
                    return false;
            return !p.contains(QStringLiteral("al"));
        }
        if (kind == QLatin1String("gif"))   // cap a1 (section 25): an animated GIF, never in an album
            return !p.contains(QStringLiteral("al"));
        return kind == QLatin1String("photo") || kind == QLatin1String("voice");
    }
    return false;
}

bool MoreWire::isVideo(const QByteArray &data) { return data.size() >= 12 && data.mid(4, 4) == "ftyp"; }

int MoreWire::mp4Ms(const QByteArray &data)
{
    const auto top = boxes(data, 0, data.size());
    if (!top.contains("moov"))
        return 0;
    const auto moov = top.value("moov");
    const auto in = boxes(data, moov.first, moov.second);
    if (!in.contains("mvhd"))
        return 0;
    const qsizetype at = in.value("mvhd").first, end = in.value("mvhd").second;
    if (at + 32 > end)
        return 0;
    const int version = uchar(data[at]);
    quint64 scale = 0, dur = 0;
    if (version == 1) {
        scale = be32(data, at + 20);
        dur = (quint64(be32(data, at + 24)) << 32) | be32(data, at + 28);
    } else {
        scale = be32(data, at + 12);
        dur = be32(data, at + 16);
    }
    if (scale == 0)
        return 0;
    const quint64 ms = dur * 1000 / scale;
    return ms > quint64(24 * 3600 * 1000) ? 0 : int(ms);
}

QString MoreWire::fallback(const QString &q, const QStringList &options)
{
    QStringList lines{QStringLiteral("Poll: ") + q};
    for (int i = 0; i < options.size(); ++i)
        lines << QStringLiteral("%1. %2").arg(i + 1).arg(options.at(i));
    return lines.join(QLatin1Char('\n'));
}

QJsonObject MoreWire::pollJson(const QString &q, const QStringList &options, bool multi)
{
    QJsonObject o{{QStringLiteral("q"), q}, {QStringLiteral("a"), QJsonArray::fromStringList(options)}};
    if (multi)
        o.insert(QStringLiteral("m"), true);
    return o;
}

QList<int> MoreWire::toInts(const QVariant &v)
{
    QList<int> out;
    for (const QVariant &x : v.toList())
        out << x.toInt();
    return out;
}

bool MoreWire::vote(QVariantMap &all, int n, const QString &from, const QList<int> &v, qint64 ts)
{
    for (int x : v)
        if (x < 0 || x >= n)
            return false;
    const QVariantMap cur = all.value(from).toMap();
    if (!cur.isEmpty() && ts <= cur.value(QStringLiteral("t")).toLongLong())
        return false;
    QVariantList l;
    for (int x : v)
        l << x;
    all.insert(from, QVariantMap{{QStringLiteral("v"), l}, {QStringLiteral("t"), ts}});
    return true;
}

QList<int> MoreWire::counts(const QVariantMap &all, int n)
{
    QList<int> c(qMax(0, n), 0);
    for (const QVariant &e : all)
        for (int x : toInts(e.toMap().value(QStringLiteral("v"))))
            if (x >= 0 && x < n)
                c[x]++;
    return c;
}

QList<int> MoreWire::choices(const QVariantMap &all, const QString &who) { return toInts(all.value(who).toMap().value(QStringLiteral("v"))); }

QByteArray MoreWire::voteMsg(const QString &msg, const QList<int> &v, qint64 ts)
{
    QList<int> s = v;
    std::sort(s.begin(), s.end());
    s.erase(std::unique(s.begin(), s.end()), s.end());
    QJsonArray a;
    for (int x : s)
        a << x;
    return QJsonDocument(QJsonObject{{QStringLiteral("t"), QStringLiteral("vote")}, {QStringLiteral("msg"), msg}, {QStringLiteral("v"), a},
                                     {QStringLiteral("ts"), QJsonValue(qint64(ts))}}).toJson(QJsonDocument::Compact);
}

void MoreWire::addPieceFields(QJsonObject &o, const QVariantMap &m)
{
    const int video = m.value(QStringLiteral("video")).toInt();
    if (video > 0) {
        o.insert(QStringLiteral("kind"), QStringLiteral("video"));
        o.insert(QStringLiteral("ms"), video);
    }
    const QString al = m.value(QStringLiteral("al")).toString();
    if (!al.isEmpty()) {
        o.insert(QStringLiteral("al"), al);
        o.insert(QStringLiteral("ai"), m.value(QStringLiteral("ai")).toInt());
        o.insert(QStringLiteral("an"), m.value(QStringLiteral("an")).toInt());
    }
}

QVariantMap MoreWire::pieceFields(const QJsonObject &o)
{
    QVariantMap m;
    if (o.value(QStringLiteral("kind")).toString() == QLatin1String("video"))
        m.insert(QStringLiteral("video"), qBound(1, o.value(QStringLiteral("ms")).toInt(), kVideoMaxMs));
    const QString al = o.value(QStringLiteral("al")).toString();
    if (!al.isEmpty()) {
        m.insert(QStringLiteral("al"), al);
        m.insert(QStringLiteral("ai"), o.value(QStringLiteral("ai")).toInt());
        m.insert(QStringLiteral("an"), o.value(QStringLiteral("an")).toInt());
    }
    return m;
}

QByteArray MoreWire::joinVideo(const QList<QByteArray> &parts)
{
    QByteArray b;
    for (const QByteArray &p : parts)
        b += p;
    return b.size() <= qsizetype(Photos::kMaxPieces) * Photos::kPiece && isVideo(b) ? b : QByteArray();
}

void MoreWire::keep(QVariantMap &m, const QString &room, const QJsonObject &p)
{
    if (!ok(room, p))
        return;
    if (p.contains(QStringLiteral("poll")))
        m.insert(QStringLiteral("poll"), p.value(QStringLiteral("poll")).toObject().toVariantMap());
    if (p.contains(QStringLiteral("lp")))
        m.insert(QStringLiteral("lp"), p.value(QStringLiteral("lp")).toObject().toVariantMap());
}

QByteArray MoreWire::richText(const QString &text, const QString &re, const QVariantMap &poll, const QVariantMap &lp)
{
    QJsonObject o{{QStringLiteral("text"), text}};
    if (!re.isEmpty())
        o.insert(QStringLiteral("re"), re);
    if (!poll.isEmpty())
        o.insert(QStringLiteral("poll"), QJsonObject::fromVariantMap(poll));
    if (!lp.isEmpty())
        o.insert(QStringLiteral("lp"), QJsonObject::fromVariantMap(lp));
    return QJsonDocument(o).toJson(QJsonDocument::Compact);
}

QString MoreWire::firstLink(const QString &text)
{
    static const QRegularExpression re(QStringLiteral("https?://[^\\s<>\"]+"));
    QString u = re.match(text).captured(0);
    while (!u.isEmpty() && QStringLiteral(".,)!?;:").contains(u.back()))
        u.chop(1);
    return u.size() >= 8 && u.size() <= kUrlMax ? u : QString();
}

QString MoreWire::host(const QString &url)
{
    QString h = QUrl(url).host();
    if (h.startsWith(QLatin1String("www.")))
        h = h.mid(4);
    return h.isEmpty() ? url : h;
}

bool MoreWire::selfTest(const QString &vectorsPath)
{
    int pass = 0, fail = 0;
    auto check = [&](bool good, const QString &what) {
        good ? ++pass : ++fail;
        if (!good)
            std::printf("m2 self-test: FAILED %s\n", qPrintable(what));
    };
    QFile f(vectorsPath);
    const QJsonObject v = f.open(QIODevice::ReadOnly) ? QJsonDocument::fromJson(f.readAll()).object() : QJsonObject();
    if (v.isEmpty()) {
        std::printf("m2 self-test: can't read %s\n", qPrintable(vectorsPath));
        return false;
    }
    auto text = [](const QJsonObject &o) { return QString::fromUtf8(QJsonDocument(o).toJson(QJsonDocument::Compact)).left(160); };
    const QJsonObject good = v.value(QStringLiteral("good")).toObject();
    for (const QString &room : good.keys())
        for (const QJsonValue &c : good.value(room).toArray())
            check(ok(room, c.toObject()), QStringLiteral("%1 good %2").arg(room, text(c.toObject())));
    const QJsonObject refuse = v.value(QStringLiteral("refuse")).toObject();
    for (const QString &room : refuse.keys())
        for (const QJsonValue &c : refuse.value(room).toArray())
            check(!ok(room, c.toObject()), QStringLiteral("%1 refuses %2").arg(room, text(c.toObject())));
    const QJsonArray votes = v.value(QStringLiteral("votes")).toArray();
    for (int i = 0; i < int(votes.size()); ++i) {
        const QJsonObject c = votes.at(i).toObject();
        const int n = c.value(QStringLiteral("n")).toInt();
        QVariantMap all;
        for (const QJsonValue &e : c.value(QStringLiteral("events")).toArray()) {
            const QJsonObject x = e.toObject();
            vote(all, n, x.value(QStringLiteral("from")).toString(), toInts(x.value(QStringLiteral("v")).toVariant()), qint64(x.value(QStringLiteral("ts")).toDouble()));
        }
        check(counts(all, n) == toInts(c.value(QStringLiteral("result")).toVariant()), QStringLiteral("votes %1").arg(i));
    }
    for (const QJsonValue &e : v.value(QStringLiteral("fallback")).toArray()) {
        const QJsonObject c = e.toObject();
        QStringList a;
        for (const QJsonValue &x : c.value(QStringLiteral("a")).toArray())
            a << x.toString();
        check(fallback(c.value(QStringLiteral("q")).toString(), a) == c.value(QStringLiteral("text")).toString(), QStringLiteral("fallback"));
    }
    for (const QJsonValue &e : v.value(QStringLiteral("video")).toArray()) {
        const QJsonObject c = e.toObject();
        check(isVideo(QByteArray::fromHex(c.value(QStringLiteral("hex")).toString().toLatin1())) == c.value(QStringLiteral("ok")).toBool(),
              QStringLiteral("video %1").arg(c.value(QStringLiteral("hex")).toString()));
    }
    // Not in the vectors: links in text, and an MP4's length.
    check(firstLink(QStringLiteral("Look https://example.com/a?b=c.")) == QLatin1String("https://example.com/a?b=c") && firstLink(QStringLiteral("no link")).isEmpty()
              && host(QStringLiteral("https://www.example.com/x")) == QLatin1String("example.com"),
          "links");
    QByteArray mp4 = QByteArray::fromHex("00000018667479706d703432000000006d70343269736f6d");
    QByteArray mvhd = QByteArray::fromHex("0000006c6d76686400000000") + QByteArray(8, 0) + QByteArray::fromHex("000003e800002710") + QByteArray(80, 0);
    QByteArray moov = QByteArray::fromHex("00000074") + "moov" + mvhd;
    check(mp4Ms(mp4 + moov) == 10000 && mp4Ms(mp4) == 0, "mp4 length");
    check(pass > 60, "the vectors have cases");
    std::printf("m2 self-test: %d passed, %d failed\n", pass, fail);
    // Cap a1: animated GIFs, from the vectors next to these.
    const bool gifs = Photos::gifSelfTest(QFileInfo(vectorsPath).absoluteDir().filePath(QStringLiteral("gif-vectors.json")));
    return fail == 0 && gifs;
}
