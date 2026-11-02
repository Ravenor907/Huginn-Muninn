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
    Q_INVOKABLE void showSteamKeyboard();
    Q_INVOKABLE void hideSteamKeyboard();
    // The computer's own file picker, several at once. Empty when nothing was picked.
    Q_INVOKABLE QStringList pickFiles() const;
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
    bool m_handheldAuto = false;
    bool m_controllerAtStart = false;
    bool m_smallScreen = false;
    QString m_model;
    QString m_handheldWhy;   // what Automatic went by, for the reports
    void detectHandheld();
    void updateHandheld();
    // Game mode's ways (the 7 inch guess, the on-screen keyboard) apply: handheld on, or game mode not forced Off.
    bool gameLike() const;
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
