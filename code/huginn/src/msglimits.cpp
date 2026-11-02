#include "msglimits.h"

#include "groupwire.h"

#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonValue>
#include <cmath>
#include <cstdio>

const char Limits::kCap[] = "d1";

int Limits::groupDays(const QJsonObject &state)
{
    if (!state.contains(QStringLiteral("kind")))
        return kDefault;
    const int d = state.value(QStringLiteral("days")).toInt(kDefault);
    return d >= kDefault && d <= kLongest ? d : kDefault;
}

int Limits::envelopeDays(const QJsonObject &o)
{
    const QJsonValue v = o.value(QStringLiteral("days"));
    if (!v.isDouble())
        return 0;
    const double d = v.toDouble();
    if (d != std::floor(d) || d < kDefault || d > kLongest)
        return 0;
    return int(d);
}

int Limits::myDays(int chosen, bool close)
{
    if (!close || chosen <= 0)
        return kDefault;
    return qBound(kDefault, chosen, kLongest);
}

int Limits::pairDays(int mine, bool hasD1, int theirs)
{
    if (!hasD1)
        return mine;
    return qMin(mine, theirs > 0 ? theirs : kDefault);
}

qint64 Limits::cutoff(qint64 now, int days) { return now - days * kDay; }

QJsonObject Limits::withLimit(QJsonObject state, const QString &kind, int days)
{
    state.remove(QStringLiteral("kind"));
    state.remove(QStringLiteral("days"));
    if (kind != QLatin1String("family") && kind != QLatin1String("work"))
        return state;   // Standard: neither field, the state any app without d1 takes
    state.insert(QStringLiteral("kind"), kind);
    if (days > kDefault)
        state.insert(QStringLiteral("days"), qMin(days, kLongest));
    return state;
}

QString Limits::kindName(const QString &kind) { return typeName(kind == QLatin1String(kFamily) ? QString::fromLatin1(kFamily) : QString()); }

const char Limits::kFolk[] = "folk";
const char Limits::kFamily[] = "family";

QString Limits::groupType(const QJsonObject &state)
{
    return state.value(QStringLiteral("kind")).toString() == QLatin1String(kFamily) ? QString::fromLatin1(kFamily) : QString::fromLatin1(kFolk);
}

QString Limits::typeName(const QString &type) { return type == QLatin1String(kFamily) ? QStringLiteral("Family") : QStringLiteral("Folk"); }

QString Limits::typeLine(const QString &type)
{
    return type == QLatin1String(kFamily) ? QStringLiteral("Your closest people. Admins add each person by QR or a code, no links. Kept up to 30 days.")
                                          : QStringLiteral("Friends and everyday groups. Anyone can invite, by a link or a code. Kept 7 days.");
}

QStringList Limits::typeRules(const QString &type)
{
    if (type == QLatin1String(kFamily))
        return {QStringLiteral("Only admins add people, face to face or by a code they hand over"), QStringLiteral("No invite links"),
                QStringLiteral("Each new member's safety code is checked once"), QStringLiteral("Messages stay up to 30 days (admins pick 7 to 30)"),
                QStringLiteral("Files and photos from members arrive by themselves"), QStringLiteral("Members are in your Family folder")};
    return {QStringLiteral("Anyone in the group can invite, by a link or a code"), QStringLiteral("Messages delete after 7 days"),
            QStringLiteral("Small files arrive by themselves, big ones ask first"), QStringLiteral("Members aren't added to your contacts")};
}

bool Limits::linksAllowed(const QJsonObject &state) { return groupType(state) != QLatin1String(kFamily); }

bool Limits::membersMayInvite(const QJsonObject &state) { return linksAllowed(state) && state.value(QStringLiteral("mi")).toBool(); }

bool Limits::filesArrive(const QString &type, qint64 size, qint64 autoBytes) { return type == QLatin1String(kFamily) || size < autoBytes; }

QJsonObject Limits::withType(QJsonObject state, const QString &type, int days, bool miOk)
{
    if (type == QLatin1String(kFamily)) {
        state.remove(QStringLiteral("mi"));
        return withLimit(state, QString::fromLatin1(kFamily), days);
    }
    if (state.value(QStringLiteral("kind")).toString() == QLatin1String("work"))
        return state;   // already Folk, keeping its days
    state = withLimit(state, QString(), kDefault);
    if (miOk)
        state.insert(QStringLiteral("mi"), true);
    return state;
}

bool Limits::selfTest(const QString &vectorsPath)
{
    int pass = 0, fail = 0;
    auto check = [&](bool good, const QString &what) {
        good ? ++pass : ++fail;
        if (!good)
            std::printf("d1 self-test: FAILED %s\n", qPrintable(what));
    };
    QFile f(vectorsPath);
    const QJsonObject v = f.open(QIODevice::ReadOnly) ? QJsonDocument::fromJson(f.readAll()).object() : QJsonObject();
    if (v.isEmpty()) {
        std::printf("d1 self-test: can't read %s\n", qPrintable(vectorsPath));
        return false;
    }
    const QString domain = QLatin1String(GroupWire::kDomain);
    const QJsonObject states = v.value(QStringLiteral("states")).toObject();
    for (const QString &k : states.keys()) {
        const QJsonObject x = states.value(k).toObject();
        const QJsonObject s = x.value(QStringLiteral("state")).toObject();
        check(GroupWire::checkState(s).isEmpty(), QStringLiteral("well-formed ") + k + QStringLiteral(": ") + GroupWire::checkState(s));
        check(GroupWire::signedBytes(domain, s) == x.value(QStringLiteral("signed")).toString().toUtf8(), QStringLiteral("signed bytes ") + k);
        check(GroupWire::hash(s) == x.value(QStringLiteral("hash")).toString(), QStringLiteral("hash ") + k);
        check(GroupWire::verify(s.value(QStringLiteral("by")).toString(), domain, s), QStringLiteral("signature ") + k);
        check(groupDays(s) == x.value(QStringLiteral("days")).toInt(), QStringLiteral("days ") + k);
    }
    const QJsonObject refuse = v.value(QStringLiteral("refuse")).toObject();
    for (const QString &k : refuse.keys())
        check(!GroupWire::checkState(refuse.value(k).toObject()).isEmpty(), QStringLiteral("refuses ") + k);
    for (const QJsonValue &a : v.value(QStringLiteral("accept")).toArray()) {
        const QJsonObject c = a.toObject();
        const QJsonObject next = states.value(c.value(QStringLiteral("new")).toString()).toObject().value(QStringLiteral("state")).toObject();
        const QJsonObject cur = states.value(c.value(QStringLiteral("current")).toString()).toObject().value(QStringLiteral("state")).toObject();
        const bool first = c.value(QStringLiteral("current")).isNull();
        check(GroupWire::accept(first ? nullptr : &cur, next).ok == c.value(QStringLiteral("accept")).toBool(),
              QStringLiteral("accept %1 -> %2").arg(c.value(QStringLiteral("current")).toString(), c.value(QStringLiteral("new")).toString()));
    }
    for (const QJsonValue &a : v.value(QStringLiteral("envelope")).toArray()) {
        const QJsonObject c = a.toObject();
        const int want = c.value(QStringLiteral("days")).isNull() ? 0 : c.value(QStringLiteral("days")).toInt();
        check(envelopeDays(c.value(QStringLiteral("o")).toObject()) == want,
              QStringLiteral("envelope ") + QString::fromUtf8(QJsonDocument(c.value(QStringLiteral("o")).toObject()).toJson(QJsonDocument::Compact)));
    }
    for (const QJsonValue &a : v.value(QStringLiteral("mine")).toArray()) {
        const QJsonObject c = a.toObject();
        const int chosen = c.value(QStringLiteral("chosen")).isNull() ? 0 : c.value(QStringLiteral("chosen")).toInt();
        check(myDays(chosen, c.value(QStringLiteral("close")).toBool()) == c.value(QStringLiteral("days")).toInt(), QStringLiteral("mine %1").arg(chosen));
    }
    for (const QJsonValue &a : v.value(QStringLiteral("pair")).toArray()) {
        const QJsonObject c = a.toObject();
        const int theirs = c.value(QStringLiteral("theirs")).isNull() ? 0 : c.value(QStringLiteral("theirs")).toInt();
        check(pairDays(c.value(QStringLiteral("mine")).toInt(), c.value(QStringLiteral("d1")).toBool(), theirs) == c.value(QStringLiteral("days")).toInt(),
              QStringLiteral("pair %1 %2").arg(c.value(QStringLiteral("mine")).toInt()).arg(theirs));
    }
    for (const QJsonValue &a : v.value(QStringLiteral("sweep")).toArray()) {
        const QJsonObject c = a.toObject();
        const qint64 cut = cutoff(qint64(c.value(QStringLiteral("now")).toDouble()), c.value(QStringLiteral("days")).toInt());
        const QJsonArray t = c.value(QStringLiteral("times")).toArray(), k = c.value(QStringLiteral("kept")).toArray();
        for (int i = 0; i < int(t.size()); ++i)
            check((qint64(t.at(i).toDouble()) >= cut) == k.at(i).toBool(), QStringLiteral("sweep %1 days, time %2").arg(c.value(QStringLiteral("days")).toInt()).arg(i));
    }
    // Building a state: Standard has neither field, so apps without d1 take it; Family keeps its days.
    QJsonObject s{{QStringLiteral("g"), QStringLiteral("x")}};
    check(!withLimit(s, QString(), 30).contains(QStringLiteral("kind")) && !withLimit(s, QString(), 30).contains(QStringLiteral("days")), "Standard has no kind or days");
    check(groupDays(withLimit(s, QStringLiteral("family"), 30)) == 30 && groupDays(withLimit(s, QStringLiteral("work"), 7)) == 7
              && !withLimit(s, QStringLiteral("work"), 7).contains(QStringLiteral("days")),
          "Family and Work keep their days, 7 is left out");
    // Group types: Folk and Family (16.2, 17.1).
    const QJsonObject work{{QStringLiteral("g"), QStringLiteral("x")}, {QStringLiteral("kind"), QStringLiteral("work")}, {QStringLiteral("days"), 14}};
    const QJsonObject fam = withType(QJsonObject{{QStringLiteral("g"), QStringLiteral("x")}, {QStringLiteral("mi"), true}}, QLatin1String(kFamily), 30);
    check(groupType(s) == QLatin1String(kFolk) && groupType(work) == QLatin1String(kFolk) && groupType(fam) == QLatin1String(kFamily), "types: no kind and work are Folk");
    check(groupDays(work) == 14 && withType(work, QLatin1String(kFolk), 7, true) == work, "an old work group is Folk and keeps its days");
    check(!fam.contains(QStringLiteral("mi")) && groupDays(fam) == 30 && !linksAllowed(fam) && !membersMayInvite(fam), "Family: no links, no members inviting, 30 days");
    check(groupDays(withType(s, QLatin1String(kFamily), 3)) == 7 && groupDays(withType(s, QLatin1String(kFamily), 90)) == 30, "Family keeps 7 to 30 days");
    const QJsonObject folk = withType(fam, QLatin1String(kFolk), 30, true);
    check(!folk.contains(QStringLiteral("kind")) && !folk.contains(QStringLiteral("days")) && membersMayInvite(folk) && linksAllowed(folk),
          "Folk: 7 days, members can invite");
    check(!withType(fam, QLatin1String(kFolk), 30, false).contains(QStringLiteral("mi")), "Folk without everyone's update: members can invite stays off");
    check(filesArrive(QLatin1String(kFamily), 4LL << 30, 50 << 20) && filesArrive(QLatin1String(kFolk), 1 << 20, 50 << 20)
              && !filesArrive(QLatin1String(kFolk), 50 << 20, 50 << 20), "files: Family all, Folk small ones");
    check(typeName(QLatin1String(kFamily)) == QLatin1String("Family") && typeName(QString()) == QLatin1String("Folk") && typeRules(QLatin1String(kFolk)).size() == 4,
          "type names and rules");
    std::printf("d1 self-test: %d passed, %d failed\n", pass, fail);
    return fail == 0;
}
