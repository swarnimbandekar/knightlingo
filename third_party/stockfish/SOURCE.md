# Stockfish 19 build provenance

Knightlingo uses Stockfish 19 as a separate local UCI process. Its executable is packaged as `app/src/main/jniLibs/arm64-v8a/libstockfish.so` so Android installs it in the app's native library directory.

## Upstream inputs

- Project: <https://github.com/official-stockfish/Stockfish>
- Release tag: `sf_19`
- Source archive: <https://github.com/official-stockfish/Stockfish/releases/download/sf_19/stockfish-android-arm64-universal.tar.gz>
- Archive SHA-256: `ebb24051aa4a222b4daaf049b882ecf1163d370c128fe02316602643f4d5e426`
- Extracted source directory used here: `.tools/stockfish19/stockfish/src/`
- Source changes: none. The local script compiles the translation units listed in the upstream Makefile's `SRCS` variable with the flags below.
- Embedded NNUE: `nn-1a298aa575a0.nnue`
- Network download: <https://github.com/official-stockfish/networks/raw/master/nn-1a298aa575a0.nnue>
- Network SHA-256: `1a298aa575a085434d29027978dc36867fe9c5bcea9376654b7a8eba1e52dfc2`
- License: GPL version 3 or later, as stated in the upstream source headers. The upstream `Copying.txt` and `AUTHORS` files accompany this record.

The executable supplied in the upstream archive is not used in Knightlingo. It was rebuilt locally to align every ELF LOAD segment to 16 KiB.

The GitHub release also includes a `ChessTip-<version>-stockfish-source.tar.gz` archive with the unchanged Stockfish sources, the exact NNUE network embedded in the binary, license files, and the local build helper. It does not include signing keys or the Android NDK.

## Toolchain and build

- Android NDK r28c, revision `28.2.13676358`, Windows x86-64 LLVM toolchain.
- NDK archive: <https://dl.google.com/android/repository/android-ndk-r28c-windows.zip>
- NDK archive SHA-1 from Google's repository metadata, verified locally: `086bba43ff2f5eb0e387b15c8278bb4e0d89ba1d`
- Target: `aarch64-linux-android29`, baseline ARM64 NEON.
- C++ flags: `-std=c++17 -O3 -DNDEBUG -DIS_64BIT -DUSE_NEON -DUSE_POPCNT -fno-exceptions -fPIE -pthread -fconstexpr-steps=500000000`.
- Link flags: `-pie -static-libstdc++ -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -latomic`.
- Postprocessing: `llvm-strip --strip-debug`.
- Output SHA-256: `b9690e66f390b571ce6993bcfe00db3a845be56adbf55e6e40cc093e7ccae9c7`.
- Verified ELF LOAD alignments: `[16384, 16384, 16384, 16384]`.

To rebuild on this Windows workspace:

1. Extract the verified source archive so `src/Makefile` exists at `.tools/stockfish19/stockfish/src/Makefile`.
2. Extract the NDK so its LLVM `bin/clang++.exe` exists under `.tools/android-ndk-r28c/toolchains/llvm/prebuilt/windows-x86_64/`.
3. Run `python scripts/build_stockfish.py`. It fetches the named network if missing, verifies its hash prefix, compiles, links, checks segment alignment, and copies the result into `jniLibs`.
4. Run `.\scripts\android.ps1 build` to build the APK and run checks. The script's printed SHA-256 identifies the locally produced engine binary; exact bytes can depend on the toolchain and input paths.

For a clean rebuild after changing headers, remove the object files inside `.tools/stockfish-android16k/` first. The helper caches object files by each translation unit's modification time.
