# Third-party notices

The original copyright and license headers remain in the vendored source.

| Component | Vendored location | License / notice |
| --- | --- | --- |
| VirtualApp / VirtualXposed | `src/main/java`, `src/main/aidl`, native integration | GNU GPL v3; `UPSTREAM-LICENSE.txt` |
| FreeReflection 3.0.1 by weishu | `src/main/java/me/weishu/reflection` | MIT; `licenses/FreeReflection-LICENSE.txt` |
| And64InlineHook by Rprop | `src/main/jni/A64Inlinehook` | MIT; complete terms are preserved at the top of `And64InlineHook.cpp` and `.hpp` |
| fake_dlfcn by avs333 | `src/main/jni/Foundation/fake_dlfcn.*` | MIT; complete terms are preserved in both files |
| Cydia Substrate by Jay Freeman | `src/main/jni/Substrate` | GNU LGPL v3 or later; copyright and grant are preserved in source headers |
| Hacker Disassembler Engine 64 by Vyacheslav Patkov | `src/main/jni/Substrate/hde64.*`, `table64.h` | Upstream copyright and all-rights-reserved notice preserved in source |
| fbjni / Facebook Android support | `src/main/jni/fb` | BSD-style notice plus upstream patent grant reference; copyright headers are preserved in source |

The imported VirtualXposed tree did not include standalone fbjni `LICENSE` or
`PATENTS` files. Before binary distribution, the exact fbjni snapshot must be
matched to its upstream release and those complete notices added. This is a
known provenance gap, not an assertion that the source has no obligations.

