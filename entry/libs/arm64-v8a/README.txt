FreeRDP HarmonyOS Libraries
Status: NEEDS REBUILD

The libraries in this directory must be rebuilt using GitHub Actions workflow
"Build FreeRDP for HarmonyOS (OHOS NDK)" to fix the runtime loading error.

PROBLEM:
Previous builds included libssl.so.3 and libcrypto.so.3 compiled with
standard Linux toolchain (glibc), which references ld-linux-aarch64.so.1.
HarmonyOS uses musl libc, not glibc, causing runtime load failure.

SOLUTION:
Run the updated build-freerdp-harmonyos.yml workflow which:
1. Uses OHOS NDK compiler (--target=aarch64-linux-ohos)
2. Links OpenSSL statically into FreeRDP
3. Verifies libraries don't reference glibc loader

Required libraries after rebuild:
- libfreerdp3.so (FreeRDP core, with static OpenSSL)
- libwinpr3.so (WinPR utilities)

Removed incompatible libraries:
- libssl.so.3 (was glibc-linked)
- libcrypto.so.3 (was glibc-linked)  
- libav*.so (FFmpeg, was glibc-linked)
- libz.so (zlib, was glibc-linked)
- libcjson.so (was glibc-linked)
- OpenSSL engine plugins (capi.so, legacy.so, etc.)
