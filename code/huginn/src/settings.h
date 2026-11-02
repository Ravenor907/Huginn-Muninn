#pragma once

#include <QCoreApplication>
#include <QObject>
#include <QRect>
#include <QSettings>
#include <QStringList>
#include <QVariantList>

// Everything the person can change, saved as they change it.
class Settings : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QString uiSize READ uiSize WRITE setUiSize NOTIFY changed)
    Q_PROPERTY(QString accent READ accent WRITE setAccent NOTIFY changed)
    Q_PROPERTY(QString textSize READ textSize WRITE setTextSize NOTIFY changed)
    Q_PROPERTY(bool highContrast READ highContrast WRITE setHighContrast NOTIFY changed)
    // Settings › Look › Color vision: "typical", "protan" (red-weak or red-blind), "deutan" (green-weak or green-blind),
    // "tritan" (blue-weak or blue-blind) or "mono" (no color). Theme.qml remaps every color that means something for it.
    // colorBlind is on for any but typical; an older saved Color-blind friendly opens as "deutan".
    Q_PROPERTY(QString colorVision READ colorVision WRITE setColorVision NOTIFY changed)
    Q_PROPERTY(bool colorBlind READ colorBlind WRITE setColorBlind NOTIFY changed)
    Q_PROPERTY(bool reduceMotion READ reduceMotion WRITE setReduceMotion NOTIFY changed)
    // Huginn opens in a window unless this is on; F11 switches too.
    Q_PROPERTY(bool fullScreen READ fullScreen WRITE setFullScreen NOTIFY changed)
    // Settings › Look › Window › Close button: "ask" (Leave Huginn?), "minimize" or "quit" (remembered with Don't ask again).
    Q_PROPERTY(QString closeAction READ closeAction WRITE setCloseAction NOTIFY changed)
    // Settings › Look › Handheld mode: "auto" (System decides at start), "on" or "off".
    Q_PROPERTY(QString handheldMode READ handheldMode WRITE setHandheldMode NOTIFY changed)
    // Settings › Look › Battery saver: "auto" (on while on battery), "on" or "off". On: nothing moves by itself, things
    // checked in the background are checked less often, and Huginn's own frame timers run at 30 a second (System).
    Q_PROPERTY(QString batterySaver READ batterySaver WRITE setBatterySaver NOTIFY changed)
    // The window's last place and size (empty: 80% of the screen, centered).
    Q_PROPERTY(QRect windowRect READ windowRect WRITE setWindowRect NOTIFY changed)
    // The window was maximized when last left: it opens maximized again (windowRect keeps its normal size).
    Q_PROPERTY(bool windowMaximized READ windowMaximized WRITE setWindowMaximized NOTIFY changed)
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
    // Settings › Calls: a short sound when the Mute and Deafen keys turn them on or off (heard over a game).
    Q_PROPERTY(bool muteSounds READ muteSounds WRITE setMuteSounds NOTIFY changed)
    // Mute and Deafen in your card (and Ctrl+Shift+M and D), kept between calls: every call and voice room starts this
    // way. Deafen mutes too; mutedByDeaf says the mic was on before, so undeafening turns it back on.
    Q_PROPERTY(bool micMuted READ micMuted WRITE setMicMuted NOTIFY changed)
    Q_PROPERTY(bool deafened READ deafened WRITE setDeafened NOTIFY changed)
    Q_PROPERTY(bool mutedByDeaf READ mutedByDeaf WRITE setMutedByDeaf NOTIFY changed)
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
    // Settings › Look › Seasonal, on top of any theme: "off", "auto" (follows the date) or a season's own name like
    // "snow"; the background ("" for the season's own, or one of the Look page's "#RRGGBB" swatches) and the
    // animation ("off", "full", or "battery" for On even on battery; an older "light" reads as "full"). Theme.qml checks them. Seasonal used to be a
    // theme of its own: that saved theme opens as Dark with Seasonal on and the same season (see the constructor).
    Q_PROPERTY(QString season READ season WRITE setSeason NOTIFY appearanceChanged)
    Q_PROPERTY(QString seasonGround READ seasonGround WRITE setSeasonGround NOTIFY appearanceChanged)
    Q_PROPERTY(QString seasonAnimation READ seasonAnimation WRITE setSeasonAnimation NOTIFY appearanceChanged)
    // Seasonal: how strong the ground's gradient is, 0 (flat) to 200 percent of the usual (100).
    Q_PROPERTY(int seasonGradient READ seasonGradient WRITE setSeasonGradient NOTIFY appearanceChanged)
    // Do not disturb: no message sound, no desktop notification and no ring while it's on. Set by
    // the status card for this run only; it isn't saved here (the status is).
    Q_PROPERTY(bool quiet READ quiet WRITE setQuiet NOTIFY changed)
    // Labs mode (ten presses on the raven in Settings): the Labs group with the update channel and
    // bug reports. updateChannel: "stable" or "beta" (Pre-release); only read while Labs is on.
    Q_PROPERTY(bool labs READ labs WRITE setLabs NOTIFY changed)
    Q_PROPERTY(QString updateChannel READ updateChannel WRITE setUpdateChannel NOTIFY changed)
    // Settings › Notifications: the raven call for new messages (on unless switched off), and quiet
    // hours: from and to on the hour (0 to 23), no sounds in between (notifications still show).
    Q_PROPERTY(bool messageSound READ messageSound WRITE setMessageSound NOTIFY changed)
    Q_PROPERTY(bool quietHours READ quietHours WRITE setQuietHours NOTIFY changed)
    Q_PROPERTY(int quietFrom READ quietFrom WRITE setQuietFrom NOTIFY changed)
    Q_PROPERTY(int quietTo READ quietTo WRITE setQuietTo NOTIFY changed)
    // Settings › Calls: the microphone and the speaker by id ("" = the system default). Calls and voice
    // messages use them; one that isn't plugged in falls back to the default.
    Q_PROPERTY(QString micDevice READ micDevice WRITE setMicDevice NOTIFY changed)
    Q_PROPERTY(QString speakerDevice READ speakerDevice WRITE setSpeakerDevice NOTIFY changed)
    // Settings › Privacy: the name nearby devices and LocalSend see (empty: "Huginn on <computer>").
    Q_PROPERTY(QString deviceName READ deviceName WRITE setDeviceName NOTIFY changed)
    // Settings › Privacy (Windows): screenshots and screen recordings show nothing of Huginn's window.
    Q_PROPERTY(bool blockScreenshots READ blockScreenshots WRITE setBlockScreenshots NOTIFY changed)
    // Settings › Data saver: files wait for Accept, and no link previews are made.
    Q_PROPERTY(bool dataSaver READ dataSaver WRITE setDataSaver NOTIFY changed)
    // First setup (FirstSetup.qml): "" (never run), "started" (picks up again at the next start) or "done".
    Q_PROPERTY(QString setupState READ setupState WRITE setSetupState NOTIFY changed)

public:
    explicit Settings(QObject *parent = nullptr);

    // Size of everything, 50 to 200 (percent).
    QString uiSize() const { return QString::number(qBound(50, value("size", 100).toInt(), 200)); }
    QString accent() const { return value("accent", "gold").toString(); }
    QString textSize() const { return value("textSize", "100").toString(); }
    bool highContrast() const { return value("highContrast", false).toBool(); }
    QString colorVision() const
    {
        const QString v = value("colorVision", "").toString();
        if (colorVisions().contains(v)) return v;
        return value("colorBlind", false).toBool() ? QStringLiteral("deutan") : QStringLiteral("typical");
    }
    static QStringList colorVisions() { return { QStringLiteral("typical"), QStringLiteral("protan"), QStringLiteral("deutan"), QStringLiteral("tritan"), QStringLiteral("mono") }; }
    bool colorBlind() const { return colorVision() != QLatin1String("typical"); }
    bool reduceMotion() const { return value("reduceMotion", false).toBool(); }
    bool fullScreen() const { return value("fullScreen", false).toBool(); }
    QString closeAction() const
    {
        const QString v = value("closeAction", "ask").toString();
        return v == QLatin1String("minimize") || v == QLatin1String("quit") ? v : QStringLiteral("ask");
    }
    QString handheldMode() const
    {
        const QString v = value("handheldMode", "auto").toString();
        return v == QLatin1String("on") || v == QLatin1String("off") ? v : QStringLiteral("auto");
    }
    QString batterySaver() const
    {
        const QString v = value("batterySaver", "auto").toString();
        return v == QLatin1String("on") || v == QLatin1String("off") ? v : QStringLiteral("auto");
    }
    QRect windowRect() const { return value("windowRect", QRect()).toRect(); }
    bool windowMaximized() const { return value("windowMaximized", false).toBool(); }
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
    QString theme() const
    {
        const QString v = value("theme", "dark").toString();
        return v == QLatin1String("black") || v == QLatin1String("light") ? v : QStringLiteral("dark");
    }
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
    bool muteSounds() const { return value("muteSounds", true).toBool(); }
    bool micMuted() const { return value("micMuted", false).toBool(); }
    bool deafened() const { return value("deafened", false).toBool(); }
    bool mutedByDeaf() const { return value("mutedByDeaf", false).toBool(); }
    // The same, read where no Settings object is at hand (CallManager and RoomManager, when a call or room starts).
    static bool savedMicMuted() { return saved(QStringLiteral("micMuted"), false).toBool(); }
    static bool savedDeafened() { return saved(QStringLiteral("deafened"), false).toBool(); }
    static bool savedMutedByDeaf() { return saved(QStringLiteral("mutedByDeaf"), false).toBool(); }
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
    QString season() const { return value("seasonal", "off").toString(); }
    QString seasonGround() const { return value("seasonGround", "").toString(); }
    int seasonGradient() const { return qBound(0, value("seasonGradient", 100).toInt(), 200); }
    QString seasonAnimation() const
    {
        const QString v = value("seasonAnimation", "full").toString();
        // "battery": On, even on battery (Battery saver and idle on battery don't hold the leaves still).
        return v == QLatin1String("off") || v == QLatin1String("battery") ? v : QStringLiteral("full");
    }
    bool quiet() const { return s_quiet; }
    bool labs() const { return value("labs/on", false).toBool(); }
    QString updateChannel() const { return value("labs/channel", "stable").toString() == QLatin1String("beta") ? QStringLiteral("beta") : QStringLiteral("stable"); }
    // The Pre-release channel counts only while Labs is on.
    bool preRelease() const { return labs() && updateChannel() == QLatin1String("beta"); }
    // The same, read where no Settings object is at hand (the ring in src/call).
    static bool silenced() { return s_quiet; }
    bool messageSound() const { return value("messageSound", true).toBool(); }
    bool quietHours() const { return value("quietHours", false).toBool(); }
    int quietFrom() const { return ((value("quietFrom", 23).toInt() % 24) + 24) % 24; }
    int quietTo() const { return ((value("quietTo", 7).toInt() % 24) + 24) % 24; }
    // Do not disturb, or now is in quiet hours: no sounds (the ring and the raven call).
    static bool hushed();
    static bool messageSoundOn() { return saved(QStringLiteral("messageSound"), true).toBool(); }
    QString micDevice() const { return value("micDevice", "").toString(); }
    QString speakerDevice() const { return value("speakerDevice", "").toString(); }
    static QString savedMic() { return saved(QStringLiteral("micDevice"), QString()).toString(); }
    static QString savedSpeaker() { return saved(QStringLiteral("speakerDevice"), QString()).toString(); }
    // The microphones (input) or speakers to pick from: [{id, name}], "System default" first.
    Q_INVOKABLE QVariantList audioDevices(bool input) const;
    // A device's name for its id ("System default" for "" or one not plugged in now).
    Q_INVOKABLE QString audioDeviceName(bool input, const QString &id) const;
    QString deviceName() const;
    static QString defaultDeviceName();
    // A group's about box folded to one line (GroupPage.qml), kept across restarts, per group.
    Q_INVOKABLE bool aboutFolded(const QString &gid) const { return value(QStringLiteral("aboutFold/") + gid, false).toBool(); }
    Q_INVOKABLE void setAboutFolded(const QString &gid, bool v)
    {
        if (gid.isEmpty()) return;
        if (v) m_settings.setValue(QStringLiteral("aboutFold/") + gid, true);
        else m_settings.remove(QStringLiteral("aboutFold/") + gid);
    }
    bool blockScreenshots() const { return value("blockScreenshots", false).toBool(); }
    bool dataSaver() const { return value("dataSaver", false).toBool(); }
    static bool dataSaverOn() { return saved(QStringLiteral("dataSaver"), false).toBool(); }
    QString setupState() const
    {
        const QString v = value("setup/state", "").toString();
        return v == QLatin1String("started") || v == QLatin1String("done") ? v : QString();
    }
    // The same, read where no Settings object is at hand (the call's audio, src/call/audioio.cpp).
    static QVariant saved(const QString &key, const QVariant &fallback) { return store().value(key, fallback); }
    // Every QSettings Huginn opens: org()'s "Huginn" in the default format. On the Windows portable build that is an
    // INI file in the data folder beside Huginn.exe, never the registry (platform/portable.cpp sets it at start);
    // elsewhere it is the usual settings file.
    static QSettings store() { return QSettings(QSettings::defaultFormat(), QSettings::UserScope, org(), QStringLiteral("Huginn")); }
    // The organization Huginn's settings are kept under: "Huginn", or the self-tests' own name on their throwaway
    // profile (WifiSelfTest::isolate), so they never touch the real ones.
    static QString org()
    {
        const QString o = QCoreApplication::organizationName();
        return o.isEmpty() ? QStringLiteral("Huginn") : o;
    }

    void setUiSize(const QString &v) { set("size", qBound(50, v.toInt(), 200)); }
    void setAccent(const QString &v) { set("accent", v); }
    void setTextSize(const QString &v) { set("textSize", v); }
    void setHighContrast(bool v) { set("highContrast", v); }
    void setColorVision(const QString &v) { set("colorVision", colorVisions().contains(v) ? v : QStringLiteral("typical")); }
    void setColorBlind(bool v) { setColorVision(v ? QStringLiteral("deutan") : QStringLiteral("typical")); }
    void setReduceMotion(bool v) { set("reduceMotion", v); }
    void setFullScreen(bool v) { set("fullScreen", v); }
    void setCloseAction(const QString &v) { set("closeAction", v == QLatin1String("minimize") || v == QLatin1String("quit") ? v : QStringLiteral("ask")); }
    void setHandheldMode(const QString &v) { set("handheldMode", v == QLatin1String("on") || v == QLatin1String("off") ? v : QStringLiteral("auto")); }
    void setBatterySaver(const QString &v) { set("batterySaver", v == QLatin1String("on") || v == QLatin1String("off") ? v : QStringLiteral("auto")); }
    void setWindowRect(const QRect &r) { if (r.width() >= 320 && r.height() >= 240) set("windowRect", r); }
    void setWindowMaximized(bool v) { if (v != windowMaximized()) set("windowMaximized", v); }
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
    void setMuteSounds(bool v) { set("muteSounds", v); }
    void setMicMuted(bool v) { set("micMuted", v); }
    void setDeafened(bool v) { set("deafened", v); }
    void setMutedByDeaf(bool v) { set("mutedByDeaf", v); }
    void setPatternTrail(bool v) { set("patternTrail", v); }
    // 0 goes back to following the window.
    void setFallCount(int v) { setLook("fallCount", v <= 0 ? 0 : qBound(1, v, 400)); }
    void setFallSize(int v) { setLook("fallSize", qBound(15, v, 600)); }
    void setSeason(const QString &v) { setLook("seasonal", v.isEmpty() ? QStringLiteral("off") : v); }
    void setSeasonGround(const QString &v) { setLook("seasonGround", v); }
    void setSeasonGradient(int v) { setLook("seasonGradient", qBound(0, v, 200)); }
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
    void setMessageSound(bool v) { set("messageSound", v); }
    void setQuietHours(bool v) { set("quietHours", v); }
    void setQuietFrom(int v) { set("quietFrom", ((v % 24) + 24) % 24); }
    void setQuietTo(int v) { set("quietTo", ((v % 24) + 24) % 24); }
    void setMicDevice(const QString &v) { set("micDevice", v); }
    void setSpeakerDevice(const QString &v) { set("speakerDevice", v); }
    // Trimmed, at most 40 characters; empty (or the usual name) goes back to the usual name.
    void setDeviceName(const QString &v);
    void setBlockScreenshots(bool v) { set("blockScreenshots", v); }
    void setDataSaver(bool v) { set("dataSaver", v); }
    void setSetupState(const QString &v) { set("setup/state", v == QLatin1String("started") || v == QLatin1String("done") ? v : QString()); }

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
