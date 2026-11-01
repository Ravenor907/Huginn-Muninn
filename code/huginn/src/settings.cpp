#include "settings.h"
#include <QAudioDevice>
#include <QDir>
#include <QMediaDevices>
#include <QStandardPaths>
#include <QSysInfo>
#include <QTime>
#include <QVariantMap>

bool Settings::s_quiet = false;

Settings::Settings(QObject *parent)
    : QObject(parent)
    , m_settings(QStringLiteral("Huginn"), QStringLiteral("Huginn"))
{
}

QString Settings::transferFolder() const
{
    const QString fallback = QStandardPaths::writableLocation(QStandardPaths::DownloadLocation);
    const QString v = value("transferFolder", fallback).toString();
    return v.isEmpty() ? QDir::homePath() : v;
}

// Do not disturb: kept in memory, set from your status (Main.qml binds it to Profile.myStatus).
void Settings::setQuiet(bool v)
{
    if (s_quiet == v)
        return;
    s_quiet = v;
    Q_EMIT changed();
}

void Settings::set(const QString &key, const QVariant &v)
{
    if (m_settings.value(key) == v)
        return;
    m_settings.setValue(key, v);
    m_settings.sync();
    Q_EMIT changed();
}

// Appearance: saved the same way, told only to what draws the screen.
void Settings::setLook(const QString &key, const QVariant &v)
{
    if (m_settings.value(key) == v)
        return;
    m_settings.setValue(key, v);
    m_settings.sync();
    Q_EMIT appearanceChanged();
}

bool Settings::hushed()
{
    if (s_quiet)
        return true;
    if (!saved(QStringLiteral("quietHours"), false).toBool())
        return false;
    const int from = ((saved(QStringLiteral("quietFrom"), 23).toInt() % 24) + 24) % 24;
    const int to = ((saved(QStringLiteral("quietTo"), 7).toInt() % 24) + 24) % 24;
    const int hour = QTime::currentTime().hour();
    // The same as Muninn's PowerLogic.inQuiet: from == to is never, and it may run past midnight.
    if (from == to)
        return false;
    return from < to ? hour >= from && hour < to : hour >= from || hour < to;
}

QVariantList Settings::audioDevices(bool input) const
{
    QVariantList out;
    out << QVariantMap{{QStringLiteral("id"), QString()}, {QStringLiteral("name"), QStringLiteral("System default")}};
    const QList<QAudioDevice> all = input ? QMediaDevices::audioInputs() : QMediaDevices::audioOutputs();
    for (const QAudioDevice &d : all)
        out << QVariantMap{{QStringLiteral("id"), QString::fromLatin1(d.id())}, {QStringLiteral("name"), d.description()}};
    return out;
}

QString Settings::audioDeviceName(bool input, const QString &id) const
{
    if (!id.isEmpty()) {
        const QList<QAudioDevice> all = input ? QMediaDevices::audioInputs() : QMediaDevices::audioOutputs();
        for (const QAudioDevice &d : all)
            if (QString::fromLatin1(d.id()) == id)
                return d.description();
    }
    return QStringLiteral("System default");
}

QString Settings::defaultDeviceName()
{
    QString host = QSysInfo::machineHostName();
    if (host.isEmpty() || host == QLatin1String("localhost"))
        host = QStringLiteral("handheld");
    return QStringLiteral("Huginn on %1").arg(host);
}

QString Settings::deviceName() const
{
    const QString v = value("deviceName", "").toString().trimmed();
    return v.isEmpty() ? defaultDeviceName() : v;
}

void Settings::setDeviceName(const QString &v)
{
    QString t = v.simplified().left(40);
    if (t == defaultDeviceName())
        t.clear();
    set("deviceName", t);
}
