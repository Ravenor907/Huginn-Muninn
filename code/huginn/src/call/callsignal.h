#pragma once

#include <QByteArray>
#include <QJsonObject>
#include <QObject>
#include <QString>
#include <functional>

// What calls need from Chat, and nothing more. Chat owns the envelope, the pairwise sealing and the
// routes; calls only ever see plaintext room "call" objects (shared/docs/protocol.md 5.2).
//
//  send(key, msg)          seal msg as room "call" for that contact, exactly like a private message,
//                          and send it on every route at once. Never kept in the day-long Outbox.
//  received(key, msg)      emitted by Chat for every authentic room "call" envelope it opened, with
//                          the proven sender's chat key (the envelope's fromKey after it opened).
//  sign(bytes)             DER ECDSA P-256 / SHA-256 of bytes with this device's chat key.
//  myKey()                 this device's chat key, standard base64 of the SPKI DER.
//  nameOf(key)             what to show for them (the nickname when there is one).
//  canCall(key)            a contact (or group peer) whose caps include c1.
//  lanHost(key)            the IP of the Wi-Fi route Chat already knows for them, or "".
//  inFamily(key)           they are in a contact folder named "Family" (trusted by default, 5.8.1).
//  hasCap(key, token)      their caps include token (c2: video and the direct path can be offered).
//  bleReady(key) / sendMedia(key, packet) / mediaReceived(key, packet)
//                          the Bluetooth LE MEDIA characteristic (5.6). Optional: without it,
//                          bleReady is false and calls use the relay and Wi-Fi only.
//  showPreview()           Settings > Show preview: a ringing call's notification may name the caller. Optional.
//  warmUp(key)             a call to them is about to start: open the internet routes now, so the
//                          offer isn't held up by a connection that went quiet. Optional.
class CallSignal : public QObject
{
    Q_OBJECT
public:
    using QObject::QObject;
    virtual void send(const QString &contactKey, const QJsonObject &msg) = 0;
    virtual QByteArray sign(const QByteArray &bytes) const = 0;
    virtual QString myKey() const = 0;
    virtual QString nameOf(const QString &contactKey) const = 0;
    virtual bool canCall(const QString &contactKey) const = 0;
    virtual QString lanHost(const QString &) const { return QString(); }
    virtual bool inFamily(const QString &) const { return false; }
    virtual bool hasCap(const QString &, const QString &) const { return false; }
    // For the screens: unknown caps count as a current app (the Video button shows).
    virtual bool mayHave(const QString &k, const QString &c) { return hasCap(k, c); }
    virtual bool bleReady(const QString &) const { return false; }
    virtual bool sendMedia(const QString &, const QByteArray &) { return false; }
    virtual void warmUp(const QString &) {}
    virtual bool showPreview() const { return true; }

Q_SIGNALS:
    void received(const QString &contactKey, const QJsonObject &msg);
    void mediaReceived(const QString &contactKey, const QByteArray &packet);
};

// The one main.cpp uses: each part is a function main.cpp points at Chat (and Ble). Anything left
// unset does nothing. Call deliver() / deliverMedia() from Chat's side to raise the signals.
class CallSignalHooks : public CallSignal
{
    Q_OBJECT
public:
    using CallSignal::CallSignal;
    std::function<void(const QString &, const QJsonObject &)> onSend;
    std::function<QByteArray(const QByteArray &)> onSign;
    std::function<QString()> onMyKey;
    std::function<QString(const QString &)> onNameOf;
    std::function<bool(const QString &)> onCanCall;
    std::function<QString(const QString &)> onLanHost;
    std::function<bool(const QString &)> onInFamily;
    std::function<bool(const QString &, const QString &)> onHasCap;
    std::function<bool(const QString &, const QString &)> onMayHave;
    std::function<bool(const QString &)> onBleReady;
    std::function<bool(const QString &, const QByteArray &)> onSendMedia;
    std::function<void(const QString &)> onWarmUp;
    std::function<bool()> onShowPreview;

    void send(const QString &k, const QJsonObject &m) override { if (onSend) onSend(k, m); }
    QByteArray sign(const QByteArray &b) const override { return onSign ? onSign(b) : QByteArray(); }
    QString myKey() const override { return onMyKey ? onMyKey() : QString(); }
    QString nameOf(const QString &k) const override { return onNameOf ? onNameOf(k) : QString(); }
    bool canCall(const QString &k) const override { return onCanCall && onCanCall(k); }
    QString lanHost(const QString &k) const override { return onLanHost ? onLanHost(k) : QString(); }
    bool inFamily(const QString &k) const override { return onInFamily && onInFamily(k); }
    bool hasCap(const QString &k, const QString &c) const override { return onHasCap && onHasCap(k, c); }
    bool mayHave(const QString &k, const QString &c) override { return onMayHave ? onMayHave(k, c) : hasCap(k, c); }
    bool bleReady(const QString &k) const override { return onBleReady && onBleReady(k); }
    bool sendMedia(const QString &k, const QByteArray &p) override { return onSendMedia && onSendMedia(k, p); }
    void warmUp(const QString &k) override { if (onWarmUp) onWarmUp(k); }
    bool showPreview() const override { return !onShowPreview || onShowPreview(); }

    void deliver(const QString &k, const QJsonObject &m) { Q_EMIT received(k, m); }
    void deliverMedia(const QString &k, const QByteArray &p) { Q_EMIT mediaReceived(k, p); }
};
