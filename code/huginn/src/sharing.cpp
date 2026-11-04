#include "sharing.h"

#include "groupwire.h"

#include <QDir>
#include <QFileInfo>
#include <QJsonValue>
#include <QSet>
#include <QStringList>
#include <cmath>
#include <cstdio>
#include <initializer_list>

#ifdef Q_OS_WIN
#include <windows.h>
#include <string>
#endif

namespace
{
// Characters that change which way text runs, or are there but take no room: in a file name they only ever hide what
// it is ("Photos" U+202E "gpj.scr" shows as "Photosrcs.jpg").
bool hiddenMark(char16_t c)
{
    return (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069) || (c >= 0x200b && c <= 0x200f) || c == 0x061c || c == 0xfeff
        || c == 0x2060 || c == 0x180e;
}
// Round 17 (m8): received files whose Mark of the Web couldn't be written (this run).
QSet<QString> &unmarkedPaths()
{
    static QSet<QString> s;
    return s;
}
}

bool Sharing::hiddenChar(char16_t c) { return hiddenMark(c); }

bool Sharing::unmarked(const QString &path) { return unmarkedPaths().contains(QDir::cleanPath(path)); }

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
    // "Direct" alone read like Trusted direct (straight over the internet), so each route says where it goes.
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
    for (const QChar c : name) {
        if (hiddenMark(c.unicode()))
            continue;   // left out altogether
        out += c.unicode() < 0x20 || c.unicode() == 0x7f || refused.contains(c) ? QLatin1Char('_') : c;
    }
    out = GroupWire::clip(out.trimmed(), 120);
    // No hidden names, no "." or "..", and nothing Windows would cut off.
    while (out.startsWith(QLatin1Char('.')) || out.startsWith(QLatin1Char(' ')))
        out.remove(0, 1);
    while (out.endsWith(QLatin1Char('.')) || out.endsWith(QLatin1Char(' ')))
        out.chop(1);
    if (out.isEmpty())
        return fallback;
    // CON, PRN, AUX, NUL, COM1 to COM9, LPT1 to LPT9, with or without an extension: Windows won't make them. Round 17
    // (m8): nor COM and LPT with a superscript 1, 2 or 3.
    const QString stem = out.section(QLatin1Char('.'), 0, 0).trimmed().toUpper();
    static const QStringList reserved{QStringLiteral("CON"), QStringLiteral("PRN"), QStringLiteral("AUX"), QStringLiteral("NUL"),
                                      QStringLiteral("CONIN$"), QStringLiteral("CONOUT$")};
    const auto digit = [](QChar d) { return (d >= QLatin1Char('1') && d <= QLatin1Char('9')) || d == QChar(0x00B9) || d == QChar(0x00B2) || d == QChar(0x00B3); };
    const bool numbered = stem.size() == 4 && (stem.startsWith(QLatin1String("COM")) || stem.startsWith(QLatin1String("LPT"))) && digit(stem.at(3));
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
    if (in({"doc", "docx", "docm", "odt", "rtf", "txt", "md", "pages", "ppt", "pptx", "pptm", "odp"}))
        return QStringLiteral("Document");
    if (in({"xls", "xlsx", "xlsm", "ods", "csv"}))
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
    // Everything Windows (or a Linux desktop) may run when it's opened: programs, scripts, shortcuts, installers and
    // disk images that open as a drive.
    if (in({"exe", "msi", "msix", "msixbundle", "appx", "appxbundle", "appinstaller", "msp", "bat", "cmd", "com", "scr", "pif", "cpl", "msc",
            "lnk", "url", "website", "js", "jse", "vbs", "vbe", "wsf", "wsh", "wsc", "hta", "ps1", "psm1", "psd1", "reg", "jar", "jnlp", "iso",
            "img", "vhd", "vhdx", "application", "gadget", "chm", "hlp", "inf", "ins", "isp", "sct", "scf", "shb", "settingcontent-ms",
            "library-ms", "search-ms", "xll", "sh", "run", "py", "pl", "desktop", "deb", "rpm", "appimage", "flatpak", "flatpakref", "flatpakrepo",
            // Round 17 (m8): a remote desktop to a stranger's server, click-once apps, troubleshooters, themes (which can
            // leak the Windows sign-in), sandbox setups, cabinets, saved web pages and more Windows scripts.
            "rdp", "appref-ms", "diagcab", "theme", "themepack", "desktopthemepack", "wsb", "cab", "mht", "mhtml", "ps1xml", "xbap"}))
        return QStringLiteral("Program");
    return QStringLiteral("File");
}

QString Sharing::openWarning(const QString &name)
{
    for (const QChar c : name)
        if (hiddenMark(c.unicode()))
            return QStringLiteral("The name \"%1\" has hidden characters that can make a file look like another kind. Opening it may run a "
                                  "program. Open it only if you were expecting it.")
                .arg(safeName(name, QStringLiteral("file")));
    if (kindOf(name) != QLatin1String("Program"))
        return {};
    const QFileInfo fi(name);
    const QString ext = fi.suffix();
    // "invoice.pdf.exe": Windows hides the last part, so it looks like the one before it.
    const QString inner = QFileInfo(fi.completeBaseName()).suffix();
    const QString looks = inner.isEmpty() ? QString() : kindOf(QStringLiteral("x.") + inner);
    if (!looks.isEmpty() && looks != QLatin1String("File") && looks != QLatin1String("Program"))
        return QStringLiteral("\"%1\" looks like a %2 but is a program (.%3). Opening it runs it, and a program from someone else can take "
                              "over this computer. Open it only if you were expecting it.")
            .arg(name, looks.toLower(), ext);
    return QStringLiteral("\"%1\" is a program (.%2). Opening it runs it, and a program from someone else can take over this computer. "
                          "Open it only if you were expecting it.")
        .arg(name, ext);
}

bool Sharing::markFromInternet(const QString &path)
{
#ifdef Q_OS_WIN
    // The Mark of the Web, as a browser leaves it: Windows' SmartScreen and its "Open File" warning then look at the
    // file before it runs. A drive without streams (FAT32, exFAT) can't keep it: then Huginn itself asks before a
    // program or an Office file opens (unmarked).
    const std::wstring stream = QDir::toNativeSeparators(path).toStdWString() + L":Zone.Identifier";
    HANDLE h = CreateFileW(stream.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    bool ok = h != INVALID_HANDLE_VALUE;
    if (ok) {
        static const char zone[] = "[ZoneTransfer]\r\nZoneId=3\r\n";
        DWORD wrote = 0;
        ok = WriteFile(h, zone, DWORD(sizeof(zone) - 1), &wrote, nullptr) && wrote == DWORD(sizeof(zone) - 1);
        CloseHandle(h);
    }
    if (!ok) {
        QSet<QString> &s = unmarkedPaths();
        if (s.size() > 2000)
            s.clear();
        s.insert(QDir::cleanPath(path));
    }
    return ok;
#else
    Q_UNUSED(path);
    return true;
#endif
}

QString Sharing::declineText(const QString &why)
{
    if (why == QLatin1String("rules"))
        return QStringLiteral("Refused by the group's file rules.");
    if (why == QLatin1String("space"))
        return QStringLiteral("Not enough space on their device.");
    if (why == QLatin1String("no"))
        return QStringLiteral("They declined.");
    return QStringLiteral("They declined, or direct files are off on their side (Trusted direct, or Ultra privacy).");
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
    // Round 17 (m8): COM and LPT with a superscript digit are Windows' too; and more kinds that run things are programs.
    check(safeName(QStringLiteral("COM") + QChar(0x00B2), QStringLiteral("x")).startsWith(QLatin1Char('_'))
              && safeName(QStringLiteral("LPT") + QChar(0x00B9) + QStringLiteral(".txt"), QStringLiteral("x")).startsWith(QLatin1Char('_'))
              && kindOf(QStringLiteral("work.rdp")) == QLatin1String("Program") && kindOf(QStringLiteral("dark.themepack")) == QLatin1String("Program")
              && kindOf(QStringLiteral("page.mhtml")) == QLatin1String("Program") && kindOf(QStringLiteral("box.wsb")) == QLatin1String("Program")
              && kindOf(QStringLiteral("deck.pptx")) == QLatin1String("Document"),
          QStringLiteral("name: superscript reserved names, and more program kinds"));
    check(safeName(QStringLiteral("COM10"), QStringLiteral("x")) == QLatin1String("COM10") && safeName(QStringLiteral("Console"), QStringLiteral("x")) == QLatin1String("Console"),
          QStringLiteral("name: not reserved"));
    check(safeName(QStringLiteral("Ana & Bo?"), QStringLiteral("x")) == QLatin1String("Ana & Bo_"), QStringLiteral("name: characters"));
    check(safeName(QStringLiteral("tail. . "), QStringLiteral("x")) == QLatin1String("tail"), QStringLiteral("name: trailing dots"));
    check(safeName(QStringLiteral("a\nb\tc"), QStringLiteral("x")) == QLatin1String("a_b_c"), QStringLiteral("name: control characters"));
    check(safeName(QString(300, QLatin1Char('a')), QStringLiteral("x")).size() == 120, QStringLiteral("name: length"));
    // A name turned around by a hidden direction mark shows (and is saved) as what it really is.
    check(safeName(QStringLiteral("Photos") + QChar(0x202e) + QStringLiteral("gpj.scr"), QStringLiteral("x")) == QLatin1String("Photosgpj.scr")
              && safeName(QStringLiteral("a") + QChar(0x200b) + QChar(0x2066) + QStringLiteral("b.txt"), QStringLiteral("x")) == QLatin1String("ab.txt")
              && safeName(QStringLiteral("a.txt:stream"), QStringLiteral("x")) == QLatin1String("a.txt_stream"),
          QStringLiteral("name: hidden marks and streams"));
    check(kindOf(QStringLiteral("x.scr")) == QLatin1String("Program") && kindOf(QStringLiteral("x.LNK")) == QLatin1String("Program")
              && kindOf(QStringLiteral("x.vbs")) == QLatin1String("Program") && kindOf(QStringLiteral("x.iso")) == QLatin1String("Program"),
          QStringLiteral("kinds: programs"));
    check(!openWarning(QStringLiteral("invoice.pdf.exe")).isEmpty() && openWarning(QStringLiteral("invoice.pdf.exe")).contains(QLatin1String("looks like a pdf document"))
              && !openWarning(QStringLiteral("Photos") + QChar(0x202e) + QStringLiteral("gpj.txt")).isEmpty() && openWarning(QStringLiteral("photo.jpg")).isEmpty()
              && openWarning(QStringLiteral("notes.txt")).isEmpty(),
          QStringLiteral("open warnings"));
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
