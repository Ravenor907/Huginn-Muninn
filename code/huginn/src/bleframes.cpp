#include "bleframes.h"

#include <algorithm>
#include <cstdio>

namespace
{
std::uint32_t readNumber(const std::string &frame)
{
    const auto b = [&](int i) { return std::uint32_t(std::uint8_t(frame[std::size_t(i)])); };
    return (b(1) << 24) | (b(2) << 16) | (b(3) << 8) | b(4);
}
}

int BleFrames::payloadPerFrame(int mtu)
{
    // Out-of-range MTUs (unknown is often reported as 0 or -1) fall back to the safe minimum.
    mtu = std::clamp(mtu, kMinMtu, kMaxMtu);
    // One ATT value is at most 512 bytes, whatever the MTU; Android rejects more.
    return std::min(mtu - 3, 512) - kHeader;
}

std::vector<std::string> BleFrames::split(const std::string &message, std::uint32_t number, int mtu)
{
    std::vector<std::string> frames;
    if (message.size() > kMaxMessage)
        return frames;
    const std::size_t chunk = std::size_t(payloadPerFrame(mtu));
    std::size_t at = 0;
    do {
        const std::size_t n = std::min(chunk, message.size() - at);
        std::uint8_t flags = 0;
        if (at == 0)
            flags |= kFirst;
        if (at + n == message.size())
            flags |= kLast;
        std::string f;
        f.reserve(kHeader + n);
        f.push_back(char(flags));
        f.push_back(char((number >> 24) & 0xff));
        f.push_back(char((number >> 16) & 0xff));
        f.push_back(char((number >> 8) & 0xff));
        f.push_back(char(number & 0xff));
        f.append(message, at, n);
        frames.push_back(std::move(f));
        at += n;
    } while (at < message.size());
    return frames;
}

void BleFrames::Reassembler::reset()
{
    m_active = false;
    m_number = 0;
    m_buffer.clear();
}

BleFrames::Reassembler::Result BleFrames::Reassembler::add(const std::string &frame, std::string *message)
{
    if (frame.size() < std::size_t(kHeader)) {
        reset();
        return Dropped;
    }
    const std::uint8_t flags = std::uint8_t(frame[0]);
    const std::uint32_t number = readNumber(frame);
    if (flags & kFirst) {
        // A new first frame replaces anything half built: the rest of that message isn't coming.
        m_active = true;
        m_number = number;
        m_buffer.clear();
    } else if (!m_active || number != m_number) {
        // A middle or last frame with no matching first frame is out of order.
        reset();
        return Dropped;
    }
    if (m_buffer.size() + frame.size() - kHeader > kMaxMessage) {
        reset();
        return Dropped;
    }
    m_buffer.append(frame, std::size_t(kHeader), std::string::npos);
    if (!(flags & kLast))
        return Partial;
    *message = std::move(m_buffer);
    reset();
    return Complete;
}

bool BleFrames::selfTest()
{
    bool all = true;
    auto check = [&](const char *what, bool ok) {
        printf("ble frames self-test: %s %s\n", what, ok ? "ok" : "WRONG");
        all = all && ok;
    };

    // Messages of awkward sizes: empty, one byte, exactly one frame, one byte over, large, the limit.
    std::string big;
    for (std::size_t i = 0; i < kMaxMessage; ++i)
        big.push_back(char((i * 131 + 7) & 0xff));
    bool trips = true;
    for (int mtu : {23, 185, 517}) {
        const std::size_t chunk = std::size_t(payloadPerFrame(mtu));
        for (std::size_t size : {std::size_t(0), std::size_t(1), chunk, chunk + 1, std::size_t(5000), kMaxMessage}) {
            const std::string msg = big.substr(0, size);
            const std::uint32_t number = std::uint32_t(0xA1B2C300u + size);
            const auto frames = split(msg, number, mtu);
            Reassembler r;
            std::string out;
            bool ok = !frames.empty();
            for (std::size_t i = 0; ok && i < frames.size(); ++i) {
                ok = frames[i].size() <= std::size_t(mtu - 3) && readNumber(frames[i]) == number;
                const auto res = r.add(frames[i], &out);
                ok = ok && res == (i + 1 == frames.size() ? Reassembler::Complete : Reassembler::Partial);
            }
            ok = ok && out == msg;
            if (!ok)
                printf("ble frames self-test: round trip failed at mtu %d, size %zu\n", mtu, size);
            trips = trips && ok;
        }
    }
    check("round trips at mtu 23, 185, 517", trips);

    const auto one = split("{\"t\":\"hello\"}", 0, 185);
    check("short message is one frame with flags 3", one.size() == 1 && std::uint8_t(one[0][0]) == 3);
    const auto two = split(std::string(200, 'x'), 1, 185);
    check("long message flags first 1, last 2", two.size() == 2 && std::uint8_t(two[0][0]) == 1 && std::uint8_t(two[1][0]) == 2);

    check("refuses to send over 64 KB", split(big + "x", 1, 517).empty());

    // A peer that sends more than 64 KB in frames is cut off.
    {
        Reassembler r;
        std::string out;
        const auto frames = split(big, 9, 517);
        for (std::size_t i = 0; i + 1 < frames.size(); ++i)
            r.add(frames[i], &out);
        std::string extra = frames[1];   // a middle frame, so the message keeps growing past the limit
        extra[0] = 0;
        const auto res1 = r.add(extra, &out);
        const auto res2 = r.add(frames.back(), &out);
        check("drops oversize incoming message", res1 == Reassembler::Dropped && res2 == Reassembler::Dropped);
    }

    // Out of order: a continuation with no first frame, or with the wrong message number.
    {
        const auto frames = split(std::string(500, 'y'), 4, 185);
        Reassembler r;
        std::string out;
        const bool noFirst = r.add(frames[1], &out) == Reassembler::Dropped;
        r.add(frames[0], &out);
        std::string wrong = frames[1];
        wrong[4] = char(5);
        const bool wrongNumber = r.add(wrong, &out) == Reassembler::Dropped;
        const bool tooShort = r.add(std::string("\x03\x00", 2), &out) == Reassembler::Dropped;
        // After all that, a clean message still gets through.
        bool recovers = true;
        for (std::size_t i = 0; i < frames.size(); ++i)
            recovers = recovers && r.add(frames[i], &out) == (i + 1 == frames.size() ? Reassembler::Complete : Reassembler::Partial);
        check("drops out-of-order frames", noFirst && wrongNumber && tooShort && recovers && out == std::string(500, 'y'));
    }

    printf("ble frames self-test: %s\n", all ? "all ok" : "FAILED");
    return all;
}
