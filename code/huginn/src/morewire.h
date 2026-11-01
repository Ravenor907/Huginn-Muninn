#pragma once

#include <QByteArray>
#include <QJsonObject>
#include <QList>
#include <QString>
#include <QStringList>
#include <QVariantList>
#include <QVariantMap>

// Cap m2 (shared/docs/protocol.md, section 12): photo albums, video messages, polls and link previews, the
// parts Huginn and Muninn must agree on (Muninn's net/More.kt). shared/tools/m2_ref.py is the reference;
// shared/vectors/m2-vectors.json the vectors.
//  - Albums: a photo piece's clear fields "al" (album id), "ai" (its place), "an" (how many). Anyone may get
//    them; an app without m2 shows the photos one by one.
//  - Video messages: a photo piece with "kind":"video" and "ms", the whole an MP4 of at most a minute. Only to m2.
//  - Polls: "poll" {q, a, m} in a "rich" or "gmsg" payload, whose text says the same for apps without m2.
//    Votes: room "mx" or "group", t "vote", {msg, v, ts}. Only to m2.
//  - Link previews: "lp" {u, t, d, i} in a "rich" or "gmsg" payload, made on the sender's computer. The
//    receiver never fetches anything; clicking asks before the browser opens it.
namespace MoreWire
{
extern const char kCap[];              // "m2"
const int kAlbumMax = 10;
const int kVideoMaxMs = 60000;
const int kQuestionMax = 200;
const int kOptionMax = 100;
const int kOptionsMin = 2;
const int kOptionsMax = 10;
const int kUrlMax = 2000;
const int kTitleMax = 200;
const int kDescMax = 300;
const int kThumbMax = 24000;

// Well-formed under m2 for room "rich" and "gmsg" (the fields "poll" and "lp"), "mx" and "group" (t "vote"),
// or "piece" (a photo piece's clear fields).
bool ok(const QString &room, const QJsonObject &p);
// A video message is an MP4: its first box is "ftyp".
bool isVideo(const QByteArray &data);
// How long an MP4 plays (its "mvhd" box), in ms; 0 when it doesn't say.
int mp4Ms(const QByteArray &data);
// What an app without m2 shows for a poll.
QString fallback(const QString &q, const QStringList &options);
QJsonObject pollJson(const QString &q, const QStringList &options, bool multi);

// Votes on one poll, kept as who -> {v: [options], t: ts}. One per person; a later ts replaces it ([] takes it
// back). A vote naming an option the poll doesn't have is ignored. True when it changed.
bool vote(QVariantMap &all, int n, const QString &from, const QList<int> &v, qint64 ts);
QList<int> counts(const QVariantMap &all, int n);
QList<int> choices(const QVariantMap &all, const QString &who);
QList<int> toInts(const QVariant &v);
QByteArray voteMsg(const QString &msg, const QList<int> &v, qint64 ts);

// A message as kept here and its pieces: "video" (ms), "al", "ai", "an" (an album) in the clear part of each
// piece, and back from a checked piece.
void addPieceFields(QJsonObject &o, const QVariantMap &m);
QVariantMap pieceFields(const QJsonObject &o);
// A video message's pieces put back together: an MP4 no bigger than a piece list may be; empty otherwise.
QByteArray joinVideo(const QList<QByteArray> &parts);
// The "poll" and "lp" of a checked "rich" or "gmsg" payload, kept with the message (a malformed one is left
// out and the text still shows).
void keep(QVariantMap &m, const QString &room, const QJsonObject &p);
// A "rich" payload: the text, and the reply, poll and preview when there are any.
QByteArray richText(const QString &text, const QString &re, const QVariantMap &poll, const QVariantMap &lp);

// The first web link in a text, or "".
QString firstLink(const QString &text);
// The site a link goes to, for "Open example.com?".
QString host(const QString &url);

// Checks shared/vectors/m2-vectors.json. Prints what it found; true when everything passed.
bool selfTest(const QString &vectorsPath);
}
