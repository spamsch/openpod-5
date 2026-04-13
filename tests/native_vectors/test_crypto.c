/**
 * Cryptographic reference vector generator.
 *
 * Loads a native crypto library and calls standalone functions with fixed
 * inputs, printing hex-encoded outputs as JSON reference vectors.
 *
 * Functions tested:
 * - SHA-256 (sha256_init/update/final)
 * - AES-128-ECB (AES128_ECB_encrypt) — foundation for CMAC and CCM
 * - Curve25519 (curve25519_donna)
 *
 * CMAC and CCM are not tested directly here. Instead, we test AES-ECB
 * thoroughly since both CMAC and CCM are built on it. CMAC/CCM are
 * verified against RFC test vectors in the Kotlin and Python test suites.
 */

#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <dlfcn.h>

static void print_hex(const char *label, const unsigned char *data, int len) {
    printf("    \"%s\": \"", label);
    for (int i = 0; i < len; i++)
        printf("%02x", data[i]);
    printf("\"");
}

/* SHA-256 context (standard layout) */
typedef struct {
    unsigned char data[64];
    unsigned int datalen;
    unsigned long long bitlen;
    unsigned int state[8];
} sha256_ctx;

typedef int (*curve25519_fn)(unsigned char *, const unsigned char *, const unsigned char *);
typedef void (*sha256_init_fn)(sha256_ctx *);
typedef void (*sha256_update_fn)(sha256_ctx *, const unsigned char *, unsigned int);
typedef void (*sha256_final_fn)(sha256_ctx *, unsigned char *);
typedef void (*aes128_ecb_encrypt_fn)(const unsigned char *, const unsigned char *, unsigned char *);

int main(void) {
    void *lib = dlopen("/test/libc3ec87.so", RTLD_NOW);
    if (!lib) {
        fprintf(stderr, "Failed to load libc3ec87.so: %s\n", dlerror());
        return 1;
    }

    printf("{\n");

    /* ── SHA-256 ────────────────────────────────────────────────────── */
    {
        sha256_init_fn sha_init = dlsym(lib, "sha256_init");
        sha256_update_fn sha_update = dlsym(lib, "sha256_update");
        sha256_final_fn sha_final = dlsym(lib, "sha256_final");

        if (sha_init && sha_update && sha_final) {
            sha256_ctx ctx;
            unsigned char hash[32];

            printf("  \"sha256\": [\n");

            /* Empty input */
            memset(&ctx, 0, sizeof(ctx));
            sha_init(&ctx);
            sha_final(&ctx, hash);
            printf("    {\"input\": \"\", ");
            print_hex("output", hash, 32);
            printf("},\n");

            /* "abc" */
            memset(&ctx, 0, sizeof(ctx));
            sha_init(&ctx);
            sha_update(&ctx, (const unsigned char *)"abc", 3);
            sha_final(&ctx, hash);
            printf("    {\"input\": \"616263\", ");
            print_hex("output", hash, 32);
            printf("},\n");

            /* 55 bytes of 0x42 */
            unsigned char data55[55];
            memset(data55, 0x42, 55);
            memset(&ctx, 0, sizeof(ctx));
            sha_init(&ctx);
            sha_update(&ctx, data55, 55);
            sha_final(&ctx, hash);
            printf("    {\"input\": \"");
            for (int i = 0; i < 55; i++) printf("42");
            printf("\", ");
            print_hex("output", hash, 32);
            printf("},\n");

            /* KDF-like input: length-prefixed concatenation for LTK derivation test */
            /* This simulates the KDF hash input with known firmware_id, controller_id,
               phone_key, pod_key, and shared_secret */
            unsigned char kdf_input[190]; /* 5 * (8 prefix + data) */
            int offset = 0;

            /* firmware_id = 0xaabbccddeeff (6 bytes) */
            unsigned char fwid[6] = {0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff};
            unsigned char prefix_6[8] = {0,0,0,0, 0,6, 0,0};
            memcpy(kdf_input + offset, prefix_6, 8); offset += 8;
            memcpy(kdf_input + offset, fwid, 6); offset += 6;

            /* controller_id = 0x01020304 (4 bytes) */
            unsigned char ctrlid[4] = {0x01, 0x02, 0x03, 0x04};
            unsigned char prefix_4[8] = {0,0,0,0, 0,4, 0,0};
            memcpy(kdf_input + offset, prefix_4, 8); offset += 8;
            memcpy(kdf_input + offset, ctrlid, 4); offset += 4;

            /* phone_public_key = 32 bytes of 0x11 */
            unsigned char phone_key[32];
            memset(phone_key, 0x11, 32);
            unsigned char prefix_32[8] = {0,0,0,0, 0,32, 0,0};
            memcpy(kdf_input + offset, prefix_32, 8); offset += 8;
            memcpy(kdf_input + offset, phone_key, 32); offset += 32;

            /* pod_public_key = 32 bytes of 0x22 */
            unsigned char pod_key[32];
            memset(pod_key, 0x22, 32);
            memcpy(kdf_input + offset, prefix_32, 8); offset += 8;
            memcpy(kdf_input + offset, pod_key, 32); offset += 32;

            /* shared_secret = 32 bytes of 0x33 */
            unsigned char secret[32];
            memset(secret, 0x33, 32);
            memcpy(kdf_input + offset, prefix_32, 8); offset += 8;
            memcpy(kdf_input + offset, secret, 32); offset += 32;

            memset(&ctx, 0, sizeof(ctx));
            sha_init(&ctx);
            sha_update(&ctx, kdf_input, offset);
            sha_final(&ctx, hash);
            printf("    {\"input\": \"kdf_test\", \"input_len\": %d, ", offset);
            print_hex("output", hash, 32);
            printf(",\n");
            printf("      \"conf_key\": \"");
            for (int i = 0; i < 16; i++) printf("%02x", hash[i]);
            printf("\",\n");
            printf("      \"ltk\": \"");
            for (int i = 16; i < 32; i++) printf("%02x", hash[i]);
            printf("\"}\n");

            printf("  ],\n");
        }
    }

    /* ── AES-128-ECB ────────────────────────────────────────────────── */
    {
        aes128_ecb_encrypt_fn aes_enc = dlsym(lib, "AES128_ECB_encrypt");

        if (aes_enc) {
            printf("  \"aes128_ecb\": [\n");

            /* FIPS 197 test vector */
            unsigned char key1[16] = {
                0x2b,0x7e,0x15,0x16,0x28,0xae,0xd2,0xa6,
                0xab,0xf7,0x15,0x88,0x09,0xcf,0x4f,0x3c
            };
            unsigned char pt1[16] = {
                0x32,0x43,0xf6,0xa8,0x88,0x5a,0x30,0x8d,
                0x31,0x31,0x98,0xa2,0xe0,0x37,0x07,0x34
            };
            unsigned char ct1[16];
            aes_enc(pt1, key1, ct1);
            printf("    {");
            print_hex("key", key1, 16);
            printf(", ");
            print_hex("plaintext", pt1, 16);
            printf(", ");
            print_hex("ciphertext", ct1, 16);
            printf("},\n");

            /* All-zeros key + all-zeros plaintext (for CMAC subkey gen) */
            unsigned char key0[16] = {0};
            unsigned char pt0[16] = {0};
            unsigned char ct0[16];
            aes_enc(pt0, key0, ct0);
            printf("    {");
            print_hex("key", key0, 16);
            printf(", ");
            print_hex("plaintext", pt0, 16);
            printf(", ");
            print_hex("ciphertext", ct0, 16);
            printf("},\n");

            /* RFC 4493 CMAC key: E(K, 0^128) = L for subkey derivation */
            aes_enc(pt0, key1, ct0);
            printf("    {\"description\": \"cmac_subkey_L\", ");
            print_hex("key", key1, 16);
            printf(", ");
            print_hex("plaintext", pt0, 16);
            printf(", ");
            print_hex("L", ct0, 16);
            printf("}\n");

            printf("  ],\n");
        }
    }

    /* ── Curve25519 ─────────────────────────────────────────────────── */
    {
        curve25519_fn curve25519 = dlsym(lib, "curve25519_donna");

        if (curve25519) {
            printf("  \"curve25519\": [\n");

            /* Basepoint */
            unsigned char bp[32] = {0};
            bp[0] = 9;

            /* Test 1: fixed private key */
            unsigned char priv1[32];
            memset(priv1, 0x42, 32);
            priv1[0] &= 248; priv1[31] &= 127; priv1[31] |= 64;
            unsigned char pub1[32];
            curve25519(pub1, priv1, bp);
            printf("    {\"description\": \"keygen_0x42\", ");
            print_hex("private", priv1, 32);
            printf(", ");
            print_hex("public", pub1, 32);
            printf("},\n");

            /* Test 2: shared secret */
            unsigned char alice_priv[32], bob_priv[32];
            memset(alice_priv, 0x11, 32);
            alice_priv[0] &= 248; alice_priv[31] &= 127; alice_priv[31] |= 64;
            memset(bob_priv, 0x22, 32);
            bob_priv[0] &= 248; bob_priv[31] &= 127; bob_priv[31] |= 64;

            unsigned char alice_pub[32], bob_pub[32];
            curve25519(alice_pub, alice_priv, bp);
            curve25519(bob_pub, bob_priv, bp);

            unsigned char alice_shared[32], bob_shared[32];
            curve25519(alice_shared, alice_priv, bob_pub);
            curve25519(bob_shared, bob_priv, alice_pub);

            printf("    {\"description\": \"shared_secret\", ");
            print_hex("alice_priv", alice_priv, 32);
            printf(", ");
            print_hex("alice_pub", alice_pub, 32);
            printf(", ");
            print_hex("bob_priv", bob_priv, 32);
            printf(", ");
            print_hex("bob_pub", bob_pub, 32);
            printf(", ");
            print_hex("shared", alice_shared, 32);
            printf(", \"match\": %s}\n",
                   memcmp(alice_shared, bob_shared, 32) == 0 ? "true" : "false");

            printf("  ]\n");
        }
    }

    printf("}\n");
    dlclose(lib);
    return 0;
}
