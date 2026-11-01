// Direct connect on Windows. The Linux build (src/direct.cpp) asks NetworkManager for a private
// hotspot over D-Bus; Windows has no NetworkManager, so Direct connect says how to do the same by hand
// (Windows' own Mobile hotspot) and stays off. CMake builds this file instead of src/direct.cpp on Windows.
#include "../direct.h"

Direct::Direct(QObject *parent)
    : QObject(parent)
{
}

Direct::~Direct() = default;

QString Direct::qr() const
{
    return {};
}

void Direct::findWifiDevice(std::function<void(const QString &path, const QString &error)> done)
{
    if (done)
        done(QString(), QStringLiteral("No NetworkManager on Windows."));
}

void Direct::start()
{
    m_error = QStringLiteral("On Windows, Huginn can't make its own Wi-Fi. Turn on Mobile hotspot in Windows Settings "
                             "(Network & internet), join it from the phone, then send as on any Wi-Fi.");
    Q_EMIT changed();
}

void Direct::stop()
{
}

void Direct::clearLeftovers() {}
