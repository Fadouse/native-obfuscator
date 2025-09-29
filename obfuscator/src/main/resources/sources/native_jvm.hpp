#include "jni.h"

// Fix https://developercommunity.visualstudio.com/t/Access-violation-in-_Thrd_yield-after-up/10664660#T-N10668856
// Thanks, microsoft!
#define _DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR

#include <cmath>
#include <cstring>
#include <string>
#include <cstdio>
#include <unordered_set>
#include <mutex>
#include <atomic>
#include <initializer_list>
#include <cstdint>

#ifndef NATIVE_JVM_HPP_GUARD

#define NATIVE_JVM_HPP_GUARD

namespace native_jvm::utils {

    void init_utils(JNIEnv *env);

    void debug_print_stack_state(JNIEnv *env, const char *context, int object_index, int return_index, int line);
    void debug_print_int(JNIEnv *env, const char *context, jint value, int line);

    void throw_re(JNIEnv *env, const char *exception_class, const char *error, int line);

    jobjectArray create_multidim_array(JNIEnv *env, jobject classloader, jint count, jint required_count,
        const char *class_name, int line, std::initializer_list<jint> sizes, int dim_index = 0);

    template <int sort>
    jarray create_array_value(JNIEnv* env, jint size);

    template <int sort>
    jarray create_multidim_array_value(JNIEnv *env, jint count, jint required_count,
        const char *name, int line, std::initializer_list<jint> sizes, int dim_index = 0) {
        if (required_count == 0) {
            env->FatalError("required_count == 0");
            return nullptr;
        }
        jint current_size = sizes.begin()[dim_index];
        if (current_size < 0) {
            throw_re(env, "java/lang/NegativeArraySizeException", "MULTIANEWARRAY size < 0", line);
            return nullptr;
        }
        if (count == 1) {
            return create_array_value<sort>(env, current_size);
        }
        jobjectArray result_array = nullptr;
        if (jclass clazz = env->FindClass((std::string(count - 1, '[') + std::string(name)).c_str())) {
            result_array = env->NewObjectArray(current_size, clazz, nullptr);
            if (env->ExceptionCheck()) {
                return nullptr;
            }
            env->DeleteLocalRef(clazz);
        }
        else
            return nullptr;

        if (required_count == 1) {
            return result_array;
        }

        for (jint i = 0; i < current_size; i++) {
            jarray inner_array = create_multidim_array_value<sort>(env, count - 1, required_count - 1,
                name, line, sizes, dim_index + 1);
            if (env->ExceptionCheck()) {
                env->DeleteLocalRef(result_array);
                return nullptr;
            }
            env->SetObjectArrayElement(result_array, i, inner_array);
            env->DeleteLocalRef(inner_array);
            if (env->ExceptionCheck()) {
                env->DeleteLocalRef(result_array);
                return nullptr;
            }
        }
        return result_array;
    }

#ifdef USE_HOTSPOT
    jobject link_call_site(JNIEnv *env, jobject caller_obj, jobject bootstrap_method_obj,
            jobject name_obj, jobject type_obj, jobject static_arguments, jobject appendix_result);
#endif

    jclass find_class_wo_static(JNIEnv *env, jobject classloader, jstring class_name);

    jclass get_class_from_object(JNIEnv *env, jobject object);

    jobject get_classloader_from_class(JNIEnv *env, jclass clazz);

    jobject get_lookup(JNIEnv *env, jclass clazz);

    void bastore(JNIEnv *env, jarray array, jint index, jint value);
    jbyte baload(JNIEnv *env, jarray array, jint index);

    void clear_refs(JNIEnv *env, std::unordered_set<jobject> &refs);

    jstring get_interned(JNIEnv *env, jstring value);

    // Ensure the class identified by dot-style name is initialized.
    // This mirrors JVM semantics where getstatic/putstatic/invokestatic
    // trigger <clinit> on first use.
    void ensure_initialized(JNIEnv *env, jobject classloader, const char *class_name_dot);
    void ensure_initialized(JNIEnv *env, jobject classloader, jstring class_name_dot);

    inline uint32_t rotl32(uint32_t v, int r) {
        return (v << r) | (v >> (32 - r));
    }

    inline uint32_t chacha_round(uint32_t a, uint32_t b, uint32_t c, uint32_t d) {
        a += b; d ^= a; d = rotl32(d, 16);
        c += d; b ^= c; b = rotl32(b, 12);
        a += b; d ^= a; d = rotl32(d, 8);
        c += d; b ^= c; b = rotl32(b, 7);
        return a;
    }

    inline uint32_t mix32(uint32_t key, uint32_t method_id, uint32_t class_id, uint32_t seed) {
        return chacha_round(key, method_id, class_id, seed);
    }

    inline uint64_t mix64(uint64_t key, uint32_t method_id, uint32_t class_id, uint32_t seed) {
        uint32_t k1 = static_cast<uint32_t>(key);
        uint32_t k2 = static_cast<uint32_t>(key >> 32);
        uint32_t s2 = seed ^ 0x9E3779B9u;
        uint32_t r1 = chacha_round(k1, method_id, class_id, seed);
        uint32_t r2 = chacha_round(k2, class_id, method_id, s2);
        return (static_cast<uint64_t>(r2) << 32) | r1;
    }

    inline jint decode_int(jint enc, jint key, jint method_id, jint class_id, jint seed) {
        uint32_t mixed = mix32(static_cast<uint32_t>(key), static_cast<uint32_t>(method_id),
                static_cast<uint32_t>(class_id), static_cast<uint32_t>(seed));
        return enc ^ static_cast<jint>(mixed);
    }

    inline jlong decode_long(jlong enc, jlong key, jint method_id, jint class_id, jint seed) {
        uint64_t mixed = mix64(static_cast<uint64_t>(key), static_cast<uint32_t>(method_id),
                static_cast<uint32_t>(class_id), static_cast<uint32_t>(seed));
        return enc ^ static_cast<jlong>(mixed);
    }

    inline jfloat decode_float(jint enc, jint key, jint method_id, jint class_id, jint seed) {
        jint dec = decode_int(enc, key, method_id, class_id, seed);
        jfloat result;
        std::memcpy(&result, &dec, sizeof(result));
        return result;
    }

    inline jdouble decode_double(jlong enc, jlong key, jint method_id, jint class_id, jint seed) {
        jlong dec = decode_long(enc, key, method_id, class_id, seed);
        jdouble result;
        std::memcpy(&result, &dec, sizeof(result));
        return result;
    }
}

#endif
