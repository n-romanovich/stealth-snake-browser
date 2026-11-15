#include "snake_pin_sha256.h"

#include <jni.h>
#include <stdlib.h>
#include <string.h>

#if defined(__aarch64__)
#include <asm/hwcap.h>
#include <sys/auxv.h>
#ifndef HWCAP_SHA2
#define HWCAP_SHA2 (1UL << 6)
#endif
#endif

static const uint32_t SHA256_H0[8] = {
    0x6a09e667U, 0xbb67ae85U, 0x3c6ef372U, 0xa54ff53aU,
    0x510e527fU, 0x9b05688cU, 0x1f83d9abU, 0x5be0cd19U
};

static const uint32_t SHA256_K[64] = {
    0x428a2f98U, 0x71374491U, 0xb5c0fbcfU, 0xe9b5dba5U,
    0x3956c25bU, 0x59f111f1U, 0x923f82a4U, 0xab1c5ed5U,
    0xd807aa98U, 0x12835b01U, 0x243185beU, 0x550c7dc3U,
    0x72be5d74U, 0x80deb1feU, 0x9bdc06a7U, 0xc19bf174U,
    0xe49b69c1U, 0xefbe4786U, 0x0fc19dc6U, 0x240ca1ccU,
    0x2de92c6fU, 0x4a7484aaU, 0x5cb0a9dcU, 0x76f988daU,
    0x983e5152U, 0xa831c66dU, 0xb00327c8U, 0xbf597fc7U,
    0xc6e00bf3U, 0xd5a79147U, 0x06ca6351U, 0x14292967U,
    0x27b70a85U, 0x2e1b2138U, 0x4d2c6dfcU, 0x53380d13U,
    0x650a7354U, 0x766a0abbU, 0x81c2c92eU, 0x92722c85U,
    0xa2bfe8a1U, 0xa81a664bU, 0xc24b8b70U, 0xc76c51a3U,
    0xd192e819U, 0xd6990624U, 0xf40e3585U, 0x106aa070U,
    0x19a4c116U, 0x1e376c08U, 0x2748774cU, 0x34b0bcb5U,
    0x391c0cb3U, 0x4ed8aa4aU, 0x5b9cca4fU, 0x682e6ff3U,
    0x748f82eeU, 0x78a5636fU, 0x84c87814U, 0x8cc70208U,
    0x90befffaU, 0xa4506cebU, 0xbef9a3f7U, 0xc67178f2U
};

static inline uint32_t rotr32(uint32_t x, int n) {
    return (x >> n) | (x << (32 - n));
}

static inline uint32_t load_be32(const uint8_t *p) {
    return ((uint32_t)p[0] << 24) |
           ((uint32_t)p[1] << 16) |
           ((uint32_t)p[2] << 8)  |
           ((uint32_t)p[3]);
}

static inline void store_be32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v >> 24);
    p[1] = (uint8_t)(v >> 16);
    p[2] = (uint8_t)(v >> 8);
    p[3] = (uint8_t)(v);
}

void snake_secure_zero(void *ptr, size_t len) {
    if (!ptr || len == 0) {
        return;
    }
    volatile uint8_t *p = (volatile uint8_t *)ptr;
    while (len--) {
        *p++ = 0;
    }
    __asm__ __volatile__("" : : "r"(ptr) : "memory");
}

void snake_sha256_compress_words_scalar(uint32_t state[8], const uint32_t w_host[16]) {
    uint32_t w[64];
    for (int i = 0; i < 16; ++i) {
        w[i] = w_host[i];
    }
    for (int i = 16; i < 64; ++i) {
        uint32_t s0 = rotr32(w[i - 15], 7) ^ rotr32(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t s1 = rotr32(w[i - 2], 17) ^ rotr32(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }

    uint32_t a = state[0];
    uint32_t b = state[1];
    uint32_t c = state[2];
    uint32_t d = state[3];
    uint32_t e = state[4];
    uint32_t f = state[5];
    uint32_t g = state[6];
    uint32_t h = state[7];

    for (int i = 0; i < 64; ++i) {
        uint32_t S1 = rotr32(e, 6) ^ rotr32(e, 11) ^ rotr32(e, 25);
        uint32_t ch = (e & f) ^ ((~e) & g);
        uint32_t temp1 = h + S1 + ch + SHA256_K[i] + w[i];
        uint32_t S0 = rotr32(a, 2) ^ rotr32(a, 13) ^ rotr32(a, 22);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        uint32_t temp2 = S0 + maj;

        h = g;
        g = f;
        f = e;
        e = d + temp1;
        d = c;
        c = b;
        b = a;
        a = temp1 + temp2;
    }

    state[0] += a;
    state[1] += b;
    state[2] += c;
    state[3] += d;
    state[4] += e;
    state[5] += f;
    state[6] += g;
    state[7] += h;

    snake_secure_zero(w, sizeof(w));
}

void snake_sha256_compress_block_scalar(uint32_t state[8], const uint8_t block[64]) {
    uint32_t w[16];
    for (int i = 0; i < 16; ++i) {
        w[i] = load_be32(block + (i * 4));
    }
    snake_sha256_compress_words_scalar(state, w);
    snake_secure_zero(w, sizeof(w));
}

typedef void (*sha256_compress_block_fn)(uint32_t state[8], const uint8_t block[64]);
typedef void (*sha256_compress_words_fn)(uint32_t state[8], const uint32_t w_host[16]);

static inline void select_sha256_impl(
    sha256_compress_block_fn *out_block,
    sha256_compress_words_fn *out_words
) {
#if defined(__aarch64__)
    unsigned long hwcap = getauxval(AT_HWCAP);
    if ((hwcap & HWCAP_SHA2) != 0UL) {
        *out_block = snake_sha256_compress_block_crypto;
        *out_words = snake_sha256_compress_words_crypto;
        return;
    }
#endif
    *out_block = snake_sha256_compress_block_scalar;
    *out_words = snake_sha256_compress_words_scalar;
}

typedef struct {
    uint32_t state[8];
    uint64_t total_bits;
    uint8_t buf[64];
    size_t buf_len;
} snake_sha256_ctx;

static void sha256_ctx_init(snake_sha256_ctx *ctx, const uint32_t init_state[8], uint64_t initial_bits) {
    memcpy(ctx->state, init_state, sizeof(ctx->state));
    ctx->total_bits = initial_bits;
    ctx->buf_len = 0;
}

static void sha256_ctx_update(
    snake_sha256_ctx *ctx,
    const uint8_t *data,
    size_t len,
    sha256_compress_block_fn compress_block
) {
    if (len == 0) {
        return;
    }
    ctx->total_bits += (uint64_t)len * 8ULL;
    size_t offset = 0;
    if (ctx->buf_len > 0) {
        size_t need = 64U - ctx->buf_len;
        size_t take = len < need ? len : need;
        memcpy(ctx->buf + ctx->buf_len, data, take);
        ctx->buf_len += take;
        offset += take;
        if (ctx->buf_len == 64U) {
            compress_block(ctx->state, ctx->buf);
            ctx->buf_len = 0;
        }
    }
    while (offset + 64U <= len) {
        compress_block(ctx->state, data + offset);
        offset += 64U;
    }
    if (offset < len) {
        size_t rem = len - offset;
        memcpy(ctx->buf, data + offset, rem);
        ctx->buf_len = rem;
    }
}

static void sha256_ctx_final_words(
    snake_sha256_ctx *ctx,
    uint32_t out_state[8],
    sha256_compress_block_fn compress_block
) {
    ctx->buf[ctx->buf_len++] = 0x80U;
    if (ctx->buf_len > 56U) {
        memset(ctx->buf + ctx->buf_len, 0, 64U - ctx->buf_len);
        compress_block(ctx->state, ctx->buf);
        ctx->buf_len = 0;
    }
    memset(ctx->buf + ctx->buf_len, 0, 56U - ctx->buf_len);
    uint64_t bits = ctx->total_bits;
    ctx->buf[56] = (uint8_t)(bits >> 56);
    ctx->buf[57] = (uint8_t)(bits >> 48);
    ctx->buf[58] = (uint8_t)(bits >> 40);
    ctx->buf[59] = (uint8_t)(bits >> 32);
    ctx->buf[60] = (uint8_t)(bits >> 24);
    ctx->buf[61] = (uint8_t)(bits >> 16);
    ctx->buf[62] = (uint8_t)(bits >> 8);
    ctx->buf[63] = (uint8_t)(bits);
    compress_block(ctx->state, ctx->buf);
    memcpy(out_state, ctx->state, 32U);
}

static int derive_pbkdf2_hmac_sha256(
    const uint8_t *password,
    size_t password_len,
    const uint8_t *salt,
    size_t salt_len,
    uint32_t iterations,
    uint8_t *out,
    size_t out_len
) {
    sha256_compress_block_fn compress_block = NULL;
    sha256_compress_words_fn compress_words = NULL;
    select_sha256_impl(&compress_block, &compress_words);

    uint8_t key_block[64];
    uint8_t ipad[64];
    uint8_t opad[64];
    uint32_t ipad_midstate[8];
    uint32_t opad_midstate[8];
    uint32_t u_words[8];
    uint32_t t_words[8];
    uint32_t msg_words[16];
    snake_sha256_ctx ctx;

    memset(key_block, 0, sizeof(key_block));
    if (password_len > 64U) {
        sha256_ctx_init(&ctx, SHA256_H0, 0ULL);
        sha256_ctx_update(&ctx, password, password_len, compress_block);
        uint32_t digest_words[8];
        sha256_ctx_final_words(&ctx, digest_words, compress_block);
        for (int i = 0; i < 8; ++i) {
            store_be32(key_block + (i * 4), digest_words[i]);
        }
        snake_secure_zero(digest_words, sizeof(digest_words));
    } else if (password_len > 0U) {
        memcpy(key_block, password, password_len);
    }

    for (size_t i = 0; i < 64U; ++i) {
        ipad[i] = key_block[i] ^ 0x36U;
        opad[i] = key_block[i] ^ 0x5cU;
    }

    memcpy(ipad_midstate, SHA256_H0, sizeof(ipad_midstate));
    compress_block(ipad_midstate, ipad);

    memcpy(opad_midstate, SHA256_H0, sizeof(opad_midstate));
    compress_block(opad_midstate, opad);

    snake_secure_zero(key_block, sizeof(key_block));
    snake_secure_zero(ipad, sizeof(ipad));
    snake_secure_zero(opad, sizeof(opad));

    /* Pre-populate fixed single-block SHA-256 padding for 32-byte HMAC payloads:
     * total bits = (64 byte key pad block + 32 byte payload) * 8 = 768 (0x300). */
    memset(msg_words, 0, sizeof(msg_words));
    msg_words[8] = 0x80000000U;
    msg_words[15] = 0x00000300U;

    size_t produced = 0U;
    uint32_t block_index = 1U;
    while (produced < out_len) {
        /* U_1 = HMAC(key, salt || BE32(block_index)) */
        sha256_ctx_init(&ctx, ipad_midstate, 512ULL);
        sha256_ctx_update(&ctx, salt, salt_len, compress_block);
        uint8_t idx_be[4];
        store_be32(idx_be, block_index);
        sha256_ctx_update(&ctx, idx_be, sizeof(idx_be), compress_block);
        sha256_ctx_final_words(&ctx, msg_words, compress_block);

        /* Outer SHA-256 on 32-byte inner digest using precomputed opad_midstate */
        memcpy(u_words, opad_midstate, sizeof(u_words));
        compress_words(u_words, msg_words);
        memcpy(t_words, u_words, sizeof(t_words));

        for (uint32_t iter = 1U; iter < iterations; ++iter) {
            memcpy(msg_words, u_words, 32U);
            memcpy(u_words, ipad_midstate, sizeof(u_words));
            compress_words(u_words, msg_words);

            memcpy(msg_words, u_words, 32U);
            memcpy(u_words, opad_midstate, sizeof(u_words));
            compress_words(u_words, msg_words);

            for (int w = 0; w < 8; ++w) {
                t_words[w] ^= u_words[w];
            }
        }

        uint8_t t_bytes[32];
        for (int w = 0; w < 8; ++w) {
            store_be32(t_bytes + (w * 4), t_words[w]);
        }
        size_t rem = out_len - produced;
        size_t take = rem < 32U ? rem : 32U;
        memcpy(out + produced, t_bytes, take);
        snake_secure_zero(t_bytes, sizeof(t_bytes));

        produced += take;
        ++block_index;
    }

    snake_secure_zero(&ctx, sizeof(ctx));
    snake_secure_zero(ipad_midstate, sizeof(ipad_midstate));
    snake_secure_zero(opad_midstate, sizeof(opad_midstate));
    snake_secure_zero(u_words, sizeof(u_words));
    snake_secure_zero(t_words, sizeof(t_words));
    snake_secure_zero(msg_words, sizeof(msg_words));
    return 0;
}

JNIEXPORT jbyteArray JNICALL
Java_com_nromanovich_stealthsnake_NativePinKdf_nativeDerivePbkdf2HmacSha256(
    JNIEnv *env,
    jclass clazz,
    jbyteArray password_obj,
    jbyteArray salt_obj,
    jint iterations,
    jint key_len_bytes
) {
    (void)clazz;
    if (!env || !password_obj || !salt_obj || iterations <= 0 || key_len_bytes <= 0 || key_len_bytes > 1024) {
        return NULL;
    }

    jsize pw_len = (*env)->GetArrayLength(env, password_obj);
    jsize salt_len = (*env)->GetArrayLength(env, salt_obj);
    if (pw_len < 0 || pw_len > 65536 || salt_len < 0 || salt_len > 65536) {
        return NULL;
    }

    uint8_t stack_pw[256];
    uint8_t stack_salt[256];
    uint8_t *pw_buf = stack_pw;
    uint8_t *salt_buf = stack_salt;

    if ((size_t)pw_len > sizeof(stack_pw)) {
        pw_buf = (uint8_t *)calloc((size_t)pw_len, 1U);
        if (!pw_buf) {
            return NULL;
        }
    }
    if ((size_t)salt_len > sizeof(stack_salt)) {
        salt_buf = (uint8_t *)calloc((size_t)salt_len, 1U);
        if (!salt_buf) {
            if (pw_buf != stack_pw) {
                snake_secure_zero(pw_buf, (size_t)pw_len);
                free(pw_buf);
            }
            return NULL;
        }
    }

    if (pw_len > 0) {
        (*env)->GetByteArrayRegion(env, password_obj, 0, pw_len, (jbyte *)pw_buf);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            goto cleanup_fail;
        }
    }
    if (salt_len > 0) {
        (*env)->GetByteArrayRegion(env, salt_obj, 0, salt_len, (jbyte *)salt_buf);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            goto cleanup_fail;
        }
    }

    uint8_t out_buf[1024];
    derive_pbkdf2_hmac_sha256(
        pw_buf,
        (size_t)pw_len,
        salt_buf,
        (size_t)salt_len,
        (uint32_t)iterations,
        out_buf,
        (size_t)key_len_bytes
    );

    jbyteArray result = (*env)->NewByteArray(env, key_len_bytes);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, key_len_bytes, (const jbyte *)out_buf);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            (*env)->DeleteLocalRef(env, result);
            result = NULL;
        }
    }
    snake_secure_zero(out_buf, (size_t)key_len_bytes);

    snake_secure_zero(pw_buf, (size_t)pw_len);
    snake_secure_zero(salt_buf, (size_t)salt_len);
    if (pw_buf != stack_pw) free(pw_buf);
    if (salt_buf != stack_salt) free(salt_buf);
    return result;

cleanup_fail:
    snake_secure_zero(pw_buf, (size_t)pw_len);
    snake_secure_zero(salt_buf, (size_t)salt_len);
    if (pw_buf != stack_pw) free(pw_buf);
    if (salt_buf != stack_salt) free(salt_buf);
    return NULL;
}
