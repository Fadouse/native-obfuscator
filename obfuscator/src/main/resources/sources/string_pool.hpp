#ifndef STRING_POOL_HPP_GUARD

#define STRING_POOL_HPP_GUARD

#include <cstddef>

namespace native_jvm::string_pool {
    // Decrypts a single string at the given offset and returns a pointer to
    // the decrypted data. Only the requested string is decrypted in-place.
    char *decrypt_string(std::size_t offset);
}

#endif
