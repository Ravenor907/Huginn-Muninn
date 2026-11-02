#include "sharing.h"

#include "groupwire.h"

#include <QDir>
#include <QFileInfo>
#include <QJsonValue>
#include <QStringList>
#include <cmath>
#include <cstdio>
#include <initializer_list>

const char Sharing::kCap[] = "f2";
const char Sharing::kEveryone[] = "everyone";
const char Sharing::kAdmins[] = "admins";
const char Sharing::kNobody[] = "nobody";

namespace
{
const qint64 kMb = 1024 * 1024;

bool whole(const QJsonValue &v, qint64 lo, qint64 hi)
{
    if (!v.isDouble())
        return false;
    const double d = v.toDouble();
    return d == std::floor(d) && d >= double(lo) && d <= double(hi);
}
}

QString Sharing::route(bool sameWifi, bool trusted, qint64 size, qint64 relayMax)
{
    if (sameWifi)
        return QStringLiteral("direct");
    if (trusted)
        return QStringLiteral("internet");
    if (size > 0 && size <= relayMax)
        return QStringLiteral("relay");
    return QString();
}

QString Sharing::routeLabel(const QString &route)
{
    // "Direct" alone read like Direct calls and files (straight over the internet), so each route says where it goes.
    if (route == QLatin1String("direct"))
        return QStringLiteral("Same Wi-Fi");
    return route == QLatin1String("internet") ? QStringLiteral("Straight to them") : QStringLiteral("Over the internet");
}

QString Sharing::whoMaySend(const QJsonObject &state)
{
    const QString fs = state.value(QStringLiteral("fs")).toString();
    if (fs == QLatin1String(kAdmins) || fs == QLatin1String(kNobody))
        return fs;
    return QString::fromLatin1(kEveryone);
}

int Sharing::limitMb(const QJsonObject &state)
{
    const QJsonValue v = state.value(QStringLiteral("fm"));
    return whole(v, 1, kMaxLimitMb) ? int(v.toDouble()) : 0;
}

bool Sharing::hasRules(const QJsonObject &state)
{
    return state.contains(QStringLiteral("fs")) || state.contains(QStringLiteral("fm"));
}

QString Sharing::refusal(const QJsonObject &state, const QString &from, qint64 size)
{
    if (!GroupWire::isMember(state, from))
        return QStringLiteral("Refused: they aren't in this group.");
    const QString who = whoMaySend(state);
    if (who == QLatin1String(kNobody))
        return QStringLiteral("Refused by the group's file rules: nobody can send files.");
    if (who == QLatin1String(kAdmins) && !GroupWire::isAdmin(state, from))
        return QStringLiteral("Refused by the group's file rules: only admins can send files.");
    const int mb = limitMb(state);
    if (mb > 0 && size > qint64(mb) * kMb)
        return QStringLiteral("Refused by the group's file rules: files up to %1 MB.").arg(mb);
    return QString();
}

QJsonObject Sharing::withRules(QJsonObject state, const QString &who, int mb)
{
    state.remove(QStringLiteral("fs"));
    state.remove(QStringLiteral("fm"));
    if (who == QLatin1String(kAdmins) || who == QLatin1String(kNobody))
        state.insert(QStringLiteral("fs"), who);
    if (mb >= 1 && mb <= kMaxLimitMb)
        state.insert(QStringLiteral("fm"), mb);
    return state;
}

QString Sharing::checkRules(const QJsonObject &state)
{
    if (state.contains(QStringLiteral("fs"))) {
        const QJsonValue v = state.value(QStringLiteral("fs"));
        if (v != QJsonValue(QString::fromLatin1(kAdmins)) && v != QJsonValue(QString::fromLatin1(kNobody)))
            return QStringLiteral("fs");
    }
    if (state.contains(QStringLiteral("fm")) && !whole(state.value(QStringLiteral("fm")), 1, kMaxLimitMb))
        return QStringLiteral("fm");
    return QString();
}

QString Sharing::rulesLine(const QJsonObject &state)
{
    const QString who = whoMaySend(state);
    QString line = who == QLatin1String(kNobody)  ? QStringLiteral("Nobody can send files")
                 : who == QLatin1String(kAdmins) ? QStringLiteral("Only admins can send files")
                                                 : QStringLiteral("Everyone can send files");
    const int mb = limitMb(state);
    if (mb > 0 && who != QLatin1String(kNobody))
        line += QStringLiteral(" · Up to %1 MB").arg(mb);
    return line;
}

QString Sharing::safeName(const QString &name, const QString &fallback)
{
    static const QString refused = QStringLiteral("/\\:*?\"<>|");
    QString out;
    for (const QChar c : name)
        out += c.unicode() < 0x20 || c.unicode() == 0x7f || refused.contains(c) ? QLatin1Char('_') : c;
    out = GroupWire::clip(out.trimmed(), 120);
    // No hidden names, no "." or "..", and nothing Windows would cut off.
    while (out.startsWith(QLatin1Char('.')) || out.startsWith(QLatin1Char(' ')))
        out.remove(0, 1);
    while (out.endsWith(QLatin1Char('.')) || out.endsWith(QLatin1Char(' ')))
        out.chop(1);
    if (out.isEmpty())
        return fallback;
    // CON, PRN, AUX, NUL, COM1 to COM9, LPT1 to LPT9, with or without an extension: Windows won't make them.
    const QString stem = out.section(QLatin1Char('.'), 0, 0).trimmed().toUpper();
    static const QStringList reserved{QStringLiteral("CON"), QStringLiteral("PRN"), QStringLiteral("AUX"), QStringLiteral("NUL"),
                                      QStringLiteral("CONIN$"), QStringLiteral("CONOUT$")};
    const bool numbered = stem.size() == 4 && (stem.startsWith(QLatin1String("COM")) || stem.startsWith(QLatin1String("LPT")))
        && stem.at(3) >= QLatin1Char('1') && stem.at(3) <= QLatin1Char('9');
    if (reserved.contains(stem) || numbered)
        out.prepend(QLatin1Char('_'));
    return out;
}

QString Sharing::folderFor(const QString &base, const QString &app, const QString &chatName)
{
    return QDir(QDir(base).filePath(safeName(app, QStringLiteral("Huginn")))).filePath(safeName(chatName, QStringLiteral("Chat")));
}

QString Sharing::kindOf(const QString &name)
{
    const QString ext = QFileInfo(name).suffix().toLower();
    auto in = [&ext](std::initializer_list<const char *> list) {
        for (const char *e : list)
            if (ext == QLatin1String(e))
                return true;
        return false;
    };
    if (in({"pdf"}))
        return QStringLiteral("PDF document");
    if (in({"doc", "docx", "odt", "rtf", "txt", "md", "pages"}))
        return QStringLiteral("Document");
    if (in({"xls", "xlsx", "ods", "csv"}))
        return QStringLiteral("Spreadsheet");
    if (in({"jpg", "jpeg", "png", "gif", "webp", "heic", "bmp"}))
        return QStringLiteral("Picture");
    if (in({"mp4", "m4v", "mov", "mkv", "webm", "avi"}))
        return QStringLiteral("Video");
    if (in({"mp3", "m4a", "aac", "ogg", "opus", "wav", "flac"}))
        return QStringLiteral("Sound");
    if (in({"zip", "7z", "rar", "tar", "gz", "xz", "bz2"}))
        return QStringLiteral("Archive");
    if (in({"apk", "apks", "xapk", "apkm"}))
        return QStringLiteral("App installer");
    if (in({"exe", "msi", "bat", "cmd", "ps1", "sh", "appimage", "flatpak", "flatpakref"}))
        return QStringLiteral("Program");
    return QStringLiteral("File");
}

QString Sharing::declineText(const QString &why)
{
    if (why == QLatin1String("rules"))
        return QStringLiteral("Refused by the group's file rules.");
    if (why == QLatin1String("space"))
        return QStringLiteral("Not enough space on their device.");
    if (why == QLatin1String("no"))
        return QStringLiteral("They declined.");
    return QStringLiteral("They declined, or Direct calls and files is off on their side.");
}

bool Sharing::selfTest()
{
    int bad = 0;
    auto check = [&bad](bool ok, const QString &what) {
        if (!ok) {
            bad++;
            std::fprintf(stderr, "f2 FAIL: %s\n", qPrintable(what));
        }
    };
    const qint64 relayMax = 2 * kMb;
    // The route: the same Wi-Fi first, then straight over the internet (trust), then the relays (small only).
    check(route(true, false, 900 * kMb, relayMax) == QLatin1String("direct"), QStringLiteral("route: same Wi-Fi, no trust"));
    check(route(true, true, 10, relayMax) == QLatin1String("direct"), QStringLiteral("route: same Wi-Fi wins"));
    check(route(false, true, 900 * kMb, relayMax) == QLatin1String("internet"), QStringLiteral("route: trusted"));
    check(route(false, false, relayMax, relayMax) == QLatin1String("relay"), QStringLiteral("route: small, no trust"));
    check(route(false, false, relayMax + 1, relayMax).isEmpty(), QStringLiteral("route: big, no trust, not close"));
    check(routeLabel(QStringLiteral("direct")) == QLatin1String("Same Wi-Fi") && routeLabel(QStringLiteral("relay")) == QLatin1String("Over the internet")
              && routeLabel(QStringLiteral("internet")) == QLatin1String("Straight to them"), QStringLiteral("route labels"));

    // Group rules, as every member's app enforces them.
    QJsonObject s = GroupWire::withMembers(QJsonObject{{QStringLiteral("g"), QStringLiteral("x")}},
                                           {GroupWire::member(QStringLiteral("A"), QStringLiteral("Ana"), QString()),
                                            GroupWire::member(QStringLiteral("B"), QStringLiteral("Bo"), QString())});
    s = GroupWire::withAdmins(s, {QStringLiteral("A")});
    check(whoMaySend(s) == QLatin1String(kEveryone) && limitMb(s) == 0 && !hasRules(s), QStringLiteral("rules: none"));
    check(refusal(s, QStringLiteral("B"), 900 * kMb).isEmpty(), QStringLiteral("rules: everyone"));
    check(!refusal(s, QStringLiteral("Z"), 10).isEmpty(), QStringLiteral("rules: not a member"));
    const QJsonObject admins = withRules(s, QString::fromLatin1(kAdmins), 25);
    check(checkRules(admins).isEmpty() && hasRules(admins), QStringLiteral("rules: well-formed"));
    check(refusal(admins, QStringLiteral("A"), 25 * kMb).isEmpty(), QStringLiteral("rules: an admin within the limit"));
    check(refusal(admins, QStringLiteral("A"), 25 * kMb + 1).contains(QLatin1String("25 MB")), QStringLiteral("rules: over the limit"));
    check(refusal(admins, QStringLiteral("B"), 10).startsWith(QLatin1String("Refused by the group's file rules")), QStringLiteral("rules: admins only"));
    const QJsonObject nobody = withRules(s, QString::fromLatin1(kNobody), 0);
    check(!refusal(nobody, QStringLiteral("A"), 10).isEmpty() && !nobody.contains(QStringLiteral("fm")), QStringLiteral("rules: nobody"));
    const QJsonObject back = withRules(admins, QString::fromLatin1(kEveryone), 0);
    check(!hasRules(back) && back == s, QStringLiteral("rules: back to everyone leaves the state as it was"));
    check(rulesLine(admins) == QStringLiteral("Only admins can send files · Up to 25 MB") && rulesLine(nobody) == QLatin1String("Nobody can send files")
              && rulesLine(s) == QLatin1String("Everyone can send files"), QStringLiteral("rules: in words"));
    auto badRules = [&](const char *k, const QJsonValue &v) { QJsonObject t = s; t.insert(QLatin1String(k), v); return !checkRules(t).isEmpty(); };
    check(badRules("fs", QStringLiteral("everyone")) && badRules("fs", QStringLiteral("x")) && badRules("fs", true), QStringLiteral("rules: bad fs"));
    check(badRules("fm", 0) && badRules("fm", 4097) && badRules("fm", 2.5) && badRules("fm", QStringLiteral("5")), QStringLiteral("rules: bad fm"));

    // Names: never a way out of the folder, never a name Windows refuses.
    check(safeName(QStringLiteral("../../etc/passwd"), QStringLiteral("x")) == QLatin1String("_.._etc_passwd"), QStringLiteral("name: ") + safeName(QStringLiteral("../../etc/passwd"), QStringLiteral("x")));
    check(safeName(QStringLiteral(".."), QStringLiteral("Chat")) == QLatin1String("Chat"), QStringLiteral("name: .."));
    check(safeName(QStringLiteral("."), QStringLiteral("Chat")) == QLatin1String("Chat"), QStringLiteral("name: ."));
    check(safeName(QStringLiteral("C:\\Windows"), QStringLiteral("x")) == QLatin1String("C__Windows"), QStringLiteral("name: drive"));
    check(safeName(QStringLiteral("CON"), QStringLiteral("x")) == QLatin1String("_CON") && safeName(QStringLiteral("nul.txt"), QStringLiteral("x")) == QLatin1String("_nul.txt")
              && safeName(QStringLiteral("COM3"), QStringLiteral("x")) == QLatin1String("_COM3") && safeName(QStringLiteral("lpt9.log"), QStringLiteral("x")) == QLatin1String("_lpt9.log"),
          QStringLiteral("name: reserved"));
    check(safeName(QStringLiteral("COM10"), QStringLiteral("x")) == QLatin1String("COM10") && safeName(QStringLiteral("Console"), QStringLiteral("x")) == QLatin1String("Console"),
          QStringLiteral("name: not reserved"));
    check(safeName(QStringLiteral("Ana & Bo?"), QStringLiteral("x")) == QLatin1String("Ana & Bo_"), QStringLiteral("name: characters"));
    check(safeName(QStringLiteral("tail. . "), QStringLiteral("x")) == QLatin1String("tail"), QStringLiteral("name: trailing dots"));
    check(safeName(QStringLiteral("a\nb\tc"), QStringLiteral("x")) == QLatin1String("a_b_c"), QStringLiteral("name: control characters"));
    check(safeName(QString(300, QLatin1Char('a')), QStringLiteral("x")).size() == 120, QStringLiteral("name: length"));
    const QString f = folderFor(QStringLiteral("/home/me/Downloads"), QStringLiteral("Huginn"), QStringLiteral("../Ana"));
    check(f == QStringLiteral("/home/me/Downloads/Huginn/_Ana") && !f.contains(QLatin1String("..")), QStringLiteral("folder: ") + f);
    check(folderFor(QStringLiteral("/d"), QStringLiteral("Huginn"), QStringLiteral("..")) == QLatin1String("/d/Huginn/Chat"), QStringLiteral("folder: .."));
    check(folderFor(QStringLiteral("/d"), QStringLiteral("Huginn"), QStringLiteral("AUX")) == QLatin1String("/d/Huginn/_AUX"), QStringLiteral("folder: reserved"));
    check(kindOf(QStringLiteral("a.PDF")) == QLatin1String("PDF document") && kindOf(QStringLiteral("game.apk")) == QLatin1String("App installer")
              && kindOf(QStringLiteral("x")) == QLatin1String("File"), QStringLiteral("kinds"));
    check(declineText(QStringLiteral("rules")) == QLatin1String("Refused by the group's file rules.")
              && declineText(QStringLiteral("space")) == QLatin1String("Not enough space on their device."), QStringLiteral("decline words"));
    std::fprintf(stderr, "f2: %s\n", bad == 0 ? "all checks passed" : "FAILED");
    return bad == 0;
}
