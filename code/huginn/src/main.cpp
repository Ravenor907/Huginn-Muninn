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
#include "nostr.h"
#include "photos.h"
#include "schnorr.h"
#include "vault.h"
#include "voice.h"
#include "platform/chime.h"
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
#include "livelist.h"
#include "updater.h"
#include "labs.h"
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
#include <QCommandLineParser>
#include <QTimer>
#include <QDateTime>
#include <QDir>
#include <QDirIterator>
#include <QFile>
#include <QFileInfo>
#include <QFont>
#include <QFontDatabase>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickWindow>
#include <QStandardPaths>
#include <QUrl>
#include <QVariantMap>

#ifndef HUGINN_VERSION
#define HUGINN_VERSION "0.1"
#endif
// A pre-release build's label ("0.99 beta 12"); a stable build's is its version.
#ifndef HUGINN_VERSION_LABEL
#define HUGINN_VERSION_LABEL HUGINN_VERSION
#endif

namespace
{
QtMessageHandler s_previous = nullptr;
// Screenshot tour only: each counted warning is also written here (Windows builds have no console to show them).
QString s_warningFile;

// Counts QML warnings so the CI screenshot tour can fail the build on any of them.
void messages(QtMsgType type, const QMessageLogContext &ctx, const QString &msg)
{
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
    if (s_previous)
        s_previous(type, ctx, msg);
}
}

int main(int argc, char *argv[])
{
    // Windows: a start log next to Huginn.exe, cleanup after an update, settings that work under Wine.
    Platform::beginStart();
    s_previous = qInstallMessageHandler(messages);
    QApplication app(argc, argv);
    QApplication::setApplicationName(QStringLiteral("Huginn"));
    QApplication::setOrganizationName(QStringLiteral("Huginn"));
    QApplication::setApplicationVersion(QStringLiteral(HUGINN_VERSION_LABEL));
    QApplication::setDesktopFileName(QStringLiteral("io.github.ravenor907.Huginn"));
    QQuickWindow::setTextRenderType(QQuickWindow::NativeTextRendering);
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
    QCommandLineOption privacyTest(QStringLiteral("privacy-selftest"), QStringLiteral("Check the vault, photo scrubbing and Bluetooth framing (for CI), then quit."));
    args.addOption(privacyTest);
    QCommandLineOption backupTest(QStringLiteral("backup-selftest"), QStringLiteral("Back up and restore a temporary chat folder (for CI), then quit."));
    args.addOption(backupTest);
    QCommandLineOption callTest(QStringLiteral("call-selftest"), QStringLiteral("Check calls against the shared test vectors and an in-process loopback (for CI), then quit."), QStringLiteral("FILE"));
    args.addOption(callTest);
    QCommandLineOption wifiTest(QStringLiteral("wifi-selftest"), QStringLiteral("Run on a throwaway profile, receive an encrypted message from a pretend Muninn over the local HTTPS endpoint, raise the call and a notification (for CI), then quit."));
    args.addOption(wifiTest);
    QCommandLineOption releaseSigTest(QStringLiteral("release-sig-selftest"), QStringLiteral("Check the update signature check against the shared test vectors (for CI), then quit."), QStringLiteral("FILE"));
    args.addOption(releaseSigTest);
    args.process(app);
    if (args.isSet(tour)) {
        QDir().mkpath(args.value(tour));
        s_warningFile = QDir(args.value(tour)).filePath(QStringLiteral("warnings.txt"));
    }
    const bool wifiSelf = args.isSet(wifiTest);
    if (wifiSelf)
        WifiSelfTest::isolate();
    // The screenshot tour and the Wi-Fi self-test stay off the internet and don't claim the single instance.
    const bool quiet = args.isSet(tour) || wifiSelf;
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
    if (args.isSet(privacyTest)) {
        const bool a = Vault::selfTest(), b = Photos::selfTest(), c = BleFrames::selfTest();
        return selfTested(a && b && c);
    }

    // One copy at a time: two would share the same chat folder and vault files.
    auto *one = new SingleInstance(&app);
    if (!quiet && !one->claim()) {
        Platform::noStartLog();
        return 0;
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
    auto *gamepad = new Gamepad(&app);
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
        vault->setSecret(QStringLiteral("tour mode only"), false);   // CI screenshots: no lock to draw
    auto *updater = new Updater(settings, &app);
    // The internet inbox's key lives in the vault; empty while locked (the inbox still says "new message").
    auto *nostr = new Nostr([vault] {
        if (vault->locked())
            return QByteArray();
        QJsonObject s = QJsonDocument::fromJson(vault->read(QStringLiteral("secrets.json"))).object();
        QByteArray secret = QByteArray::fromHex(s.value(QStringLiteral("nostrSecret")).toString().toLatin1());
        if (secret.size() != 32) {
            secret = Schnorr::newSecret();
            s.insert(QStringLiteral("nostrSecret"), QString::fromLatin1(secret.toHex()));
            vault->write(QStringLiteral("secrets.json"), QJsonDocument(s).toJson(QJsonDocument::Compact));
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
    vault->beforeBackup = [chat] { chat->pruneNow(); };
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
    // Trusted for direct calls by default: anyone in a folder called "Family" (shared/docs/protocol-0.6.md 5.8.1).
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
    // Labs mode: bug reports, and what goes back to plain settings before an older stable release is installed.
    auto *labs = new Labs(chat, vault, settings, &app);
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
                if (!Settings::silenced())
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
        // Wine and Proton have no Bluetooth of their own to offer Qt.
        if (!Platform::underWine())
            ble->start();
    }
    // A message arrived: the raven call, and a desktop notification unless that chat is on screen.
    QObject::connect(chat, &Chat::arrived, &app, [chat, sound, vault](const QString &name, const QString &text, const QString &key) {
        const bool looking = QGuiApplication::applicationState() == Qt::ApplicationActive && chat->property("openChat").toString() == key;
        // A muted chat makes no sound; an archived one comes back to the list.
        // Do not disturb: no sound and no notification either.
        if (!chat->notifyFor(key) || looking || Settings::silenced())
            return;
        sound->play();
        if (QGuiApplication::applicationState() != Qt::ApplicationActive) {
            const bool preview = chat->showPreview() && !vault->screenLocked();
            Platform::notify(preview ? name : QStringLiteral("Huginn"), preview ? text : QStringLiteral("New message"));
        }
    });
    // Calls live in their chats: each one that ends goes into that chat's timeline. A missed one raises a
    // notification (unless Do not disturb is on), and a click on it opens the chat.
    auto missedNote = [chat, vault](const QString &where, const QString &who, const QString &text) {
        if (Settings::silenced())
            return;
        const bool hide = vault->screenLocked();
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
    if (wifiSelf)
        localSend->start();
    if (!quiet) {
        if (settings->nearbySharing())
            localSend->start();
        QObject::connect(settings, &Settings::changed, localSend, [settings, localSend] {
            if (settings->nearbySharing() != localSend->running())
                settings->nearbySharing() ? localSend->start() : localSend->stop();
        });
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

    QQmlApplicationEngine engine;
    engine.addImageProvider(QStringLiteral("qr"), new QrImageProvider);
    engine.addImageProvider(QStringLiteral("thumb"), new ThumbProvider(chat));
    engine.addImageProvider(QStringLiteral("profile"), new ProfileImageProvider(profile));
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed, &app, [] {
        Platform::startFailed(QStringLiteral("the main screen didn't load"));
        QCoreApplication::exit(2);
    }, Qt::QueuedConnection);
    // The lock is off: Chat opens now, with everything above listening.
    vault->openWithoutLock();
    Platform::startStep(QStringLiteral("loading the main screen"));
    engine.loadFromModule("Huginn", "Main");
    if (!engine.rootObjects().isEmpty())
        Platform::watchMainWindow(qobject_cast<QQuickWindow *>(engine.rootObjects().constFirst()));
    if (wifiSelf)
        WifiSelfTest::start(chat, localSend, sound);
    if (!quiet)
        updater->startAutoUpdate();
    return app.exec();
}
