#ifndef STRING_POOL_HPP_GUARD

#define STRING_POOL_HPP_GUARD

#include <cstddef>
#include <cstdint>

namespace native_jvm::string_pool {
    unsigned char *decode_key(const unsigned char in[32], uint32_t seed);
    unsigned char *decode_nonce(const unsigned char in[12], uint32_t seed);
    void decrypt_string(unsigned char *key, unsigned char *nonce,
                        uint32_t seed, std::size_t offset, std::size_t len);
    void encrypt_string(unsigned char *key, unsigned char *nonce,
                        uint32_t seed, std::size_t offset, std::size_t len);
    void clear_string(std::size_t offset, std::size_t len);
    char *get_pool();
    std::size_t get_pool_size();
}

#endif
