#ifndef APPTWIN_A64_FAR_JUMP_PATCH_H
#define APPTWIN_A64_FAR_JUMP_PATCH_H
#include <cstdint>
#include <cstring>
#include <cstddef>
namespace A64FarJump {
constexpr std::size_t kSize = 16;
constexpr int kInstructionCount = 4;
// AArch64 normal-memory LDR supports a literal at an address aligned to four
// bytes. Adding an alignment NOP would overwrite the next 16-byte libc veneer.
// memcpy avoids an unaligned C++ uint64_t store when the entry is 4 mod 8.
inline void encode(void *entry, std::uint64_t destination) {
    const std::uint32_t instructions[] = {0x58000051u, 0xd61f0220u};
    std::memcpy(entry, instructions, sizeof(instructions));
    std::memcpy(static_cast<unsigned char *>(entry) + sizeof(instructions),
                &destination, sizeof(destination));
}
}
#endif
