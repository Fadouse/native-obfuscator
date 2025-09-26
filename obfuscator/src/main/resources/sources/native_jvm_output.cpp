#include "native_jvm.hpp"
#include "native_jvm_output.hpp"
#include "string_pool.hpp"
#include "anti_debug.hpp"

$includes

namespace native_jvm {

    typedef void (* reg_method)(JNIEnv *,jclass);

    reg_method reg_methods[$class_count] = {0};
    static JavaVM* cached_vm = nullptr;

    void register_for_class(JNIEnv *env, jclass, jint id, jclass clazz) {
        // Guard against out-of-range indexes or missing registration entries.
        // This avoids calling through a null/garbage function pointer and crashing the JVM.
        if (id < 0 || id >= $class_count) {
            return;
        }
        if (cached_vm && env == nullptr) {
            if (cached_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_8) == JNI_EDETACHED) {
                if (cached_vm->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr) != JNI_OK) {
                    return;
                }
            }
        }
        if (env == nullptr) {
            return;
        }
        if (reg_methods[id] == nullptr) {
            return;
        }
        reg_methods[id](env, clazz);
    }

    void prepare_lib(JNIEnv *env) {
        utils::init_utils(env);
        if (env->ExceptionCheck())
            return;

$anti_debug_init

        char* string_pool = string_pool::get_pool();

$register_code

        if (env->ExceptionCheck())
            return;

        char method_name[] = "registerNativesForClass";
        char method_desc[] = "(ILjava/lang/Class;)V";
        JNINativeMethod loader_methods[] = {
            { (char *) method_name, (char *) method_desc, (void *)&register_for_class }
        };
        env->RegisterNatives(env->FindClass("$native_dir/Loader"), loader_methods, 1);
    }
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = nullptr;
    vm->GetEnv((void **)&env, JNI_VERSION_1_8);
    native_jvm::cached_vm = vm;
    native_jvm::prepare_lib(env);
    return JNI_VERSION_1_8;
}
