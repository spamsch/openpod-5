# Build and run the cryptographic reference vector generator under ARM emulation.
#
# Usage:
#   docker build -t openpod-native-vectors tests/native_vectors/
#   docker run --rm openpod-native-vectors > tests/native_vectors/vectors.json

FROM debian:bookworm-slim

# Install ARM cross-compiler (armel = soft-float)
RUN dpkg --add-architecture armel && \
    apt-get update && apt-get install -y --no-install-recommends \
    gcc-arm-linux-gnueabi libc6-dev-armel-cross \
    qemu-user-static \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /test

COPY test_crypto.c stub_liblog.c libc3ec87.so ./

# Build a stub liblog.so (soft-float ABI)
RUN arm-linux-gnueabi-gcc -shared -o liblog.so stub_liblog.c -fPIC

# Cross-compile the test harness for armel
RUN arm-linux-gnueabi-gcc -o test_crypto test_crypto.c -ldl -Wall -Wextra

# Create symlinks for Android library names expected by the native binary
RUN ln -sf /usr/arm-linux-gnueabi/lib/libdl.so.2 /test/libdl.so && \
    ln -sf /usr/arm-linux-gnueabi/lib/libm.so.6 /test/libm.so && \
    ln -sf /usr/arm-linux-gnueabi/lib/libc.so.6 /test/libc.so

# Run under QEMU user-mode emulation
ENV LD_LIBRARY_PATH=/test
CMD ["qemu-arm-static", "-L", "/usr/arm-linux-gnueabi", "/test/test_crypto"]
