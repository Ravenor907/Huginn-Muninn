#include "modwire.h"
#include "groupwire.h"

#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QSet>
#include <QStringList>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <functional>

const char ModWire::kCap[] = "g2";
const char ModWire::kDomain[] = "huginn-muninn gmod v1";

namespace
{
const qint64 kMaxSafe = 9007199254740992LL;   // 2^53

bool isWhole(const QJsonValue &v)
{
    if (!v.isDouble())
        return false;
    const double d = v.toDouble();
    return std::isfinite(d) && d == std::trunc(d) && std::fabs(d) < double(kMaxSafe);
}

bool isAction(const QString &a)
{
    return a == QLatin1String("ban") || a == QLatin1String("unban") || a == QLatin1String("timeout") || a == QLatin1String("untimeout")
        || a == QLatin1String("remove");
}

QString s(const QJsonObject &o, const char *k) { return o.value(QLatin1String(k)).toString(); }
}

QString ModWire::check(const QJsonObject &r)
{
    const QString a = s(r, "a");
    QSet<QString> want{QStringLiteral("a"), QStringLiteral("g"), QStringLiteral("k"), QStringLiteral("by"), QStringLiteral("ts"), QStringLiteral("sig")};
    if (a == QLatin1String("timeout"))
        want.insert(QStringLiteral("until"));
    if (a == QLatin1String("remove"))
        want.insert(QStringLiteral("m"));
    const QStringList keys = r.keys();
    if (QSet<QString>(keys.cbegin(), keys.cend()) != want)
        return QStringLiteral("fields");
    if (!r.value(QStringLiteral("a")).isString() || !isAction(a))
        return QStringLiteral("action");
    if (!r.value(QStringLiteral("g")).isString() || !GroupWire::isGroupId(s(r, "g")))
        return QStringLiteral("group id");
    for (const char *f : {"k", "by"}) {
        const QJsonValue v = r.value(QLatin1String(f));
        if (!v.isString() || v.toString().isEmpty() || v.toString().size() > 200)
            return QString::fromLatin1(f);
    }
    if (s(r, "k") == s(r, "by"))
        return QStringLiteral("self");
    const QJsonValue ts = r.value(QStringLiteral("ts"));
    if (!isWhole(ts) || ts.toInteger() <= 0)
        return QStringLiteral("ts");
    if (a == QLatin1String("timeout")) {
        const QJsonValue u = r.value(QStringLiteral("until"));
        if (!isWhole(u) || u.toInteger() <= ts.toInteger() || u.toInteger() > ts.toInteger() + kLongest)
            return QStringLiteral("until");
    }
    if (a == QLatin1String("remove") && !GroupWire::isLogicalId(s(r, "m")))
        return QStringLiteral("m");
    return QString();
}

QString ModWire::accept(const QJsonObject *state, const QJsonObject &r)
{
    const QString why = check(r);
    if (!why.isEmpty())
        return QStringLiteral("malformed: ") + why;
    const QString by = s(r, "by"), k = s(r, "k");
    if (!GroupWire::verify(by, QLatin1String(kDomain), r))
        return QStringLiteral("bad signature");
    if (!state || state->isEmpty() || s(*state, "g") != s(r, "g"))
        return QStringLiteral("unknown group");
    if (!GroupWire::isAdmin(*state, by))
        return QStringLiteral("signer is not an admin");
    const QString owner = s(*state, "owner");
    if (k == owner)
        return QStringLiteral("the owner");
    if (GroupWire::isAdmin(*state, k) && by != owner)
        return QStringLiteral("an admin, and the signer isn't the owner");
    return QStringLiteral("ok");
}

QString ModWire::slot(const QJsonObject &r)
{
    const QString a = s(r, "a");
    return a == QLatin1String("ban") || a == QLatin1String("unban") ? QStringLiteral("ban") : QStringLiteral("timeout");
}

bool ModWire::newer(const QJsonObject &cur, const QJsonObject &next)
{
    return cur.isEmpty() || next.value(QStringLiteral("ts")).toInteger() > cur.value(QStringLiteral("ts")).toInteger();
}

bool ModWire::banned(const QJsonObject &rec) { return s(rec, "a") == QLatin1String("ban"); }

bool ModWire::timedOut(const QJsonObject &rec, qint64 nowMs)
{
    return s(rec, "a") == QLatin1String("timeout") && nowMs < rec.value(QStringLiteral("until")).toInteger();
}

QJsonObject ModWire::make(const QString &action, const QString &g, const QString &who, const QString &by, qint64 ts, qint64 until)
{
    QJsonObject r{{QStringLiteral("a"), action}, {QStringLiteral("g"), g}, {QStringLiteral("k"), who}, {QStringLiteral("by"), by},
                  {QStringLiteral("ts"), ts}};
    if (action == QLatin1String("timeout"))
        r.insert(QStringLiteral("until"), until);
    return r;
}

QJsonObject ModWire::makeRemove(const QString &g, const QString &author, const QString &by, qint64 ts, const QString &msg)
{
    QJsonObject r = make(QStringLiteral("remove"), g, author, by, ts);
    r.insert(QStringLiteral("m"), msg);
    return r;
}

bool ModWire::isRemove(const QJsonObject &r) { return s(r, "a") == QLatin1String("remove"); }

int ModWire::clampVolume(int pct) { return std::clamp(pct, kVolumeMin, kVolumeMax); }

int ModWire::clampVolume(const QJsonValue &v)
{
    if (!isWhole(v))
        return kVolumeDefault;
    const qint64 p = v.toInteger();
    return int(std::clamp<qint64>(p, kVolumeMin, kVolumeMax));
}

qint16 ModWire::gain(qint16 sample, int pct)
{
    const int p = clampVolume(pct);
    const int v = int(sample) * p / 100;   // C++ integer division truncates toward zero
    return qint16(std::clamp(v, -32768, 32767));
}

void ModWire::applyGain(qint16 *pcm, int n, int pct)
{
    const int p = clampVolume(pct);
    if (p == 100 || !pcm)
        return;
    if (p == 0) {
        std::fill(pcm, pcm + n, qint16(0));
        return;
    }
    for (int i = 0; i < n; ++i)
        pcm[i] = gain(pcm[i], p);
}

// ------------------------------------------------------------------ self-test

namespace
{
QJsonObject readVectors(const QString &path)
{
    QFile f(path);
    return f.open(QIODevice::ReadOnly) ? QJsonDocument::fromJson(f.readAll()).object() : QJsonObject();
}

void volumeChecks(const QJsonObject &v, const std::function<void(bool, const QString &)> &check)
{
    for (const QJsonValue &x : v.value(QStringLiteral("volume")).toArray()) {
        const QJsonObject c = x.toObject();
        const int got = ModWire::clampVolume(c.value(QStringLiteral("in")));
        check(got == c.value(QStringLiteral("out")).toInt(),
              QStringLiteral("volume %1 -> %2").arg(QString::fromUtf8(QJsonDocument(QJsonArray{c.value(QStringLiteral("in"))}).toJson(QJsonDocument::Compact))).arg(got));
    }
    for (const QJsonValue &x : v.value(QStringLiteral("gain")).toArray()) {
        const QJsonObject c = x.toObject();
        const int sample = c.value(QStringLiteral("sample")).toInt(), pct = c.value(QStringLiteral("pct")).toInt();
        const int got = ModWire::gain(qint16(sample), pct);
        check(got == c.value(QStringLiteral("out")).toInt(), QStringLiteral("gain %1 at %2 percent -> %3").arg(sample).arg(pct).arg(got));
    }
    // A whole frame: 0 % is silence, 100 % untouched, 200 % clipped, never past 16 bits.
    qint16 frame[4] = {1000, -1000, 20000, -20000};
    ModWire::applyGain(frame, 4, 100);
    check(frame[0] == 1000 && frame[3] == -20000, QStringLiteral("frame at 100%"));
    ModWire::applyGain(frame, 4, 200);
    check(frame[0] == 2000 && frame[1] == -2000 && frame[2] == 32767 && frame[3] == -32768, QStringLiteral("frame at 200%"));
    ModWire::applyGain(frame, 4, 0);
    check(frame[0] == 0 && frame[2] == 0 && frame[3] == 0, QStringLiteral("frame at 0%"));
    ModWire::applyGain(frame, 4, 900);   // clamped to 200 %, so silence stays silence and nothing overflows
    check(frame[1] == 0, QStringLiteral("frame clamped"));
}
}

bool ModWire::volumeSelfTest(const QString &vectorsPath)
{
    int pass = 0, fail = 0;
    const QJsonObject v = readVectors(vectorsPath);
    if (v.isEmpty()) {
        std::printf("g2 volume self-test: can't read %s\n", qPrintable(vectorsPath));
        return false;
    }
    volumeChecks(v, [&](bool good, const QString &what) {
        good ? ++pass : ++fail;
        if (!good)
            std::printf("g2 volume self-test: FAILED %s\n", qPrintable(what));
    });
    std::printf("g2 volume self-test: %d passed, %d failed\n", pass, fail);
    return fail == 0 && pass > 0;
}

bool ModWire::selfTest(const QString &vectorsPath)
{
    int pass = 0, fail = 0;
    auto check = [&](bool good, const QString &what) {
        good ? ++pass : ++fail;
        if (!good)
            std::printf("g2 self-test: FAILED %s\n", qPrintable(what));
    };
    const QJsonObject v = readVectors(vectorsPath);
    if (v.isEmpty()) {
        std::printf("g2 self-test: can't read %s\n", qPrintable(vectorsPath));
        return false;
    }
    check(v.value(QStringLiteral("domain")).toString() == QLatin1String(kDomain), QStringLiteral("domain"));
    const QJsonObject state = v.value(QStringLiteral("state")).toObject();
    check(GroupWire::checkState(state).isEmpty(), QStringLiteral("the vectors' state"));
    // Signed records: the same bytes, the signature, taken.
    const QJsonObject records = v.value(QStringLiteral("records")).toObject();
    for (const QString &k : records.keys()) {
        const QJsonObject x = records.value(k).toObject();
        const QJsonObject r = x.value(QStringLiteral("record")).toObject();
        check(GroupWire::signedBytes(QLatin1String(kDomain), r) == x.value(QStringLiteral("signed")).toString().toUtf8(), QStringLiteral("signed bytes ") + k);
        const QString got = accept(&state, r);
        check(got == QLatin1String("ok"), QStringLiteral("takes ") + k + QStringLiteral(": ") + got);
    }
    // Forged, tampered and malformed ones: refused for the same reason the reference gives.
    auto refused = [&](const QJsonObject &st, const QJsonObject &list) {
        for (const QString &k : list.keys()) {
            const QJsonObject x = list.value(k).toObject();
            const QString got = accept(&st, x.value(QStringLiteral("record")).toObject());
            check(got != QLatin1String("ok") && got == x.value(QStringLiteral("verdict")).toString(),
                  QStringLiteral("refuses ") + k + QStringLiteral(": ") + got);
        }
    };
    refused(state, v.value(QStringLiteral("refuse")).toObject());
    const QJsonObject admins = v.value(QStringLiteral("admins")).toObject();
    refused(admins.value(QStringLiteral("state")).toObject(), admins.value(QStringLiteral("refuse")).toObject());
    check(accept(nullptr, records.value(QStringLiteral("ban")).toObject().value(QStringLiteral("record")).toObject()) == QLatin1String("unknown group"),
          QStringLiteral("no state"));
    // My own signing: a ban made here verifies, and changing any field afterwards breaks it.
    {
        auto rec = [&](const char *k) { return records.value(QLatin1String(k)).toObject().value(QStringLiteral("record")).toObject(); };
        const QJsonObject ban = rec("ban");
        QJsonObject moved = ban;
        moved.insert(QStringLiteral("k"), s(ban, "by"));
        check(accept(&state, moved) != QLatin1String("ok"), QStringLiteral("a ban turned on its signer"));
        QJsonObject later = ban;
        later.insert(QStringLiteral("ts"), ban.value(QStringLiteral("ts")).toInteger() + 1);
        check(accept(&state, later) == QLatin1String("bad signature"), QStringLiteral("a ban with a new time"));
        QJsonObject other = ban;
        other.insert(QStringLiteral("a"), QStringLiteral("unban"));
        check(accept(&state, other) == QLatin1String("bad signature"), QStringLiteral("a ban turned into an unban"));
    }
    // Order: a higher ts replaces, the same or a lower one doesn't.
    for (const QJsonValue &x : v.value(QStringLiteral("order")).toArray()) {
        const QJsonObject c = x.toObject();
        const QString cur = c.value(QStringLiteral("cur")).toString();
        const QJsonObject curRec = cur.isEmpty() ? QJsonObject() : records.value(cur).toObject().value(QStringLiteral("record")).toObject();
        const QJsonObject next = records.value(c.value(QStringLiteral("new")).toString()).toObject().value(QStringLiteral("record")).toObject();
        check(newer(curRec, next) == c.value(QStringLiteral("newer")).toBool(), QStringLiteral("order ") + cur + QStringLiteral(" -> ") + s(c, "new"));
    }
    // Active: a ban until it's lifted; a timeout until the moment it ends, not a millisecond more.
    for (const QJsonValue &x : v.value(QStringLiteral("active")).toArray()) {
        const QJsonObject c = x.toObject();
        const QJsonObject r = records.value(s(c, "record")).toObject().value(QStringLiteral("record")).toObject();
        const qint64 at = c.value(QStringLiteral("now")).toInteger();
        check(banned(r) == c.value(QStringLiteral("banned")).toBool() && timedOut(r, at) == c.value(QStringLiteral("timedOut")).toBool(),
              QStringLiteral("active %1 at %2").arg(s(c, "record")).arg(at));
    }
    // The menus' timeouts all fit.
    for (qint64 d : kTimeouts)
        check(d > 0 && d <= kLongest, QStringLiteral("timeout %1").arg(d));
    const QJsonArray offered = v.value(QStringLiteral("limits")).toObject().value(QStringLiteral("timeouts")).toArray();
    check(offered.size() == 4 && offered.at(0).toInteger() == kTimeouts[0] && offered.at(3).toInteger() == kTimeouts[3], QStringLiteral("timeouts offered"));
    // A removed message: the record names it, and it is never a ban or a timeout.
    {
        const QJsonObject rm = records.value(QStringLiteral("remove")).toObject().value(QStringLiteral("record")).toObject();
        check(isRemove(rm) && !banned(rm) && !timedOut(rm, rm.value(QStringLiteral("ts")).toInteger()) && GroupWire::isLogicalId(s(rm, "m")),
              QStringLiteral("remove"));
        QJsonObject other = rm;
        other.insert(QStringLiteral("m"), QString(32, QLatin1Char('f')));
        check(accept(&state, other) == QLatin1String("bad signature"), QStringLiteral("a remove moved to another message"));
    }
    // Cap g3 (protocol 28): the group's about text and "only admins post" in the state, taken or refused for the same
    // reason as the reference.
    const QJsonObject about = v.value(QStringLiteral("g3")).toObject();
    check(about.size() >= 11, QStringLiteral("g3 vectors"));
    for (const QString &k : about.keys()) {
        const QJsonObject c = about.value(k).toObject();
        const QString got = GroupWire::checkState(c.value(QStringLiteral("state")).toObject());
        check(got == c.value(QStringLiteral("verdict")).toString(), QStringLiteral("g3 ") + k + QStringLiteral(": ") + got);
    }
    volumeChecks(v, check);
    std::printf("g2 self-test: %d passed, %d failed\n", pass, fail);
    return fail == 0 && pass > 0;
}
