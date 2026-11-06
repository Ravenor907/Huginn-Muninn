#pragma once

#include <QByteArray>
#include <QHash>
#include <QIODevice>
#include <QList>
#include <QMutex>
#include <QString>
#include <functional>

class QObject;

// A longer video from Family or trusted contacts (up to kInlineMaxMs), shown in the chat like a photo, the same rules as
// Muninn's BigVideo.kt. It came (or went) through FileLink as a file offer with "vid"; here it's kept in the vault in
// sealed pieces of kPiece bytes ("photos/<id>.v/00000", ... and "photos/<id>.v/size"), so it plays without ever being
// whole in memory or in the clear on disk. Its first frame, as a JPEG, is the message's own blob ("photos/<id>").
// Nothing about it goes over the network: older apps see the same file offer as before.
namespace BigVideo
{
constexpr int kInlineMaxMs = 180000;            // up to 3 minutes goes in the chat; longer stays a file
constexpr int kSlackMs = 500;                   // GroupVideo::kSlackMs
constexpr qint64 kPiece = 1024 * 1024;
constexpr int kPosterSide = 480;                // the poster's long side at most

// Whether a video [ms] long from someone who is Family or trusted (FileLink::mayAlways) goes in the chat.
inline bool inlineOk(int ms, bool familyOrTrusted) { return familyOrTrusted && ms >= 1 && ms <= kInlineMaxMs + kSlackMs; }

// Names inside the vault.
QString dir(const QString &id);                 // "photos/<id>.v"
QString piece(const QString &id, int i);        // "photos/<id>.v/00000"
QString sizeName(const QString &id);            // "photos/<id>.v/size"

// A dark picture for a video whose first frame can't be read.
QByteArray blank();

// Reads a big video piece by piece (random access), keeping the last two pieces. [read] gives piece i (empty when it
// isn't there); it may be called from the media player's own thread.
class Device : public QIODevice
{
public:
    Device(qint64 total, std::function<QByteArray(int)> read, QObject *parent = nullptr);
    ~Device() override;
    bool open(OpenMode mode) override;
    void close() override;
    bool isSequential() const override { return false; }
    qint64 size() const override { return m_total; }
    bool seek(qint64 pos) override;

protected:
    qint64 readData(char *data, qint64 maxlen) override;
    qint64 writeData(const char *, qint64) override { return -1; }

private:
    QByteArray get(int i);
    const qint64 m_total;
    std::function<QByteArray(int)> m_read;
    QMutex m_lock;
    QHash<int, QByteArray> m_kept;
    QList<int> m_order;
};

// The first frame of the video in [dev] (opened here, owned from now on) as a JPEG of at most kPosterSide on its long
// side, given to [done] on the thread of [context]; blank() when no frame came within a few seconds.
void poster(QIODevice *dev, QObject *context, std::function<void(const QByteArray &)> done);
}
