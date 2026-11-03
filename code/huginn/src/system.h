#pragma once

#include <QElapsedTimer>
#include <QObject>
#include <QPointer>
#include <QTimer>
#include <QStringList>
#include <QUrl>
#include <QVariantList>
#include <QVariantMap>

class QQuickWindow;
#include <QScreen>
class Settings;

// Small helpers QML needs: the Steam keyboard, Game Mode detection, the Size setting, files
// to share, and the screenshot tour that CI runs.
class System : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool gameMode READ gameMode CONSTANT)
    // Everything's size as a factor: the screen's own fit (screenScale) times the Size setting (1.0 at 100%).
    Q_PROPERTY(double uiScale READ uiScale NOTIFY layoutChanged)
    // 1.0 on a monitor; more on a small, dense screen (a handheld) so text and buttons are a readable, touchable size.
    Q_PROPERTY(double screenScale READ screenScale NOTIFY layoutChanged)
    // What draws the window, for Settings › About: the graphics API and the GPU ("OpenGL, AMD Radeon Graphics").
    Q_PROPERTY(QString graphics READ graphics NOTIFY graphicsChanged)
    // Drawn by the processor instead of a graphics card (slow).
    Q_PROPERTY(bool softwareGraphics READ softwareGraphics NOTIFY graphicsChanged)
    // How frames are made: "threaded" (on their own thread) or "basic" (on the main thread).
    Q_PROPERTY(QString renderLoop READ renderLoop NOTIFY graphicsChanged)
    Q_PROPERTY(bool windowed READ windowed CONSTANT)
    // Handheld mode (Settings › Look): full screen, controller-first wording, the on-screen keyboard, the
    // small-screen size, lighter Seasonal animation and bigger touch targets. Automatic follows the detection.
    Q_PROPERTY(bool handheld READ handheld NOTIFY handheldChanged)
    // What the detection found, for Settings' note: the model from the known list ("ROG Ally X"), or empty.
    Q_PROPERTY(QString handheldModel READ handheldModel NOTIFY handheldChanged)
    // Automatic's answer, whatever the setting says.
    Q_PROPERTY(bool handheldDetected READ handheldDetected NOTIFY handheldChanged)
    Q_PROPERTY(QString version READ version CONSTANT)
    Q_PROPERTY(QString tourDir READ tourDir CONSTANT)
    // Settings › About › Your data: the folder Huginn keeps everything in, as the system writes it.
    Q_PROPERTY(QString dataFolder READ dataFolder CONSTANT)
    // The Windows portable build (its data folder sits beside Huginn.exe).
    Q_PROPERTY(bool portable READ portable CONSTANT)
    // Running on battery (UPower on Linux, the Flatpak too; GetSystemPowerStatus on Windows). False when not known.
    Q_PROPERTY(bool onBattery READ onBattery NOTIFY powerChanged)
    // Settings › Look › Battery saver is on now (On, or Automatic on battery): nothing moves by itself (Theme.motion),
    // background checks are further apart and Huginn's own frame timers run at 30 a second.
    Q_PROPERTY(bool batterySaving READ batterySaving NOTIFY powerChanged)
    // No key, mouse, touch or controller input for 30 seconds.
    Q_PROPERTY(bool inputIdle READ inputIdle NOTIFY inputIdleChanged)
    // The on-screen keyboard can be opened now (showSteamKeyboard), and it covers the lower half of the window when it
    // is up. Both follow the last input: in game mode always; elsewhere only while the controller or touch is in use.
    Q_PROPERTY(bool keyboardAvailable READ keyboardAvailable NOTIFY keyboardChanged)
    Q_PROPERTY(bool keyboardCovers READ keyboardCovers NOTIFY keyboardChanged)

public:
    System(Settings *settings, const QString &tourDir, bool windowed, QObject *parent = nullptr);

    static QString formatSize(qint64 bytes);
    static QString displayPath(const QUrl &url);
    static bool inFlatpak();

    // SteamOS Game Mode (gamescope) or Steam's Big Picture, from the environment alone (works before the app exists).
    static bool detectGameMode();
    // How much bigger than its design size a w by h screen (device-independent pixels, after the system's own
    // scaling) of physical size mm must draw to be readable. 1.0 for monitors and TVs.
    static double fitFor(const QSize &px, const QSizeF &mm, bool gameMode);
    // The screen's diagonal in inches when its reported physical size can be believed, else 0 (fitFor's rules).
    static double trustedInches(const QSize &px, const QSizeF &mm, bool gameMode);

    // Handheld detection, once at start. The known models are one table in system.cpp (kHandhelds): a
    // case-insensitive piece of the product name, family or board, and where given, of the maker too.
    // The model's name from that table for these firmware strings, or empty.
    static QString handheldModelFor(const QString &product, const QString &family, const QString &board, const QString &vendor);
    // Automatic: a known model, or at least two of a 6 to 9 inch built-in screen, a controller at start and game mode.
    static bool handheldRule(bool knownModel, bool smallScreen, bool controller, bool gameMode);
    // --privacy-selftest: the model table, the two-of-three rule and a laptop with a pad that must not count.
    static bool handheldSelfTest();
    // The on-screen keyboard can be opened (keyboardAvailable), from what's known: Handheld mode, the tour, game mode,
    // the controller or touch in use, Windows, Steam there to show its keyboard.
    static bool keyboardRule(bool gameLike, bool tour, bool gameMode, bool padOrTouch, bool windows, bool steam);
    // main(): whether a controller was there at start (the last fact Automatic needs).
    void noteControllerAtStart(bool connected);
    bool handheld() const { return m_handheld; }
    QString handheldModel() const { return m_model; }
    bool handheldDetected() const { return m_handheldAuto; }
    // For the Performance check and crash.log: "on (Automatic, ROG Ally X)".
    QString handheldSummary() const;
    bool gameMode() const { return m_gameMode; }
    double uiScale() const { return m_uiScale; }
    double screenScale() const { return m_fit; }
    QString graphics() const { return m_graphics; }
    bool softwareGraphics() const { return m_software; }
    QString renderLoop() const { return m_renderLoop; }
    // Follows the main window's screen (its size and physical size) and notes what draws it.
    void watchWindow(QQuickWindow *window);
    bool windowed() const { return m_windowed; }
    QString version() const;
    QString tourDir() const { return m_tourDir; }
    bool onBattery() const { return m_onBattery; }
    bool batterySaving() const { return m_saving; }
    bool inputIdle() const { return m_inputIdle; }
    // A controller press or stick move counts as input (main.cpp); keys, the mouse and touch are seen by eventFilter.
    void noteInput();
    // For the Performance check: "on battery, saver Automatic (on)".
    QString powerSummary() const;

    // Leave Huginn running out of sight: minimized, or in game mode (no minimizing there) hidden with Steam in front.
    // A notification, the tray, the taskbar or starting Huginn again brings it back.
    Q_INVOKABLE void minimize(QQuickWindow *window);
    // Windows: the unread count on the taskbar button (a badge) and in the tray icon's tooltip, and whether the tray's
    // Mute notifications is on. Elsewhere it does nothing.
    Q_INVOKABLE void setUnreadState(QQuickWindow *window, int unread, bool muted);
    // A system notification now (Huginn out of sight, so a toast in the window wouldn't be seen). A click brings
    // Huginn to the front.
    Q_INVOKABLE void notifyNow(const QString &title, const QString &body);
    // The screenshot tour only: draw Handheld mode for a stop (the tour otherwise always draws the desktop layout).
    Q_INVOKABLE void setTourHandheld(bool on);
    // The on-screen keyboard in game mode and Handheld mode: Steam's where Steam is there (started from it, or running
    // beside Huginn on Linux), else Windows' touch keyboard on Windows. Elsewhere it does nothing. Outside game mode it
    // opens only for the controller or touch (keyboardAvailable), never over a mouse or a real keyboard.
    Q_INVOKABLE void showSteamKeyboard();
    // That keyboard covers the lower half of the window: prompts sit at the top while it's up, and message boxes are
    // written in the prompt at the top.
    bool keyboardCovers() const;
    // showSteamKeyboard() opens something here (the "Keyboard" button chip shows only then).
    bool keyboardAvailable() const;
    // Main.qml: the controller is what's being used (padInUse). A mouse or a keyboard's keys turn it off there.
    Q_INVOKABLE void setControllerInUse(bool on);
    // Main.qml: a real keyboard's key (not the controller's stand-ins from the on-screen keyboard): touch no longer counts.
    Q_INVOKABLE void noteKeyboardUsed();
    Q_INVOKABLE void hideSteamKeyboard();
    // The computer's own file picker, several at once. Empty when nothing was picked.
    Q_INVOKABLE QStringList pickFiles() const;
    // Huginn's own file picker (game mode, driven by the controller; Ui.pickFiles): the usual folders that exist here
    // [{name, path}], and one folder's contents, folders first, then up to 300 files newest first
    // [{name, path, dir, size, time}]. Hidden files are left out. With Steam screenshots on this computer, the last place
    // is "Steam screenshots" (path "steam:screenshots"): every game's, newest first.
    Q_INVOKABLE QVariantList filePlaces() const;
    Q_INVOKABLE QVariantList listFolder(const QString &path) const;
    /** A picture file from the computer's own file picker, as a file URL; empty when none was chosen. */
    Q_INVOKABLE QString pickPicture() const;
    // Ask the system not to dim or sleep while files are being sent or received.
    void setKeepAwake(bool on, const QString &reason);
    Q_INVOKABLE QString sizeText(double bytes) const { return formatSize(qint64(bytes)); }
    Q_INVOKABLE void grab(QQuickWindow *window, const QString &name);
    Q_INVOKABLE void tourFinished();
    QString dataFolder() const;
    bool portable() const;
    // Opens the data folder in the file manager. False when it couldn't.
    Q_INVOKABLE bool openDataFolder() const;

    static int s_qmlWarnings;
    // Where Huginn keeps its own files (the vault and chats, keys, tls, crash.log) and what can be made again (update
    // downloads, a move's parts). Every file Huginn keeps goes through these: the data folder beside Huginn.exe on the
    // Windows portable build, Qt's app data and cache folders elsewhere (platform/portable.h).
    static QString dataDir();
    static QString cacheDir();
    // Full screen when the window opens. Read once: after that only F11, Settings › Look › Open full screen and Handheld
    // mode change it (Main.qml), never another setting.
    Q_INVOKABLE bool startsFullScreen() const;

Q_SIGNALS:
    void layoutChanged();
    void graphicsChanged();
    void handheldChanged();
    void powerChanged();
    void inputIdleChanged();
    void keyboardChanged();

protected:
    bool eventFilter(QObject *watched, QEvent *event) override;

private Q_SLOTS:
    // UPower's PropertiesChanged (Linux): OnBattery may have changed.
    void upowerChanged(const QString &iface, const QVariantMap &changed, const QStringList &invalidated);

private:
    QString m_inhibitHandle;     // portal request path
    uint m_screensaverCookie = 0;
    bool m_awake = false;
    int m_awakeAsk = 0;          // each change: an answer for an older one lets go of what it got
    void openSteamUrl(const QString &url);
    Settings *m_settings;
    QString m_tourDir;
    bool m_windowed;
    bool m_gameMode;
    QPointer<QScreen> m_screen;
    double m_fit = 1.0;
    double m_uiScale = 1.0;
    QString m_graphics;
    bool m_software = false;
    QString m_renderLoop;
    bool m_handheld = false;
    bool m_tourHandheld = false;   // the tour's handheld stops
    bool m_handheldAuto = false;
    bool m_controllerAtStart = false;
    bool m_smallScreen = false;
    QString m_model;
    QString m_handheldWhy;   // what Automatic went by, for the reports
    void detectHandheld();
    void updateHandheld();
    // Game mode's ways (the 7 inch guess, the on-screen keyboard) apply: handheld on, or game mode not forced Off.
    bool gameLike() const;
    // Steam is there to show its keyboard: game mode, started from Steam, or (Linux, outside game mode) its client
    // running beside Huginn, looked for now and then without waiting (checkSteam).
    bool steamKeyboard() const;
    void checkSteam();
    // The controller or touch is what's in use now (the on-screen keyboard is for them only, outside game mode).
    bool padOrTouch() const { return m_controllerInUse || m_touchInUse; }
    void setTouchInUse(bool on);
    void setSteamRunning(bool on);
    bool m_steamRunning = false;
    bool m_controllerInUse = false;
    bool m_touchInUse = false;
    bool m_steamChecking = false;
    qint64 m_steamCheckedAt = 0;
    QTimer m_steamPoll;   // Linux: Steam looked for again every 10 seconds while the controller or touch is in use
    void watchScreen(QScreen *screen);
    void refit();
    void noteGraphics(const QString &what, bool software, const QString &loop);
    // Battery: asked at start and followed (UPower's signal on Linux, every minute on Windows).
    void watchPower();
    void askBattery();
    void setOnBattery(bool on);
    void updateSaving();
    bool m_onBattery = false;
    bool m_saving = false;
    QTimer m_powerPoll;
    // Input: the time of the last one, looked at every 5 seconds until 30 have passed without any.
    QElapsedTimer m_inputClock;
    qint64 m_lastInput = 0;
    bool m_inputIdle = false;
    QTimer m_idleCheck;
};
