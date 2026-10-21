# LiteRT public C headers

Public C API headers from Google's [LiteRT 2.2.0 C++ SDK](https://github.com/google-ai-edge/LiteRT/releases/download/v2.2.0/litert_cc_sdk.zip),
under Apache-2.0 (copyright and license notices retained in each header).
`build_config.h` enables declarations for both hardware accelerator families.
Only the stable C ABI is used, dynamically loaded from the official LiteRT 2.3.0
Android Maven runtime. No C++ ABI or private runtime structures are used.
