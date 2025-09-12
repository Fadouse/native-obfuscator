#ifndef STRING_POOL_HPP_GUARD

#define STRING_POOL_HPP_GUARD

#include <cstddef>

namespace native_jvm::string_pool {
    struct entry {
        std::size_t offset;
        unsigned char key[32];
        unsigned char nonce[12];
    };

    void decrypt_string(std::size_t offset, std::size_t len);
    void encrypt_string(std::size_t offset, std::size_t len);
    void clear_string(std::size_t offset, std::size_t len);
    char *get_pool();
}

#endif
