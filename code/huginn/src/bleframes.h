#pragma once

#include <cstdint>
#include <string>
#include <vector>

// Framing for the Bluetooth LE link to Muninn (see ble.h). Plain C++ with no Qt or Bluetooth
// types so it can be tested anywhere; std::string is used as a byte buffer.
//
// Frame: byte 0 flags (bit0 first, bit1 last), bytes 1-4 message number (uint32 big-endian,
// counted per sender per connection), then payload. A one-frame message has flags 3.
namespace BleFrames
{
constexpr std::uint8_t kFirst = 0x01;
constexpr std::uint8_t kLast = 0x02;
constexpr int kHeader = 5;
constexpr std::size_t kMaxMessage = 64 * 1024;
constexpr int kMinMtu = 23;
constexpr int kMaxMtu = 517;

// Bytes of payload that fit in one frame at this ATT MTU (frame size is mtu - 3).
int payloadPerFrame(int mtu);

// Cuts a message into frames for this MTU. Empty when the message is over kMaxMessage.
// An empty message still makes one frame (flags 3, no payload).
std::vector<std::string> split(const std::string &message, std::uint32_t number, int mtu);

// Puts frames from one sender back together. Frames on a single BLE link arrive in order,
// so one message is built at a time.
class Reassembler
{
public:
    enum Result { Partial, Complete, Dropped };

    // Feeds one frame. On Complete, *message holds the whole message.
    Result add(const std::string &frame, std::string *message);
    void reset();

private:
    bool m_active = false;
    std::uint32_t m_number = 0;
    std::string m_buffer;
};

// Round-trips messages at MTUs 23, 185 and 517 and checks the error cases; prints what it found.
bool selfTest();
}
