#pragma once

#include <QByteArray>
#include <QList>
#include <QString>

class QImage;

// Photos and voice messages for chat, the same as Muninn's Photos.kt and Voice.kt so the two agree.
//  - Photos are re-encoded (only pixels survive) and then scrubbed of every metadata block, so
//    location, camera and dates never leave this computer. Normal: 640 px, at most 60 KB.
//    High quality: 2048 px, at most 2 MB.
//  - Voice messages are bare AAC frames (ADTS): no container, so nothing but the sound.
//  - Both travel in 16 KB pieces, which fit every route (Bluetooth, Wi-Fi, relays).
namespace Photos
{
const int kPiece = 16 * 1024;
const int kMaxPieces = 200;            // 3.2 MB
const int kInternetPieces = 130;       // 2 MB over the relays at most
const int kMaxVoiceMs = 5 * 60 * 1000;

QByteArray prepare(const QString &path, bool hd);   // empty if it isn't a picture
QByteArray scrub(const QByteArray &jpeg);           // empty if it isn't a well-formed JPEG
// A picture already in memory (a Labs report's screenshot): at most side px and limit bytes, scrubbed the same way.
QByteArray prepareImage(const QImage &img, int side, int limit);
bool isAdts(const QByteArray &b);
QList<QByteArray> split(const QByteArray &data);
QByteArray join(const QList<QByteArray> &parts);    // a JPEG, scrubbed; empty otherwise
QByteArray joinVoice(const QList<QByteArray> &parts);
bool selfTest();

// Animated GIFs (cap a1, protocol section 25), the same as Muninn's Gifs.kt and code/shared/tools/gif_ref.py.
// cleanGif keeps only the picture (screen, colors, frames with their timing, the loop count) and drops comments,
// plain text, XMP, ICC and every other application block. It runs before sending and on every GIF received, and the
// limits keep a hostile GIF from freezing or crashing the app when it's shown.
const char kGifCap[] = "a1";
const int kGifMaxBytes = 8 * 1024 * 1024;
const int kGifMaxFrames = 400;
const int kGifMaxSide = 2048;
const qint64 kGifMaxArea = 160LL * 1024 * 1024;
const int kGifPieces = kGifMaxBytes / kPiece;   // 512, on every route

bool isGif(const QByteArray &b);
bool isGifFile(const QString &path);
// The cleaned GIF, or empty; *why then says "gif" (not a well-formed GIF), "big" or "frames".
QByteArray cleanGif(const QByteArray &b, QString *why = nullptr, int *frames = nullptr);
QByteArray readGif(const QString &path, QString *why);   // a GIF file, read (at most 8 MB) and cleaned
QByteArray joinGif(const QList<QByteArray> &parts);      // a received GIF, cleaned again; empty otherwise
QByteArray stillOfGif(const QByteArray &gif);           // its first frame as an ordinary photo (640 px, 60 KB)
QString gifWhy(const QString &why);                      // what to tell the person
// Checks code/shared/vectors/gif-vectors.json. Prints what it found; true when everything passed.
bool gifSelfTest(const QString &vectorsPath);
}
