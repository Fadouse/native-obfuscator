#include "string_pool.hpp"

namespace native_jvm::string_pool {
    static char pool[$size] = $value;

    void decrypt_pool() {
        for (size_t i = 0; i < $size; ++i) {
            if (pool[i] != 0) {
                unsigned char key = (i * 0x5A + 0xAC) & 0xFF;
                unsigned char val = static_cast<unsigned char>(pool[i]) - 0x33;
                pool[i] = static_cast<char>(val ^ key);
            }
        }
    }

    char *get_pool() {
        return pool;
    }
}
