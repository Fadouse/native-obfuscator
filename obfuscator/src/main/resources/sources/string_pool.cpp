#include "string_pool.hpp"
#include "micro_vm.hpp"
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace native_jvm::string_pool {
    static unsigned char pool[$size] = $value;
    static unsigned char decrypted[$size] = {};

    static inline uint32_t rotl(uint32_t v, int c) {
        return (v << c) | (v >> (32 - c));
    }

    static void quarter_round(uint32_t &a, uint32_t &b, uint32_t &c, uint32_t &d) {
        a += b; d ^= a; d = rotl(d, 16);
        c += d; b ^= c; b = rotl(b, 12);
        a += b; d ^= a; d = rotl(d, 8);
        c += d; b ^= c; b = rotl(b, 7);
    }

    static void chacha_block(uint32_t out[16], const uint32_t key[8], const uint32_t nonce[3], uint32_t counter) {
        uint32_t state[16] = {
                0x61707865, 0x3320646e, 0x79622d32, 0x6b206574,
                key[0], key[1], key[2], key[3],
                key[4], key[5], key[6], key[7],
                counter, nonce[0], nonce[1], nonce[2]
        };
        std::memcpy(out, state, sizeof(state));
        for (int i = 0; i < 10; ++i) {
            quarter_round(out[0], out[4], out[8], out[12]);
            quarter_round(out[1], out[5], out[9], out[13]);
            quarter_round(out[2], out[6], out[10], out[14]);
            quarter_round(out[3], out[7], out[11], out[15]);
            quarter_round(out[0], out[5], out[10], out[15]);
            quarter_round(out[1], out[6], out[11], out[12]);
            quarter_round(out[2], out[7], out[8], out[13]);
            quarter_round(out[3], out[4], out[9], out[14]);
        }
        for (int i = 0; i < 16; ++i) {
            out[i] += state[i];
        }
    }

    static void crypt_string(const unsigned char key[32], const unsigned char nonce[12],
                             std::size_t offset, std::size_t len) {
        uint32_t key_words[8];
        uint32_t nonce_words[3];
        std::memcpy(key_words, key, 32);
        std::memcpy(nonce_words, nonce, 12);

        uint32_t block[16];
        uint32_t counter = 0;
        std::size_t i = 0;
        unsigned char *stream;
        while (i < len) {
            chacha_block(block, key_words, nonce_words, counter++);
            stream = reinterpret_cast<unsigned char *>(block);
            for (std::size_t j = 0; j < 64 && i < len; ++j, ++i) {
                pool[offset + i] ^= stream[j];
            }
        }
    }

    unsigned char *decode_key(const unsigned char in[32], uint32_t seed) {
        auto *out = new unsigned char[32];
        std::size_t i = 0;
        unsigned char *ptr = out;
        unsigned char tmp;
        goto CHECK;
    LOOP:
        tmp = static_cast<unsigned char>(
                vm::run_arith_vm(nullptr, vm::OP_XOR, in[i],
                                  seed >> ((i & 3) * 8), seed));
        *ptr++ = tmp;
        ++i;
    CHECK:
        if (i < 32) goto LOOP;
        return out;
    }

    unsigned char *decode_nonce(const unsigned char in[12], uint32_t seed) {
        auto *out = new unsigned char[12];
        std::size_t i = 0;
        unsigned char *ptr = out;
        unsigned char tmp;
        goto N_CHECK;
    N_LOOP:
        tmp = static_cast<unsigned char>(
                vm::run_arith_vm(nullptr, vm::OP_XOR, in[i],
                                  seed >> ((i & 3) * 8), seed));
        *ptr++ = tmp;
        ++i;
    N_CHECK:
        if (i < 12) goto N_LOOP;
        return out;
    }

    void decrypt_string(unsigned char *key, unsigned char *nonce,
                        uint32_t seed, std::size_t offset, std::size_t len) {
        (void)seed;
        if (!decrypted[offset]) {
            crypt_string(key, nonce, offset, len);
            std::memset(decrypted + offset, 1, len);
        }
        std::memset(key, 0, 32);
        std::memset(nonce, 0, 12);
        delete[] key;
        delete[] nonce;
    }

    void encrypt_string(unsigned char *key, unsigned char *nonce,
                        uint32_t seed, std::size_t offset, std::size_t len) {
        (void)seed;
        if (decrypted[offset]) {
            crypt_string(key, nonce, offset, len);
            std::memset(decrypted + offset, 0, len);
        }
        std::memset(key, 0, 32);
        std::memset(nonce, 0, 12);
        delete[] key;
        delete[] nonce;
    }

    void clear_string(std::size_t offset, std::size_t len) {
        std::memset(pool + offset, 0, len);
        std::memset(decrypted + offset, 0, len);
    }

    char *get_pool() {
        return reinterpret_cast<char *>(pool);
    }
}

