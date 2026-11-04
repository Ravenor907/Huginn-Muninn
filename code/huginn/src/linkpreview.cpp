#include "linkpreview.h"
#include "morewire.h"
#include "ultraprivacy.h"
#include "photos.h"

#include <QBuffer>
#include <QHostAddress>
#include <QHostInfo>
#include <QCoreApplication>
#include <QImage>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QPointer>
#include <QRegularExpression>
#include <QUrl>
#include <cstdio>
#include <memory>

namespace
{
const int kPageMax = 512 * 1024;
const int kImageMax = 4 * 1024 * 1024;
const int kThumbSide = 240;
const int kThumbBytes = 16 * 1024;

// Its own connection pool: unlike Chat's (which talks to devices with their own certificates), this one
// checks every certificate the normal way.
QNetworkAccessManager *web()
{
    static QPointer<QNetworkAccessManager> nam;
    if (!nam)
        nam = new QNetworkAccessManager(QCoreApplication::instance());
    return nam;
}

void fetchChecked(const QString &url, int max, const QString &type, QObject *ctx, std::function<void(const QByteArray &)> done);

// [host] is on the internet: written out, a public address; a name, every address it has is public.
void checkHost(const QString &host, QObject *ctx, std::function<void(bool)> then)
{
    const QHostAddress literal(host);
    if (!literal.isNull()) {
        then(LinkPreview::publicAddress(literal));
        return;
    }
    if (host.isEmpty()) {
        then(false);
        return;
    }
    QHostInfo::lookupHost(host, ctx, [then](const QHostInfo &info) {
        const QList<QHostAddress> all = info.addresses();
        bool ok = info.error() == QHostInfo::NoError && !all.isEmpty();
        for (const QHostAddress &a : all)
            ok = ok && LinkPreview::publicAddress(a);
        then(ok);
    });
}

// The start of [url] (at most [max] bytes), when it answers with a content type starting [type]; empty otherwise.
// Only https, and never into this computer's own network: the page, the picture and every redirect.
void get(const QString &url, int max, const QString &type, QObject *ctx, std::function<void(const QByteArray &)> done)
{
    if (!LinkPreview::allowedUrl(url)) {
        done({});
        return;
    }
    QPointer<QObject> guard(ctx);
    checkHost(QUrl(url).host(), ctx, [url, max, type, guard, done](bool ok) {
        if (!ok || !guard) {
            done({});
            return;
        }
        fetchChecked(url, max, type, guard, done);
    });
}

void fetchChecked(const QString &url, int max, const QString &type, QObject *ctx, std::function<void(const QByteArray &)> done)
{
    QNetworkRequest req{QUrl(url)};
    req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::UserVerifiedRedirectPolicy);
    req.setTransferTimeout(6000);
    req.setHeader(QNetworkRequest::UserAgentHeader, QStringLiteral("Mozilla/5.0 (compatible; link preview)"));
    req.setRawHeader("Accept", type.endsWith(QLatin1Char('/')) ? QByteArray("image/*") : type.toLatin1());
    QNetworkReply *r = web()->get(req);
    QObject::connect(r, &QNetworkReply::redirected, ctx, [r](const QUrl &to) {
        if (to.scheme() != QLatin1String("https")) {
            r->abort();
            return;
        }
        checkHost(to.host(), r, [r](bool ok) {
            if (ok)
                Q_EMIT r->redirectAllowed();
            else
                r->abort();
        });
    });
    auto buf = std::make_shared<QByteArray>();
    auto over = std::make_shared<bool>(false);
    auto end = [r, buf, over, type, done] {
        if (*over)
            return;
        *over = true;
        const int code = r->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        const QString ct = r->header(QNetworkRequest::ContentTypeHeader).toString().trimmed().toLower();
        const bool good = code >= 200 && code < 300 && ct.startsWith(type) && !buf->isEmpty();
        r->deleteLater();
        done(good ? *buf : QByteArray());
    };
    QObject::connect(r, &QNetworkReply::readyRead, ctx, [r, buf, max, end] {
        buf->append(r->read(max - buf->size()));
        if (buf->size() >= max) {
            end();   // the head of a page is enough; a huge picture isn't worth it
            r->abort();
        }
    });
    QObject::connect(r, &QNetworkReply::finished, ctx, [r, buf, max, end, over] {
        if (*over)
            return;
        if (r->error() != QNetworkReply::NoError)
            buf->clear();
        else
            buf->append(r->read(max - buf->size()));
        end();
    });
}

QString attr(const QString &tag, const QString &name)
{
    const QRegularExpression re(QStringLiteral("\\b%1\\s*=\\s*(\"([^\"]*)\"|'([^']*)')").arg(name), QRegularExpression::CaseInsensitiveOption);
    const auto m = re.match(tag);
    if (!m.hasMatch())
        return {};
    return m.captured(2).isEmpty() ? m.captured(3) : m.captured(2);
}

QString unescape(QString s)
{
    s.replace(QLatin1String("&amp;"), QLatin1String("&")).replace(QLatin1String("&quot;"), QLatin1String("\"")).replace(QLatin1String("&#39;"), QLatin1String("'"))
        .replace(QLatin1String("&apos;"), QLatin1String("'")).replace(QLatin1String("&lt;"), QLatin1String("<")).replace(QLatin1String("&gt;"), QLatin1String(">"));
    static const QRegularExpression num(QStringLiteral("&#(\\d+);"));
    QString out;
    qsizetype at = 0;
    for (auto it = num.globalMatch(s); it.hasNext();) {
        const auto m = it.next();
        out += s.mid(at, m.capturedStart() - at);
        const uint c = m.captured(1).toUInt();
        if (c > 0 && c < 0x110000)
            out += QString::fromUcs4(reinterpret_cast<const char32_t *>(&c), 1);
        at = m.capturedEnd();
    }
    return out + s.mid(at);
}

// <meta property="og:title" content="..."> or name="...", in either order.
QString meta(const QString &page, const QString &key)
{
    static const QRegularExpression tag(QStringLiteral("<meta\\b[^>]*>"), QRegularExpression::CaseInsensitiveOption);
    for (auto it = tag.globalMatch(page); it.hasNext();) {
        const QString t = it.next().captured(0);
        QString k = attr(t, QStringLiteral("property"));
        if (k.isEmpty())
            k = attr(t, QStringLiteral("name"));
        if (k.compare(key, Qt::CaseInsensitive) == 0) {
            const QString c = unescape(attr(t, QStringLiteral("content")));
            return c.trimmed().isEmpty() ? QString() : c;
        }
    }
    return {};
}

QString title(const QString &page)
{
    static const QRegularExpression re(QStringLiteral("<title\\b[^>]*>(.*?)</title>"), QRegularExpression::CaseInsensitiveOption | QRegularExpression::DotMatchesEverythingOption);
    const auto m = re.match(page);
    return m.hasMatch() ? unescape(m.captured(1)) : QString();
}

// One line, no control characters, at most [max] code points.
QString clean(const QString &s, int max)
{
    static const QRegularExpression space(QStringLiteral("\\s+"));
    QString one = QString(s).replace(space, QStringLiteral(" "));
    QString kept;
    for (const QChar c : std::as_const(one))
        if (c.unicode() >= 0x20 && c.unicode() != 0x7F)
            kept += c;
    kept = kept.trimmed();
    const QList<uint> cps = kept.toUcs4();
    if (cps.size() <= max)
        return kept;
    return QString::fromUcs4(reinterpret_cast<const char32_t *>(cps.constData()), max - 1).trimmed() + QStringLiteral("…");
}
}

QJsonObject LinkPreview::fromPage(const QString &url, const QString &html, QString *image)
{
    QString t = meta(html, QStringLiteral("og:title"));
    if (t.isEmpty())
        t = meta(html, QStringLiteral("twitter:title"));
    if (t.isEmpty())
        t = title(html);
    t = clean(t, MoreWire::kTitleMax);
    if (t.isEmpty())
        return {};
    QString d = meta(html, QStringLiteral("og:description"));
    if (d.isEmpty())
        d = meta(html, QStringLiteral("description"));
    d = clean(d, MoreWire::kDescMax);
    QJsonObject lp{{QStringLiteral("u"), url}, {QStringLiteral("t"), t}};
    if (!d.isEmpty())
        lp.insert(QStringLiteral("d"), d);
    if (image) {
        const QString img = meta(html, QStringLiteral("og:image")).trimmed();
        *image = img.isEmpty() ? QString() : QUrl(url).resolved(QUrl(img)).toString();
    }
    return lp;
}

QByteArray LinkPreview::thumbnail(const QByteArray &picture)
{
    QImage img = QImage::fromData(picture);
    if (img.isNull())
        return {};
    if (qMax(img.width(), img.height()) > kThumbSide)
        img = img.scaled(kThumbSide, kThumbSide, Qt::KeepAspectRatio, Qt::SmoothTransformation);
    img = img.convertToFormat(QImage::Format_RGB32);
    for (int q : {70, 55, 40}) {
        QByteArray out;
        QBuffer b(&out);
        b.open(QIODevice::WriteOnly);
        img.save(&b, "JPEG", q);
        if (!out.isEmpty() && out.size() <= kThumbBytes)
            return Photos::scrub(out);
    }
    return {};
}

void LinkPreview::fetch(const QString &url, QObject *ctx, std::function<void(const QJsonObject &)> done)
{
    // Checked as the other side will: a preview that wouldn't pass isn't sent.
    auto checked = [url, done](QJsonObject lp) {
        const QJsonObject p{{QStringLiteral("text"), url}, {QStringLiteral("lp"), lp}};
        done(!lp.isEmpty() && MoreWire::ok(QStringLiteral("rich"), p) ? lp : QJsonObject());
    };
    // Ultra privacy: no preview at all (the site would see the address).
    if (!UltraPrivacy::previewsAllowed()) {
        checked({});
        return;
    }
    get(url, kPageMax, QStringLiteral("text/html"), ctx, [url, ctx, checked](const QByteArray &page) {
        if (page.isEmpty()) {
            checked({});
            return;
        }
        QString image;
        QJsonObject lp = fromPage(url, QString::fromUtf8(page), &image);
        if (lp.isEmpty() || image.isEmpty()) {
            checked(lp);
            return;
        }
        get(image, kImageMax, QStringLiteral("image/"), ctx, [lp, checked](const QByteArray &pic) mutable {
            const QByteArray thumb = pic.isEmpty() ? QByteArray() : thumbnail(pic);
            if (!thumb.isEmpty())
                lp.insert(QStringLiteral("i"), QString::fromLatin1(thumb.toBase64()));
            checked(lp);
        });
    });
}

namespace
{
bool v4Public(quint32 a)
{
    const int b0 = int(a >> 24), b1 = int((a >> 16) & 0xff), b2 = int((a >> 8) & 0xff);
    if (b0 == 0 || b0 == 10 || b0 == 127 || b0 >= 224)
        return false;
    if ((b0 == 100 && b1 >= 64 && b1 <= 127) || (b0 == 169 && b1 == 254) || (b0 == 172 && b1 >= 16 && b1 <= 31) || (b0 == 192 && b1 == 168))
        return false;   // carrier-grade NAT, link-local, private
    if ((b0 == 192 && b1 == 0 && (b2 == 0 || b2 == 2)) || (b0 == 198 && (b1 == 18 || b1 == 19)) || (b0 == 198 && b1 == 51 && b2 == 100)
        || (b0 == 203 && b1 == 0 && b2 == 113))
        return false;   // reserved, benchmarking, documentation
    return true;
}
}

bool LinkPreview::publicAddress(const QHostAddress &a)
{
    if (a.isNull() || a.isLoopback() || a.isMulticast() || a.isLinkLocal())
        return false;
    if (a.protocol() == QAbstractSocket::IPv4Protocol)
        return v4Public(a.toIPv4Address());
    const Q_IPV6ADDR b = a.toIPv6Address();
    bool zeros = true;
    for (int i = 0; i < 10; ++i)
        zeros = zeros && b[i] == 0;
    const quint32 tail = (quint32(b[12]) << 24) | (quint32(b[13]) << 16) | (quint32(b[14]) << 8) | quint32(b[15]);
    if (zeros && b[10] == 0xff && b[11] == 0xff)
        return v4Public(tail);   // ::ffff:a.b.c.d
    if (b[0] == 0x00 && b[1] == 0x64 && b[2] == 0xff && b[3] == 0x9b)
        return v4Public(tail);   // 64:ff9b::/96
    if ((b[0] & 0xfe) == 0xfc || (b[0] == 0xfe && (b[1] & 0xc0) == 0xc0) || (b[0] == 0xfe && (b[1] & 0xc0) == 0x80))
        return false;   // unique local, old site-local, link-local
    if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8)
        return false;   // documentation
    bool any = false;
    for (int i = 0; i < 16; ++i)
        any = any || b[i] != 0;
    return any;   // :: is no address
}

bool LinkPreview::allowedUrl(const QString &url)
{
    const QUrl u(url);
    if (!u.isValid() || u.scheme() != QLatin1String("https") || u.host().isEmpty())
        return false;
    const QHostAddress literal(u.host());
    return literal.isNull() || publicAddress(literal);
}

bool LinkPreview::selfTest()
{
    QString image;
    const QJsonObject a = fromPage(QStringLiteral("https://example.com/a/b"),
                                   QStringLiteral("<html><head><title>Plain</title><meta content=\"A &amp; B\" property=\"og:title\">"
                                                  "<meta name='description' content='Two\n lines &#65;'><meta property=\"og:image\" content=\"/p.jpg\"></head></html>"),
                                   &image);
    const QJsonObject b = fromPage(QStringLiteral("https://example.com"), QStringLiteral("<TITLE>\n  Only a title </TITLE>"), &image);
    const QJsonObject c = fromPage(QStringLiteral("https://example.com"), QStringLiteral("<p>nothing</p>"), nullptr);
    const bool ok = a.value(QStringLiteral("t")).toString() == QLatin1String("A & B") && a.value(QStringLiteral("d")).toString() == QLatin1String("Two lines A")
        && b.value(QStringLiteral("t")).toString() == QLatin1String("Only a title") && !b.contains(QStringLiteral("d")) && image.isEmpty() && c.isEmpty();
    QString img2;
    fromPage(QStringLiteral("https://example.com/a/b"), QStringLiteral("<title>x</title><meta property=\"og:image\" content=\"/p.jpg\">"), &img2);
    const bool resolved = img2 == QLatin1String("https://example.com/p.jpg");
    // Only the internet, the same lists as Muninn's SecDataTest.
    bool addresses = true;
    for (const char *a : {"10.0.0.1", "172.16.5.4", "192.168.1.1", "127.0.0.1", "169.254.1.1", "100.64.0.1", "0.0.0.0", "::1", "fe80::1", "fd00::1",
                          "fc12::5", "::ffff:192.168.0.1", "224.0.0.1"})
        addresses = addresses && !publicAddress(QHostAddress(QString::fromLatin1(a)));
    for (const char *a : {"1.1.1.1", "93.184.216.34", "2606:4700::1111", "172.32.0.1"})
        addresses = addresses && publicAddress(QHostAddress(QString::fromLatin1(a)));
    addresses = addresses && allowedUrl(QStringLiteral("https://example.com/a")) && !allowedUrl(QStringLiteral("http://example.com/a"))
        && !allowedUrl(QStringLiteral("https://192.168.1.1/admin")) && !allowedUrl(QStringLiteral("https://[fd00::1]/"))
        && allowedUrl(QStringLiteral("https://1.1.1.1/"));
    std::printf("link preview addresses: %s\n", addresses ? "passed" : "FAILED");
    std::printf("link preview self-test: %s\n", ok && resolved ? "passed" : "FAILED");
    return ok && resolved && addresses;
}
