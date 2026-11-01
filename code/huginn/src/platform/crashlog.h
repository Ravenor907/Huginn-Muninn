#pragma once

#include <QString>

// crash.log in Huginn's app data folder (Windows and Linux): what a crash handler saw, a fatal error or abort,
// graphics failures and runs that ended without closing. Only Huginn's own workings go in: never message text,
// names or network addresses (note() takes out the home folder and anything that looks like an address).
// Kept to the last ~200 KB. Settings › Labs › Report a bug attaches its last lines only when the person ticks it.
namespace CrashLog
{
// Right after QApplication has its name: knows where the log is and catches crashes (Linux, and abort on both;
// Windows' crash handler is in winstart.cpp and writes here through line()).
void start();
// Once this copy holds the single instance: trims the log and notes it when the last run never closed.
void claimed();
// At the very end of main, after everything else has closed: this run closed cleanly.
void cleanExit();
// A timestamped line (scrubbed of the home folder and addresses).
void note(const QString &what);
// A line as it is, from a crash handler.
void line(const char *text);
// The last lines, at most maxChars characters, for a bug report; empty when there are none.
QString tail(int maxChars);
bool hasLines();
// Takes the home folder and anything that looks like a network address out of a line (for the self-test too).
QString scrub(const QString &text);
}
