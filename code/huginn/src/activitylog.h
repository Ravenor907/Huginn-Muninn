#pragma once

#include <QString>
#include <QStringList>

// Labs: what the app did lately, for a bug report (hold the raven 7 seconds). In memory only, the last kMax lines,
// and only while Labs mode is on (turning it off drops what was kept). It says what happened (screens, messages and
// files moving, calls, what the app said, going to the background), never what anyone wrote, no keys, no addresses.
// The same as Muninn's ActivityLog.kt. Safe from any thread.
namespace ActivityLog
{
const int kMax = 600;
// At most this much of it goes with a report (Labs::kMaxLog).
const int kMaxChars = 24000;

void setOn(bool on);
bool on();
void add(const QString &tag, const QString &text);
// A short, safe form of an id ("3fa2c1").
QString id(const QString &s);
int count();
// The newest lines that fit kMaxChars after head (the device and app facts), oldest first.
QString dump(const QStringList &head);
}
