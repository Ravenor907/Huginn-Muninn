#pragma once

#include <QObject>
#include <QSettings>

// Everything the person can change, saved as they change it.
class Settings : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QString uiSize READ uiSize WRITE setUiSize NOTIFY changed)
    Q_PROPERTY(QString accent READ accent WRITE setAccent NOTIFY changed)
    Q_PROPERTY(QString textSize READ textSize WRITE setTextSize NOTIFY changed)
    Q_PROPERTY(bool highContrast READ highContrast WRITE setHighContrast NOTIFY changed)
    Q_PROPERTY(bool colorBlind READ colorBlind WRITE setColorBlind NOTIFY changed)
    Q_PROPERTY(bool reduceMotion READ reduceMotion WRITE setReduceMotion NOTIFY changed)
    Q_PROPERTY(bool autoUpdate READ autoUpdate WRITE setAutoUpdate NOTIFY changed)
    Q_PROPERTY(bool runInBackground READ runInBackground WRITE setRunInBackground NOTIFY changed)
    Q_PROPERTY(bool nearbySharing READ nearbySharing WRITE setNearbySharing NOTIFY changed)
    Q_PROPERTY(QString transferFolder READ transferFolder WRITE setTransferFolder NOTIFY changed)
    Q_PROPERTY(bool noiseRemoval READ noiseRemoval WRITE setNoiseRemoval NOTIFY changed)
    Q_PROPERTY(bool internetCalls READ internetCalls WRITE setInternetCalls NOTIFY changed)
    // Settings › Appearance (the desktop layout): theme, message density and size, and the panels.
    // Their own signal, so a resize or a theme change doesn't wake everything that follows changed().
    Q_PROPERTY(QString theme READ theme WRITE setTheme NOTIFY appearanceChanged)
    Q_PROPERTY(QString density READ density WRITE setDensity NOTIFY appearanceChanged)
    // Where the pattern sits on the lock screen: "top", "middle" or "lower" (the default, for one hand).
    Q_PROPERTY(QString lockPosition READ lockPosition WRITE setLockPosition NOTIFY appearanceChanged)
    Q_PROPERTY(int railWidth READ railWidth WRITE setRailWidth NOTIFY appearanceChanged)
    Q_PROPERTY(int listWidth READ listWidth WRITE setListWidth NOTIFY appearanceChanged)
    Q_PROPERTY(int memberWidth READ memberWidth WRITE setMemberWidth NOTIFY appearanceChanged)
    Q_PROPERTY(bool showList READ showList WRITE setShowList NOTIFY appearanceChanged)
    Q_PROPERTY(bool showMembers READ showMembers WRITE setShowMembers NOTIFY appearanceChanged)
    // Settings › Calls › Push to talk: in a voice room the microphone only sends while the key is held.
    Q_PROPERTY(bool pushToTalk READ pushToTalk WRITE setPushToTalk NOTIFY changed)
    // Settings › Privacy and lock: the pattern's line and lit dots show while it's drawn (off: only
    // a brief pulse on each dot, so someone looking over your shoulder can't read it).
    Q_PROPERTY(bool patternTrail READ patternTrail WRITE setPatternTrail NOTIFY changed)
    // The Seasonal look's falling leaves or snowflakes. fallCount: how many on screen, 1 to 400, or
    // 0 until the Amount slider is moved: then fallBase on a 1600x900 window, scaled by the window's
    // area. fallSize: percent of the usual, 15 to 600. Older "leafCount" and "leafSize" percents and
    // the Light animation carry over; their old defaults get today's (12 and 60%).
    Q_PROPERTY(int fallCount READ fallCount WRITE setFallCount NOTIFY appearanceChanged)
    Q_PROPERTY(double fallBase READ fallBase NOTIFY appearanceChanged)
    Q_PROPERTY(int fallSize READ fallSize WRITE setFallSize NOTIFY appearanceChanged)
    // Seasonal: the season ("auto" follows the date, or a season's own name like "snow"), the
    // background ("" for the season's own, or one of the Look page's "#RRGGBB" swatches) and the
    // animation ("off" or "full"; an older "light" reads as "full"). Theme.qml checks them.
    Q_PROPERTY(QString season READ season WRITE setSeason NOTIFY appearanceChanged)
    Q_PROPERTY(QString seasonGround READ seasonGround WRITE setSeasonGround NOTIFY appearanceChanged)
    Q_PROPERTY(QString seasonAnimation READ seasonAnimation WRITE setSeasonAnimation NOTIFY appearanceChanged)
    // Do not disturb: no message sound, no desktop notification and no ring while it's on. Set by
    // the status card for this run only; it isn't saved here (the status is).
    Q_PROPERTY(bool quiet READ quiet WRITE setQuiet NOTIFY changed)
    // Labs mode (ten presses on the raven in Settings): the Labs group with the update channel and
    // bug reports. updateChannel: "stable" or "beta" (Pre-release); only read while Labs is on.
    Q_PROPERTY(bool labs READ labs WRITE setLabs NOTIFY changed)
    Q_PROPERTY(QString updateChannel READ updateChannel WRITE setUpdateChannel NOTIFY changed)

public:
    explicit Settings(QObject *parent = nullptr);

    // Size of everything, 50 to 200 (percent).
    QString uiSize() const { return QString::number(qBound(50, value("size", 100).toInt(), 200)); }
    QString accent() const { return value("accent", "gold").toString(); }
    QString textSize() const { return value("textSize", "100").toString(); }
    bool highContrast() const { return value("highContrast", false).toBool(); }
    bool colorBlind() const { return value("colorBlind", false).toBool(); }
    bool reduceMotion() const { return value("reduceMotion", false).toBool(); }
    // Looks for a new release at start. Off: only Check now asks GitHub.
    bool autoUpdate() const { return value("autoUpdate", true).toBool(); }
    bool runInBackground() const { return value("runInBackground", true).toBool(); }
    bool nearbySharing() const { return value("nearbySharing", true).toBool(); }
    QString transferFolder() const;
    // Settings › Calls › Noise removal (on unless switched off). Off: calls keep the echo canceller and level
    // control but leave background noise in, for a voice the noise suppressor turns down too far.
    bool noiseRemoval() const { return saved("noiseRemoval", true).toBool(); }
    // Settings › Calls › Internet calls (on unless switched off). Off: calls go only over Wi-Fi and
    // Bluetooth; the relay Worker, the TURN relay and the STUN server are never asked.
    bool internetCalls() const { return saved("internetCalls", true).toBool(); }
    static bool internetCallsOn() { return saved(QStringLiteral("internetCalls"), true).toBool(); }
    // "dark", "black" (true black, for OLED screens) or "light".
    QString theme() const { return value("theme", "dark").toString(); }
    // "compact" (more messages on screen, no pictures of people), "cozy" (the default) or "spacious".
    QString density() const { return value("density", "cozy").toString(); }
    QString lockPosition() const
    {
        const QString v = value("lockPosition", "lower").toString();
        return v == QLatin1String("top") || v == QLatin1String("middle") ? v : QStringLiteral("lower");
    }
    // Panel widths at 100% size.
    int railWidth() const { return 72; }
    int listWidth() const { return qBound(200, value("panels/list", 240).toInt(), 360); }
    int memberWidth() const { return qBound(200, value("panels/members", 240).toInt(), 360); }
    bool showList() const { return value("showList", true).toBool(); }
    bool showMembers() const { return value("showMembers", true).toBool(); }
    bool pushToTalk() const { return value("pushToTalk", false).toBool(); }
    bool patternTrail() const { return value("patternTrail", true).toBool(); }
    int fallCount() const
    {
        const int v = value("fallCount", 0).toInt();
        return v <= 0 ? 0 : qBound(1, v, 400);
    }
    double fallBase() const
    {
        if (m_settings.contains(QStringLiteral("fallBase"))) return qBound(0.1, value("fallBase", 12.0).toDouble(), 400.0);
        const int old = qBound(25, value("leafCount", 100).toInt(), 200);
        const bool light = value("seasonAnimation", "full").toString() == QLatin1String("light");
        // About 23 fell on a 1600x900 window at the old 100%; Light was about half.
        return old == 100 && !light ? 12.0 : 23.0 * old / 100.0 * (light ? 0.5 : 1.0);
    }
    int fallSize() const
    {
        if (m_settings.contains(QStringLiteral("fallSize"))) return qBound(15, value("fallSize", 60).toInt(), 600);
        const int old = value("leafSize", 100).toInt();
        return old == 100 ? 60 : qBound(15, old, 600);
    }
    QString season() const { return value("season", "auto").toString(); }
    QString seasonGround() const { return value("seasonGround", "").toString(); }
    QString seasonAnimation() const
    {
        const QString v = value("seasonAnimation", "full").toString();
        return v == QLatin1String("off") ? v : QStringLiteral("full");
    }
    bool quiet() const { return s_quiet; }
    bool labs() const { return value("labs/on", false).toBool(); }
    QString updateChannel() const { return value("labs/channel", "stable").toString() == QLatin1String("beta") ? QStringLiteral("beta") : QStringLiteral("stable"); }
    // The Pre-release channel counts only while Labs is on.
    bool preRelease() const { return labs() && updateChannel() == QLatin1String("beta"); }
    // The same, read where no Settings object is at hand (the ring in src/call).
    static bool silenced() { return s_quiet; }
    // The same, read where no Settings object is at hand (the call's audio, src/call/audioio.cpp).
    static QVariant saved(const QString &key, const QVariant &fallback) { return QSettings(QStringLiteral("Huginn"), QStringLiteral("Huginn")).value(key, fallback); }

    void setUiSize(const QString &v) { set("size", qBound(50, v.toInt(), 200)); }
    void setAccent(const QString &v) { set("accent", v); }
    void setTextSize(const QString &v) { set("textSize", v); }
    void setHighContrast(bool v) { set("highContrast", v); }
    void setColorBlind(bool v) { set("colorBlind", v); }
    void setReduceMotion(bool v) { set("reduceMotion", v); }
    void setAutoUpdate(bool v) { set("autoUpdate", v); }
    void setRunInBackground(bool v) { set("runInBackground", v); }
    void setNearbySharing(bool v) { set("nearbySharing", v); }
    void setTransferFolder(const QString &v) { set("transferFolder", v); }
    void setNoiseRemoval(bool v) { set("noiseRemoval", v); }
    void setInternetCalls(bool v) { set("internetCalls", v); }
    void setTheme(const QString &v) { setLook("theme", v); }
    void setDensity(const QString &v) { setLook("density", v); }
    void setLockPosition(const QString &v) { setLook("lockPosition", v); }
    void setRailWidth(int) {}
    void setListWidth(int v) { setLook("panels/list", v); }
    void setMemberWidth(int v) { setLook("panels/members", v); }
    void setShowList(bool v) { setLook("showList", v); }
    void setShowMembers(bool v) { setLook("showMembers", v); }
    void setPushToTalk(bool v) { set("pushToTalk", v); }
    void setPatternTrail(bool v) { set("patternTrail", v); }
    // 0 goes back to following the window.
    void setFallCount(int v) { setLook("fallCount", v <= 0 ? 0 : qBound(1, v, 400)); }
    void setFallSize(int v) { setLook("fallSize", qBound(15, v, 600)); }
    void setSeason(const QString &v) { setLook("season", v); }
    void setSeasonGround(const QString &v) { setLook("seasonGround", v); }
    void setSeasonAnimation(const QString &v)
    {
        // An older Light or "how many" percent lives on in fallBase once the choice is saved again.
        const double base = fallBase();
        if (!m_settings.contains(QStringLiteral("fallBase")) && base != 12.0) m_settings.setValue(QStringLiteral("fallBase"), base);
        setLook("seasonAnimation", v);
    }
    void setQuiet(bool v);
    void setLabs(bool v) { set("labs/on", v); }
    void setUpdateChannel(const QString &v) { set("labs/channel", v == QLatin1String("beta") ? QStringLiteral("beta") : QStringLiteral("stable")); }

Q_SIGNALS:
    void changed();
    void appearanceChanged();

private:
    QVariant value(const QString &key, const QVariant &fallback) const { return m_settings.value(key, fallback); }
    void set(const QString &key, const QVariant &v);
    void setLook(const QString &key, const QVariant &v);
    QSettings m_settings;
    static bool s_quiet;
};
