#ifndef STRING_POOL_HPP_GUARD

#define STRING_POOL_HPP_GUARD

#include <cstddef>

namespace native_jvm::string_pool {
    void decrypt_string(std::size_t offset, std::size_t len, const unsigned char key[32], const unsigned char nonce[12]);
    void encrypt_string(std::size_t offset, std::size_t len, const unsigned char key[32], const unsigned char nonce[12]);
    void clear_string(std::size_t offset, std::size_t len);
    char *get_pool();
}

#endif
