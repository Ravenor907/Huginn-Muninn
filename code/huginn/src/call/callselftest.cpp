#include "callselftest.h"

#include "audiodsp.h"
#include "callcrypto.h"
#include "callmedia.h"
#include "callsession.h"
#include "callsignal.h"
#include "directpath.h"
#include "videowire.h"
#include "videoengine.h"
#include "vpxcodec.h"
#include "jitterbuffer.h"
#include "opuscodec.h"
#include "roomwire.h"
#include "stun.h"
#include "turnclient.h"

#ifdef HUGINN_HAVE_VOICE
#include "tv_voice.h"
#endif

#include <QColor>
#include <QCoreApplication>
#include <QImage>
#include <QVideoFrame>
#include <cmath>
#include <cstdlib>
#include <QFile>
#include <QDateTime>
#include <QElapsedTimer>
#include <QEventLoop>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QStringList>
#include <QPointer>
#include <QHash>
#include <QSet>
#include <QUdpSocket>
#include <memory>
#include <vector>
#include <cstdio>
#include <functional>

namespace
{
int g_fail = 0;
void check(bool ok, const char *what)
{
    std::printf("  %s %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok)
        ++g_fail;
}

// Runs the event loop until done() is true or ms pass.
bool waitFor(const std::function<bool()> &done, int ms)
{
    QElapsedTimer t;
    t.start();
    while (!done() && t.elapsed() < ms)
        QCoreApplication::processEvents(QEventLoop::AllEvents, 20);
    return done();
}

void relayChecks()
{
    std::printf("relay\n");
    const QByteArray key = QByteArray::fromBase64("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEAhfmF/C2RDkoJ4+WmZ5pojpPLBUr321s32bluAKC1O0ZSn3ry5dxLS3aPKhaqHZaVvRfx1hZllLyiXxlMG5XlA==");
    const QJsonObject b = QJsonDocument::fromJson(RelayCredentials::body(key, 1800000000, QByteArray(64, 'a'))).object();
    const QString id = b.value(QStringLiteral("id")).toString();
    const QByteArray want = CallCrypto::hmacSha256(QByteArray(64, 'a'), id.toLatin1() + ".1800000000").toHex();
    check(id.size() == 64 && id == QString::fromLatin1(CallCrypto::sha256(key).toHex()) && b.value(QStringLiteral("t")).toDouble() == 1800000000.0
              && b.value(QStringLiteral("t")).isDouble() && b.value(QStringLiteral("sig")).toString().toLatin1() == want,
          "Worker request: id, t as a number, sig over id.t");
    // The same request Muninn's TurnTest.workerBodyMatchesHuginn builds: id is SHA-256 of the raw SPKI DER.
    check(id == QLatin1String("76805a63a6980c18075424a13f32772d02d7c71b4965754c1ce84f5841220327")
              && want == "a0115b4186fe1780017780b84bf5a20f0501934df65efb994e35c88f745e188c",
          "Worker request id and sig match Muninn's");
    {
        // Install tokens (relay.md): the same proof of work vector as the Worker's test and Muninn's TurnTest.
        const QByteArray aid(64, 'a');
        check(RelayCredentials::solvePow(aid, 1800000000) == 465984 && RelayCredentials::powOk(aid, 1800000000, 465984)
                  && !RelayCredentials::powOk(aid, 1800000001, 465984, 30) && !RelayCredentials::powOk(aid, 1800000000, -1),
              "relay registration: proof of work matches the Worker's");
        const QJsonObject reg = QJsonDocument::fromJson(RelayCredentials::regBody(key, 1800000000, QByteArray(64, 'a'), 7)).object();
        const QJsonObject withTok = QJsonDocument::fromJson(RelayCredentials::body(key, 1, "s", QStringLiteral("x.y"))).object();
        check(reg.value(QStringLiteral("pow")).toDouble() == 7.0 && reg.value(QStringLiteral("id")).toString() == id && !b.contains(QStringLiteral("tok"))
                  && withTok.value(QStringLiteral("tok")).toString() == QLatin1String("x.y"),
              "relay registration body, and tok only when there is a token");
        check(!withTok.contains(QStringLiteral("rn")) && !reg.contains(QStringLiteral("rn"))
                  && QJsonDocument::fromJson(RelayCredentials::body(key, 1, "s", QString(), true)).object().value(QStringLiteral("rn")).toInt() == 1
                  && RelayCredentials::retryMs(QJsonObject{{QStringLiteral("retry"), 3600.0}}, 86400000) == 3600000
                  && RelayCredentials::retryMs(QJsonObject{{QStringLiteral("retry"), 5.0}}, 86400000) == 60000
                  && RelayCredentials::retryMs(QJsonObject(), 86400000) == -1,
              "relay renewal: rn only on a login, and a 429 waits as long as it says");
        const QString sid = QString(64, QLatin1Char('a'));
        const QString kept = RelayCredentials::tokenOf(QJsonObject{{QStringLiteral("tok"), QStringLiteral("1800604800.abc")}, {QStringLiteral("exp"), 1800604800.0}}, sid);
        check(kept == sid + QStringLiteral("|1800604800.abc|1800604800") && RelayCredentials::tokenFor(kept, sid, 1800000000) == QLatin1String("1800604800.abc")
                  && RelayCredentials::tokenFor(kept, QString(64, QLatin1Char('b')), 1800000000).isEmpty()
                  && RelayCredentials::tokenFor(kept, sid, 1800604800 - 86400).isEmpty()
                  && RelayCredentials::tokenOf(QJsonObject{{QStringLiteral("urls"), QJsonArray()}}, sid).isEmpty(),
              "relay tokens are kept per id and registered again with less than a day left");
        check(RelayCredentials::staleNow(QJsonObject{{QStringLiteral("error"), QStringLiteral("stale")}, {QStringLiteral("now"), 1800000000.0}}) == 1800000000
                  && RelayCredentials::staleNow(QJsonObject{{QStringLiteral("error"), QStringLiteral("stale")}}) == -1,
              "a stale answer's time corrects the clock");
    }
    {
        QObject ctx;
        QHostAddress got, lit;
        bool done = false, litDone = false;
        CallNet::resolve(QStringLiteral("localhost"), &ctx, [&](const QHostAddress &a) { got = a; done = true; }, 1);
        CallNet::resolve(QStringLiteral("104.30.1.2"), &ctx, [&](const QHostAddress &a) { lit = a; litDone = true; });
        waitFor([&] { return done && litDone; }, 5000);
        check(done && got.isLoopback(), "the relay's name is looked up (localhost answers)");
        check(litDone && lit == QHostAddress(QStringLiteral("104.30.1.2")), "an IP literal needs no lookup");
        QHostAddress b4, b6;
        bool bothDone = false;
        CallNet::resolveBoth(QStringLiteral("::1"), &ctx, [&](const QHostAddress &v4, const QHostAddress &v6) { b4 = v4; b6 = v6; bothDone = true; });
        waitFor([&] { return bothDone; }, 5000);
        check(bothDone && b4.isNull() && b6 == QHostAddress(QHostAddress::LocalHostIPv6),
              "an IPv6 literal is kept as the IPv6 address to fall back to");
    }
    const auto s = TurnClient::servers({QStringLiteral("turn:turn.cloudflare.com:3478?transport=udp"), QStringLiteral("turn:turn.cloudflare.com:3478?transport=tcp"),
                                        QStringLiteral("turns:turn.cloudflare.com:5349?transport=tcp"), QStringLiteral("turns:turn.cloudflare.com:443?transport=tcp"),
                                        QStringLiteral("stun:stun.cloudflare.com:3478")});
    check(s.size() == 3 && !s[0].tls && s[0].port == 3478 && s[1].tls && s[1].port == 443 && s[2].tls && s[2].port == 5349
              && s[0].host == QLatin1String("turn.cloudflare.com"),
          "TURN servers: UDP 3478, then TLS 443, then TLS 5349, never STUN");
}

void mediaLoopback()
{
    std::printf("media loopback (local UDP, no TURN)\n");
    if (!opusAvailable()) {
        check(false, "built with Opus");
        return;
    }
    QByteArray root(32, 0);
    for (int i = 0; i < 32; ++i)
        root[i] = char(i * 7 + 1);
    const QByteArray tag = CallCrypto::tag(QByteArray(16, 'x'));
    CallMedia a(root, tag, true), b(root, tag, false);
    QUdpSocket *sa = CallMedia::bindLan(nullptr), *sb = CallMedia::bindLan(nullptr);
    check(sa && sb, "two UDP sockets");
    if (!sa || !sb)
        return;
    const quint16 pa = sa->localPort(), pb = sb->localPort();
    a.adoptLan(sa);
    b.adoptLan(sb);
    a.setLanPeer(QHostAddress(QHostAddress::LocalHost), pb);
    b.setLanPeer(QHostAddress(QHostAddress::LocalHost), pa);
    // A second path that copies every packet: the receiver must keep only the first.
    QPointer<CallMedia> pA(&a), pB(&b);
    a.setBle([pB](const QByteArray &p) { QTimer::singleShot(3, pB, [pB, p] { if (pB) pB->receive(p, CallMedia::Ble); }); return true; });
    b.setBle([pA](const QByteArray &p) { QTimer::singleShot(3, pA, [pA, p] { if (pA) pA->receive(p, CallMedia::Ble); }); return true; });
    a.dropEvery = 10;   // 10 % of A's audio never leaves (on either path)
    a.start(false);
    b.start(false);
    check(waitFor([&] { return a.live(CallMedia::Wifi) && b.live(CallMedia::Wifi) && a.live(CallMedia::Ble) && b.live(CallMedia::Ble); }, 3000),
          "Wi-Fi and Bluetooth paths live both ways");

    qint64 phaseA = 0, phaseB = 0;
    int frames = 0;
    double hzAtB = 0, hzAtA = 0;
    int loudAtB = 0, loudAtA = 0;
    bool speakingB = false;
    QTimer clock;
    clock.setTimerType(Qt::PreciseTimer);
    clock.setInterval(20);
    QObject::connect(&clock, &QTimer::timeout, [&] {
        qint16 pcm[AudioDsp::kFrame];
        AudioDsp::tone(pcm, AudioDsp::kFrame, 440, 0.4, &phaseA);
        a.feed(pcm);
        AudioDsp::tone(pcm, AudioDsp::kFrame, 660, 0.4, &phaseB);
        b.feed(pcm);
        qint16 out[AudioDsp::kFrame];
        b.playFrame(out);
        if (AudioDsp::levelDb(out, AudioDsp::kFrame) > -30) {
            hzAtB += AudioDsp::zeroCrossingHz(out, AudioDsp::kFrame);
            ++loudAtB;
        }
        speakingB = speakingB || b.speaking();
        a.playFrame(out);
        if (AudioDsp::levelDb(out, AudioDsp::kFrame) > -30) {
            hzAtA += AudioDsp::zeroCrossingHz(out, AudioDsp::kFrame);
            ++loudAtA;
        }
        ++frames;
    });
    clock.start();
    waitFor([&] { return frames >= 150; }, 6000);   // 3 seconds of sound
    clock.stop();
    waitFor([] { return false; }, 200);
    std::printf("       A sent %d, B got %d (+%d copies), B played %d, FEC %d, concealed %d, delay %d ms, rtt %d ms\n", a.stats().audioSent,
                b.stats().audioIn, b.stats().duplicates, b.jitter().stats().played, b.jitter().stats().fec, b.jitter().stats().concealed,
                b.jitter().targetMs(), a.rttMs());
    check(b.stats().audioIn >= a.stats().audioSent * 8 / 10 && b.stats().audioIn <= a.stats().audioSent, "B got A's audio, each packet once");
    check(b.stats().duplicates >= b.stats().audioIn / 2, "copies from the second path dropped as duplicates");
    check(b.stats().refused == 0 && a.stats().refused == 0, "nothing refused");
    check(b.jitter().stats().fec + b.jitter().stats().concealed > 0, "lost packets rebuilt (FEC) or concealed");
    check(loudAtB > 80 && hzAtB / qMax(1, loudAtB) > 380 && hzAtB / qMax(1, loudAtB) < 500, "B hears A's 440 Hz tone");
    check(loudAtA > 80 && hzAtA / qMax(1, loudAtA) > 580 && hzAtA / qMax(1, loudAtA) < 740, "A hears B's 660 Hz tone");
    check(speakingB, "speaking indicator");
    a.setMuted(true);
    const int before = b.stats().audioIn;
    for (int i = 0; i < 25; ++i) {
        qint16 pcm[AudioDsp::kFrame];
        AudioDsp::tone(pcm, AudioDsp::kFrame, 440, 0.4, &phaseA);
        a.feed(pcm);
    }
    waitFor([] { return false; }, 200);
    check(b.stats().audioIn == before && b.live(CallMedia::Wifi), "muted: no audio sent, the path stays live on pings");
    a.stop();
    b.stop();
}

struct Side {
    CallCrypto::Ephemeral id;   // stands in for the chat key
    CallSignalHooks *sig = nullptr;
    QPointer<CallSession> session;
};

// Messages exactly as Muninn's CallLogic builds them (CallLogicTest.crossApp checks the same text),
// and the other forms both apps accept: each side reads the other's.
void crossApp()
{
    std::printf("Muninn's candidate formats\n");
    auto parse = [](const char *json) { return CallSession::parseCands(QJsonDocument::fromJson(QByteArray(json)).object()); };
    const Stun::Address relay = CallNet::fromHost(QHostAddress(QStringLiteral("104.30.1.2")), 61000);
    CallSession::Cands c = parse(R"({"t":"cands","call":"wMHCw8TFxsfIycrLzM3Ozw","cands":[{"t":"relay","a":"104.30.1.2","p":61000},{"t":"lan","p":40000}],"lp":40000})");
    check(c.ok && c.relay == relay && c.lanPort == 40000, "Muninn: relay, lan candidate and lp");
    c = parse(R"({"t":"cands","call":"wMHCw8TFxsfIycrLzM3Ozw","cands":[{"t":"relay","a":"104.30.1.2","p":61000}],"lp":40001})");
    check(c.ok && c.relay == relay && c.lanPort == 40001, "lp alone");
    c = parse(R"({"t":"cands","call":"wMHCw8TFxsfIycrLzM3Ozw","cands":[{"t":"lan","p":40002}]})");
    check(c.ok && !c.relay.valid() && c.lanPort == 40002, "a lan candidate alone");
    c = parse(R"({"t":"cands","call":"wMHCw8TFxsfIycrLzM3Ozw","cands":[{"t":"host","a":"192.168.1.20","p":40312},{"t":"srflx","a":"203.0.113.7","p":51820},{"t":"relay","a":"evil.example","p":3478},{"t":"relay","a":"104.30.1.2","p":61000}]})");
    check(c.ok && c.relay == relay && c.lanPort == 0, "host, srflx and a relay by name are ignored");
    c = parse(R"({"t":"cands","call":"wMHCw8TFxsfIycrLzM3Ozw","cands":[{},{},{},{},{},{},{},{},{}]})");
    check(!c.ok, "more than 8 candidates is malformed");
}

void signalingLoopback()
{
    std::printf("signaling loopback (two sessions in-process)\n");
    Side a, b;
    a.id = CallCrypto::newEphemeral();
    b.id = CallCrypto::newEphemeral();
    const QString keyA = QString::fromLatin1(a.id.publicDer.toBase64()), keyB = QString::fromLatin1(b.id.publicDer.toBase64());
    a.sig = new CallSignalHooks();
    b.sig = new CallSignalHooks();
    for (Side *s : {&a, &b}) {
        Side *other = s == &a ? &b : &a;
        const QString mine = s == &a ? keyA : keyB;
        s->sig->onMyKey = [mine] { return mine; };
        s->sig->onSign = [s](const QByteArray &bytes) { return CallCrypto::signDer(s->id.privateDer, bytes); };
        s->sig->onNameOf = [](const QString &) { return QStringLiteral("Test"); };
        s->sig->onCanCall = [](const QString &) { return true; };
        s->sig->onLanHost = [](const QString &) { return QStringLiteral("127.0.0.1"); };
        QPointer<CallSignalHooks> to = other->sig;
        s->sig->onSend = [to, mine](const QString &, const QJsonObject &m) {
            QTimer::singleShot(5, to, [to, mine, m] { if (to) to->deliver(mine, m); });
        };
    }
    // B: rings on the offer and answers after a moment, like a person would.
    QObject::connect(b.sig, &CallSignal::received, b.sig, [&b](const QString &from, const QJsonObject &m) {
        if (b.session) {
            b.session->handle(m);
            return;
        }
        if (m.value(QStringLiteral("t")).toString() != QLatin1String("offer"))
            return;
        if (!CallSession::checkOffer(m, from, QDateTime::currentMSecsSinceEpoch()).isEmpty())
            return;
        b.session = new CallSession(b.sig, from, nullptr, b.sig);
        b.session->useDevices = false;
        b.session->takeOffer(m);
        QTimer::singleShot(300, b.session, [&b] { b.session->answer(); });
    });
    QObject::connect(a.sig, &CallSignal::received, a.sig, [&a](const QString &, const QJsonObject &m) {
        if (a.session)
            a.session->handle(m);
    });
    a.session = new CallSession(a.sig, keyB, nullptr, a.sig);
    a.session->useDevices = false;
    a.session->start();
    check(waitFor([&] { return b.session && b.session->state() == CallSession::Incoming; }, 3000), "B rings");
    check(waitFor([&] { return a.session->state() == CallSession::Ringing || a.session->state() == CallSession::Connecting
                               || a.session->state() == CallSession::Active; }, 3000),
          "A hears it ringing");
    check(waitFor([&] { return a.session->state() == CallSession::Active && b.session->state() == CallSession::Active; }, 6000),
          "both sides connected (answer signed, keys agree, Wi-Fi path live)");
    check(a.session->media() && a.session->media()->live(CallMedia::Wifi) && !a.session->media()->live(CallMedia::Relay), "over Wi-Fi only (no relay in this test)");
    a.session->setMuted(true);
    check(waitFor([&] { return b.session->peerMuted(); }, 2000), "mute reaches the other side");
    a.session->hangup();
    check(waitFor([&] { return b.session->state() == CallSession::Ended; }, 3000) && b.session->endText() == QLatin1String("Call ended"),
          "hangup ends both sides");

    // A forged answer (signed by someone else) is refused.
    Side c;
    c.id = CallCrypto::newEphemeral();
    CallSignalHooks lone;
    QJsonObject lastOffer;
    lone.onSend = [&lastOffer](const QString &, const QJsonObject &m) { if (m.value(QStringLiteral("t")).toString() == QLatin1String("offer")) lastOffer = m; };
    lone.onSign = [&a](const QByteArray &bytes) { return CallCrypto::signDer(a.id.privateDer, bytes); };
    lone.onCanCall = [](const QString &) { return true; };
    CallSession caller(&lone, keyB, nullptr);
    caller.useDevices = false;
    caller.start();
    waitFor([&] { return !lastOffer.isEmpty(); }, 6000);
    const CallCrypto::Ephemeral eph = CallCrypto::newEphemeral();
    const QString e = QString::fromLatin1(eph.publicDer.toBase64());
    const QString callId = caller.callId();
    caller.handle(QJsonObject{{QStringLiteral("t"), QStringLiteral("answer")}, {QStringLiteral("call"), callId}, {QStringLiteral("eph"), e},
                              {QStringLiteral("sig"), QString::fromLatin1(CallCrypto::signDer(c.id.privateDer, CallCrypto::signedBytes(QStringLiteral("answer"), callId, e)).toBase64())}});
    check(caller.state() == CallSession::Calling && !caller.media(), "an answer signed by someone else is refused");
    check(CallSession::checkOffer(lastOffer, keyA, QDateTime::currentMSecsSinceEpoch()).isEmpty(), "our own offer checks out");
    check(CallSession::checkOffer(lastOffer, keyB, QDateTime::currentMSecsSinceEpoch()) == QLatin1String("signature"), "an offer from the wrong key is refused");
    check(CallSession::checkOffer(lastOffer, keyA, QDateTime::currentMSecsSinceEpoch() + 61000) == QLatin1String("stale"), "an offer over 60 s old is a missed call");
    {
        // Round 14: a caller's clock 70 s slow still rings once a second copy shows it is live; a relay's burst never does.
        CallSkewGate g;
        const qint64 now = 1800000000000, ts = now - 70000;
        const bool live = g.judge(QStringLiteral("A"), ts, now, 1000) == QLatin1String("wait") && g.judge(QStringLiteral("A"), ts, now + 3000, 4000) == QLatin1String("ring");
        const bool burst = g.judge(QStringLiteral("B"), ts, now, 1000) == QLatin1String("wait") && g.judge(QStringLiteral("B"), ts, now, 1100) == QLatin1String("wait")
                           && g.judge(QStringLiteral("B"), ts, now + 40000, 41000) == QLatin1String("missed");
        check(live && burst && g.judge(QStringLiteral("C"), now - 11 * 60000, now, 1) == QLatin1String("missed")
                  && g.judge(QStringLiteral("D"), now + 5 * 60000, now, 1) == QLatin1String("wait") && g.judge(QStringLiteral("E"), now - 1000, now, 1) == QLatin1String("ring"),
              "offers from a skewed clock ring once a second copy comes (5.2)");
    }
    const QJsonArray cands = lastOffer.value(QStringLiteral("cands")).toArray();
    bool noIp = true;
    for (const QJsonValue &v : cands)
        if (v.toObject().value(QStringLiteral("t")).toString() != QLatin1String("relay") && v.toObject().contains(QStringLiteral("a")))
            noIp = false;
    check(noIp, "offers carry no address but the relay's");
    {
        const QJsonObject mine = a.session->offerMessage();
        const CallSession::Cands c = CallSession::parseCands(mine);
        bool lanCand = false;
        for (const QJsonValue &v : mine.value(QStringLiteral("cands")).toArray())
            lanCand = lanCand || v.toObject().value(QStringLiteral("t")).toString() == QLatin1String("lan");
        check(lanCand && mine.value(QStringLiteral("lp")).toInt() == c.lanPort && c.lanPort != 0 && mine.value(QStringLiteral("ts")).isDouble()
                  && QJsonDocument(mine).toJson(QJsonDocument::Compact).contains("\"ts\":" + QByteArray::number(qint64(mine.value(QStringLiteral("ts")).toDouble()))),
              "an offer on Wi-Fi says the port both ways (lan candidate and lp) and ts is a plain integer");
    }
    crossApp();
    caller.hangup();
    delete a.sig;
    delete b.sig;
}

// Two c2 apps on one computer: receiver reports, the higher Opus bitrate, and video fragments with
// a lost packet now and then (resent after a NACK) arriving whole and in order.
void c2Media()
{
    std::printf("c2 media: reports, bitrate, video with resends\n");
    if (!opusAvailable()) {
        check(false, "built with Opus");
        return;
    }
    QByteArray root(32, 0);
    for (int i = 0; i < 32; ++i)
        root[i] = char(i * 5 + 3);
    const QByteArray tag = CallCrypto::tag(QByteArray(16, 'v'));
    CallMedia a(root, tag, true), b(root, tag, false);
    QUdpSocket *sa = CallMedia::bindLan(nullptr), *sb = CallMedia::bindLan(nullptr);
    if (!sa || !sb) {
        check(false, "two UDP sockets");
        return;
    }
    const quint16 pa = sa->localPort(), pb = sb->localPort();
    a.adoptLan(sa);
    b.adoptLan(sb);
    a.setLanPeer(QHostAddress(QHostAddress::LocalHost), pb);
    b.setLanPeer(QHostAddress(QHostAddress::LocalHost), pa);
    a.setC2(true, false);
    b.setC2(true, false);
    a.start(false);
    b.start(false);
    check(!a.micGated() && !b.micGated(), "a 1:1 call's microphone is never gated by voice activity");
    check(waitFor([&] { return a.live(CallMedia::Wifi) && b.live(CallMedia::Wifi); }, 3000), "Wi-Fi path live both ways");
    qint64 ph = 0;
    QTimer clock;
    clock.setInterval(20);
    QObject::connect(&clock, &QTimer::timeout, [&] {
        qint16 pcm[AudioDsp::kFrame];
        AudioDsp::tone(pcm, AudioDsp::kFrame, 440, 0.4, &ph);
        a.feed(pcm);
        b.feed(pcm);
        qint16 out[AudioDsp::kFrame];
        a.playFrame(out);
        b.playFrame(out);
    });
    clock.start();
    waitFor([] { return false; }, 2300);
    check(a.stats().reports >= 1 && b.stats().reports >= 1, "both send a receiver report every second");
    check(a.encoder().bitrate() == 32000, "Opus at 32 kbps on a free path (Wi-Fi)");
    check(a.freeFresh() && a.stats().relayAudio == 0, "the relay rests while Wi-Fi carries the call");

    // Video: 60 frames of made-up "VP8", every 9th packet lost on the way.
    std::vector<QByteArray> sent, got;
    bool keyAsked = false, peerSees = false;
    QObject::connect(&b, &CallMedia::videoFrame, [&](int stream, const QByteArray &data, bool, int rotation) {
        if (stream == VideoWire::Camera && rotation == 90)
            got.push_back(data);
    });
    QObject::connect(&a, &CallMedia::keyframeWanted, [&](int) { keyAsked = true; });
    QObject::connect(&b, &CallMedia::peerVideoChanged, [&] { peerSees = b.peerSending(VideoWire::Camera); });
    a.setVideoSending(VideoWire::Camera, true);
    a.dropVideoEvery = 9;
    for (int i = 0; i < 60; ++i) {
        QByteArray f(1500 + (i % 7) * 900, 0);
        for (int k = 0; k < f.size(); ++k)
            f[k] = char((k + i * 13) & 0xFF);
        sent.push_back(f);
        const bool ok = a.sendVideo(VideoWire::Camera, i == 0 || keyAsked, 90, f);
        keyAsked = false;
        if (i == 0)
            check(ok, "a keyframe goes out");
        waitFor([] { return false; }, 25);
    }
    waitFor([&] { return got.size() >= 60; }, 3000);
    std::printf("       video: sent %d packets, resent %d, B put together %d frames, asked %d times\n", a.stats().videoSent, a.stats().resent,
                int(got.size()), b.assembler(VideoWire::Camera).stats().nacked);
    check(peerSees, "B knows A's camera is on");
    check(got.size() == 60 && got == sent, "every frame arrives whole and in order, lost packets resent after a NACK");
    check(a.stats().resent > 0 && b.assembler(VideoWire::Camera).stats().abandoned == 0, "losses repaired by resending, nothing given up");
    a.setVideoSending(VideoWire::Camera, false);
    check(waitFor([&] { return !b.peerSending(VideoWire::Camera); }, 2000), "camera off reaches B");
    clock.stop();
    a.stop();
    b.stop();
}

// The c2 part of shared/vectors/proto06-vectors.json ("call" > "c2"), which Muninn's CallTest checks too.
void c2Vectors(const QString &path)
{
    std::printf("c2 vectors (video fragments, report, NACK, sealed packets)\n");
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly)) {
        check(false, "vectors file opens");
        return;
    }
    const QJsonObject call = QJsonDocument::fromJson(f.readAll()).object().value(QStringLiteral("call")).toObject();
    const QJsonObject x = call.value(QStringLiteral("c2")).toObject();
    const QByteArray vp8 = QByteArray::fromHex(x.value(QStringLiteral("vp8")).toString().toLatin1());
    const QList<QByteArray> frags = VideoWire::split(x.value(QStringLiteral("stream")).toInt(), quint16(x.value(QStringLiteral("frame")).toInt()),
                                                     x.value(QStringLiteral("key")).toBool(), x.value(QStringLiteral("rotation")).toInt(), vp8);
    QStringList hex;
    for (const QByteArray &b : frags)
        hex << QString::fromLatin1(b.toHex());
    QStringList want;
    for (const QJsonValue &v : x.value(QStringLiteral("fragments")).toArray())
        want << v.toString();
    check(!want.isEmpty() && hex == want, "a frame splits into the same fragments as the reference");
    const QJsonObject r = x.value(QStringLiteral("report")).toObject();
    VideoWire::Report rep;
    rep.lossPct = r.value(QStringLiteral("loss")).toInt();
    rep.camera = r.value(QStringLiteral("camera")).toBool();
    rep.screen = r.value(QStringLiteral("screen")).toBool();
    rep.jitterMs = r.value(QStringLiteral("jitter")).toInt();
    rep.kbps = r.value(QStringLiteral("kbps")).toInt();
    rep.rttMs = r.value(QStringLiteral("rtt")).toInt();
    check(QString::fromLatin1(VideoWire::encodeReport(rep).toHex()) == r.value(QStringLiteral("bytes")).toString(), "the report's 8 bytes match");
    const QJsonObject nk = x.value(QStringLiteral("nack")).toObject();
    VideoWire::Nack n;
    n.stream = nk.value(QStringLiteral("stream")).toInt();
    n.frame = quint16(nk.value(QStringLiteral("frame")).toInt());
    for (const QJsonValue &v : nk.value(QStringLiteral("indexes")).toArray())
        n.indexes << v.toInt();
    check(QString::fromLatin1(VideoWire::encodeNack(n).toHex()) == nk.value(QStringLiteral("bytes")).toString(), "the NACK's bytes match");
    const QByteArray root = QByteArray::fromHex(call.value(QStringLiteral("root")).toString().toLatin1());
    const QByteArray tag = QByteArray::fromHex(call.value(QStringLiteral("tag")).toString().toLatin1());
    CallCrypto::Keyring ka(root), kb(root);
    CallCrypto::Receiver toB(&kb, CallCrypto::O2A, tag), toA(&ka, CallCrypto::A2O, tag);
    bool all = true;
    int count = 0;
    for (const QJsonValue &v : x.value(QStringLiteral("packets")).toArray()) {
        const QJsonObject p = v.toObject();
        const bool o2a = p.value(QStringLiteral("dir")).toString() == QLatin1String("o2a");
        CallCrypto::Sender tx(o2a ? &ka : &kb, o2a ? CallCrypto::O2A : CallCrypto::A2O, tag);
        const QByteArray payload = QByteArray::fromHex(p.value(QStringLiteral("payload")).toString().toLatin1());
        const QByteArray sealed = tx.sealAt(p.value(QStringLiteral("type")).toInt(), quint32(p.value(QStringLiteral("seq")).toDouble()),
                                            quint32(p.value(QStringLiteral("ts")).toDouble()), payload);
        CallCrypto::Receiver::Packet got;
        const QString why = (o2a ? toB : toA).open(QByteArray::fromHex(p.value(QStringLiteral("packet")).toString().toLatin1()), &got);
        all = all && QString::fromLatin1(sealed.toHex()) == p.value(QStringLiteral("packet")).toString() && why.isEmpty() && got.payload == payload
              && got.type == p.value(QStringLiteral("type")).toInt();
        ++count;
    }
    check(count == 6 && all, "video, report, NACK and keyframe request packets seal and open like the reference");
}

// Real VP8 through two c2 medias on one computer: libvpx encodes a moving picture at 640 x 480, the
// fragments go over the Wi-Fi path with a packet lost now and then, and the other side's decoder
// (on its worker thread, as in a call) shows every frame at the right size and close to the original.
void vp8Loopback()
{
    std::printf("VP8 video loopback (libvpx, 640 x 480)\n");
    if (!vpxAvailable()) {
        std::printf("  --   built without libvpx: video calls are left out of this build\n");
        return;
    }
    QByteArray root(32, 0);
    for (int i = 0; i < 32; ++i)
        root[i] = char(i * 11 + 1);
    const QByteArray tag = CallCrypto::tag(QByteArray(16, 'w'));
    CallMedia a(root, tag, true), b(root, tag, false);
    QUdpSocket *sa = CallMedia::bindLan(nullptr), *sb = CallMedia::bindLan(nullptr);
    if (!sa || !sb) {
        check(false, "two UDP sockets");
        return;
    }
    const quint16 pa = sa->localPort(), pb = sb->localPort();
    a.adoptLan(sa);
    b.adoptLan(sb);
    a.setLanPeer(QHostAddress(QHostAddress::LocalHost), pb);
    b.setLanPeer(QHostAddress(QHostAddress::LocalHost), pa);
    a.setC2(true, false);
    b.setC2(true, false);
    a.start(false);
    b.start(false);
    waitFor([&] { return a.live(CallMedia::Wifi) && b.live(CallMedia::Wifi); }, 3000);
    Vp8Encoder enc(640, 480, 24, 700);
    check(enc.ok(), "the VP8 encoder starts");
    VideoReceiver rx;
    QObject::connect(&b, &CallMedia::videoFrame, &rx, [&rx](int stream, const QByteArray &vp8, bool key, int rotation) {
        if (stream == VideoWire::Camera)
            rx.frame(vp8, key, rotation);
    });
    bool wantKey = true;
    QObject::connect(&a, &CallMedia::keyframeWanted, [&](int) { wantKey = true; });
    QObject::connect(&rx, &VideoReceiver::needKey, [&] { b.requestKeyframe(VideoWire::Camera); });
    a.setVideoSending(VideoWire::Camera, true);
    a.dropVideoEvery = 13;
    int sent = 0, keys = 0;
    qint64 bytes = 0;
    for (int i = 0; i < 72; ++i) {
        bool isKey = false;
        const QByteArray f = enc.encode(VideoFrames::testPattern(640, 480, i), wantKey, &isKey);
        if (!f.isEmpty()) {
            if (isKey) {
                wantKey = false;
                ++keys;
            }
            a.sendVideo(VideoWire::Camera, isKey, 0, f);
            ++sent;
            bytes += f.size();
        }
        waitFor([] { return false; }, 40);
    }
    int width = 0, height = 0;
    check(keys >= 1 && sent >= 60, "72 pictures make at least 60 VP8 frames, the first a keyframe");
    waitFor([&] { return rx.frames() >= sent - 2; }, 4000);
    std::printf("       %d frames sent (%d keyframes, %lld kbps at 24 fps), %d shown, %d packets resent\n", sent, keys,
                (long long)(bytes * 8 * 24 / qMax(1, sent) / 1000), rx.frames(), a.stats().resent);
    check(rx.frames() >= sent - 2 && rx.size() == QSize(640, 480), "the other side shows the frames at 640 x 480");
    check(a.stats().resent > 0, "lost packets were resent (NACK)");
    bool forced = false;
    check(vp8KeyframeSize(enc.encode(VideoFrames::testPattern(640, 480, 99), true, &forced), &width, &height) && forced && width == 640 && height == 480,
          "a keyframe says its size");
    // The last picture is close to what was sent (a broken decode would be far off).
    QVideoFrame last = rx.lastFrame();
    bool close = false;
    if (last.isValid() && last.map(HUGINN_MAP_READ)) {
        const I420 want = VideoFrames::testPattern(640, 480, 71);
        qint64 diff = 0;
        const uchar *y = last.bits(0);
        for (int r = 0; r < 480; r += 8)
            for (int c = 0; c < 640; c += 8)
                diff += std::abs(int(y[r * last.bytesPerLine(0) + c]) - int(want.y()[r * 640 + c]));
        last.unmap();
        close = diff / (60 * 80) < 24;
    }
    check(close, "and looks like the picture that was sent");
    // A picture from the camera path (QImage to I420) at the profile's size.
    QImage img(1280, 720, QImage::Format_RGB32);
    img.fill(QColor(200, 30, 30));
    const I420 small = VideoFrames::toI420(img, QSize(640, 480));
    check(small.width == 640 && small.height == 360 && small.valid() && small.y()[0] > 60 && small.y()[0] < 90 && small.v()[0] > 200,
          "camera pictures are scaled to fit 640 x 480 (16:9 stays 16:9) and turned into I420");
    a.stop();
    b.stop();
}

// Someone who copies a real packet and sends it again from their own address must not become the
// Direct path: only packets that open as new teach an address (5.8.2).
void directReplay()
{
    std::printf("direct path: replayed packets teach nothing\n");
    DirectPath::g_testLocal = true;
    QByteArray root(32, 0);
    for (int i = 0; i < 32; ++i)
        root[i] = char(i * 13 + 5);
    const QByteArray tag = CallCrypto::tag(QByteArray(16, 'r'));
    CallMedia a(root, tag, true), b(root, tag, false);
    QUdpSocket *sa = CallMedia::bindLan(nullptr), *sb = CallMedia::bindLan(nullptr);
    QUdpSocket thief;
    thief.bind(QHostAddress(QHostAddress::LocalHost), 0);
    if (!sa || !sb) {
        check(false, "two UDP sockets");
        return;
    }
    const quint16 pa = sa->localPort(), pb = sb->localPort();
    a.adoptLan(sa);
    b.adoptLan(sb);
    a.enableDirect({CallNet::fromHost(QHostAddress(QHostAddress::LocalHost), pb)});
    b.enableDirect({CallNet::fromHost(QHostAddress(QHostAddress::LocalHost), pa)});
    QByteArray copied;
    // A copy of what B really got (on a second path): to B, the thief's packet is a duplicate.
    QPointer<CallMedia> pB(&b);
    a.setBle([&copied, pB](const QByteArray &pkt) {
        if (copied.isEmpty())
            copied = pkt;
        if (pB)
            pB->receive(pkt, CallMedia::Ble);
        return true;
    });
    a.start(false);
    b.start(false);
    check(waitFor([&] { return a.live(CallMedia::Direct) && b.live(CallMedia::Direct) && !copied.isEmpty(); }, 3000), "direct path live both ways");
    const Stun::Address before = b.directAddr();
    const int dups = b.stats().duplicates;
    for (int i = 0; i < 5; ++i)
        thief.writeDatagram(copied, QHostAddress(QHostAddress::LocalHost), pb);
    waitFor([] { return false; }, 300);
    check(b.stats().duplicates > dups && b.directAddr() == before && b.directAddr().port == pa, "copies from another address are dropped and don't move the path");
    b.disableDirect();
    check(!b.directEnabled() && !b.directAddr().valid(), "turning Direct calls off drops the path at once");
    a.stop();
    b.stop();
    DirectPath::g_testLocal = false;
}

// The fake STUN server of the direct test: answers every Binding with the address it came from.
class FakeStun
{
public:
    QUdpSocket sock;
    int answered = 0;
    FakeStun()
    {
        sock.bind(QHostAddress(QHostAddress::LocalHost), 0);
        QObject::connect(&sock, &QUdpSocket::readyRead, &sock, [this] {
            while (sock.hasPendingDatagrams()) {
                QByteArray b(int(sock.pendingDatagramSize()), 0);
                QHostAddress from;
                quint16 port = 0;
                b.resize(int(sock.readDatagram(b.data(), b.size(), &from, &port)));
                Stun::Message m;
                if (!Stun::parse(b, &m) || m.method != Stun::Binding || m.cls != Stun::Request)
                    continue;
                Stun::Message r;
                r.method = Stun::Binding;
                r.cls = Stun::Success;
                r.tid = m.tid;
                r.addAddress(Stun::XorMappedAddress, CallNet::fromHost(from, port));
                sock.writeDatagram(Stun::encode(r, QByteArray(), true), from, port);
                ++answered;
            }
        });
    }
};

// Two people who trust each other, with no Wi-Fi and no relay between them: the call goes direct
// (STUN answer, candidates in offer and answer, pings both ways). Then one side doesn't trust the
// other: nothing about either address is sent, and nothing is tried.
void directCall()
{
    std::printf("direct call between trusted contacts (fake STUN, no relay, no Wi-Fi)\n");
    DirectPath::g_testLocal = true;
    FakeStun stun;
    const QString stunAt = QStringLiteral("127.0.0.1:%1").arg(stun.sock.localPort());
    for (int round = 0; round < 3; ++round) {
        // round 0: both trust; 1: the callee doesn't trust the caller; 2: the callee has no c2.
        Side a, b;
        a.id = CallCrypto::newEphemeral();
        b.id = CallCrypto::newEphemeral();
        const QString keyA = QString::fromLatin1(a.id.publicDer.toBase64()), keyB = QString::fromLatin1(b.id.publicDer.toBase64());
        a.sig = new CallSignalHooks();
        b.sig = new CallSignalHooks();
        QList<QJsonObject> fromB, fromA;
        for (Side *s : {&a, &b}) {
            Side *other = s == &a ? &b : &a;
            const QString mine = s == &a ? keyA : keyB;
            QList<QJsonObject> *log = s == &a ? &fromA : &fromB;
            s->sig->onMyKey = [mine] { return mine; };
            s->sig->onSign = [s](const QByteArray &bytes) { return CallCrypto::signDer(s->id.privateDer, bytes); };
            s->sig->onCanCall = [](const QString &) { return true; };
            QPointer<CallSignalHooks> to = other->sig;
            s->sig->onSend = [to, mine, log](const QString &, const QJsonObject &m) {
                log->append(m);
                QTimer::singleShot(5, to, [to, mine, m] { if (to) to->deliver(mine, m); });
            };
        }
        QObject::connect(b.sig, &CallSignal::received, b.sig, [&b, &stunAt, round](const QString &from, const QJsonObject &m) {
            if (b.session) {
                b.session->handle(m);
                return;
            }
            if (m.value(QStringLiteral("t")).toString() != QLatin1String("offer") || !CallSession::checkOffer(m, from, QDateTime::currentMSecsSinceEpoch()).isEmpty())
                return;
            b.session = new CallSession(b.sig, from, nullptr, b.sig);
            b.session->useDevices = false;
            b.session->lanHostOverride = [](const QString &) { return QString(); };
            b.session->stunHost = stunAt;
            b.session->trusted = round != 1;
            b.session->c2Local = round != 2;
            b.session->takeOffer(m);
            QTimer::singleShot(300, b.session, [&b] { b.session->answer(); });
        });
        QObject::connect(a.sig, &CallSignal::received, a.sig, [&a](const QString &, const QJsonObject &m) {
            if (a.session)
                a.session->handle(m);
        });
        a.session = new CallSession(a.sig, keyB, nullptr, a.sig);
        a.session->useDevices = false;
        a.session->lanHostOverride = [](const QString &) { return QString(); };
        a.session->stunHost = stunAt;
        a.session->trusted = true;
        a.session->start();
        auto hasAddr = [](const QList<QJsonObject> &msgs, const char *type) {
            for (const QJsonObject &m : msgs)
                for (const QJsonValue &v : m.value(QStringLiteral("cands")).toArray())
                    if (v.toObject().value(QStringLiteral("t")).toString() == QLatin1String(type))
                        return true;
            return false;
        };
        if (round == 0) {
            check(waitFor([&] { return a.session->state() == CallSession::Active && b.session && b.session->state() == CallSession::Active; }, 8000),
                  "both trust each other: connected with no relay and no Wi-Fi");
            check(a.session->directTried() && b.session->directTried() && a.session->media() && a.session->media()->live(CallMedia::Direct)
                      && b.session->media()->live(CallMedia::Direct),
                  "over the direct path, both ways");
            check(hasAddr(fromA, "srflx") && hasAddr(fromB, "srflx") && stun.answered >= 2, "each side sent what the STUN server saw (srflx)");
            CallMedia *ma = a.session->media(), *mb = b.session->media();
            const int inB = mb->stats().audioIn;
            qint64 ph = 0;
            for (int i = 0; i < 25; ++i) {
                qint16 pcm[AudioDsp::kFrame];
                AudioDsp::tone(pcm, AudioDsp::kFrame, 440, 0.4, &ph);
                ma->feed(pcm);
                waitFor([] { return false; }, 20);
            }
            check(waitFor([&] { return mb->stats().audioIn >= inB + 20; }, 3000) && ma->stats().relayAudio == 0, "A's audio reaches B directly");
            check(a.session->bothC2() && ma->c2(), "both speak c2");
        } else if (round == 1) {
            waitFor([&] { return b.session && b.session->state() == CallSession::Connecting; }, 4000);
            waitFor([] { return false; }, 600);
            check(hasAddr(fromA, "srflx") && !hasAddr(fromB, "srflx") && !hasAddr(fromB, "host"), "the callee who doesn't trust sends no address of its own");
            bool noP2p = true;
            for (const QJsonObject &m : fromB)
                noP2p = noP2p && !m.contains(QStringLiteral("p2p"));
            check(noP2p && b.session && !b.session->directTried() && !a.session->directTried(), "and neither side tries the direct path");
        } else {
            check(waitFor([&] { return b.session && b.session->state() == CallSession::Connecting; }, 4000), "a callee without c2 answers");
            waitFor([] { return false; }, 600);
            bool plain = true;
            for (const QJsonObject &m : fromB)
                plain = plain && !m.contains(QStringLiteral("c2")) && !m.contains(QStringLiteral("p2p"));
            check(plain && !a.session->bothC2() && a.session->media() && !a.session->media()->c2() && !a.session->directTried(),
                  "with an app without c2: no c2 features, no direct path, a plain call");
        }
        a.session->hangup();
        waitFor([&] { return !b.session || b.session->state() == CallSession::Ended; }, 3000);
        delete a.sig;
        delete b.sig;
    }
    DirectPath::g_testLocal = false;
}

// A small TURN server (RFC 8656 over UDP, long-term credentials) that translates addresses like a
// relay with several sites: each allocation sends from another port than the relayed address it
// reported (Cloudflare anchors each allocation at the nearest data center). Its first ChannelBind or
// CreatePermission gets a 438 (stale nonce) without MESSAGE-INTEGRITY, as servers send it.
class FakeTurn
{
public:
    struct Alloc {
        QHostAddress client;
        quint16 clientPort = 0;
        QUdpSocket *in = nullptr, *out = nullptr;
        QSet<QByteArray> perms;   // per IP address
        QHash<quint16, Stun::Address> chans;
    };
    QUdpSocket main;
    const QString realm = QStringLiteral("test.relay");
    QString nonce = QStringLiteral("nonce-1");
    int stale = 0, channelDataIn = 0, dataOut = 0;
    std::vector<std::unique_ptr<Alloc>> allocs;

    FakeTurn()
    {
        main.bind(QHostAddress(QHostAddress::LocalHost), 0);
        QObject::connect(&main, &QUdpSocket::readyRead, &main, [this] { onMain(); });
    }
    ~FakeTurn()
    {
        for (auto &a : allocs) {
            delete a->in;
            delete a->out;
        }
    }
    quint16 port() const { return main.localPort(); }

    Alloc *find(const QHostAddress &h, quint16 p)
    {
        for (auto &a : allocs)
            if (a->clientPort == p && a->client.isEqual(h, QHostAddress::TolerantConversion))
                return a.get();
        return nullptr;
    }
    void out(Alloc *a, const Stun::Address &peer, const QByteArray &data)
    {
        if (a && a->perms.contains(peer.ip))
            a->out->writeDatagram(data, CallNet::toHost(peer), peer.port);
    }
    void onRelay(Alloc *a, QUdpSocket *s)
    {
        while (s->hasPendingDatagrams()) {
            QByteArray b(int(s->pendingDatagramSize()), 0);
            QHostAddress from;
            quint16 port = 0;
            b.resize(int(s->readDatagram(b.data(), b.size(), &from, &port)));
            const Stun::Address src = CallNet::fromHost(from, port);
            if (!a->perms.contains(src.ip))
                continue;
            quint16 ch = 0;
            for (auto it = a->chans.cbegin(); it != a->chans.cend(); ++it)
                if (it.value() == src)
                    ch = it.key();
            QByteArray msg;
            if (ch) {
                msg = Stun::channelData(ch, b, false);
            } else {
                Stun::Message m;
                m.method = Stun::Data;
                m.cls = Stun::Indication;
                m.tid = Stun::newTid();
                m.addAddress(Stun::XorPeerAddress, src);
                m.add(Stun::DataAttr, b);
                msg = Stun::encode(m);
                ++dataOut;
            }
            main.writeDatagram(msg, a->client, a->clientPort);
        }
    }
    void error(const Stun::Message &req, int code, const QHostAddress &to, quint16 port)
    {
        Stun::Message e;
        e.method = req.method;
        e.cls = Stun::Error;
        e.tid = req.tid;
        QByteArray ec(4, 0);
        ec[2] = char(code / 100);
        ec[3] = char(code % 100);
        e.add(Stun::ErrorCode, ec + "x");
        e.addText(Stun::Realm, realm);
        e.addText(Stun::Nonce, nonce);
        main.writeDatagram(Stun::encode(e), to, port);
    }
    void onMain()
    {
        while (main.hasPendingDatagrams()) {
            QByteArray b(int(main.pendingDatagramSize()), 0);
            QHostAddress from;
            quint16 port = 0;
            b.resize(int(main.readDatagram(b.data(), b.size(), &from, &port)));
            Alloc *a = find(from, port);
            if (Stun::isChannelData(b)) {
                quint16 ch = 0;
                QByteArray data;
                if (a && Stun::parseChannelData(b, &ch, &data) && a->chans.contains(ch)) {
                    ++channelDataIn;
                    out(a, a->chans.value(ch), data);
                }
                continue;
            }
            Stun::Message m;
            if (!Stun::parse(b, &m))
                continue;
            if (m.cls == Stun::Indication && m.method == Stun::Send) {
                out(a, m.address(Stun::XorPeerAddress), m.get(Stun::DataAttr));
                continue;
            }
            if (m.cls != Stun::Request)
                continue;
            const QByteArray key = Stun::longTermKey(m.text(Stun::Username), realm, QStringLiteral("secret"));
            if (!m.has(Stun::MessageIntegrity) || !Stun::checkIntegrity(b, key)) {
                error(m, 401, from, port);
                continue;
            }
            if (m.text(Stun::Nonce) != nonce) {
                error(m, 438, from, port);
                continue;
            }
            if ((m.method == Stun::ChannelBind || m.method == Stun::CreatePermission) && stale == 0) {
                ++stale;
                nonce = QStringLiteral("nonce-2");
                error(m, 438, from, port);
                continue;
            }
            Stun::Message r;
            r.method = m.method;
            r.cls = Stun::Success;
            r.tid = m.tid;
            if (m.method == Stun::Allocate) {
                if (!a) {
                    auto n = std::make_unique<Alloc>();
                    n->client = from;
                    n->clientPort = port;
                    n->in = new QUdpSocket;
                    n->in->bind(QHostAddress(QHostAddress::LocalHost), 0);
                    n->out = new QUdpSocket;
                    n->out->bind(QHostAddress(QHostAddress::LocalHost), 0);
                    a = n.get();
                    for (QUdpSocket *s : {a->in, a->out})
                        QObject::connect(s, &QUdpSocket::readyRead, s, [this, a, s] { onRelay(a, s); });
                    allocs.push_back(std::move(n));
                }
                r.addAddress(Stun::XorRelayedAddress, CallNet::fromHost(QHostAddress(QHostAddress::LocalHost), a->in->localPort()));
                r.addAddress(Stun::XorMappedAddress, CallNet::fromHost(from, port));
                r.addU32(Stun::Lifetime, 600);
            } else if (m.method == Stun::Refresh) {
                r.addU32(Stun::Lifetime, 600);
            } else if (m.method == Stun::CreatePermission || m.method == Stun::ChannelBind) {
                if (!a)
                    continue;
                const Stun::Address peer = m.address(Stun::XorPeerAddress);
                a->perms.insert(peer.ip);
                if (m.method == Stun::ChannelBind) {
                    const QByteArray c = m.get(Stun::ChannelNumber);
                    a->chans.insert(quint16((quint8(c.at(0)) << 8) | quint8(c.at(1))), peer);
                }
            }
            main.writeDatagram(Stun::encode(r, key, true), from, port);
        }
    }
};

// A whole call between two sessions with no Wi-Fi path: everything goes through the relay, which
// translates addresses and lets a nonce go stale.
void relayCall()
{
    std::printf("relay-only call (TURN with address translation)\n");
    FakeTurn turn;
    TurnLogin login;
    login.urls << QStringLiteral("turn:127.0.0.1:%1?transport=udp").arg(turn.port());
    login.username = QStringLiteral("user");
    login.credential = QStringLiteral("secret");
    login.expiresMs = QDateTime::currentMSecsSinceEpoch() + 3600000;
    auto fetch = [login](std::function<void(const TurnLogin &, const QString &)> done) {
        QTimer::singleShot(10, [done, login] { done(login, QString()); });
    };
    Side a, b;
    a.id = CallCrypto::newEphemeral();
    b.id = CallCrypto::newEphemeral();
    const QString keyA = QString::fromLatin1(a.id.publicDer.toBase64()), keyB = QString::fromLatin1(b.id.publicDer.toBase64());
    a.sig = new CallSignalHooks();
    b.sig = new CallSignalHooks();
    for (Side *s : {&a, &b}) {
        Side *other = s == &a ? &b : &a;
        const QString mine = s == &a ? keyA : keyB;
        s->sig->onMyKey = [mine] { return mine; };
        s->sig->onSign = [s](const QByteArray &bytes) { return CallCrypto::signDer(s->id.privateDer, bytes); };
        s->sig->onNameOf = [](const QString &) { return QStringLiteral("Test"); };
        s->sig->onCanCall = [](const QString &) { return true; };
        QPointer<CallSignalHooks> to = other->sig;
        s->sig->onSend = [to, mine](const QString &, const QJsonObject &m) {
            QTimer::singleShot(5, to, [to, mine, m] { if (to) to->deliver(mine, m); });
        };
    }
    QObject::connect(b.sig, &CallSignal::received, b.sig, [&b, fetch](const QString &from, const QJsonObject &m) {
        if (b.session) {
            b.session->handle(m);
            return;
        }
        if (m.value(QStringLiteral("t")).toString() != QLatin1String("offer") || !CallSession::checkOffer(m, from, QDateTime::currentMSecsSinceEpoch()).isEmpty())
            return;
        b.session = new CallSession(b.sig, from, fetch, b.sig);
        b.session->useDevices = false;
        b.session->takeOffer(m);
        QTimer::singleShot(300, b.session, [&b] { b.session->answer(); });
    });
    QObject::connect(a.sig, &CallSignal::received, a.sig, [&a](const QString &, const QJsonObject &m) {
        if (a.session)
            a.session->handle(m);
    });
    a.session = new CallSession(a.sig, keyB, fetch, a.sig);
    a.session->useDevices = false;
    a.session->start();
    check(waitFor([&] { return a.session && b.session && a.session->state() == CallSession::Active && b.session->state() == CallSession::Active; }, 10000),
          "both sides connected over the relay alone");
    check(a.session && a.session->media() && a.session->media()->live(CallMedia::Relay) && !a.session->media()->live(CallMedia::Wifi), "A: the relay path is live, no Wi-Fi");
    check(b.session && b.session->media() && b.session->media()->live(CallMedia::Relay), "B: the relay path is live");
    if (a.session && b.session && a.session->media() && b.session->media()) {
        CallMedia *ma = a.session->media(), *mb = b.session->media();
        const int inB = mb->stats().audioIn, inA = ma->stats().audioIn;
        qint64 phA = 0, phB = 0;
        for (int i = 0; i < 25; ++i) {
            qint16 pcm[AudioDsp::kFrame];
            AudioDsp::tone(pcm, AudioDsp::kFrame, 440, 0.4, &phA);
            ma->feed(pcm);
            AudioDsp::tone(pcm, AudioDsp::kFrame, 660, 0.4, &phB);
            mb->feed(pcm);
            waitFor([] { return false; }, 20);
        }
        waitFor([&] { return mb->stats().audioIn >= inB + 20 && ma->stats().audioIn >= inA + 20; }, 3000);
        check(mb->stats().audioIn >= inB + 20, "A's audio reaches B through the relay");
        check(ma->stats().audioIn >= inA + 20, "B's audio reaches A through the relay");
        check(turn.dataOut > 0 && ma->relayTargets().size() == 2 && mb->relayTargets().size() == 2,
              "translated sources accepted and answered (advertised and seen address)");
        check(turn.stale == 1 && turn.channelDataIn > 0, "a stale nonce (438 without integrity) doesn't stop the channel");
    }
    if (a.session)
        a.session->hangup();
    waitFor([&] { return !b.session || b.session->state() == CallSession::Ended; }, 3000);
    delete a.sig;
    delete b.sig;
}

// Round 18 (relay.md "On demand"): a call on the Wi-Fi asks the relay Worker for nothing, at the start or later.
void lanCallNoLogin()
{
    std::printf("a call on the Wi-Fi asks for no relay login\n");
    int fetches = 0;
    auto fetch = [&fetches](std::function<void(const TurnLogin &, const QString &)> done) {
        ++fetches;
        QTimer::singleShot(10, [done] { done(TurnLogin(), QStringLiteral("test: no relay")); });
    };
    Side a, b;
    a.id = CallCrypto::newEphemeral();
    b.id = CallCrypto::newEphemeral();
    const QString keyA = QString::fromLatin1(a.id.publicDer.toBase64()), keyB = QString::fromLatin1(b.id.publicDer.toBase64());
    a.sig = new CallSignalHooks();
    b.sig = new CallSignalHooks();
    for (Side *s : {&a, &b}) {
        Side *other = s == &a ? &b : &a;
        const QString mine = s == &a ? keyA : keyB;
        s->sig->onMyKey = [mine] { return mine; };
        s->sig->onSign = [s](const QByteArray &bytes) { return CallCrypto::signDer(s->id.privateDer, bytes); };
        s->sig->onNameOf = [](const QString &) { return QStringLiteral("Test"); };
        s->sig->onCanCall = [](const QString &) { return true; };
        s->sig->onLanHost = [](const QString &) { return QStringLiteral("127.0.0.1"); };
        QPointer<CallSignalHooks> to = other->sig;
        s->sig->onSend = [to, mine](const QString &, const QJsonObject &m) {
            QTimer::singleShot(5, to, [to, mine, m] { if (to) to->deliver(mine, m); });
        };
    }
    QObject::connect(b.sig, &CallSignal::received, b.sig, [&b, fetch](const QString &from, const QJsonObject &m) {
        if (b.session) {
            b.session->handle(m);
            return;
        }
        if (m.value(QStringLiteral("t")).toString() != QLatin1String("offer") || !CallSession::checkOffer(m, from, QDateTime::currentMSecsSinceEpoch()).isEmpty())
            return;
        b.session = new CallSession(b.sig, from, fetch, b.sig);
        b.session->useDevices = false;
        b.session->takeOffer(m);
        QTimer::singleShot(300, b.session, [&b] { b.session->answer(); });
    });
    QObject::connect(a.sig, &CallSignal::received, a.sig, [&a](const QString &, const QJsonObject &m) {
        if (a.session)
            a.session->handle(m);
    });
    a.session = new CallSession(a.sig, keyB, fetch, a.sig);
    a.session->useDevices = false;
    a.session->start();
    check(waitFor([&] { return a.session && b.session && a.session->state() == CallSession::Active && b.session->state() == CallSession::Active; }, 6000),
          "on demand: both sides connected over the Wi-Fi");
    // Longer than the 2.5 s a call waits for a free path before it asks.
    waitFor([] { return false; }, 3500);
    check(fetches == 0 && a.session && !a.session->relayUp() && b.session && !b.session->relayUp(), "on demand: no relay login asked for while the Wi-Fi carries the call");
    if (a.session)
        a.session->hangup();
    waitFor([&] { return !b.session || b.session->state() == CallSession::Ended; }, 3000);
    delete a.sig;
    delete b.sig;
}

// Echo cancellation (AudioIO, HUGINN_HAVE_VOICE): a made-up room at the call's 16 kHz where the microphone hears the
// speaker 150 ms later, quieter and with a little reverb. The speaker must get the call unchanged, and once the
// canceller has settled most of the echo must be gone from what the microphone sends.
void voiceChecks()
{
#ifdef HUGINN_HAVE_VOICE
    const int rate = AudioDsp::kRate, step = rate / 100, secs = 10, delay = rate * 150 / 1000;
    const double pi = 3.14159265358979323846;   // M_PI is missing on MSVC
    std::vector<qint16> far(size_t(rate) * secs), mic(far.size()), out(far.size()), play(far.size());
    double ph = 0;
    for (size_t i = 0; i < far.size(); ++i) {
        const double t = double(i) / rate;
        ph += 2 * pi * 180 * (1 + 0.15 * std::sin(2 * pi * 0.7 * t)) / rate;
        const double env = std::sin(2 * pi * 3.1 * t) > -0.2 ? 1.0 : 0.05;   // on and off like syllables
        double v = 0;
        for (int h = 1; h <= 6; ++h)
            v += std::sin(ph * h) / h;
        far[i] = qint16(std::lrint(env * 6000 * v));
    }
    for (size_t i = 0; i < mic.size(); ++i) {
        double v = 0;
        if (i >= size_t(delay)) v += 0.5 * far[i - delay];
        if (i >= size_t(delay + 160)) v += 0.2 * far[i - delay - 160];
        mic[i] = qint16(std::lrint(v));
    }
    TvVoice *v = tvv_create(rate, rate, rate, rate, TVV_AEC);
    check(v, "the echo canceller starts");
    if (!v)
        return;
    tvv_set_delay(v, 150);
    bool lengths = true;
    for (size_t f = 0; f + size_t(step) <= far.size(); f += size_t(step)) {
        lengths = tvv_render(v, &far[f], &play[f]) == step && lengths;
        lengths = tvv_capture(v, &mic[f], &out[f]) == step && lengths;
    }
    tvv_destroy(v);
    check(lengths, "the echo canceller takes and gives 10 ms at a time");
    const auto energy = [](const std::vector<qint16> &x, size_t a, size_t b) {
        double e = 1e-9;
        for (size_t i = a; i < b; ++i)
            e += double(x[i]) * x[i];
        return e;
    };
    check(play == far, "the speaker plays the call exactly as it came");
    const size_t from = size_t(rate) * 6;
    const double removed = 10 * std::log10(energy(mic, from, mic.size()) / energy(out, from, out.size()));
    std::printf("call self-test: echo removed %.1f dB\n", removed);
    check(removed >= 10, "the echo canceller takes the echo out");
#endif
}
}

bool CallSelfTest::run(const QString &vectorsPath)
{
    g_fail = 0;
    const bool vectors = CallCrypto::selfTest(vectorsPath);
    const bool rooms = RoomWire::selfTest(vectorsPath);
    const bool stun = Stun::selfTest();
    const bool jitter = JitterBuffer::selfTest();
    const bool dsp = AudioDsp::selfTest();
    const bool video = VideoWire::selfTest();
    relayChecks();
    c2Vectors(vectorsPath);
    mediaLoopback();
    c2Media();
    vp8Loopback();
    signalingLoopback();
    directCall();
    directReplay();
    relayCall();
    lanCallNoLogin();
    voiceChecks();
    const bool ok = vectors && rooms && stun && jitter && dsp && video && g_fail == 0;
    std::printf("call self-test: %s\n", ok ? "all good" : "FAILED");
    std::fflush(stdout);
    return ok;
}
