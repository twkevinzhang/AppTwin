#include "../../main/jni/A64Inlinehook/A64FarJumpPatch.h"
#include <array>
#include <cassert>
#include <cstring>
#include <cstdint>
int main() {
    static_assert(A64FarJump::kSize == 16, "fixed island stub size");
    static_assert(A64FarJump::kInstructionCount == 4, "fixed island instruction footprint");
    // Test unpublished stub buffers at both alignments, preserving adjacent bytes.
    for (std::size_t offset : {std::size_t(0), std::size_t(4)}) {
        alignas(8) std::array<unsigned char, 36> bytes;
        bytes.fill(0xA5);
        unsigned char *entry = bytes.data() + offset;
        const std::uint32_t adjacent = 0xd503233fu; // PACIASP from ASUS libc open+0
        std::memcpy(entry + 16, &adjacent, sizeof(adjacent));
        const auto before = bytes;
        constexpr std::uint64_t destination = 0x123456789abcdef0ULL;
        A64FarJump::encode(entry, destination);
        std::uint32_t load, branch, next;
        std::uint64_t target;
        std::memcpy(&load, entry, 4); std::memcpy(&branch, entry + 4, 4);
        std::memcpy(&target, entry + 8, 8); std::memcpy(&next, entry + 16, 4);
        assert(load == 0x58000051u); // LDR X17 at entry, literal entry+8
        assert(branch == 0xd61f0220u); // BR X17
        assert(target == destination);
        assert(next == adjacent);
        for (std::size_t i = 0; i < bytes.size(); ++i) {
            if (i < offset || i >= offset + A64FarJump::kSize) assert(bytes[i] == before[i]);
        }
    }
}
