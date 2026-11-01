#pragma once

#include <QObject>
#include <QPointer>
#include <QUrl>
#include <QVariantList>

class QQuickWindow;
class QScreen;
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
    Q_PROPERTY(bool windowed READ windowed CONSTANT)
    Q_PROPERTY(QString version READ version CONSTANT)
    Q_PROPERTY(QString tourDir READ tourDir CONSTANT)

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
    bool gameMode() const { return m_gameMode; }
    double uiScale() const { return m_uiScale; }
    double screenScale() const { return m_fit; }
    QString graphics() const { return m_graphics; }
    bool softwareGraphics() const { return m_software; }
    // Follows the main window's screen (its size and physical size) and notes what draws it.
    void watchWindow(QQuickWindow *window);
    bool windowed() const { return m_windowed; }
    QString version() const;
    QString tourDir() const { return m_tourDir; }

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

    static int s_qmlWarnings;

Q_SIGNALS:
    void layoutChanged();
    void graphicsChanged();

private:
    QString m_inhibitHandle;     // portal request path
    uint m_screensaverCookie = 0;
    bool m_awake = false;
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
    void watchScreen(QScreen *screen);
    void refit();
    void noteGraphics(const QString &what, bool software);
};
