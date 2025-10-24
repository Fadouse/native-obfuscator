#include <cstddef>

namespace native_jvm::string_pool {
    static unsigned char pool[$size] = $value;

    char *get_pool() {
        return reinterpret_cast<char *>(pool);
    }

    std::size_t get_pool_size() {
        return sizeof(pool);
    }
}
