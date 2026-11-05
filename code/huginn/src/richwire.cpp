#include "richwire.h"
#include "groupwire.h"

#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <algorithm>
#include <cmath>
#include <cstdio>

const char RichWire::kCap[] = "m1";

bool RichWire::isMsgId(const QJsonValue &v)
{
    if (!v.isString())
        return false;
    const QString s = v.toString();
    if (s.isEmpty() || s.size() > kMaxId)
        return false;
    for (const QChar c : s) {
        const ushort u = c.unicode();
        if (!((u >= 'A' && u <= 'Z') || (u >= 'a' && u <= 'z') || (u >= '0' && u <= '9') || u == '-' || u == '_'))
            return false;
    }
    return true;
}

bool RichWire::isTs(const QJsonValue &v)
{
    if (!v.isDouble())
        return false;
    const double d = v.toDouble();
    return d >= 0 && d < 9007199254740992.0 && std::floor(d) == d;
}

qint64 RichWire::ts(const QJsonObject &p) { return qint64(p.value(QStringLiteral("ts")).toDouble()); }

bool RichWire::textOk(const QJsonValue &v)
{
    if (!v.isString())
        return false;
    const QString t = v.toString();
    return GroupWire::length(t) <= kMaxText && !t.trimmed().isEmpty();
}

bool RichWire::emojiOk(const QJsonValue &v)
{
    if (!v.isString())
        return false;
    const QString e = v.toString();
    if (e.isEmpty())
        return true;   // takes my reaction away
    if (GroupWire::length(e) > kMaxEmoji || e.trimmed().isEmpty())
        return false;
    for (const QChar c : e)
        if (c.unicode() < 0x20 || c.unicode() == 0x7F)
            return false;
    return true;
}

bool RichWire::customOk(const QJsonValue &v)
{
    if (!v.isString())
        return false;
    const QString c = v.toString();
    if (GroupWire::length(c) > kMaxCustom)
        return false;
    for (const QChar ch : c)
        if (ch.unicode() < 0x20 || ch.unicode() == 0x7F)
            return false;
    return true;
}

QString RichWire::cut(const QString &s, int n)
{
    if (GroupWire::length(s) <= n)
        return s;
    qsizetype i = 0;
    for (int seen = 0; i < s.size() && seen < n; ++seen)
        i += s.at(i).isHighSurrogate() && i + 1 < s.size() ? 2 : 1;
    return s.left(i);
}

static bool logical(const QJsonValue &v) { return v.isString() && GroupWire::isLogicalId(v.toString()); }

bool RichWire::ok(const QString &room, const QJsonObject &p)
{
    const QString t = p.value(QStringLiteral("t")).toString();
    if (room == QLatin1String("rich"))
        return textOk(p.value(QStringLiteral("text"))) && (!p.contains(QStringLiteral("re")) || isMsgId(p.value(QStringLiteral("re"))));
    if (room == QLatin1String("mx")) {
        if (!isMsgId(p.value(QStringLiteral("msg"))) || !isTs(p.value(QStringLiteral("ts"))))
            return false;
        if (t == QLatin1String("react"))
            return emojiOk(p.value(QStringLiteral("e")));
        if (t == QLatin1String("edit"))
            return textOk(p.value(QStringLiteral("text")));
        return false;
    }
    if (room == QLatin1String("live")) {
        if (!(t == QLatin1String("typing") || t == QLatin1String("active")) || !p.value(QStringLiteral("on")).isBool() || !isTs(p.value(QStringLiteral("ts"))))
            return false;
        // Cap p1: a status and a custom status text, when given.
        if (p.contains(QStringLiteral("st")) && !kStatuses.contains(p.value(QStringLiteral("st")).toString(QStringLiteral("?"))))
            return false;
        return !p.contains(QStringLiteral("cs")) || customOk(p.value(QStringLiteral("cs")));
    }
    if (room == QLatin1String("gmsg"))
        return !p.contains(QStringLiteral("re")) || logical(p.value(QStringLiteral("re")));
    if (room == QLatin1String("group")) {
        if (t != QLatin1String("react") && t != QLatin1String("edit"))
            return false;
        const QJsonValue g = p.value(QStringLiteral("g"));
        if (!g.isString() || g.toString().isEmpty() || g.toString().size() > 40)
            return false;
        if (!logical(p.value(QStringLiteral("id"))) || !logical(p.value(QStringLiteral("msg"))) || !isTs(p.value(QStringLiteral("ts"))))
            return false;
        return t == QLatin1String("react") ? emojiOk(p.value(QStringLiteral("e"))) : textOk(p.value(QStringLiteral("text")));
    }
    return false;
}

bool RichWire::fresh(qint64 ts, qint64 now) { return ts >= now - kLiveWindow && ts <= now + kLiveWindow; }

bool RichWire::react(QVariantMap &all, const QString &from, const QString &e, qint64 ts)
{
    const QVariantMap cur = all.value(from).toMap();
    if (!cur.isEmpty() && ts <= cur.value(QStringLiteral("t")).toLongLong())
        return false;
    all.insert(from, QVariantMap{{QStringLiteral("e"), e}, {QStringLiteral("t"), ts}});
    return true;
}

QVariantMap RichWire::shown(const QVariantMap &all)
{
    QVariantMap out;
    for (auto it = all.begin(); it != all.end(); ++it) {
        const QString e = it.value().toMap().value(QStringLiteral("e")).toString();
        if (!e.isEmpty())
            out.insert(it.key(), e);
    }
    return out;
}

QVariantList RichWire::counts(const QVariantMap &all, const QString &me)
{
    QMap<QString, int> n;
    const QVariantMap s = shown(all);
    for (const QVariant &e : s)
        n[e.toString()]++;
    QList<QPair<QString, int>> l;
    for (auto it = n.begin(); it != n.end(); ++it)
        l << qMakePair(it.key(), it.value());
    std::stable_sort(l.begin(), l.end(), [](const QPair<QString, int> &a, const QPair<QString, int> &b) { return a.second > b.second; });
    const QString mine = s.value(me).toString();
    QVariantList out;
    for (const auto &p : l)
        out << QVariantMap{{QStringLiteral("e"), p.first}, {QStringLiteral("n"), p.second}, {QStringLiteral("mine"), p.first == mine}};
    return out;
}

bool RichWire::editWins(const QString &author, const QString &from, qint64 ts, qint64 shownTs) { return from == author && ts > shownTs; }

bool RichWire::mentions(const QString &text, const QString &me)
{
    const QString n = me.trimmed();
    if (n.isEmpty())
        return false;
    const QString at = QLatin1Char('@') + n;
    for (qsizetype i = text.indexOf(at, 0, Qt::CaseInsensitive); i >= 0; i = text.indexOf(at, i + 1, Qt::CaseInsensitive)) {
        const qsizetype end = i + at.size();
        const bool before = i > 0 && text.at(i - 1).isLetterOrNumber();
        const bool after = end < text.size() && text.at(end).isLetterOrNumber();
        if (!before && !after)
            return true;
    }
    return false;
}

static QByteArray compact(const QJsonObject &o) { return QJsonDocument(o).toJson(QJsonDocument::Compact); }

QByteArray RichWire::richText(const QString &text, const QString &re)
{
    QJsonObject o{{QStringLiteral("text"), text}};
    if (!re.isEmpty())
        o.insert(QStringLiteral("re"), re);
    return compact(o);
}

QByteArray RichWire::reactMsg(const QString &msg, const QString &e, qint64 ts)
{
    return compact({{QStringLiteral("t"), QStringLiteral("react")}, {QStringLiteral("msg"), msg}, {QStringLiteral("e"), e}, {QStringLiteral("ts"), QJsonValue(qint64(ts))}});
}

QByteArray RichWire::editMsg(const QString &msg, const QString &text, qint64 ts)
{
    return compact({{QStringLiteral("t"), QStringLiteral("edit")}, {QStringLiteral("msg"), msg}, {QStringLiteral("text"), text}, {QStringLiteral("ts"), QJsonValue(qint64(ts))}});
}

QByteArray RichWire::liveMsg(const QString &t, bool on, qint64 ts)
{
    return compact({{QStringLiteral("t"), t}, {QStringLiteral("on"), on}, {QStringLiteral("ts"), QJsonValue(qint64(ts))}});
}

QByteArray RichWire::activeMsg(bool on, qint64 ts, const QString &status, const QString &custom)
{
    if (!on || status == QLatin1String("invisible"))
        return liveMsg(QStringLiteral("active"), false, ts);
    QJsonObject o{{QStringLiteral("t"), QStringLiteral("active")}, {QStringLiteral("on"), true}, {QStringLiteral("ts"), QJsonValue(qint64(ts))},
                  {QStringLiteral("st"), kStatuses.contains(status) ? status : QStringLiteral("online")}};
    const QString c = cut(custom, kMaxCustom);
    if (!c.isEmpty())
        o.insert(QStringLiteral("cs"), c);
    return compact(o);
}

bool RichWire::selfTest(const QString &vectorsPath)
{
    int pass = 0, fail = 0;
    auto check = [&](bool good, const QString &what) {
        good ? ++pass : ++fail;
        if (!good)
            std::printf("m1 self-test: FAILED %s\n", qPrintable(what));
    };
    QFile f(vectorsPath);
    const QJsonObject v = f.open(QIODevice::ReadOnly) ? QJsonDocument::fromJson(f.readAll()).object() : QJsonObject();
    if (v.isEmpty()) {
        std::printf("m1 self-test: can't read %s\n", qPrintable(vectorsPath));
        return false;
    }
    const QJsonObject good = v.value(QStringLiteral("good")).toObject();
    for (const QString &room : good.keys()) {
        const QJsonArray a = good.value(room).toArray();
        for (int i = 0; i < int(a.size()); ++i)
            check(ok(room, a.at(i).toObject()), QStringLiteral("%1 good %2").arg(room).arg(i));
    }
    const QJsonObject refuse = v.value(QStringLiteral("refuse")).toObject();
    for (const QString &room : refuse.keys()) {
        const QJsonArray a = refuse.value(room).toArray();
        for (int i = 0; i < int(a.size()); ++i)
            check(!ok(room, a.at(i).toObject()), QStringLiteral("%1 refuses %2").arg(room, QString::fromUtf8(compact(a.at(i).toObject()))));
    }
    const QJsonArray rx = v.value(QStringLiteral("reactions")).toArray();
    for (int i = 0; i < int(rx.size()); ++i) {
        const QJsonObject c = rx.at(i).toObject();
        QVariantMap all;
        for (const QJsonValue &e : c.value(QStringLiteral("events")).toArray()) {
            const QJsonObject x = e.toObject();
            react(all, x.value(QStringLiteral("from")).toString(), x.value(QStringLiteral("e")).toString(), qint64(x.value(QStringLiteral("ts")).toDouble()));
        }
        check(shown(all) == c.value(QStringLiteral("result")).toObject().toVariantMap(), QStringLiteral("reactions %1").arg(i));
    }
    const QJsonArray ed = v.value(QStringLiteral("edits")).toArray();
    for (int i = 0; i < int(ed.size()); ++i) {
        const QJsonObject c = ed.at(i).toObject();
        const QString author = c.value(QStringLiteral("author")).toString();
        QString text = c.value(QStringLiteral("text")).toString();
        bool edited = false;
        qint64 at = 0;
        for (const QJsonValue &e : c.value(QStringLiteral("events")).toArray()) {
            const QJsonObject x = e.toObject();
            const qint64 t = qint64(x.value(QStringLiteral("ts")).toDouble());
            if (editWins(author, x.value(QStringLiteral("from")).toString(), t, at)) {
                text = x.value(QStringLiteral("text")).toString();
                edited = true;
                at = t;
            }
        }
        const QJsonObject r = c.value(QStringLiteral("result")).toObject();
        check(text == r.value(QStringLiteral("text")).toString() && edited == r.value(QStringLiteral("edited")).toBool()
                  && at == qint64(r.value(QStringLiteral("ts")).toDouble()), QStringLiteral("edits %1").arg(i));
    }
    for (const QJsonValue &e : v.value(QStringLiteral("fresh")).toArray()) {
        const QJsonObject x = e.toObject();
        check(fresh(qint64(x.value(QStringLiteral("ts")).toDouble()), qint64(x.value(QStringLiteral("now")).toDouble())) == x.value(QStringLiteral("ok")).toBool(),
              QStringLiteral("fresh %1").arg(qint64(x.value(QStringLiteral("ts")).toDouble())));
    }
    check(mentions(QStringLiteral("hey @Raven look"), QStringLiteral("Raven")) && mentions(QStringLiteral("@raven"), QStringLiteral("Raven"))
              && !mentions(QStringLiteral("@Ravenor here"), QStringLiteral("Raven")) && !mentions(QStringLiteral("mail@Raven.com"), QStringLiteral("Raven")),
          "@mentions");
    check(pass > 40, "the vectors have cases");
    std::printf("m1 self-test: %d passed, %d failed\n", pass, fail);
    return fail == 0;
}
