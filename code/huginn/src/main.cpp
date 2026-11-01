#include "gamepad.h"
#include "localsend.h"
#include "chat.h"
#include "direct.h"
#include "chatcrypto.h"
#include "chatwire.h"
#include "call/callmanager.h"
#include "call/callselftest.h"
#include "call/callsignal.h"
#include "call/roommanager.h"
#include "groupwire.h"
#include "ratchet.h"
#include "ble.h"
#include "bleframes.h"
#include "busy.h"
#include "nostr.h"
#include "photos.h"
#include "schnorr.h"
#include "vault.h"
#include "voice.h"
#include "platform/chime.h"
#include "platform/crashlog.h"
#include "platform/notify.h"
#include "platform/scratch.h"
#include "platform/singleinstance.h"
#include "platform/winstart.h"
#include "platform/winupdate.h"
#include <QJsonDocument>
#include <QJsonObject>
#include "settings.h"
#include "talkkey.h"
#include "system.h"
#include "transfer.h"
#include "fall.h"
#include "livelist.h"
#include "updater.h"
#include "labs.h"
#include "appstorage.h"
#include "perfcheck.h"
#include "wifiselftest.h"
#include "proof.h"
#include "move.h"
#include "morewire.h"
#include "linkpreview.h"
#include "videomsg.h"
#include "thumbs.h"
#include "msglimits.h"
#include "richwire.h"
#include "filelink.h"
#include "link.h"
#include "trust.h"
#include "profile.h"
#include "ring.h"

#include <QApplication>
#include <QEvent>
#include <QCommandLineParser>
#include <QTimer>
#include <QDateTime>
#include <QDir>
#include <QDirIterator>
#include <QFile>
#include <QFileInfo>
#include <QColor>
#include <QFont>
#include <QFontDatabase>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickWindow>
#include <QQuickImageProvider>
#include <QRandomGenerator>
#include <QStyleHints>
#include <QStandardPaths>
#include <QUrl>
#include <QVariantMap>
#include <atomic>
#include <cstdio>
#include <memory>

#ifndef HUGINN_VERSION
#define HUGINN_VERSION "0.1"
#endif
// A pre-release build's label ("0.99 beta 12"); a stable build's is its version.
#ifndef HUGINN_VERSION_LABEL
#define HUGINN_VERSION_LABEL HUGINN_VERSION
#endif

namespace
{
// "image://ground/TOP-MID-DEEP": the Seasonal ground (its colors as RRGGBB, lighter near the top), made once per
// size and color on a loading thread and then shown as one still, opaque picture. A faint speckle in it (about one
// step of 8-bit color) keeps the gradient smooth, without bands; nothing is blended over the window each frame.
class GroundProvider : public QQuickImageProvider
{
public:
    GroundProvider() : QQuickImageProvider(QQuickImageProvider::Image) {}
    QImage requestImage(const QString &id, QSize *size, const QSize &requested) override
    {
        const QStringList parts = id.split(QLatin1Char('-'));
        auto colour = [&parts](int i) {
            const QColor c = QColor::fromString(QString(QLatin1Char('#') + parts.value(i)));
            return c.isValid() ? c : QColor(0x24, 0x18, 0x10);
        };
        const QColor top = colour(0), mid = colour(1), deep = colour(2);
        // Up to a large screen's size; a bigger window stretches it (the gradient only runs top to bottom).
        const int w = qBound(1, requested.width() > 0 ? requested.width() : 256, 2560);
        const int h = qBound(1, requested.height() > 0 ? requested.height() : 256, 1600);
        QImage img(w, h, QImage::Format_RGB32);
        QRandomGenerator rng(0x5eed1e5u);
        for (int y = 0; y < h; ++y) {
            const double p = h > 1 ? double(y) / (h - 1) : 0.0;
            const QColor &a = p < 0.45 ? top : mid, &b = p < 0.45 ? mid : deep;
            const double k = p < 0.45 ? p / 0.45 : (p - 0.45) / 0.55;
            const double r = a.redF() + (b.redF() - a.redF()) * k, g = a.greenF() + (b.greenF() - a.greenF()) * k,
                         bl = a.blueF() + (b.blueF() - a.blueF()) * k;
            auto *line = reinterpret_cast<QRgb *>(img.scanLine(y));
            for (int x = 0; x < w; ++x) {
                const double n = (int(rng.bounded(5u)) - 2) * 0.5;   // -1 to +1 steps of 8-bit color
                line[x] = qRgb(qBound(0, int(r * 255.0 + 0.5 + n), 255), qBound(0, int(g * 255.0 + 0.5 + n), 255),
                               qBound(0, int(bl * 255.0 + 0.5 + n), 255));
            }
        }
        if (size)
            *size = img.size();
        return img;
    }
};
}

namespace
{
QtMessageHandler s_previous = nullptr;
// Screenshot tour only: each counted warning is also written here (Windows builds have no console to show them).
QString s_warningFile;

// Counts QML warnings so the CI screenshot tour can fail the build on any of them.
void messages(QtMsgType type, const QMessageLogContext &ctx, const QString &msg)
{
    // A fatal error ends Huginn right after this: kept in crash.log (scrubbed of the home folder and addresses).
    if (type == QtFatalMsg)
        CrashLog::note(QStringLiteral("fatal: ") + msg);
    if (type >= QtWarningMsg) {
        const QString file = QString::fromUtf8(ctx.file ? ctx.file : "");
        // A screen smaller than the size asked for (a CI machine's small display) is not a QML problem.
        const bool screenTooSmall = msg.startsWith(QLatin1String("QWindowsWindow::setGeometry"));
        if (!screenTooSmall && (file.endsWith(QLatin1String(".qml")) || msg.contains(QLatin1String(".qml:")) || msg.contains(QLatin1String("QML"))))
        {
            ++System::s_qmlWarnings;
            if (!s_warningFile.isEmpty()) {
                QFile f(s_warningFile);
                if (f.open(QIODevice::WriteOnly | QIODevice::Append | QIODevice::Text))
                    f.write((msg + QLatin1Char('\n')).toUtf8());
            }
        }
    }
    // Labs › Performance check: layout that keeps redoing itself is named in crash.log (the full report shows it).
    if (type == QtWarningMsg && (msg.contains(QLatin1String("polish() loop")) || msg.contains(QLatin1String("Binding loop")))) {
        static std::atomic<int> noted{0};
        if (noted.fetch_add(1) < 20)
            CrashLog::note(QStringLiteral("warning: ") + msg.left(240));
    }
    if (s_previous)
        s_previous(type, ctx, msg);
}

// Labs › Performance check: every event the window's thread handles is timed while a check runs (busy.h).
class HuginnApp : public QApplication
{
public:
    HuginnApp(int &argc, char **argv)
        : QApplication(argc, argv)
    {
    }
    bool notify(QObject *receiver, QEvent *event) override
    {
        if (!Busy::on)
            return QApplication::notify(receiver, event);
        Busy::Event timed(receiver, event);
        return QApplication::notify(receiver, event);
    }
};
}

int main(int argc, char *argv[])
{
    // Windows: a start log next to Huginn.exe and cleanup after an update.
    Platform::beginStart();
    s_previous = qInstallMessageHandler(messages);
    // Labs › Performance check: settings being tried (display mode, drawing, render loop) go in before Qt starts.
    PerfCheck::applyAtStart(argc, argv);
#ifndef Q_OS_WIN
    // SteamOS Game Mode: gamescope shows games through Xwayland, and Huginn takes the same path as every game
    // there (gamescope's frame pacing is built around it), unless a platform was set by hand.
    if (System::detectGameMode() && qEnvironmentVariableIsEmpty("QT_QPA_PLATFORM") && !qEnvironmentVariableIsEmpty("DISPLAY"))
        qputenv("QT_QPA_PLATFORM", "xcb");
#endif
    HuginnApp app(argc, argv);
    QApplication::setApplicationName(QStringLiteral("Huginn"));
    QApplication::setOrganizationName(QStringLiteral("Huginn"));
    QApplication::setApplicationVersion(QStringLiteral(HUGINN_VERSION_LABEL));
    QApplication::setDesktopFileName(QStringLiteral("io.github.ravenor907.Huginn"));
    QQuickWindow::setTextRenderType(QQuickWindow::NativeTextRendering);
    // The single-instance self-test's helper copy: holds a lock in a temporary folder, nothing else.
    {
        const QStringList a = QCoreApplication::arguments();
        if (a.size() >= 5 && a.at(1) == QLatin1String("--instance-selftest-holder"))
            return SingleInstance::selfTestHolder(a.at(2), a.at(3).toInt(), a.at(4) == QLatin1String("hang"));
    }
    Platform::chooseGraphics();

    QCommandLineParser args;
    args.addHelpOption();
    args.addVersionOption();
    QCommandLineOption tour(QStringLiteral("tour"), QStringLiteral("Open every screen, save a screenshot of each into DIR, then quit."), QStringLiteral("DIR"));
    QCommandLineOption windowed(QStringLiteral("windowed"), QStringLiteral("Run in a window instead of full screen."));
    args.addOption(tour);
    args.addOption(windowed);
    QCommandLineOption chatTest(QStringLiteral("chat-selftest"), QStringLiteral("Check the chat encryption against Muninn's test vectors, the message envelopes and the 0.6 groups (proto06-vectors.json next to FILE) (for CI), then quit."), QStringLiteral("FILE"));
    args.addOption(chatTest);
    QCommandLineOption ratchetTest(QStringLiteral("ratchet-selftest"), QStringLiteral("Check the message ratchet against Muninn's test vectors (for CI), then quit."), QStringLiteral("FILE"));
    args.addOption(ratchetTest);
    QCommandLineOption nostrTest(QStringLiteral("nostr-selftest"), QStringLiteral("Check the Nostr signatures against Muninn's test vectors (for CI), then quit."), QStringLiteral("FILE"));
    args.addOption(nostrTest);
    QCommandLineOption privacyTest(QStringLiteral("privacy-selftest"), QStringLiteral("Check the vault, photo scrubbing, Bluetooth framing, the App storage allowlist and the handheld detection (for CI), then quit."));
    args.addOption(privacyTest);
    QCommandLineOption backupTest(QStringLiteral("backup-selftest"), QStringLiteral("Back up and restore a temporary chat folder (for CI), then quit."));
    args.addOption(backupTest);
    QCommandLineOption callTest(QStringLiteral("call-selftest"), QStringLiteral("Check calls against the shared test vectors and an in-process loopback (for CI), then quit."), QStringLiteral("FILE"));
    args.addOption(callTest);
    QCommandLineOption wifiTest(QStringLiteral("wifi-selftest"), QStringLiteral("Run on a throwaway profile, receive an encrypted message from a pretend Muninn over the local HTTPS endpoint, raise the call and a notification (for CI), then quit."));
    args.addOption(wifiTest);
    QCommandLineOption stabilityTest(QStringLiteral("stability-selftest"), QStringLiteral("On a throwaway profile: a saved file that can't be read is never written over, a restarted copy waits for the closing one, a stuck one is taken over (for CI), then quit."));
    args.addOption(stabilityTest);
    // Started by a copy that is closing (an update, a restore): wait for it to end before starting.
    QCommandLineOption restarted(QStringLiteral("restarted"), QStringLiteral("Wait for the copy that started this one to close."));
    restarted.setFlags(QCommandLineOption::HiddenFromHelp);
    args.addOption(restarted);
    QCommandLineOption releaseSigTest(QStringLiteral("release-sig-selftest"), QStringLiteral("Check the update signature check against the shared test vectors (for CI), then quit."), QStringLiteral("FILE"));
    args.addOption(releaseSigTest);
    args.process(app);
    if (args.isSet(tour)) {
        QDir().mkpath(args.value(tour));
        s_warningFile = QDir(args.value(tour)).filePath(QStringLiteral("warnings.txt"));
    }
    const bool wifiSelf = args.isSet(wifiTest);
    // The screenshot tour and the Wi-Fi self-test run on a throwaway profile (settings, vault and lock), stay off
    // the internet and don't claim the single instance: the person's own Huginn is never touched.
    const bool quiet = args.isSet(tour) || wifiSelf;
    if (quiet)
        WifiSelfTest::isolate();
    // Command-line self-tests: a passing one leaves no start log behind.
    auto selfTested = [](bool ok) {
        if (ok)
            Platform::noStartLog();
        else
            Platform::startClaimed(false);   // a failing one keeps its log, without touching an update
        return ok ? 0 : 1;
    };
    if (args.isSet(callTest))
        return selfTested(CallSelfTest::run(args.value(callTest)));
    if (args.isSet(chatTest)) {
        const bool a = ChatCrypto::selfTest(args.value(chatTest)), b = ChatWire::selfTest();
        const bool c = GroupWire::selfTest(QFileInfo(args.value(chatTest)).absoluteDir().filePath(QStringLiteral("proto06-vectors.json")));
        const bool d = RichWire::selfTest(QFileInfo(args.value(chatTest)).absoluteDir().filePath(QStringLiteral("m1-vectors.json")));
        const bool e = MoreWire::selfTest(QFileInfo(args.value(chatTest)).absoluteDir().filePath(QStringLiteral("m2-vectors.json")));
        const bool f = LinkPreview::selfTest();
        const bool g = Limits::selfTest(QFileInfo(args.value(chatTest)).absoluteDir().filePath(QStringLiteral("d1-vectors.json")));
        const bool h = Trust::selfTest(QFileInfo(args.value(chatTest)).absoluteDir().filePath(QStringLiteral("i1f1-vectors.json")));
        // Moving (x1), invite links (j1) and "mi" in a group state.
        const bool i = MoveWire::selfTest(QFileInfo(args.value(chatTest)).absoluteDir().filePath(QStringLiteral("p95-vectors.json")));
        const bool j = Proof::selfTest(QFileInfo(args.value(chatTest)).absoluteDir().filePath(QStringLiteral("s1-vectors.json")));
        // Profile pictures and status (p1).
        const bool k = Profile::selfTest(QFileInfo(args.value(chatTest)).absoluteDir().filePath(QStringLiteral("p1-vectors.json")));
        // The ring key and the relay wrapping (b1, e1).
        const bool l = RingWire::selfTest(QFileInfo(args.value(chatTest)).absoluteDir().filePath(QStringLiteral("e1b1-vectors.json")));
        return selfTested(a && b && c && d && e && f && g && h && i && j && k && l);
    }
    if (args.isSet(releaseSigTest))
        return selfTested(Updater::selfTest(args.value(releaseSigTest)));
    if (args.isSet(backupTest))
        return selfTested(Vault::backupSelfTest());
    if (args.isSet(ratchetTest))
        return selfTested(Ratchet::selfTest(args.value(ratchetTest)));
    if (args.isSet(nostrTest))
        return selfTested(Nostr::selfTest(args.value(nostrTest)));
    if (args.isSet(stabilityTest)) {
        WifiSelfTest::isolate();
        const bool a = Vault::stabilitySelfTest(), b = SingleInstance::selfTest(), c = Chat::keySelfTest();
        // crash.log lines never carry the home folder (the account name) or a network address.
        const QString home = QDir::homePath();
        const QString scrubbed = CrashLog::scrub(QStringLiteral("at 192.168.1.20 and fe80::1 in ") + home + QStringLiteral("/x QQuickWindow::sceneGraphError"));
        const bool d = !scrubbed.contains(QLatin1String("192.168")) && !scrubbed.contains(QLatin1String("fe80"))
            && (home.size() <= 1 || !scrubbed.contains(home)) && scrubbed.contains(QLatin1String("QQuickWindow::sceneGraphError"));
        std::printf("crash log keeps out addresses and the home folder: %s\n", d ? "ok" : "FAILED");
        return selfTested(a && b && c && d);
    }
    if (args.isSet(privacyTest)) {
        const bool a = Vault::selfTest(), b = Photos::selfTest(), c = BleFrames::selfTest();
        // Labs › App storage never reaches the vault, keys or chats.
        const bool d = AppStorage::selfTest();
        // Labs › Performance check's report never carries names, addresses, ids or file names.
        const bool e = PerfCheck::selfTest();
        // Handheld mode's detection: the known models, the two-of-three rule, a laptop with a pad.
        const bool f = System::handheldSelfTest();
        return selfTested(a && b && c && d && e && f);
    }

    // crash.log (Windows and Linux): crashes, fatal errors, graphics failures and runs that never closed.
    if (!quiet)
        CrashLog::start();
    // One copy at a time: two would share the same chat folder and vault files. A restarted copy waits here
    // until the copy that started it has saved and closed.
    auto *one = new SingleInstance(&app);
    if (!quiet && !one->claim(args.isSet(restarted))) {
        Platform::noStartLog();
        PerfCheck::notStarting();   // this copy draws nothing: no failed start to count
        return 0;
    }
    if (!quiet) {
        CrashLog::claimed();
        PerfCheck::noteStart();
    }
    // Only now the start log is written fresh and an update's leftovers are cleaned up (not by a
    // second copy that is about to quit).
    Platform::startClaimed(!quiet);
    Platform::startStep(QStringLiteral("single instance claimed"));
    // A voice or video note or a pasted picture left behind by a crash mid-recording.
    if (!quiet)
        Scratch::clearLeftovers();

    QDirIterator fonts(QStringLiteral(":/qt/qml/Huginn/fonts"), {QStringLiteral("*.ttf")}, QDir::Files);
    while (fonts.hasNext())
        QFontDatabase::addApplicationFont(fonts.next());
    QFont base(QStringLiteral("Barlow"));
    base.setPixelSize(17);
    QApplication::setFont(base);

    auto *settings = new Settings(&app);
    auto *system = new System(settings, args.value(tour), args.isSet(windowed) || quiet, &app);
    // Less motion and Battery saver: nothing moves by itself, the text cursor included (it stays lit instead of
    // blinking, so a focused text box doesn't redraw twice a second).
    {
        const int flash = QGuiApplication::styleHints()->cursorFlashTime();
        auto applyMotion = [settings, system, flash] {
            QGuiApplication::styleHints()->setCursorFlashTime(settings->reduceMotion() || system->batterySaving() ? 0 : flash);
        };
        applyMotion();
        QObject::connect(settings, &Settings::changed, &app, applyMotion);
        QObject::connect(system, &System::powerChanged, &app, applyMotion);
    }
    auto *gamepad = new Gamepad(&app);
    // Handheld mode's last fact: a controller there from the start (a handheld's own).
    system->noteControllerAtStart(gamepad->connected());
    auto *transfer = new Transfer(settings, &app);
    auto *localSend = new LocalSend(settings, &app);
    // Bluetooth first: Qt decides how to reach BlueZ the first time anything asks (see Ble).
    Chat *chat = nullptr;
    auto *ble = new Ble([&chat] {
        // Hidden from people nearby: no hello at all (no name, chat key, mailbox or hint), and Bluetooth stays silent
        // (Ble::hidden), so nobody can follow this computer from place to place.
        if (!chat || !chat->ready() || !chat->visibleNearby())
            return QByteArray();
        const QJsonObject o{{QStringLiteral("t"), QStringLiteral("hello")}, {QStringLiteral("name"), chat->username()},
                            {QStringLiteral("app"), QStringLiteral("huginn")}, {QStringLiteral("chatKey"), chat->chatKey()},
                            {QStringLiteral("mailbox"), chat->mailbox()}};
        return QJsonDocument(o).toJson(QJsonDocument::Compact);
    }, &app);
    auto *vault = new Vault(&app);
    if (quiet && !vault->exists())
        vault->setSecret(QStringLiteral("tour mode only"), false);   // the throwaway profile only: no lock to draw
    auto *updater = new Updater(settings, &app);
    // The internet inbox's key lives in the vault; empty while locked (the inbox still says "new message").
    auto *nostr = new Nostr([vault] {
        if (vault->locked())
            return QByteArray();
        // There but not readable: no inbox key this time, and never a new one over the one in that file.
        Vault::ReadStatus st = Vault::ReadFailed;
        const QByteArray raw = vault->read(QStringLiteral("secrets.json"), &st);
        QJsonParseError pe;
        const QJsonDocument doc = QJsonDocument::fromJson(raw, &pe);
        if (st == Vault::ReadFailed || (st == Vault::ReadOk && !doc.isObject()))
            return QByteArray();
        QJsonObject s = doc.object();
        QByteArray secret = QByteArray::fromHex(s.value(QStringLiteral("nostrSecret")).toString().toLatin1());
        if (secret.size() != 32) {
            secret = Schnorr::newSecret();
            s.insert(QStringLiteral("nostrSecret"), QString::fromLatin1(secret.toHex()));
            if (!vault->write(QStringLiteral("secrets.json"), QJsonDocument(s).toJson(QJsonDocument::Compact)))
                return QByteArray();   // not kept: not used either (the next start would make another)
        }
        return secret;
    }, &app);
    // The internet inbox's list of events already taken: in the vault too.
    nostr->keepSeen = [vault](const QByteArray &stored) {
        return !vault->locked() && vault->write(QStringLiteral("nostrseen.json"), stored);
    };
    QObject::connect(vault, &Vault::opened, nostr, [vault, nostr] { nostr->restoreSeen(vault->read(QStringLiteral("nostrseen.json"))); });
    if (!vault->locked())
        nostr->restoreSeen(vault->read(QStringLiteral("nostrseen.json")));
    chat = new Chat(localSend, vault, nostr, ble, &app);
    // Hidden from people nearby: Bluetooth is silent too (section 21.3).
    ble->hidden = [chat] { return !chat->visibleNearby(); };
    vault->beforeBackup = [chat] { chat->flushSaves(); chat->pruneNow(); };
    auto *voice = new Voice(chat, &app);
    auto *videos = new VideoMsg(chat, &app);
    // Calls: everything they send and receive goes through Chat's sealing and routes.
    auto *cs = new CallSignalHooks(&app);
    cs->onSend = [chat](const QString &k, const QJsonObject &m) { chat->sendCall(k, m); };
    cs->onSign = [chat](const QByteArray &b) { return chat->signWithChatKey(b); };
    cs->onMyKey = [chat] { return chat->chatKey(); };
    cs->onNameOf = [chat](const QString &k) { return chat->nameOf(k); };
    cs->onCanCall = [chat](const QString &k) { return chat->canCall(k); };
    cs->onLanHost = [chat](const QString &k) { return chat->lanHost(k); };
    cs->onWarmUp = [nostr](const QString &) { nostr->warmSenders(); };
    cs->onShowPreview = [chat] { return chat->showPreview(); };
    // Trusted for direct calls by default: anyone in a folder called "Family" (shared/docs/protocol.md 5.8.1).
    cs->onInFamily = [chat](const QString &k) {
        const QStringList in = chat->foldersOf(k);
        for (const QVariant &v : chat->folders()) {
            const QVariantMap f = v.toMap();
            if (in.contains(f.value(QStringLiteral("id")).toString()) && f.value(QStringLiteral("name")).toString().trimmed().compare(QLatin1String("Family"), Qt::CaseInsensitive) == 0)
                return true;
        }
        return false;
    };
    cs->onHasCap = [chat](const QString &k, const QString &c) { return chat->hasCap(k, c); };
    cs->onMayHave = [chat](const QString &k, const QString &c) { return chat->mayHave(k, c); };
    QObject::connect(chat, &Chat::callMessage, cs, &CallSignalHooks::deliver);
    auto *calls = new CallManager(cs, vault, &app);
    // Cap b1: the ring key lives in Huginn's own data folder but outside the vault (never an OS key store), so a call
    // rings even while Huginn is locked: only "Incoming call", no name, until it's unlocked.
    auto *ringKey = new RingKey(QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + QStringLiteral("/ring-key.json"));
    chat->setRingKey(ringKey);
    nostr->ringMailboxes = [ringKey] { return ringKey->mailboxes(); };
    nostr->ringSecrets = [ringKey] { return ringKey->nostrSecrets(); };
    // Erase everything (and Move, which erases this computer when it's done): the ring key file goes too.
    QObject::connect(vault, &Vault::erased, vault, [ringKey] { ringKey->forget(); });
    // Two days after a block renewed the ring key, the old ring mailbox is no longer read.
    auto *ringOld = new QTimer(&app);
    ringOld->setInterval(3600000);
    QObject::connect(ringOld, &QTimer::timeout, nostr, [ringKey, nostr] {
        if (ringKey->dropOld())
            nostr->resubscribe();
    });
    ringOld->start();
    nostr->onRing = [ringKey, calls](const QString &content) {
        QString call;
        qint64 ts = 0;
        if (ringKey->open(content, &call, &ts))
            calls->ringLocked(call, ts);
    };
    // Call history keeps as long as that chat keeps messages (7 days, up to 30 in Family and Work).
    QObject::connect(chat, &Chat::pruned, calls, [chat, calls] { calls->pruneHistory([chat](const QString &k) { return chat->keepCutoff(k); }); });
    // Voice rooms and group calls: a mesh of 1:1 calls over the same signaling and relay logins.
    auto *rooms = new RoomManager(cs, calls, [calls](auto done) { calls->getLogin(std::move(done)); }, &app);
    rooms->groupState = [chat](const QString &g) { return chat->groupState(g); };
    rooms->canRoom = [chat](const QString &k) { return chat->canRoom(k); };
    rooms->lacksRooms = [chat](const QString &k) { return chat->lacksRooms(k); };
    rooms->memberName = [chat](const QString &g, const QString &k) { return chat->groupMemberName(g, k); };
    calls->busyElsewhere = [rooms] { return rooms->inRoom(); };
    // Caps i1 and f1 and linked devices: introductions, files straight between trusted contacts
    // ("Direct calls" on for each other, as calls decide it) and a phone's companion.
    auto *trust = new Trust(chat, &app);
    auto *files = new FileLink(chat, &app);
    files->trusted = [calls](const QString &k) { return calls->trusted(k); };
    auto *linked = new Link(chat, &app);
    chat->setExtras(trust, files, linked);
    // Profile pictures and status (cap p1).
    auto *profile = new Profile(chat, vault, &app);
    chat->setProfile(profile);
    // Linked devices: a linked phone's name, picture and status are "you" on this screen while it's linked; on the
    // phone's side, my own go to every companion (and a companion's name or status change comes back here).
    linked->myProfile = [chat, profile] {
        QJsonObject me{{QStringLiteral("n"), chat->username()}, {QStringLiteral("st"), profile->myStatus()}};
        if (!profile->myCustomStatus().isEmpty())
            me.insert(QStringLiteral("cs"), profile->myCustomStatus());
        const QByteArray pic = profile->myPictureJpeg();
        if (!pic.isEmpty())
            me.insert(QStringLiteral("pic"), QString::fromLatin1(pic.toBase64()));
        return me;
    };
    linked->setMyProfile = [chat, profile](const QString &name, const QString &status, const QString &custom) {
        if (!name.isEmpty())
            chat->setUsername(name);
        if (!status.isEmpty())
            profile->setStatus(status);
        profile->setCustomStatus(custom);
    };
    auto phoneMe = [linked, profile] { profile->setPhoneMe(!linked->me().isEmpty(), linked->mePicture(), linked->meStatus()); };
    QObject::connect(linked, &Link::changed, profile, phoneMe);
    QObject::connect(profile, &Profile::changed, linked, [linked] { linked->sendMe(false); });
    // Labs mode: bug reports, and the channel back to Stable before the stable release is installed.
    auto *labs = new Labs(chat, vault, settings, &app);
    auto *appStorage = new AppStorage(updater, &app);
    auto *perf = new PerfCheck(settings, system, &app);
    // Never in a performance report: the names this computer and its person go by.
    perf->privateWords = [chat, settings, linked] {
        return QStringList{chat->username(), settings->deviceName(), Settings::defaultDeviceName(),
                           linked->me().value(QStringLiteral("name")).toString()};
    };
    chat->setLabs(labs);
    updater->beforeGoingBack = [labs] { return labs->beforeGoingBack(); };
    // Moving to another device (cap x1): on the Wi-Fi port, and locked while it moves.
    auto *mover = new Move(chat, vault, linked, localSend, &app);
    localSend->moveReceive = [mover](const QByteArray &body) { return mover->handle(body); };
    QObject::connect(vault, &Vault::panicked, rooms, &RoomManager::leave);
    // The raven call: for new messages, and once when something arrives while Chat is locked.
    auto *sound = new Chime(QStringLiteral(":/qt/qml/Huginn/assets/raven_message.wav"), &app);

    if (!quiet) {
        // Stay reachable while Huginn runs: the relays (reconnecting by themselves) and Bluetooth.
        // Locked: say something came, once per message (replaced by Chat once unlocked).
        // At most once every few minutes: retries and receipts would otherwise each raise one.
        nostr->onEnvelope = [chat, sound](const QString &) {
            static qint64 last = 0;
            const qint64 t = QDateTime::currentMSecsSinceEpoch();
            if (t - last > 5 * 60000) {
                last = t;
                if (!Settings::hushed() && Settings::messageSoundOn())
                    sound->play();
                Q_EMIT chat->notice(QStringLiteral("New message. Unlock Huginn to read it."));
            }
            return false;
        };
        // Unlocked (Chat has just started): introduce ourselves on Bluetooth links made while locked,
        // and ask the relays for the inbox again so messages that came while locked are read now.
        QObject::connect(vault, &Vault::opened, nostr, [nostr, ble] { ble->sayHello(); nostr->reconnectNow(); nostr->resubscribe(); });
        QObject::connect(nostr, &Nostr::publicKeyChanged, nostr, [nostr] { nostr->resubscribe(); });
        nostr->setKeepAlive(true);
        nostr->connectRelays();
        ble->start();
    }
    // A message arrived: the raven call, and a desktop notification unless that chat is on screen.
    QObject::connect(chat, &Chat::arrived, &app, [chat, sound, vault, linked](const QString &name, const QString &text, const QString &key) {
        const bool looking = QGuiApplication::applicationState() == Qt::ApplicationActive && chat->property("openChat").toString() == key;
        // A muted chat makes no sound; an archived one comes back to the list.
        // Do not disturb: no sound and no notification either.
        if (!chat->notifyFor(key) || looking || Settings::silenced())
            return;
        // Quiet hours and Settings › Notifications › Message sound: the notification still shows.
        if (!Settings::hushed() && Settings::messageSoundOn())
            sound->play();
        if (QGuiApplication::applicationState() != Qt::ApplicationActive) {
            // While a linked phone's chats are shown, this device's own sealed chats never show who or what.
            const bool preview = chat->showPreview() && !vault->screenLocked();
            Platform::notify(preview ? name : QStringLiteral("Huginn"), preview ? text : QStringLiteral("New message"));
        }
    });
    // Calls live in their chats: each one that ends goes into that chat's timeline. A missed one raises a
    // notification (unless Do not disturb is on), and a click on it opens the chat. Who called shows only
    // with Show preview on and Huginn unlocked.
    auto missedNote = [chat, vault](const QString &where, const QString &who, const QString &text) {
        if (Settings::silenced())
            return;
        const bool hide = vault->screenLocked() || !chat->showPreview();
        Platform::notify(hide ? QStringLiteral("Huginn") : QStringLiteral("Missed call"), hide ? QStringLiteral("Missed call") : text.isEmpty() ? who : text, 15000,
                         [chat, where] { Q_EMIT chat->showChat(where); });
    };
    QObject::connect(calls, &CallManager::logged, chat, [chat, calls, missedNote](const QString &key, bool out, const QString &result, int seconds) {
        const bool missed = result == QLatin1String("missed") || result == QLatin1String("internetOff");
        QString text;
        if (result == QLatin1String("internetOff"))
            text = QStringLiteral("Missed call · Internet calls are off");
        else if (missed)
            text = QStringLiteral("Missed call");
        else if (result == QLatin1String("answered"))
            text = (out ? QStringLiteral("Outgoing call") : QStringLiteral("Incoming call")) + (seconds > 0 ? QStringLiteral(" · ") + calls->duration(seconds) : QString());
        else if (out)
            text = result == QLatin1String("Declined") ? QStringLiteral("Call declined") : result == QLatin1String("Busy") ? QStringLiteral("They were busy") : QStringLiteral("No answer");
        else
            text = result == QLatin1String("Declined") ? QStringLiteral("You declined a call") : QStringLiteral("Incoming call");
        chat->noteCall(key, text, missed);
        if (missed)
            missedNote(key, chat->nameOf(key), QStringLiteral("From %1").arg(chat->nameOf(key)));
    });
    QObject::connect(rooms, &RoomManager::logged, chat, [chat, missedNote](const QString &g, const QString &text, bool missed) {
        chat->noteCall(QStringLiteral("g:") + g, text, missed);
        if (missed)
            missedNote(QStringLiteral("g:") + g, QString(), text);
    });
    auto *direct = new Direct(&app);
    // A linked phone's photos, voice and files come over the home Wi-Fi only, never over Direct connect's hotspot.
    linked->homeWifi = [direct] { return !direct->on(); };
    if (wifiSelf)
        localSend->start();
    if (!quiet) {
        direct->clearLeftovers();
        if (settings->nearbySharing())
            localSend->start();
        QObject::connect(settings, &Settings::changed, localSend, [settings, localSend] {
            if (settings->nearbySharing() != localSend->running())
                settings->nearbySharing() ? localSend->start() : localSend->stop();
        });
    }
    // Battery saver: background checks and Bluetooth scans further apart and the controller read less often while it's on.
    {
        auto relax = [system, chat, gamepad, localSend, ble] {
            const bool saving = system->batterySaving();
            chat->setRelaxed(saving);
            gamepad->setRelaxed(saving);
            localSend->setRelaxed(saving);
            ble->setRelaxed(saving);
        };
        relax();
        QObject::connect(system, &System::powerChanged, &app, relax);
        // A controller press or stick move is input too (keys, the mouse and touch System sees itself).
        QObject::connect(gamepad, &Gamepad::pressed, system, [system] { system->noteInput(); });
        QObject::connect(gamepad, &Gamepad::stickChanged, system, [system] { system->noteInput(); });
    }
    // Sending, receiving or a call keeps the handheld awake until it finishes.
    auto awake = [system, localSend, calls, rooms] {
        if (calls->inCall() || rooms->inRoom())
            system->setKeepAwake(true, QStringLiteral("In a call"));
        else
            system->setKeepAwake(localSend->busy(), localSend->sending() ? QStringLiteral("Sending files") : QStringLiteral("Receiving files"));
    };
    QObject::connect(localSend, &LocalSend::progressChanged, system, awake);
    QObject::connect(calls, &CallManager::changed, system, awake);
    QObject::connect(rooms, &RoomManager::changed, system, awake);
    // Push to talk (Settings › Calls): the key is watched only while Huginn is in front.
    rooms->setPushToTalk(settings->pushToTalk());
    QObject::connect(settings, &Settings::changed, rooms, [settings, rooms] { rooms->setPushToTalk(settings->pushToTalk()); });
    auto *talkKey = new TalkKey(settings, rooms, &app);

    qmlRegisterType<LiveList>("Huginn", 1, 0, "LiveList");
    qmlRegisterType<Fall>("Huginn", 1, 0, "Fall");
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Settings", settings);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "System", system);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Gamepad", gamepad);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Transfer", transfer);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Updater", updater);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "LocalSend", localSend);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Chat", chat);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Vault", vault);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Voice", voice);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Videos", videos);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Ble", ble);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Direct", direct);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Calls", calls);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Rooms", rooms);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Trust", trust);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "FileLink", files);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Linked", linked);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Mover", mover);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "TalkKey", talkKey);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Profile", profile);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Labs", labs);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "AppStorage", appStorage);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Perf", perf);

    // Closed cleanly once everything below (the window, the engine) has gone; a run that never gets here shows in crash.log.
    struct CleanExit {
        bool on;
        ~CleanExit() { if (on) CrashLog::cleanExit(); }
    } cleanExit{!quiet};
    QQmlApplicationEngine engine;
    engine.addImageProvider(QStringLiteral("qr"), new QrImageProvider);
    // The image providers run on Qt's loading threads: they read copies kept for them, never waiting on the main
    // thread (which could hang Huginn while it closes).
    engine.addImageProvider(QStringLiteral("thumb"), new ThumbProvider(vault->anyThreadReader()));
    engine.addImageProvider(QStringLiteral("profile"), new ProfileImageProvider(profile));
    engine.addImageProvider(QStringLiteral("ground"), new GroundProvider);
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed, &app, [] {
        Platform::startFailed(QStringLiteral("the main screen didn't load"));
        QCoreApplication::exit(2);
    }, Qt::QueuedConnection);
    // The lock is off: Chat opens now, with everything above listening.
    vault->openWithoutLock();
    Platform::startStep(QStringLiteral("loading the main screen"));
    engine.loadFromModule("Huginn", "Main");
    if (!engine.rootObjects().isEmpty()) {
        auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().constFirst());
        Platform::watchMainWindow(window);
        // The size that fits this screen, and what draws it (crash.log and Settings › About).
        system->watchWindow(window);
        // Labs › Performance check: its frames, and the first one shows tried settings started.
        perf->watch(window);
#ifndef Q_OS_WIN
        // Windows notes these itself (and starts again, winstart.cpp).
        if (window)
            QObject::connect(window, &QQuickWindow::sceneGraphError, window, [](QQuickWindow::SceneGraphError, const QString &msg) {
                CrashLog::note(QStringLiteral("graphics failed: ") + msg);
            });
#endif
        // Settings › Privacy › Block screenshots (Windows), set again whenever it's switched.
        if (window) {
            auto shield = std::make_shared<int>(-1);
            auto applyShield = [window, settings, shield] {
                const int on = settings->blockScreenshots() ? 1 : 0;
                if (on == *shield)
                    return;
                *shield = on;
                Platform::blockCapture(window, on == 1);
            };
            applyShield();
            QObject::connect(settings, &Settings::changed, window, applyShield);
        }
    }
    if (wifiSelf)
        WifiSelfTest::start(chat, localSend, sound);
    QObject::connect(updater, &Updater::aboutToRestart, chat, &Chat::flushSaves);
    QObject::connect(&app, &QCoreApplication::aboutToQuit, chat, &Chat::flushSaves);
    // Saved: from here on, a copy that finds this one stuck while closing may end it (SingleInstance::takeOverStaleLock).
    if (!quiet)
        QObject::connect(&app, &QCoreApplication::aboutToQuit, one, &SingleInstance::markClosing);
    if (!quiet)
        updater->startAutoUpdate();
    return app.exec();
}
