#pragma once

#include <QString>

// Where a voice note, a video note or a pasted picture is written for the moment it takes to read it
// back into memory. Linux: the runtime folder (memory-backed and private to the sandbox). Windows: a
// "scratch" folder inside Huginn's own data folder (beside Huginn.exe), never the home folder.
namespace Scratch
{
// The folder (made if it isn't there yet), ending without a slash.
QString dir();
// A path in it for name.
QString path(const QString &name);
// Overwrites the file with zeros, then deletes it.
void shred(const QString &path);
// At start: shreds whatever an earlier run left in the folder (a crash mid-recording).
void clearLeftovers();
}
