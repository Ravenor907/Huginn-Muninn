#pragma once

#include <QString>

// huginn --call-selftest shared/vectors/proto06-vectors.json (CI, no network or sound card needed):
//  - the call vectors of the protocol spec (signatures, keys, epochs, packets, receive steps)
//  - RFC 5769 STUN vectors, TURN message types, ChannelData, the Worker's URLs and request body
//  - the jitter buffer and the audio helpers
//  - two CallMedia instances talking over a local UDP pair (no TURN), with a second copy of every
//    packet on a fake Bluetooth path and 10 % of audio left out, through real Opus
//  - two CallSessions doing offer, ring, answer, mute and hangup with each other in-process
// Prints what it found; true when everything passed. Needs a Q(Core)Application.
namespace CallSelfTest
{
bool run(const QString &vectorsPath);
}
