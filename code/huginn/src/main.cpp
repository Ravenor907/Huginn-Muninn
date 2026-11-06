#include "gamepad.h"
#include "localsend.h"
#include "chat.h"
#include "direct.h"
#include "chatcrypto.h"
#include "chatwire.h"
#include "call/callmanager.h"
#include "call/callselftest.h"
#include "call/callsession.h"
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
#include "platform/portable.h"
#include "platform/scratch.h"
#include "platform/singleinstance.h"
#include "platform/winstart.h"
#include "platform/winupdate.h"
#include <cstring>
#include <QNetworkInformation>
#include <QJsonDocument>
#include <QJsonObject>
#include "settings.h"
#include "talkkey.h"
#include "globalkeys.h"
#include "system.h"
#include "transfer.h"
#include "fall.h"
#include "icons.h"
#include "livelist.h"
#include "updater.h"
#include "ultraprivacy.h"
#include "labs.h"
#include "appstorage.h"
#include "perfcheck.h"
#include "wifiselftest.h"
#include "readaloud.h"
#include "dailyverse.h"
#include "sharing.h"
#include "proof.h"
#include "move.h"
#include "morewire.h"
#include "linkpreview.h"
#include "videomsg.h"
#include "thumbs.h"
#include "msglimits.h"
#include "moderation.h"
#include "modwire.h"
#include "richwire.h"
#include "filelink.h"
#include "link.h"
#include "trust.h"
#include "profile.h"
#include "ring.h"
#include "savedmedia.h"

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
#include <QMessageBox>
#include <QPushButton>
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
    // Windows: a start log and cleanup after an update.
    Platform::beginStart();
    // The Windows portable build keeps everything in the data folder beside Huginn.exe (settings as an INI file there,
    // never the registry): set before anything opens a setting or a folder.
    Portable::setUp();
    // Round 17: a staged update asked by the copy that unpacked it whether it may save where it must go (Windows'
    // Controlled folder access allows programs by path): it tries, answers with its exit code and ends. Nothing else runs.
    for (int i = 1; i < argc; ++i)
        if (std::strcmp(argv[i], "--update-probe") == 0)
            return Portable::canWriteForUpdate() ? WinUpdate::kProbeOk : WinUpdate::kProbeBlocked;
    s_previous = qInstallMessageHandler(messages);
    // Labs › Performance check: settings being tried (display mode, drawing, render loop) go in before Qt starts.
    PerfCheck::applyAtStart(argc, argv);
#ifndef Q_OS_WIN
    // Game mode (gamescope): gamescope shows games through Xwayland, and Huginn takes the same path as every game
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
    QCommandLineOption perfTest(QStringLiteral("perf-selftest"), QStringLiteral("On a throwaway profile: events through the relay thread and back, a shutdown that never hangs, saves in order on the saving thread, a flush that waits for them, list rows changed one at a time, icons drawn once, Seasonal leaves in one batch (for CI), then quit."));
    args.addOption(perfTest);
    // Started by a copy that is closing (an update, a restore): wait for it to end before starting.
    QCommandLineOption restarted(QStringLiteral("restarted"), QStringLiteral("Wait for the copy that started this one to close."));
    restarted.setFlags(QCommandLineOption::HiddenFromHelp);
    args.addOption(restarted);
    QCommandLineOption releaseSigTest(QStringLiteral("release-sig-selftest"), QStringLiteral("Check the update signature check against the shared test vectors (for CI), then quit."), QStringLiteral("FILE"));
    args.addOption(releaseSigTest);
    QCommandLineOption readAloudTest(QStringLiteral("readaloud-selftest"), QStringLiteral("Check Read aloud's plain text, sentences, voice hashes and safe unpacking, and that the bundled speech engine loads (for CI), then quit."));
    args.addOption(readAloudTest);
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
    if (args.isSet(callTest)) {
        // Cap g2: the per-person volume is clamped and applied to decoded sound the same way as Muninn (g2-vectors.json).
        const bool callsOk = CallSelfTest::run(args.value(callTest));
        const bool volume = ModWire::volumeSelfTest(QFileInfo(args.value(callTest)).absoluteDir().filePath(QStringLiteral("g2-vectors.json")));
        return selfTested(callsOk && volume);
    }
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
        // Group timeouts and bans (g2): signed records, forged ones refused, expiry, volume.
        const bool m = Moderation::selfTest(QFileInfo(args.value(chatTest)).absoluteDir().filePath(QStringLiteral("g2-vectors.json")));
        // File sharing (f2): routes, group file rules, safe folder and file names.
        const bool n = Sharing::selfTest();
        // Backups made by the reference as Huginn and as Muninn (shared/docs/backup.md).
        const bool o = Vault::backupVectorTest(QFileInfo(args.value(chatTest)).absoluteDir().filePath(QStringLiteral("backup-vectors.json")));
        return selfTested(a && b && c && d && e && f && g && h && i && j && k && l && m && n && o);
    }
    if (args.isSet(releaseSigTest))
        return selfTested(Updater::selfTest(args.value(releaseSigTest)));
    if (args.isSet(readAloudTest))
        return selfTested(ReadAloud::selfTest());
    if (args.isSet(backupTest))
        return selfTested(Vault::backupSelfTest());
    if (args.isSet(ratchetTest))
        return selfTested(Ratchet::selfTest(args.value(ratchetTest)));
    if (args.isSet(nostrTest))
        return selfTested(Nostr::selfTest(args.value(nostrTest)));
    if (args.isSet(stabilityTest)) {
        WifiSelfTest::isolate();
        const bool a = Vault::stabilitySelfTest(), b = SingleInstance::selfTest(), c = Chat::keySelfTest();
        // Everything in the data folder beside Huginn.exe (Windows): its place, moving old data in, Erase everything.
        const bool e = Portable::selfTest();
        // crash.log lines never carry the home folder (the account name) or a network address.
        const QString home = QDir::homePath();
        const QString scrubbed = CrashLog::scrub(QStringLiteral("at 192.168.1.20 and fe80::1 in ") + home + QStringLiteral("/x QQuickWindow::sceneGraphError"));
        const bool d = !scrubbed.contains(QLatin1String("192.168")) && !scrubbed.contains(QLatin1String("fe80"))
            && (home.size() <= 1 || !scrubbed.contains(home)) && scrubbed.contains(QLatin1String("QQuickWindow::sceneGraphError"));
        std::printf("crash log keeps out addresses and the home folder: %s\n", d ? "ok" : "FAILED");
        // 1.42 (cap h1): saved addresses asked only with a pin, and when the hidden certificate changes.
        const bool h = LocalSend::hiddenSelfTest();
        std::printf("hidden certificates: pins and when they change: %s\n", h ? "ok" : "FAILED");
        return selfTested(a && b && c && d && e && h);
    }
    if (args.isSet(perfTest)) {
        WifiSelfTest::isolate();
        const bool a = Nostr::threadSelfTest(), b = Vault::saveQueueSelfTest();
        // Phase 3: list rows changed one at a time, icons drawn once and shared, Seasonal in one batch.
        const bool c = LiveList::selfTest(), d = IconProvider::selfTest(), e = Fall::selfTest();
        return selfTested(a && b && c && d && e);
    }
    if (args.isSet(privacyTest)) {
        const bool a = Vault::selfTest(), b = Photos::selfTest(), c = BleFrames::selfTest();
        // Labs › App storage never reaches the vault, keys or chats.
        const bool d = AppStorage::selfTest();
        // Labs › Performance check's report never carries names, addresses, ids or file names.
        const bool e = PerfCheck::selfTest();
        // Handheld mode's detection: the known models, the two-of-three rule, a laptop with a pad.
        const bool f = System::handheldSelfTest();
        // One controller seen as two devices taps once (round 15, R9).
        const bool g = TapTwins::selfTest();
        // A voice message held on Y goes only to the chat it started in, never across a sleep (round 17).
        const bool h = Voice::selfTest();
        // Daily Havamal: the shared verses, the verse of the day as Muninn picks it, an import, off by default.
        const bool i = DailyVerse::selfTest();
        // Ultra privacy (1.42): the switch gates each feature, Tor's proxy goes on the sockets, names go to Tor.
        const bool ultra = UltraPrivacy::selfTest();
        // Saved media (1.42): the picker's list and its limits.
        const bool sm = SavedMedia::selfTest();
        return selfTested(a && b && c && d && e && f && g && h && i && ultra && sm);
    }

    // Said on screen when Huginn can't keep its data where it is (Windows: a folder it can't write, like Program
    // Files): it never falls back to somewhere else.
    auto stopWith = [](const QString &text) {
        // Round 17: the staged update that can't go on (Controlled folder access blocking it, say): it gives up, Huginn
        // starts again from its own folder and says why there, so a failed update never leaves nothing running.
        if (WinUpdate::giveUpStaged(text)) {
            Platform::noStartLog();
            PerfCheck::notStarting();
            return 1;
        }
        QMessageBox::warning(nullptr, QStringLiteral("Huginn"), text);
        Platform::noStartLog();
        PerfCheck::notStarting();   // nothing was drawn: no failed start to count
        return 1;
    };
    // Windows: unzipped inside the old Huginn folder (Huginn\Huginn\Huginn.exe), this copy would start with no chats
    // as if they were lost: asked first, before anything is made here.
    if (!quiet && !args.isSet(restarted)) {
        const QString older = Portable::nestedCopy();
        if (!older.isEmpty()) {
            QMessageBox box(QMessageBox::Warning, QStringLiteral("Huginn"),
                            QStringLiteral("This Huginn was unzipped inside another Huginn folder (%1), which has your chats. This copy "
                                           "would start with none.\n\nClose this one and open Huginn.exe in %1 instead, or move this "
                                           "folder's files up into it to update it.")
                                .arg(older));
            QPushButton *close = box.addButton(QStringLiteral("Close"), QMessageBox::AcceptRole);
            box.addButton(QStringLiteral("Start fresh here"), QMessageBox::DestructiveRole);
            box.setDefaultButton(close);
            box.exec();
            if (box.clickedButton() == close) {
                Platform::noStartLog();
                PerfCheck::notStarting();
                return 0;
            }
        }
    }
    if (!quiet) {
        const QString problem = Portable::folderProblem();
        if (!problem.isEmpty())
            return stopWith(problem);
    }
    // crash.log (Windows and Linux): crashes, fatal errors, graphics failures and runs that never closed.
    if (!quiet)
        CrashLog::start();
    // Windows: a Huginn from before the data folder keeps its lock in AppData. One that is closing (it started this
    // copy to update) is waited for; one still open stays the only copy.
    if (!quiet && !Portable::oldCopyClosed(args.isSet(restarted) ? SingleInstance::kRestartWaitMs : 1000))
        return stopWith(QStringLiteral("An older Huginn is still open. Close it, then open Huginn again."));
    // One copy at a time: two would share the same chat folder and vault files. A restarted copy waits here
    // until the copy that started it has saved and closed.
    auto *one = new SingleInstance(&app);
    if (!quiet && !one->claim(args.isSet(restarted))) {
        // A copy that answered came to the front: nothing to say. One that holds the lock without answering (hung, or
        // open on another computer or account using this folder), or a data folder that can't be written (a full
        // disk, read-only): said on screen, never a silent quit.
        const QString why = one->problemText();
        // Round 17: another computer's lock (a synced folder) is never taken by itself. Close is the default; Open here
        // anyway is for a copy that crashed there, or a drive moved here.
        bool tookAnyway = false;
        if (!why.isEmpty() && !one->otherComputer().isEmpty()) {
            QMessageBox box(QMessageBox::Warning, QStringLiteral("Huginn"), why);
            QPushButton *close = box.addButton(QStringLiteral("Close"), QMessageBox::AcceptRole);
            QPushButton *anyway = box.addButton(QStringLiteral("Open here anyway"), QMessageBox::DestructiveRole);
            box.setDefaultButton(close);
            box.exec();
            if (box.clickedButton() == anyway) {
                tookAnyway = one->takeAnyway();
                if (!tookAnyway)
                    return stopWith(QStringLiteral("Huginn still couldn't open its folder. Close Huginn on the other computer, then try again."));
            } else {
                Platform::noStartLog();
                PerfCheck::notStarting();
                return 0;
            }
        }
        if (!tookAnyway) {
            if (!why.isEmpty())
                return stopWith(why);
            Platform::noStartLog();
            PerfCheck::notStarting();   // this copy draws nothing: no failed start to count
            return 0;
        }
    }
    // Windows: an update staged beside Huginn.exe is finished by the staged Huginn.exe itself, now that the copy before
    // it has closed; it then starts Huginn from its own folder and this copy ends (WinUpdate::finishAtStart).
    if (!quiet && WinUpdate::finishAtStart()) {
        Platform::noStartLog();
        PerfCheck::notStarting();
        return 0;
    }
    // Windows, first start of this version: Huginn's AppData folders and registry settings move into the data folder.
    // Nothing starts on an empty folder while the old data is still elsewhere.
    QString openFolderNote;
    if (!quiet) {
        QString why;
        if (!Portable::moveOldData(&why))
            return stopWith(why);
        // Windows: the data folder is this account's only, and one inside OneDrive is kept on this computer and said once.
        // One that can't be private is said once too, once the vault shows whether a lock already seals it.
        openFolderNote = Portable::makePrivate();
        const QString synced = Portable::syncedWarning();
        if (!synced.isEmpty())
            QMessageBox::warning(nullptr, QStringLiteral("Huginn"), synced);
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
    // Windows: a data folder other accounts can read, and no lock sealing it: nudged toward a lock once.
    if (!openFolderNote.isEmpty() && vault->exists() && !vault->hasLock()) {
        QMessageBox::information(nullptr, QStringLiteral("Huginn"), openFolderNote);
        Portable::openWarned();
    }
    if (quiet && !vault->exists())
        vault->setSecret(QStringLiteral("tour mode only"), false);   // the throwaway profile only: no lock to draw
    auto *updater = new Updater(settings, &app);
    auto *dailyVerse = new DailyVerse(vault, &app);
    updater->saving = [system] { return system->batterySaving(); };   // Battery saver: timed update checks every 12 hours
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
    // The inbox key is read from the vault once and kept in memory until the vault locks or is erased.
    // Ultra privacy: Tor starts now when it's on (never on the throwaway profile of the tour and Wi-Fi self-test). On,
    // off, or Tor ready or lost: every relay socket made the old way goes, and they connect again the new way.
    auto *ultra = new UltraPrivacy(&app);
    QObject::connect(ultra, &UltraPrivacy::redial, nostr, [nostr] { nostr->redial(); });
    // Round 19 (FIX 5): an update check or download under way never goes on the old way: it stops, and comes again
    // in a minute the new way.
    QObject::connect(ultra, &UltraPrivacy::redial, updater, [updater] { updater->redial(); });
    // Round 19: Erase everything stops Tor first and waits for it, so its folder can be deleted.
    QObject::connect(vault, &Vault::aboutToErase, ultra, [ultra] { ultra->stopNow(); });
    if (!quiet)
        ultra->start();
    QObject::connect(vault, &Vault::erased, nostr, [nostr] { nostr->forgetSecret(); });
    QObject::connect(vault, &Vault::changed, nostr, [vault, nostr] {
        if (vault->locked())
            nostr->forgetSecret();
    });
    // The internet inbox's list of events already taken: in the vault too.
    nostr->keepSeen = [vault](const QByteArray &stored) {
        return !vault->locked() && vault->write(QStringLiteral("nostrseen.json"), stored);
    };
    // Made and written on the saving thread (the list can be large).
    nostr->keepSeenLater = [vault](std::function<QByteArray()> make) {
        if (!vault->locked())
            vault->writeLater(QStringLiteral("nostrseen.json"), std::move(make));
    };
    QObject::connect(vault, &Vault::opened, nostr, [vault, nostr] { nostr->restoreSeen(vault->read(QStringLiteral("nostrseen.json"))); });
    // Round 18 (m2): the relay cursors wait while the vault holds its saves (sealing for a new lock), and go once it's done.
    nostr->holdCursors = [vault] { return vault->sealing(); };
    QObject::connect(vault, &Vault::changed, nostr, [vault, nostr] {
        if (!vault->sealing())
            nostr->releaseCursors();
    });
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
    cs->onShowPreview = [chat] { return chat->showName(); };   // Settings › Notifications › Show: Name only names the caller too
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
    // Labs › Performance check: a call going on at the end of a check.
    Busy::addStatus(calls, [calls] { return QStringList{QStringLiteral("calls: ") + (calls->inCall() ? calls->state() : QStringLiteral("none"))}; });
    // Cap b1: the ring key lives in Huginn's own data folder but outside the vault (never an OS key store), so a call
    // rings even while Huginn is locked: only "Incoming call", no name, until it's unlocked.
    auto *ringKey = new RingKey(System::dataDir() + QStringLiteral("/ring-key.json"));
    chat->setRingKey(ringKey);
    // Cap n2: the nudge key, kept the same way, goes to contacts and group peers so they can nudge. Its mailbox is read
    // on the ring mailbox's request, but only the ring key ever opens a ring: a ring sealed to the nudge key never rings.
    auto *nudgeKey = new NudgeKey(System::dataDir() + QStringLiteral("/nudge-key.json"));
    chat->setNudgeKey(nudgeKey);
    // Cap r2 (1.42): the ring tag each contact was given is in the vault; beside ring-key.json only the hashes of the
    // ones still allowed, so a locked Huginn tells callers apart and a taken-back tag never rings.
    auto *ringTags = new RingTags(System::dataDir() + QStringLiteral("/ring-allow.json"), [vault] { return vault->read(QStringLiteral("ring-tags.json")); },
                                  [vault](const QByteArray &b) { vault->write(QStringLiteral("ring-tags.json"), b); });
    chat->setRingTags(ringTags);
    nostr->ringMailboxes = [ringKey, nudgeKey] { return ringKey->mailboxes() + nudgeKey->mailboxes(); };
    nostr->ringSecrets = [ringKey, nudgeKey] { return ringKey->nostrSecrets() + nudgeKey->nostrSecrets(); };
    // Erase everything (and Move, which erases this computer when it's done): the ring and nudge key files go too.
    QObject::connect(vault, &Vault::erased, vault, [ringKey, nudgeKey, ringTags] {
        ringKey->forget();
        nudgeKey->forget();
        ringTags->forget();
    });
    // Two days after a block renewed the ring and nudge keys, the old mailboxes are no longer read.
    auto *ringOld = new QTimer(&app);
    ringOld->setInterval(3600000);
    // Round 17 (security M7): a copy of ring-key.json or nudge-key.json taken once stops working within a week: both are
    // made new every RingKey::kRenewMs (checked hourly and at each unlock; only while unlocked, as they go out sealed)
    // and whenever the lock is set, changed or turned off, and announced as after a block.
    auto renewIfDue = [ringKey, chat] {
        const qint64 made = ringKey->madeAt(), now = QDateTime::currentMSecsSinceEpoch();
        if (made > 0 && now - made >= RingKey::kRenewMs && chat->renewRingKeys())
            CrashLog::note(QStringLiteral("ring: the ring and nudge keys were renewed on schedule"));
    };
    QObject::connect(ringOld, &QTimer::timeout, nostr, [ringKey, nudgeKey, nostr, renewIfDue] {
        const bool ringGone = ringKey->dropOld();
        const bool nudgeGone = nudgeKey->dropOld();
        if (ringGone || nudgeGone)
            nostr->resubscribe();
        renewIfDue();
    });
    ringOld->start();
    QObject::connect(vault, &Vault::opened, chat, [renewIfDue] { QTimer::singleShot(5000, qApp, renewIfDue); });
    QObject::connect(vault, &Vault::lockChanged, chat, [chat] {
        // Chat may still be starting (the first lock is set before it opens): then the scheduled check catches it.
        if (chat->renewRingKeys())
            CrashLog::note(QStringLiteral("ring: the ring and nudge keys were renewed after a lock change"));
    });
    // Caps n1 and n2: a nudge while the vault is locked says a real message came (lockedNudge, set below once the
    // message sound is made). Unlocked, a nudge is ignored: the message itself notifies.
    auto lockedNudge = std::make_shared<std::function<void(const RingWire::Nudge &)>>();
    nostr->onRing = [ringKey, nudgeKey, calls, vault, lockedNudge, ringTags](const QString &content, bool live) {
        QString call, rt;
        qint64 ts = 0;
        bool old = false;
        if (ringKey->open(content, &call, &ts, &rt, &old)) {
            // Cap r2 (1.42): a tag taken back (blocked, calls off, removed) never rings; a replaced ring key rings only
            // with a tag still allowed (a ring without one, on a replaced key, may be from whoever was blocked).
            const RingTags::Who who = ringTags->judge(rt);
            if (who == RingTags::TagAllowed || (who == RingTags::NoTag && !old))
                calls->ringLocked(call, ts, live, rt.isEmpty() ? QString() : RingWire::tagHash(rt));
            return;
        }
        // Round 14: the ring key only contacts hold, so its nudge may name them; the nudge key may be any group peer's,
        // so its nudge names only the group. Older than 2 days (a replay): dropped.
        RingWire::Nudge n;
        if (!vault->locked() || !*lockedNudge)
            return;
        const qint64 at = QDateTime::currentMSecsSinceEpoch();
        if (ringKey->nudge(content, &n)) {
            if (RingWire::nudgeFresh(n, at))
                (*lockedNudge)(n);
        } else if (nudgeKey->nudge(content, &n)) {
            if (RingWire::nudgeFresh(n, at))
                (*lockedNudge)(RingWire::anonymous(n));
        }
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
    // Mute for me, volume, group timeouts and bans (cap g2).
    auto *mod = new Moderation(chat, &app);
    chat->setModeration(mod);
    CallSession::gainFor = [mod](const QString &k, bool room) { return mod->gainFor(k, room); };
    rooms->barred = [mod](const QString &g, const QString &k) { return mod->barred(g, k); };
    rooms->joinBlock = [mod](const QString &g) { return mod->postBlock(g); };
    // A muted chat: its calls ring silently (shown, without a sound or a notification). A muted group's call is a missed-call line.
    calls->hushedFor = [chat](const QString &k) { return chat->isMuted(k) && !chat->isRightAway(k); };
    rooms->hushedFor = [chat](const QString &g) {
        const QString k = QStringLiteral("g:") + g;
        return chat->isMuted(k) && !chat->isRightAway(k);
    };
    // Caps i1 and f1 and linked devices: introductions, files straight between trusted contacts
    // ("Direct calls" on for each other, as calls decide it) and a phone's companion.
    auto *trust = new Trust(chat, &app);
    auto *files = new FileLink(chat, &app);
    files->trusted = [calls](const QString &k) { return calls->trusted(k); };
    files->relayLogin = [calls](std::function<void(const TurnLogin &, const QString &)> done) { calls->getLogin(std::move(done)); };
    files->relayMeter = [calls](qint64 mb, std::function<void(qint64)> done) { calls->relayMeter(mb, std::move(done)); };
    auto *linked = new Link(chat, &app);
    chat->setExtras(trust, files, linked);
    // Round 19 (FIX 4, owner decision on calls): Ultra privacy turned on stops whatever already goes straight to someone:
    // the call going on ends (calls are off while it's on), a voice room is left and its ring declined, and direct file
    // and video offers go by relay or are cancelled. Later accepts are checked again where they happen.
    QObject::connect(ultra, &UltraPrivacy::turnedOn, calls, [calls, rooms, files] {
        calls->ultraOn();
        if (rooms->ringing())
            rooms->declineRing();
        if (rooms->inRoom()) {
            rooms->leave();
            Q_EMIT calls->finished(UltraPrivacy::callsOffText());
        }
        files->ultraOn();
    });
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
    // Cap l1: the phone is the whole account here: its picture becomes this device's own, its contacts' pictures show.
    linked->setMyPicture = [profile](const QByteArray &jpeg) {
        const QImage img = jpeg.isEmpty() ? QImage() : QImage::fromData(jpeg, "JPEG");
        if (img.isNull())
            profile->clearMyPicture();
        else
            profile->setMyPicture(img);
    };
    QObject::connect(linked, &Link::picturesChanged, profile, [linked, profile] { profile->setLinkedPictures(linked->contactPictures()); });
    // Cap p2: Refresh on a person's page while the phone is the account here: the phone asks them.
    profile->askPhone = [linked](const QString &key) { return linked->askPicture(key); };
    // The phone lists this computer by its device name (Settings › Linked devices), kept up to date by "ping".
    linked->deviceName = [settings] { return settings->deviceName(); };
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
    // Backups carry the shared form, so one made in Muninn restores here (shared/docs/backup.md).
    vault->sharedForm = [mover] { return mover->sharedForm(); };
    vault->fromSharedForm = [mover](const QByteArray &form, QString *error) { return mover->fromSharedForm(form, error); };
    vault->deviceName = [settings] { return settings->deviceName(); };
    localSend->moveReceive = [mover](const QByteArray &body) { return mover->handle(body); };
    QObject::connect(vault, &Vault::panicked, rooms, &RoomManager::leave);
    // The raven call: for new messages, and once when something arrives while Chat is locked.
    auto *sound = new Chime(QStringLiteral(":/qt/qml/Huginn/assets/raven_message.wav"), &app);

    // Caps n1 and n2: a nudge while locked. What it shows follows Settings > Notifications > Show: who and what kind
    // ("Photo"), never the message (it can't be opened while locked); nothing shows "Huginn", "New message". One per
    // sender and chat, counting up as more come; the sound only for the first. Do not disturb and quiet hours as for
    // a message. The counts start over at the next unlock.
    auto lockedCounts = std::make_shared<QHash<QString, int>>();
    // Round 14: nudges naming nobody (the nudge key) fold into at most 3 notifications and sound once per 2 minutes.
    auto lockedLimit = std::make_shared<LockedNudgeLimit>();
    // Round 14: "New activity" once per lock, only while someone here is known to be on an app that can't nudge.
    auto activityShown = std::make_shared<bool>(false);
    QObject::connect(vault, &Vault::changed, &app, [vault, lockedCounts, lockedLimit, activityShown] {
        if (!vault->locked()) {
            lockedCounts->clear();
            lockedLimit->clear();
            *activityShown = false;
        }
    });
    // Whether anyone here can't nudge, kept outside the vault (checked every 2 minutes while unlocked), so a locked
    // Huginn started fresh still knows.
    auto *cantNudgeCheck = new QTimer(&app);
    cantNudgeCheck->setInterval(120000);
    QObject::connect(cantNudgeCheck, &QTimer::timeout, chat, [vault, chat] {
        if (vault->locked() || !chat->ready())
            return;
        const bool old = chat->someoneCantNudge();
        QSettings st = Settings::store();
        if (st.value(QStringLiteral("notify/lockedFallback"), false).toBool() != old)
            st.setValue(QStringLiteral("notify/lockedFallback"), old);
    });
    cantNudgeCheck->start();
    *lockedNudge = [chat, sound, system, lockedCounts, lockedLimit](const RingWire::Nudge &came) {
        if (Settings::silenced())
            return;
        const int mode = chat->previewMode();
        const LockedNudgeLimit::Take took = lockedLimit->take(mode, came, QDateTime::currentMSecsSinceEpoch());
        const RingWire::Nudge &n = took.n;
        const QString key = took.key;
        const int count = lockedCounts->value(key) + 1;
        lockedCounts->insert(key, count);
        // A contact's nudge sounds the first time; the shared "Group message" one (nudge key) whenever its limit
        // allows, at most once per 2 minutes, however long Huginn stays locked (round 16, as Muninn).
        if (LockedNudgeLimit::sounds(took, count) && !Settings::hushed() && Settings::messageSoundOn())
            sound->play();
        const QString title = RingWire::nudgeTitle(mode, n);
        const QString body = RingWire::nudgeText(mode, n.kind, count);
        // A click brings Huginn to its lock screen; the chat list shows once it's unlocked.
        auto open = [chat] { Q_EMIT chat->showChat(QString()); };
        auto fallback = [chat, title, body] { Q_EMIT chat->banner(title, body, QString()); };
        if (QGuiApplication::applicationState() != Qt::ApplicationActive) {
            Platform::notify(title, body, 6000, open, fallback, QString());
            if (system->gameMode())
                fallback();
        } else {
            Q_EMIT chat->notice(title + QStringLiteral(": ") + body);
        }
    };

    if (!quiet) {
        // Stay reachable while Huginn runs: the relays (reconnecting by themselves) and Bluetooth.
        // Locked: what comes waits on the relays until Chat opens it once unlocked. Nothing in the inbox says anything
        // while locked (it may be a receipt, a check-in or presence): only a nudge does (lockedNudge).
        // Round 14: except one "New activity" per lock while someone here is known to be on an app that can't nudge
        // (1.30 to 1.32, an older Kids' TV). It says nothing of who or what (it may be a receipt).
        nostr->onEnvelope = [vault, chat, sound, system, activityShown](const QString &) {
            if (!vault->locked() || *activityShown || Settings::silenced()
                || !Settings::store().value(QStringLiteral("notify/lockedFallback"), false).toBool())
                return false;
            *activityShown = true;
            if (!Settings::hushed() && Settings::messageSoundOn())
                sound->play();
            const QString title = QStringLiteral("Huginn"), body = QStringLiteral("New activity");
            auto open = [chat] { Q_EMIT chat->showChat(QString()); };
            auto fallback = [chat, title, body] { Q_EMIT chat->banner(title, body, QString()); };
            if (QGuiApplication::applicationState() != Qt::ApplicationActive) {
                Platform::notify(title, body, 6000, open, fallback, QString());
                if (system->gameMode())
                    fallback();
            } else {
                Q_EMIT chat->notice(title + QStringLiteral(": ") + body);
            }
            return false;
        };
        // Unlocked (Chat has just started): introduce ourselves on Bluetooth links made while locked,
        // and ask the relays for the inbox again so messages that came while locked are read now.
        QObject::connect(vault, &Vault::opened, nostr, [nostr, ble] { ble->sayHello(); nostr->reconnectNow(); nostr->resubscribe(); });
        QObject::connect(nostr, &Nostr::publicKeyChanged, nostr, [nostr] { nostr->resubscribe(); });
        nostr->setKeepAlive(true);
        nostr->connectRelays();
        // The network came back (a hotel login done, Wi-Fi rejoined): the relays reconnect now, not on the next retry.
        if (QNetworkInformation::loadBackendByFeatures(QNetworkInformation::Feature::Reachability)) {
            QObject::connect(QNetworkInformation::instance(), &QNetworkInformation::reachabilityChanged, nostr,
                             [nostr](QNetworkInformation::Reachability r) {
                                 if (r == QNetworkInformation::Reachability::Online)
                                     nostr->reconnectNow();
                             });
        }
        ble->start();
    }
    // A message arrived: the raven call, and a desktop notification unless that chat is on screen.
    QObject::connect(chat, &Chat::arrived, &app, [chat, sound, vault, linked, system](const QString &name, const QString &text, const QString &key) {
        // A linked phone's group names its channel ("mirror:<peer>#<channel>"): looking only when that tab is on screen,
        // or when the group shows as one list ("mirror:<peer>").
        const QString shown = chat->property("openChat").toString();
        const bool onScreen = shown == key || (shown.startsWith(QLatin1String("mirror:")) && key.startsWith(shown + QLatin1Char('#')));
        const bool looking = QGuiApplication::applicationState() == Qt::ApplicationActive && onScreen;
        // A muted chat makes no sound, nor someone nearby put away with Hide; an archived one comes back to the list.
        // Reduce notifications: one per chat until it's opened (a group: mentions and replies only, when set so).
        // Do not disturb: no sound and no notification either.
        if (!chat->notifyFor(key, name) || looking || Settings::silenced())
            return;
        // Quiet hours and Settings › Notifications › Message sound: the notification still shows.
        if (!Settings::hushed() && Settings::messageSoundOn())
            sound->play();
        if (QGuiApplication::applicationState() != Qt::ApplicationActive) {
            // Settings › Notifications › Show: the name and a short preview, the name only, or nothing. Locked: nothing.
            // While a linked phone's chats are shown, this device's own sealed chats never show who or what.
            const int mode = vault->screenLocked() ? 0 : chat->previewMode();
            QString shortText = text.simplified();
            if (shortText.size() > 100)
                shortText = shortText.left(99) + QStringLiteral("…");
            const QString title = mode >= 1 ? name : QStringLiteral("Huginn");
            const QString body = mode == 2 ? shortText : QStringLiteral("New message");
            // A click opens that chat (a group's "g:<group>:<channel>", a phone group's "mirror:<peer>#<channel>"; an
            // invite opens the chat list).
            const QString where = key.startsWith(QLatin1String("invite:")) ? QString() : key;
            auto open = [chat, where] { Q_EMIT chat->showChat(where); };
            // No system notification here (or, in game mode, none that shows over the game): a banner in the window.
            auto fallback = [chat, title, body, where] { Q_EMIT chat->banner(title, body, where); };
            Platform::notify(title, body, 6000, open, fallback, where);
            if (system->gameMode())
                fallback();
        }
    });
    // Calls live in their chats: each one that ends goes into that chat's timeline. A missed one raises a
    // notification (unless Do not disturb is on), and a click on it opens the chat. Who called shows only
    // with Show preview on and Huginn unlocked.
    auto missedNote = [chat, vault, system](const QString &where, const QString &who, const QString &text) {
        if (Settings::silenced())
            return;
        const bool hide = vault->screenLocked() || !chat->showName();
        const QString title = hide ? QStringLiteral("Huginn") : QStringLiteral("Missed call");
        const QString body = hide ? QStringLiteral("Missed call") : text.isEmpty() ? who : text;
        auto fallback = [chat, title, body, where] { Q_EMIT chat->banner(title, body, where); };
        Platform::notify(title, body, 15000, [chat, where] { Q_EMIT chat->showChat(where); }, fallback, where);
        // Game mode: the system's notification may not show over the game, so the banner waits in the window too.
        if (system->gameMode())
            fallback();
    };
    QObject::connect(calls, &CallManager::logged, chat, [chat, calls, missedNote](const QString &key, bool out, const QString &result, int seconds) {
        const bool missed = result == QLatin1String("missed") || result == QLatin1String("internetOff") || result == QLatin1String("ultraOff");
        QString text;
        if (result == QLatin1String("internetOff"))
            text = QStringLiteral("Missed call · Internet calls are off");
        else if (result == QLatin1String("ultraOff"))
            text = UltraPrivacy::callsMissedText();   // round 19: declined quietly while Ultra privacy is on
        else if (missed)
            text = QStringLiteral("Missed call");
        else if (result == QLatin1String("answered"))
            text = (out ? QStringLiteral("Outgoing call") : QStringLiteral("Incoming call")) + (seconds > 0 ? QStringLiteral(" · ") + calls->duration(seconds) : QString());
        else if (out)
            text = result == QLatin1String("Declined") ? QStringLiteral("Call declined") : result == QLatin1String("Busy") ? QStringLiteral("They were busy") : QStringLiteral("No answer");
        else
            text = result == QLatin1String("Declined") ? QStringLiteral("You declined a call") : QStringLiteral("Incoming call");
        chat->noteCall(key, text, missed);
        if (missed && !(chat->isMuted(key) && !chat->isRightAway(key)))
            missedNote(key, chat->nameOf(key), result == QLatin1String("ultraOff") ? QStringLiteral("From %1. Calls are off while Ultra privacy is on.").arg(chat->nameOf(key))
                                                                                    : QStringLiteral("From %1").arg(chat->nameOf(key)));
    });
    QObject::connect(rooms, &RoomManager::logged, chat, [chat, missedNote](const QString &g, const QString &text, bool missed) {
        chat->noteCall(QStringLiteral("g:") + g, text, missed);
        if (missed && !(chat->isMuted(QStringLiteral("g:") + g) && !chat->isRightAway(QStringLiteral("g:") + g)))
            missedNote(QStringLiteral("g:") + g, QString(), text);
    });
    // A big file waiting for Accept or Decline: the message sound and a system notification, as a message gets (it
    // waits up to a day). A click opens its chat. Who and what show only as a message preview would.
    QObject::connect(files, &FileLink::offered, chat, [chat, vault, sound, system](const QString &, const QString &where, const QString &text) {
        if (Settings::silenced())
            return;
        if (!Settings::hushed() && Settings::messageSoundOn())
            sound->play();
        if (QGuiApplication::applicationState() == Qt::ApplicationActive && !system->gameMode())
            return;   // the toast says it
        const bool hide = vault->screenLocked() || chat->previewMode() == 0;
        const QString title = hide ? QStringLiteral("Huginn") : QStringLiteral("File waiting");
        const QString body = hide ? QStringLiteral("A file is waiting for you") : text;
        auto fallback = [chat, title, body, where] { Q_EMIT chat->banner(title, body, where); };
        Platform::notify(title, body, 15000, [chat, where] { Q_EMIT chat->showChat(where); }, fallback, where);
        if (system->gameMode())
            fallback();
    });
    // Windows: a chat open with Huginn in front takes its notifications off the list a later click guesses from
    // (Platform::forgetChat), so a click from the notification list opens the one chat still unread. A phone group
    // shown as one list is open as "mirror:<peer>", and forgetChat takes every "mirror:<peer>#<channel>" with it; a tab
    // ("mirror:<peer>#<channel>") takes only its own.
    auto forgetOpen = [chat] {
        if (QGuiApplication::applicationState() == Qt::ApplicationActive)
            Platform::forgetChat(chat->property("openChat").toString());
    };
    QObject::connect(chat, &Chat::changed, chat, forgetOpen);
    QObject::connect(qApp, &QGuiApplication::applicationStateChanged, chat, forgetOpen);
    // Windows: the tray icon from the start, with Open, Mute notifications, Lock and close and Quit (Main.qml answers).
    if (!quiet)
        Platform::setUpTray([] { return Settings::silenced(); }, [vault] { return vault->hasLock() && !vault->screenLocked(); },
                            [chat](const QString &what) { Q_EMIT chat->trayAction(what); });
    // Round 17: Lock now and the panic lock take Huginn's notifications (who and what) out of Windows' notification
    // center and the desktop's list too, not only off the screen.
    if (!quiet)
        QObject::connect(vault, &Vault::lockedNow, &app, [] { Platform::clearShown(); });
    // Round 17: a synced folder (OneDrive, Dropbox) that brought another computer's lock in while this one runs: said at
    // once, as two copies on one folder break the chats.
    if (!quiet) {
        QObject::connect(one, &SingleInstance::sharedElsewhere, &app, [](const QString &host) {
            auto *box = new QMessageBox(QMessageBox::Warning, QStringLiteral("Huginn"),
                                        QStringLiteral("Huginn is also open on another computer (%1) that uses this same synced folder. Two copies "
                                                       "on one folder break your chats. Close Huginn on one of them now, and give each computer "
                                                       "its own Huginn folder that isn't synced.")
                                            .arg(host));
            box->setAttribute(Qt::WA_DeleteOnClose);
            box->show();
        });
        one->watchShared();
    }
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
        // A stick counts as someone there once it is pushed past its rest zone, not for drift.
        QObject::connect(gamepad, &Gamepad::stickChanged, system, [system, gamepad] {
            if (gamepad->stickInUse())
                system->noteInput();
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
    // Push to talk (Settings › Calls): TalkKey watches the key while Huginn is in front; GlobalKeys lets it, Mute and
    // Deafen work while a game is in front where the system allows it.
    // With push to talk on but no key or controller button picked yet, the mic uses voice activity: never dead.
    // A controller talk button with no controller connected can't be pressed, so that's voice activity too, but only
    // at join time: a controller that goes away mid-room keeps push to talk on, with the mic muted (TalkKey::padGone).
    auto *talkKey = new TalkKey(settings, rooms, &app);
    talkKey->setPadConnected(gamepad->connected());
    QObject::connect(gamepad, &Gamepad::connectedChanged, talkKey, [talkKey, gamepad] { talkKey->setPadConnected(gamepad->connected()); });
    const auto talkMode = [settings, rooms, talkKey] {
        rooms->setPushToTalk(settings->pushToTalk() && (talkKey->canTalk() || talkKey->padGone()));
    };
    talkMode();
    QObject::connect(settings, &Settings::changed, rooms, talkMode);
    QObject::connect(talkKey, &TalkKey::changed, rooms, talkMode);
    auto *globalKeys = new GlobalKeys(settings, rooms, talkKey, &app);
    globalKeys->inCall = [calls] { return calls->inCall(); };
    QObject::connect(calls, &CallManager::changed, globalKeys, &GlobalKeys::update);

    qmlRegisterType<LiveList>("Huginn", 1, 0, "LiveList");
    qmlRegisterType<Fall>("Huginn", 1, 0, "Fall");
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Settings", settings);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "System", system);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Gamepad", gamepad);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Transfer", transfer);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Updater", updater);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "UltraPrivacy", ultra);
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
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Mod", mod);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "FileLink", files);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Linked", linked);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Mover", mover);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "TalkKey", talkKey);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "GlobalKeys", globalKeys);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Profile", profile);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Labs", labs);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "AppStorage", appStorage);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "Perf", perf);
    qmlRegisterSingletonInstance("Huginn", 1, 0, "DailyVerse", dailyVerse);

    // Closed cleanly once everything below (the window, the engine) has gone; a run that never gets here shows in crash.log.
    struct CleanExit {
        bool on;
        ~CleanExit() { if (on) CrashLog::cleanExit(); }
    } cleanExit{!quiet};
    QQmlApplicationEngine engine;
    // Read aloud (shared/docs/voices.md): every message's menu and Daily Hávamál call ReadAloud.speak(text), stop() and
    // read ReadAloud.available. It stops when the lock goes up and when a call starts.
    auto *readAloud = new ReadAloud(&app);
    QObject::connect(vault, &Vault::changed, readAloud, [vault, readAloud] { if (vault->locked()) readAloud->stop(); });
    QObject::connect(vault, &Vault::lockedNow, readAloud, &ReadAloud::stop);
    QObject::connect(vault, &Vault::panicked, readAloud, &ReadAloud::stop);
    QObject::connect(calls, &CallManager::changed, readAloud, [calls, readAloud] { if (calls->inCall()) readAloud->stop(); });
    // Round 19 (security 19): nothing is read aloud behind the lock or during a call or voice room, whatever asks.
    readAloud->setBlocker([vault, calls, rooms] { return vault->locked() || vault->screenLocked() || calls->inCall() || rooms->inRoom(); });
    QObject::connect(vault, &Vault::aboutToErase, readAloud, &ReadAloud::stop);
    engine.rootContext()->setContextProperty(QStringLiteral("ReadAloud"), readAloud);
    engine.addImageProvider(QStringLiteral("qr"), new QrImageProvider);
    // The image providers run on Qt's loading threads: they read copies kept for them, never waiting on the main
    // thread (which could hang Huginn while it closes).
    engine.addImageProvider(QStringLiteral("thumb"), new ThumbProvider(vault->anyThreadReader()));
    engine.addImageProvider(QStringLiteral("profile"), new ProfileImageProvider(profile));
    engine.addImageProvider(QStringLiteral("ground"), new GroundProvider);
    // Icon.qml: each icon drawn once per name, size and color, then shared.
    engine.addImageProvider(QStringLiteral("icon"), new IconProvider);
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
        // Settings › Privacy › Block screenshots (Windows, off unless switched on), set again whenever it's switched.
        // The window starts without it, so nothing is asked of Windows until it is turned on.
        if (window) {
            auto shield = std::make_shared<int>(0);
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
    // Saved: from here on, a copy that finds this one stuck while closing may ask it to end itself (SingleInstance::markClosing).
    if (!quiet)
        QObject::connect(&app, &QCoreApplication::aboutToQuit, one, &SingleInstance::markClosing);
    if (!quiet)
        updater->startAutoUpdate();
    return app.exec();
}
