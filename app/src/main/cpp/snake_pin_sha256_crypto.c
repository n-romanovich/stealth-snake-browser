#include "snake_pin_sha256.h"

#if defined(__aarch64__)
#include <arm_neon.h>

static const uint32_t SHA256_K_CRYPTO[64] __attribute__((aligned(16))) = {
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

#define SHA256_CRYPTO_4ROUNDS(msg_vec, k_offset)                           \
    do {                                                                   \
        uint32x4_t wk = vaddq_u32((msg_vec), vld1q_u32(&SHA256_K_CRYPTO[k_offset])); \
        uint32x4_t abcd_save = abcd;                                       \
        abcd = vsha256hq_u32(abcd, efgh, wk);                              \
        efgh = vsha256h2q_u32(efgh, abcd_save, wk);                        \
    } while (0)

#define SHA256_CRYPTO_SCHEDULE(t0, t1, t2, t3)                             \
    do {                                                                   \
        (t0) = vsha256su1q_u32(vsha256su0q_u32((t0), (t1)), (t2), (t3));  \
    } while (0)

static inline void sha256_compress_vectors(
    uint32_t state[8],
    uint32x4_t m0,
    uint32x4_t m1,
    uint32x4_t m2,
    uint32x4_t m3
) {
    uint32x4_t abcd = vld1q_u32(&state[0]);
    uint32x4_t efgh = vld1q_u32(&state[4]);
    const uint32x4_t abcd0 = abcd;
    const uint32x4_t efgh0 = efgh;

    SHA256_CRYPTO_4ROUNDS(m0, 0);
    SHA256_CRYPTO_SCHEDULE(m0, m1, m2, m3);
    SHA256_CRYPTO_4ROUNDS(m1, 4);
    SHA256_CRYPTO_SCHEDULE(m1, m2, m3, m0);
    SHA256_CRYPTO_4ROUNDS(m2, 8);
    SHA256_CRYPTO_SCHEDULE(m2, m3, m0, m1);
    SHA256_CRYPTO_4ROUNDS(m3, 12);
    SHA256_CRYPTO_SCHEDULE(m3, m0, m1, m2);

    SHA256_CRYPTO_4ROUNDS(m0, 16);
    SHA256_CRYPTO_SCHEDULE(m0, m1, m2, m3);
    SHA256_CRYPTO_4ROUNDS(m1, 20);
    SHA256_CRYPTO_SCHEDULE(m1, m2, m3, m0);
    SHA256_CRYPTO_4ROUNDS(m2, 24);
    SHA256_CRYPTO_SCHEDULE(m2, m3, m0, m1);
    SHA256_CRYPTO_4ROUNDS(m3, 28);
    SHA256_CRYPTO_SCHEDULE(m3, m0, m1, m2);

    SHA256_CRYPTO_4ROUNDS(m0, 32);
    SHA256_CRYPTO_SCHEDULE(m0, m1, m2, m3);
    SHA256_CRYPTO_4ROUNDS(m1, 36);
    SHA256_CRYPTO_SCHEDULE(m1, m2, m3, m0);
    SHA256_CRYPTO_4ROUNDS(m2, 40);
    SHA256_CRYPTO_SCHEDULE(m2, m3, m0, m1);
    SHA256_CRYPTO_4ROUNDS(m3, 44);
    SHA256_CRYPTO_SCHEDULE(m3, m0, m1, m2);

    SHA256_CRYPTO_4ROUNDS(m0, 48);
    SHA256_CRYPTO_4ROUNDS(m1, 52);
    SHA256_CRYPTO_4ROUNDS(m2, 56);
    SHA256_CRYPTO_4ROUNDS(m3, 60);

    abcd = vaddq_u32(abcd, abcd0);
    efgh = vaddq_u32(efgh, efgh0);
    vst1q_u32(&state[0], abcd);
    vst1q_u32(&state[4], efgh);
}

void snake_sha256_compress_block_crypto(uint32_t state[8], const uint8_t block[64]) {
    uint32x4_t m0 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(block + 0)));
    uint32x4_t m1 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(block + 16)));
    uint32x4_t m2 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(block + 32)));
    uint32x4_t m3 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(block + 48)));
    sha256_compress_vectors(state, m0, m1, m2, m3);
}

void snake_sha256_compress_words_crypto(uint32_t state[8], const uint32_t w_host[16]) {
    uint32x4_t m0 = vld1q_u32(w_host + 0);
    uint32x4_t m1 = vld1q_u32(w_host + 4);
    uint32x4_t m2 = vld1q_u32(w_host + 8);
    uint32x4_t m3 = vld1q_u32(w_host + 12);
    sha256_compress_vectors(state, m0, m1, m2, m3);
}

#endif /* __aarch64__ */
