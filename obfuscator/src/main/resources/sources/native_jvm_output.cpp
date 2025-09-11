#include "native_jvm.hpp"
#include "native_jvm_output.hpp"
#include "string_pool.hpp"

$includes

namespace native_jvm {

    typedef void (* reg_method)(JNIEnv *,jclass);

    reg_method reg_methods[$class_count];

    void register_for_class(JNIEnv *env, jclass, jint id, jclass clazz) {
        reg_methods[id](env, clazz);
    }

    void prepare_lib(JNIEnv *env) {
        utils::init_utils(env);
        if (env->ExceptionCheck())
            return;

$register_code

        if (env->ExceptionCheck())
            return;

        char method_name[] = "registerNativesForClass";
        char method_desc[] = "(ILjava/lang/Class;)V";
        JNINativeMethod loader_methods[] = {
            { (char *) method_name, (char *) method_desc, (void *)&register_for_class }
        };

        jclass class_loader_class = env->FindClass("java/lang/ClassLoader");
        if (env->ExceptionCheck())
            return;
        jmethodID get_system_loader = env->GetStaticMethodID(class_loader_class, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
        if (env->ExceptionCheck())
            return;
        jobject system_loader = env->CallStaticObjectMethod(class_loader_class, get_system_loader);
        if (env->ExceptionCheck())
            return;
        jmethodID load_class = env->GetMethodID(class_loader_class, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        if (env->ExceptionCheck())
            return;
        jstring loader_name = env->NewStringUTF("$native_dir.Loader");
        jclass loader_class = (jclass) env->CallObjectMethod(system_loader, load_class, loader_name);
        env->DeleteLocalRef(loader_name);
        env->DeleteLocalRef(system_loader);
        env->DeleteLocalRef(class_loader_class);
        if (env->ExceptionCheck())
            return;
        env->RegisterNatives(loader_class, loader_methods, 1);
        env->DeleteLocalRef(loader_class);
    }
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = nullptr;
    vm->GetEnv((void **)&env, JNI_VERSION_1_8);
    native_jvm::prepare_lib(env);
    return JNI_VERSION_1_8;
}
