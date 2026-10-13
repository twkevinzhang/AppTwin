#include "../../main/jni/A64Inlinehook/A64NearBranch.h"
#include <cassert>
#include <cstring>
#include <cstdio>
#include <string>

struct Fixture {
    std::string input;
    std::size_t position = 0, chunk = A64NearBranch::kMaximumMapBytes;
    unsigned interruptions = 0;
    int failure = 0;
    std::uint64_t now = 1;
    A64NearBranch::MapReadMetadata metadata;
    std::vector<A64NearBranch::MapRange> ranges;
    const char *reason = "";
    int error = 0;
    bool run() {
        return A64NearBranch::readMaps([this](char *out, std::size_t capacity, int *readError) -> std::ptrdiff_t {
            if (interruptions) { --interruptions; *readError = EINTR; return -1; }
            if (failure) { *readError = failure; return -1; }
            const auto count = std::min(std::min(capacity, chunk), input.size() - position);
            std::memcpy(out, input.data() + position, count);
            position += count;
            return count;
        }, [this] { return now; }, 1, &ranges, &reason, &error, &metadata);
    }
};
int main() {
    Fixture small;
    small.input = "1000-2000 rw-p 0 0 0 path\n";
    assert(small.run() && small.metadata.ranges == 1 && small.metadata.reads == 2);
    // A complete snapshot larger than the old cap is accepted; long labels are ignored.
    Fixture large;
    large.input = "1000-2000 " + std::string(353747, 'x') + "\n";
    assert(large.input.size() > 256u * 1024u && large.run());
    Fixture exact;
    exact.input = "1000-2000 " + std::string(A64NearBranch::kMaximumMapBytes - 11, 'x') + "\n";
    assert(exact.input.size() == A64NearBranch::kMaximumMapBytes);
    assert(exact.run() && exact.metadata.bytes == exact.input.size() && exact.metadata.reads == 2);
    Fixture over;
    over.input = exact.input + "x";
    assert(!over.run() && std::strcmp(over.reason, "maps-byte-limit") == 0);
    assert(over.metadata.reads == 2 && over.metadata.bytes == A64NearBranch::kMaximumMapBytes);
    Fixture partial;
    partial.input = "1000-2000 rw-p";
    assert(!partial.run() && std::strcmp(partial.reason, "maps-format-limit") == 0);
    assert(std::strcmp(partial.metadata.parse.reason, "partial-line") == 0 && partial.metadata.parse.line == 1);
    struct Invalid { const char *input; const char *reason; };
    for (const auto &invalid : {
            Invalid{"z-2000 r--p\n", "low-address"},
            Invalid{"1000:2000 r--p\n", "separator"},
            Invalid{"1000-z r--p\n", "high-address"},
            Invalid{"1000-2000X r--p\n", "address-suffix"},
            Invalid{"2000-1000 r--p\n", "reversed-range"},
            Invalid{"2000-2000 r--p 0 0 0\n", "empty-range"},
            Invalid{"1000-3000 r--p\n2000-4000 r--p\n", "overlap-order"}}) {
        Fixture invalidMap;
        invalidMap.input = invalid.input;
        assert(!invalidMap.run());
        assert(std::strcmp(invalidMap.metadata.parse.reason, invalid.reason) == 0);
        if (std::strcmp(invalid.reason, "empty-range") == 0) {
            assert(std::strcmp(invalidMap.metadata.parse.permissions, "r--p") == 0);
            assert(invalidMap.metadata.parse.headerBytes == 9);
        }
        assert(invalidMap.metadata.parse.line > 0 && invalidMap.metadata.parse.offset < invalidMap.input.size());
    }
    Fixture paddingPair;
    paddingPair.input = "2000-2000 r-xp 0 0 0\n2000-3000 ---p 0 0 0\n";
    assert(paddingPair.run() && paddingPair.metadata.parse.emptyPairs == 1);
    assert(paddingPair.ranges.size() == 1 && paddingPair.ranges[0].start == 0x2000 && paddingPair.ranges[0].end == 0x3000);
    std::vector<std::uintptr_t> gaps;
    assert(A64NearBranch::gapCandidates(paddingPair.ranges, 0x2000, 4096, &gaps));
    for (auto address : gaps) assert(address + 4096 <= 0x2000 || address >= 0x3000);
    Fixture wrongPaddingPair;
    wrongPaddingPair.input = "2000-2000 r-xp 0 0 0\n3000-4000 r-xp 0 0 0\n";
    assert(!wrongPaddingPair.run() && !wrongPaddingPair.metadata.parse.emptyNextSameStart &&
           !wrongPaddingPair.metadata.parse.emptyNextPadding);
    for (const char *input : {
            "2000-2000 r-xp 0 0 0\n2000-2000 ---p 0 0 0\n",
            "2000-2000 r-xp 0 0 0\n2000-1000 ---p 0 0 0\n",
            "2000-2000 r-xp 0 0 0\n2000-3000 ---p 0 0 0",
            "1000-4000 r--p 0 0 0\n2000-2000 r-xp 0 0 0\n2000-3000 ---p 0 0 0\n",
            "2000-2000 r-xp 0 0 0\n2000-4000 ---p 0 0 0\n3000-5000 r--p 0 0 0\n",
            "2000-2000 r-xp 0 0 0\n2000-3000 ---s 0 0 0\n",
            "2000-2000 bogus data\n2000-3000 ---p 0 0 0\n"}) {
        Fixture rejectedPair;
        rejectedPair.input = input;
        assert(!rejectedPair.run());
    }
    Fixture pairQuota;
    for (unsigned i = 0; i < A64NearBranch::kMaximumMapRanges / 2; ++i) {
        char lines[100];
        std::snprintf(lines, sizeof(lines), "%x-%x r-xp 0 0 0\n%x-%x ---p 0 0 0\n",
            0x1000 + i * 0x2000, 0x1000 + i * 0x2000, 0x1000 + i * 0x2000, 0x2000 + i * 0x2000);
        pairQuota.input += lines;
    }
    assert(pairQuota.run() && pairQuota.metadata.parse.line == A64NearBranch::kMaximumMapRanges &&
           pairQuota.metadata.parse.emptyPairs == A64NearBranch::kMaximumMapRanges / 2);
    Fixture pairOverQuota;
    pairOverQuota.input = pairQuota.input + "2000000-2001000 ---p 0 0 0\n";
    assert(!pairOverQuota.run() && std::strcmp(pairOverQuota.metadata.parse.reason, "range-limit") == 0);
    Fixture interrupted;
    interrupted.input = small.input;
    interrupted.interruptions = 2;
    assert(interrupted.run() && interrupted.metadata.reads == 4 && interrupted.error == 0);
    // Exact-cap probe can also resume after EINTR using a custom reader.
    unsigned reads = 0;
    A64NearBranch::MapReadMetadata m;
    std::vector<A64NearBranch::MapRange> r;
    const char *reason = "";
    int error = 0;
    assert(A64NearBranch::readMaps([&](char *out, std::size_t cap, int *err) -> std::ptrdiff_t {
        ++reads;
        if (reads == 1) { assert(cap == exact.input.size()); std::memcpy(out, exact.input.data(), cap); return cap; }
        assert(cap == 1);
        if (reads == 2) { *err = EINTR; return -1; }
        return 0;
    }, [] { return std::uint64_t(1); }, 1, &r, &reason, &error, &m));
    assert(m.reads == 3);
    Fixture deadline;
    deadline.input = small.input;
    deadline.now = 1 + A64NearBranch::kAllocationDeadlineNs;
    assert(!deadline.run() && std::strcmp(deadline.reason, "deadline") == 0 && deadline.metadata.reads == 0);
    // Deadline and quota remain effective during partial reads, including the EOF probe.
    unsigned clockCalls = 0;
    assert(!A64NearBranch::readMaps([](char *out, std::size_t, int *) -> std::ptrdiff_t {
        *out = '1'; return 1;
    }, [&] { return ++clockCalls == 1 ? std::uint64_t(1) : 1 + A64NearBranch::kAllocationDeadlineNs; },
        1, &r, &reason, &error, &m));
    assert(std::strcmp(reason, "deadline") == 0 && m.reads == 1);
    Fixture partialQuota;
    partialQuota.input = exact.input;
    partialQuota.chunk = 1;
    assert(!partialQuota.run() && partialQuota.metadata.reads == 128 && partialQuota.metadata.bytes == 128);
    Fixture quota;
    quota.input = small.input;
    quota.interruptions = 128;
    assert(!quota.run() && std::strcmp(quota.reason, "maps-read-quota") == 0 && quota.metadata.reads == 128);
    Fixture io;
    io.failure = EIO;
    assert(!io.run() && io.error == EIO && std::strcmp(io.reason, "maps-read") == 0);
    Fixture ranges;
    for (unsigned i = 0; i <= A64NearBranch::kMaximumMapRanges; ++i) {
        char line[80];
        std::snprintf(line, sizeof(line), "%x-%x r--p\n", 0x1000 + i * 0x2000, 0x2000 + i * 0x2000);
        ranges.input += line;
    }
    assert(!ranges.run() && ranges.metadata.ranges == A64NearBranch::kMaximumMapRanges);
    assert(std::strcmp(ranges.metadata.parse.reason, "range-limit") == 0);
    Fixture observedShape;
    for (unsigned i = 0; i < 3692; ++i) {
        char line[100];
        std::snprintf(line, sizeof(line), "%x-%x r--p 0 0 0 ", 0x1000 + i * 0x2000, 0x2000 + i * 0x2000);
        observedShape.input += std::string(line) + std::string(80, 'x') + "\n";
    }
    assert(observedShape.input.size() > 256u * 1024u);
    assert(observedShape.run() && observedShape.metadata.ranges == 3692);
}
