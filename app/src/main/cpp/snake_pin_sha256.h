#ifndef SNAKE_PIN_SHA256_H
#define SNAKE_PIN_SHA256_H

#include <stddef.h>
#include <stdint.h>

void snake_secure_zero(void *ptr, size_t len);
void snake_sha256_compress_block_scalar(uint32_t state[8], const uint8_t block[64]);
void snake_sha256_compress_words_scalar(uint32_t state[8], const uint32_t w_host[16]);

#if defined(__aarch64__)
void snake_sha256_compress_block_crypto(uint32_t state[8], const uint8_t block[64]);
void snake_sha256_compress_words_crypto(uint32_t state[8], const uint32_t w_host[16]);
#endif

#endif /* SNAKE_PIN_SHA256_H */
