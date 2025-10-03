#include "native_jvm.hpp"
#include <algorithm>

namespace native_jvm::utils {

    jclass boolean_array_class;
    jmethodID string_intern_method;
    jclass class_class;
    jmethodID get_classloader_method;
    jclass object_class;
    jmethodID get_class_method;
    jclass classloader_class;
    jmethodID load_class_method;
    jclass no_class_def_found_class;
    jmethodID ncdf_init_method;
    jclass throwable_class;
    jmethodID get_message_method;
    jmethodID init_cause_method;
    jclass methodhandles_lookup_class;
    jmethodID lookup_init_method;
#ifdef USE_HOTSPOT
    jclass methodhandle_natives_class;
    jmethodID link_call_site_method;
    bool is_jvm11_link_call_site;
#endif

    void init_utils(JNIEnv *env) {
        jclass clazz = env->FindClass("[Z");
        if (env->ExceptionCheck())
            return;
        boolean_array_class = (jclass) env->NewGlobalRef(clazz);
        env->DeleteLocalRef(clazz);

        jclass string_clazz = env->FindClass("java/lang/String");
        if (env->ExceptionCheck())
            return;
        string_intern_method = env->GetMethodID(string_clazz, "intern", "()Ljava/lang/String;");
        if (env->ExceptionCheck())
            return;
        env->DeleteLocalRef(string_clazz);

        jclass _class_class = env->FindClass("java/lang/Class");
        if (env->ExceptionCheck())
            return;
        class_class = (jclass) env->NewGlobalRef(_class_class);
        env->DeleteLocalRef(_class_class);

        get_classloader_method = env->GetMethodID(class_class, "getClassLoader", "()Ljava/lang/ClassLoader;");
        if (env->ExceptionCheck())
            return;

        jclass _object_class = env->FindClass("java/lang/Object");
        if (env->ExceptionCheck())
            return;
        object_class = (jclass) env->NewGlobalRef(_object_class);
        env->DeleteLocalRef(_object_class);

        get_class_method = env->GetMethodID(object_class, "getClass", "()Ljava/lang/Class;");
        if (env->ExceptionCheck())
            return;

        jclass _classloader_class = env->FindClass("java/lang/ClassLoader");
        if (env->ExceptionCheck())
            return;
        classloader_class = (jclass) env->NewGlobalRef(_classloader_class);
        env->DeleteLocalRef(_classloader_class);

        load_class_method = env->GetMethodID(classloader_class, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        if (env->ExceptionCheck())
            return;

        jclass _no_class_def_found_class = env->FindClass("java/lang/NoClassDefFoundError");
        if (env->ExceptionCheck())
            return;
        no_class_def_found_class = (jclass) env->NewGlobalRef(_no_class_def_found_class);
        env->DeleteLocalRef(_no_class_def_found_class);

        ncdf_init_method = env->GetMethodID(no_class_def_found_class, "<init>", "(Ljava/lang/String;)V");
        if (env->ExceptionCheck())
            return;

        jclass _throwable_class = env->FindClass("java/lang/Throwable");
        if (env->ExceptionCheck())
            return;
        throwable_class = (jclass) env->NewGlobalRef(_throwable_class);
        env->DeleteLocalRef(_throwable_class);

        get_message_method = env->GetMethodID(throwable_class, "getMessage", "()Ljava/lang/String;");
        if (env->ExceptionCheck())
            return;

        init_cause_method = env->GetMethodID(throwable_class, "initCause",
                                            "(Ljava/lang/Throwable;)Ljava/lang/Throwable;");
        if (env->ExceptionCheck())
            return;

        jclass _methodhandles_lookup_class = env->FindClass("java/lang/invoke/MethodHandles$Lookup");
        if (env->ExceptionCheck())
            return;
        methodhandles_lookup_class = (jclass) env->NewGlobalRef(_methodhandles_lookup_class);
        env->DeleteLocalRef(_methodhandles_lookup_class);

        lookup_init_method = env->GetMethodID(methodhandles_lookup_class, "<init>", "(Ljava/lang/Class;)V");
        if (env->ExceptionCheck())
            return;

#ifdef USE_HOTSPOT
        jclass _methodhandle_natives_class = env->FindClass("java/lang/invoke/MethodHandleNatives");
        if (env->ExceptionCheck())
            return;
        methodhandle_natives_class = (jclass) env->NewGlobalRef(_methodhandle_natives_class);
        env->DeleteLocalRef(_methodhandle_natives_class);

        link_call_site_method = env->GetStaticMethodID(methodhandle_natives_class, "linkCallSite",
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/invoke/MemberName;");
        is_jvm11_link_call_site = false;
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            link_call_site_method = env->GetStaticMethodID(methodhandle_natives_class, "linkCallSite",
                "(Ljava/lang/Object;ILjava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/invoke/MemberName;");
            is_jvm11_link_call_site = true;
            if (env->ExceptionCheck())
                return;
        }
#endif
    }

#ifdef USE_HOTSPOT
    jobject link_call_site(JNIEnv *env, jobject caller_obj, jobject bootstrap_method_obj,
        jobject name_obj, jobject type_obj, jobject static_arguments, jobject appendix_result) {
        if (is_jvm11_link_call_site) {
            return env->CallStaticObjectMethod(methodhandle_natives_class, link_call_site_method, caller_obj, 0,
                bootstrap_method_obj, name_obj, type_obj, static_arguments, appendix_result);
        }
        return env->CallStaticObjectMethod(methodhandle_natives_class, link_call_site_method, caller_obj,
            bootstrap_method_obj, name_obj, type_obj, static_arguments, appendix_result);
    }
#endif

    template <>
    jarray create_array_value<1>(JNIEnv *env, jint size) {
        return env->NewBooleanArray(size);
    }

    template <>
    jarray create_array_value<2>(JNIEnv *env, jint size) {
        return env->NewCharArray(size);
    }

    template <>
    jarray create_array_value<3>(JNIEnv *env, jint size) {
        return env->NewByteArray(size);
    }

    template <>
    jarray create_array_value<4>(JNIEnv *env, jint size) {
        return env->NewShortArray(size);
    }

    template <>
    jarray create_array_value<5>(JNIEnv *env, jint size) {
        return env->NewIntArray(size);
    }

    template <>
    jarray create_array_value<6>(JNIEnv *env, jint size) {
        return env->NewFloatArray(size);
    }

    template <>
    jarray create_array_value<7>(JNIEnv *env, jint size) {
        return env->NewLongArray(size);
    }

    template <>
    jarray create_array_value<8>(JNIEnv *env, jint size) {
        return env->NewDoubleArray(size);
    }

    jobjectArray create_multidim_array(JNIEnv *env, jobject classloader, jint count, jint required_count,
        const char *class_name, int line, std::initializer_list<jint> sizes, int dim_index) {
        if (required_count == 0) {
            env->FatalError("required_count == 0");
            return nullptr;
        }
        jint current_size = sizes.begin()[dim_index];
        if (current_size < 0) {
            throw_re(env, "java/lang/NegativeArraySizeException", "MULTIANEWARRAY size < 0", line);
            return nullptr;
        }
        jobjectArray result_array = nullptr;
        if (count == 1) {
            std::string renamed_class_name(class_name);
            std::replace(renamed_class_name.begin(), renamed_class_name.end(), '/', '.');
            jstring renamed_class_name_string = env->NewStringUTF(renamed_class_name.c_str());
            jclass clazz = find_class_wo_static(env, classloader, renamed_class_name_string);
            env->DeleteLocalRef(renamed_class_name_string);
            if (env->ExceptionCheck()) {
                return nullptr;
            }
            result_array = env->NewObjectArray(current_size, clazz, nullptr);
            if (env->ExceptionCheck()) {
                return nullptr;
            }
            return result_array;
        }
        std::string clazz_name = std::string(count - 1, '[') + "L" + std::string(class_name) + ";";
        if (jclass clazz = env->FindClass(clazz_name.c_str())) {
            result_array = env->NewObjectArray(current_size, clazz, nullptr);
            if (env->ExceptionCheck()) {
                return nullptr;
            }
            env->DeleteLocalRef(clazz);
        } else {
            return nullptr;
        }

        if (required_count == 1) {
            return result_array;
        }

        for (jint i = 0; i < current_size; i++) {
            jobjectArray inner_array = create_multidim_array(env, classloader, count - 1, required_count - 1,
                class_name, line, sizes, dim_index + 1);
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

    jclass find_class_wo_static(JNIEnv *env, jobject classloader, jstring class_name_string) {
        jclass clazz = (jclass) env->CallObjectMethod(
            classloader,
            load_class_method,
            class_name_string
        );
        if (env->ExceptionCheck()) {
            jthrowable exception = env->ExceptionOccurred();
            env->ExceptionClear();
            jobject details = env->CallObjectMethod(
                exception,
                get_message_method
            );
            if (env->ExceptionCheck()) {
                env->DeleteLocalRef(exception);
                return nullptr;
            }
            jobject new_exception = env->NewObject(no_class_def_found_class,
                ncdf_init_method,
                details);
            if (env->ExceptionCheck()) {
                env->DeleteLocalRef(exception);
                env->DeleteLocalRef(details);
                return nullptr;
            }
            env->CallVoidMethod(new_exception, init_cause_method, exception);
            if (env->ExceptionCheck()) {
                env->DeleteLocalRef(new_exception);
                env->DeleteLocalRef(exception);
                env->DeleteLocalRef(details);
                return nullptr;
            }
            env->Throw((jthrowable) new_exception);
            env->DeleteLocalRef(exception);
            env->DeleteLocalRef(details);
            return nullptr;
        }
        return clazz;
    }

    void debug_print_stack_state(JNIEnv *env, const char *context, int object_index, int return_index, int line) {
        jclass system_class = env->FindClass("java/lang/System");
        jfieldID err_field = env->GetStaticFieldID(system_class, "err", "Ljava/io/PrintStream;");
        jobject err_stream = env->GetStaticObjectField(system_class, err_field);
        jclass print_stream_class = env->FindClass("java/io/PrintStream");
        jmethodID println_method = env->GetMethodID(print_stream_class, "println", "(Ljava/lang/String;)V");

        std::string debug_msg = std::string(context) + " - object_index: " + std::to_string(object_index) +
                               ", return_index: " + std::to_string(return_index) + ", line: " + std::to_string(line);
        jstring debug_str = env->NewStringUTF(debug_msg.c_str());
        env->CallVoidMethod(err_stream, println_method, debug_str);

        env->DeleteLocalRef(debug_str);
        env->DeleteLocalRef(err_stream);
        env->DeleteLocalRef(print_stream_class);
        env->DeleteLocalRef(system_class);
    }

    void debug_print_int(JNIEnv *env, const char *context, jint value, int line) {
        jclass system_class = env->FindClass("java/lang/System");
        jfieldID err_field = env->GetStaticFieldID(system_class, "err", "Ljava/io/PrintStream;");
        jobject err_stream = env->GetStaticObjectField(system_class, err_field);
        jclass print_stream_class = env->FindClass("java/io/PrintStream");
        jmethodID println_method = env->GetMethodID(print_stream_class, "println", "(Ljava/lang/String;)V");

        std::string debug_msg = std::string(context) + " = " + std::to_string(value) + ", line: " + std::to_string(line);
        jstring debug_str = env->NewStringUTF(debug_msg.c_str());
        env->CallVoidMethod(err_stream, println_method, debug_str);

        env->DeleteLocalRef(debug_str);
        env->DeleteLocalRef(err_stream);
        env->DeleteLocalRef(print_stream_class);
        env->DeleteLocalRef(system_class);
    }

    void throw_re(JNIEnv *env, const char *exception_class, const char *error, int line) {
        jclass exception_class_ptr = env->FindClass(exception_class);
        if (env->ExceptionCheck()) {
            return;
        }
        env->ThrowNew(exception_class_ptr, ("\"" + std::string(error) + "\" on " + std::to_string(line)).c_str());
        env->DeleteLocalRef(exception_class_ptr);
    }

    void bastore(JNIEnv *env, jarray array, jint index, jint value) {
        if (env->IsInstanceOf(array, boolean_array_class))
            env->SetBooleanArrayRegion((jbooleanArray) array, index, 1, (jboolean*) (&value));
        else
            env->SetByteArrayRegion((jbyteArray) array, index, 1, (jbyte*) (&value));
    }

    jbyte baload(JNIEnv *env, jarray array, jint index) {
        jbyte ret_value;
        if (env->IsInstanceOf(array, boolean_array_class))
            env->GetBooleanArrayRegion((jbooleanArray) array, index, 1, (jboolean*) (&ret_value));
        else
            env->GetByteArrayRegion((jbyteArray) array, index, 1, (jbyte*) (&ret_value));
        return ret_value;
    }

    PrimitiveArrayCache::PrimitiveArrayCache(JNIEnv *env) : env(env) {}

    PrimitiveArrayCache::~PrimitiveArrayCache() {
        release_all();
    }

    PrimitiveArrayCache::Entry *PrimitiveArrayCache::ensure_entry(jarray array, Kind kind) {
        for (auto &entry : entries) {
            if (entry.array == array) {
                return &entry;
            }
        }

        if (array == nullptr) {
            return nullptr;
        }

        Entry entry{};
        entry.array = array;
        entry.kind = kind;
        entry.dirty = false;
        entry.isBoolean = false;
        entry.length = env->GetArrayLength(array);
        if (env->ExceptionCheck()) {
            return nullptr;
        }

        switch (kind) {
            case Kind::BooleanOrByte: {
                if (env->IsInstanceOf(array, boolean_array_class)) {
                    entry.isBoolean = true;
                    entry.elements = env->GetBooleanArrayElements((jbooleanArray) array, nullptr);
                } else {
                    entry.elements = env->GetByteArrayElements((jbyteArray) array, nullptr);
                }
                break;
            }
            case Kind::Char: {
                entry.elements = env->GetCharArrayElements((jcharArray) array, nullptr);
                break;
            }
            case Kind::Short: {
                entry.elements = env->GetShortArrayElements((jshortArray) array, nullptr);
                break;
            }
            case Kind::Int: {
                entry.elements = env->GetIntArrayElements((jintArray) array, nullptr);
                break;
            }
            case Kind::Long: {
                entry.elements = env->GetLongArrayElements((jlongArray) array, nullptr);
                break;
            }
            case Kind::Float: {
                entry.elements = env->GetFloatArrayElements((jfloatArray) array, nullptr);
                break;
            }
            case Kind::Double: {
                entry.elements = env->GetDoubleArrayElements((jdoubleArray) array, nullptr);
                break;
            }
        }

        if (env->ExceptionCheck() || entry.elements == nullptr) {
            return nullptr;
        }

        entries.push_back(entry);
        return &entries.back();
    }

    bool PrimitiveArrayCache::check_index(const Entry &entry, jint index, int line, const char *opcode) {
        if (index < 0 || index >= entry.length) {
            std::string message = std::string(opcode) + " index out of range";
            throw_re(env, "java/lang/ArrayIndexOutOfBoundsException", message.c_str(), line);
            return false;
        }
        return true;
    }

    void PrimitiveArrayCache::release_all() {
        for (auto &entry : entries) {
            switch (entry.kind) {
                case Kind::BooleanOrByte: {
                    if (entry.isBoolean) {
                        env->ReleaseBooleanArrayElements((jbooleanArray) entry.array,
                                (jboolean *) entry.elements, entry.dirty ? 0 : JNI_ABORT);
                    } else {
                        env->ReleaseByteArrayElements((jbyteArray) entry.array,
                                (jbyte *) entry.elements, entry.dirty ? 0 : JNI_ABORT);
                    }
                    break;
                }
                case Kind::Char: {
                    env->ReleaseCharArrayElements((jcharArray) entry.array,
                            (jchar *) entry.elements, entry.dirty ? 0 : JNI_ABORT);
                    break;
                }
                case Kind::Short: {
                    env->ReleaseShortArrayElements((jshortArray) entry.array,
                            (jshort *) entry.elements, entry.dirty ? 0 : JNI_ABORT);
                    break;
                }
                case Kind::Int: {
                    env->ReleaseIntArrayElements((jintArray) entry.array,
                            (jint *) entry.elements, entry.dirty ? 0 : JNI_ABORT);
                    break;
                }
                case Kind::Long: {
                    env->ReleaseLongArrayElements((jlongArray) entry.array,
                            (jlong *) entry.elements, entry.dirty ? 0 : JNI_ABORT);
                    break;
                }
                case Kind::Float: {
                    env->ReleaseFloatArrayElements((jfloatArray) entry.array,
                            (jfloat *) entry.elements, entry.dirty ? 0 : JNI_ABORT);
                    break;
                }
                case Kind::Double: {
                    env->ReleaseDoubleArrayElements((jdoubleArray) entry.array,
                            (jdouble *) entry.elements, entry.dirty ? 0 : JNI_ABORT);
                    break;
                }
            }
        }
        entries.clear();
    }

    bool PrimitiveArrayCache::load_boolean_or_byte(jarray array, jint index, jint &out, int line, const char *opcode) {
        Entry *entry = ensure_entry(array, Kind::BooleanOrByte);
        if (entry == nullptr) {
            return false;
        }
        if (!check_index(*entry, index, line, opcode)) {
            return false;
        }
        if (entry->isBoolean) {
            out = static_cast<jint>(static_cast<jboolean *>(entry->elements)[index] != 0);
        } else {
            out = static_cast<jint>(static_cast<jbyte *>(entry->elements)[index]);
        }
        return true;
    }

    bool PrimitiveArrayCache::store_boolean_or_byte(jarray array, jint index, jint value, int line, const char *opcode) {
        Entry *entry = ensure_entry(array, Kind::BooleanOrByte);
        if (entry == nullptr) {
            return false;
        }
        if (!check_index(*entry, index, line, opcode)) {
            return false;
        }
        entry->dirty = true;
        if (entry->isBoolean) {
            static_cast<jboolean *>(entry->elements)[index] = value == 0 ? JNI_FALSE : JNI_TRUE;
        } else {
            static_cast<jbyte *>(entry->elements)[index] = static_cast<jbyte>(value);
        }
        return true;
    }

    bool PrimitiveArrayCache::load_char(jcharArray array, jint index, jint &out, int line, const char *opcode) {
        Entry *entry = ensure_entry(array, Kind::Char);
        if (entry == nullptr) {
            return false;
        }
        if (!check_index(*entry, index, line, opcode)) {
            return false;
        }
        out = static_cast<jint>(static_cast<jchar *>(entry->elements)[index]);
        return true;
    }

    bool PrimitiveArrayCache::store_char(jcharArray array, jint index, jint value, int line, const char *opcode) {
        Entry *entry = ensure_entry(array, Kind::Char);
        if (entry == nullptr) {
            return false;
        }
        if (!check_index(*entry, index, line, opcode)) {
            return false;
        }
        entry->dirty = true;
        static_cast<jchar *>(entry->elements)[index] = static_cast<jchar>(value);
        return true;
    }

    bool PrimitiveArrayCache::load_short(jshortArray array, jint index, jint &out, int line, const char *opcode) {
        Entry *entry = ensure_entry(array, Kind::Short);
        if (entry == nullptr) {
            return false;
        }
        if (!check_index(*entry, index, line, opcode)) {
            return false;
        }
        out = static_cast<jint>(static_cast<jshort *>(entry->elements)[index]);
        return true;
    }

    bool PrimitiveArrayCache::store_short(jshortArray array, jint index, jint value, int line, const char *opcode) {
        Entry *entry = ensure_entry(array, Kind::Short);
        if (entry == nullptr) {
            return false;
        }
        if (!check_index(*entry, index, line, opcode)) {
            return false;
        }
        entry->dirty = true;
        static_cast<jshort *>(entry->elements)[index] = static_cast<jshort>(value);
        return true;
    }

    bool PrimitiveArrayCache::load_int(jintArray array, jint index, jint &out, int line, const char *opcode) {
        Entry *entry = ensure_entry(array, Kind::Int);
        if (entry == nullptr) {
            return false;
        }
        if (!check_index(*entry, index, line, opcode)) {
            return false;
        }
        out = static_cast<jint *>(entry->elements)[index];
        return true;
    }

    bool PrimitiveArrayCache::store_int(jintArray array, jint index, jint value, int line, const char *opcode) {
        Entry *entry = ensure_entry(array, Kind::Int);
        if (entry == nullptr) {
            return false;
        }
        if (!check_index(*entry, index, line, opcode)) {
            return false;
        }
        entry->dirty = true;
        static_cast<jint *>(entry->elements)[index] = value;
        return true;
    }

    bool PrimitiveArrayCache::load_long(jlongArray array, jint index, jlong &out, int line, const char *opcode) {
        Entry *entry = ensure_entry(array, Kind::Long);
        if (entry == nullptr) {
            return false;
        }
        if (!check_index(*entry, index, line, opcode)) {
            return false;
        }
        out = static_cast<jlong *>(entry->elements)[index];
        return true;
    }

    bool PrimitiveArrayCache::store_long(jlongArray array, jint index, jlong value, int line, const char *opcode) {
        Entry *entry = ensure_entry(array, Kind::Long);
        if (entry == nullptr) {
            return false;
        }
        if (!check_index(*entry, index, line, opcode)) {
            return false;
        }
        entry->dirty = true;
        static_cast<jlong *>(entry->elements)[index] = value;
        return true;
    }

    bool PrimitiveArrayCache::load_float(jfloatArray array, jint index, jfloat &out, int line, const char *opcode) {
        Entry *entry = ensure_entry(array, Kind::Float);
        if (entry == nullptr) {
            return false;
        }
        if (!check_index(*entry, index, line, opcode)) {
            return false;
        }
        out = static_cast<jfloat *>(entry->elements)[index];
        return true;
    }

    bool PrimitiveArrayCache::store_float(jfloatArray array, jint index, jfloat value, int line, const char *opcode) {
        Entry *entry = ensure_entry(array, Kind::Float);
        if (entry == nullptr) {
            return false;
        }
        if (!check_index(*entry, index, line, opcode)) {
            return false;
        }
        entry->dirty = true;
        static_cast<jfloat *>(entry->elements)[index] = value;
        return true;
    }

    bool PrimitiveArrayCache::load_double(jdoubleArray array, jint index, jdouble &out, int line, const char *opcode) {
        Entry *entry = ensure_entry(array, Kind::Double);
        if (entry == nullptr) {
            return false;
        }
        if (!check_index(*entry, index, line, opcode)) {
            return false;
        }
        out = static_cast<jdouble *>(entry->elements)[index];
        return true;
    }

    bool PrimitiveArrayCache::store_double(jdoubleArray array, jint index, jdouble value, int line, const char *opcode) {
        Entry *entry = ensure_entry(array, Kind::Double);
        if (entry == nullptr) {
            return false;
        }
        if (!check_index(*entry, index, line, opcode)) {
            return false;
        }
        entry->dirty = true;
        static_cast<jdouble *>(entry->elements)[index] = value;
        return true;
    }

    ObjectArrayCache::ObjectArrayCache(JNIEnv *env) : env(env) {}

    ObjectArrayCache::ParentEntry *ObjectArrayCache::find_parent(jobjectArray array) {
        for (auto &entry : parents) {
            if (entry.array == array) {
                return &entry;
            }
        }
        return nullptr;
    }

    ObjectArrayCache::ParentEntry *ObjectArrayCache::ensure_parent(jobjectArray array) {
        if (ParentEntry *existing = find_parent(array)) {
            return existing;
        }

        ParentEntry new_entry{};
        new_entry.array = array;
        new_entry.length = env->GetArrayLength(array);
        if (env->ExceptionCheck()) {
            return nullptr;
        }
        new_entry.lastIndex = 0;
        new_entry.lastValue = nullptr;
        new_entry.hasLast = false;
        parents.push_back(new_entry);
        return &parents.back();
    }

    bool ObjectArrayCache::check_index(const ParentEntry &entry, jint index, int line, const char *opcode) {
        if (index < 0 || index >= entry.length) {
            std::string message = std::string(opcode) + " index out of range";
            throw_re(env, "java/lang/ArrayIndexOutOfBoundsException", message.c_str(), line);
            return false;
        }
        return true;
    }

    bool ObjectArrayCache::load(jobjectArray array, jint index, jobject &out, int line, const char *opcode) {
        if (array == nullptr) {
            return false;
        }

        if (index < 0) {
            std::string message = std::string(opcode) + " index out of range";
            throw_re(env, "java/lang/ArrayIndexOutOfBoundsException", message.c_str(), line);
            return false;
        }

        ParentEntry *parent = ensure_parent(array);
        if (parent == nullptr) {
            return false;
        }

        if (!check_index(*parent, index, line, opcode)) {
            return false;
        }

        if (parent->hasLast && parent->lastIndex == index) {
            out = parent->lastValue;
            return true;
        }

        auto it = parent->values.find(index);
        if (it != parent->values.end()) {
            parent->lastIndex = index;
            parent->lastValue = it->second;
            parent->hasLast = true;
            out = it->second;
            return true;
        }

        jobject value = env->GetObjectArrayElement(array, index);
        if (env->ExceptionCheck()) {
            return false;
        }

        parent->values.emplace(index, value);
        parent->lastIndex = index;
        parent->lastValue = value;
        parent->hasLast = true;
        out = value;
        return true;
    }

    bool ObjectArrayCache::store(jobjectArray array, jint index, jobject value, int line, const char *opcode) {
        if (array == nullptr) {
            return false;
        }

        if (index < 0) {
            std::string message = std::string(opcode) + " index out of range";
            throw_re(env, "java/lang/ArrayIndexOutOfBoundsException", message.c_str(), line);
            return false;
        }

        ParentEntry *parent = ensure_parent(array);
        if (parent == nullptr) {
            return false;
        }

        if (!check_index(*parent, index, line, opcode)) {
            return false;
        }

        env->SetObjectArrayElement(array, index, value);
        if (env->ExceptionCheck()) {
            return false;
        }

        parent->values[index] = value;
        parent->lastIndex = index;
        parent->lastValue = value;
        parent->hasLast = true;

        return true;
    }

    jclass get_class_from_object(JNIEnv *env, jobject object) {
        jobject result_class = env->CallObjectMethod(object, get_class_method);
        if (env->ExceptionCheck()) {
            return nullptr;
        }
        return (jclass) result_class;
    }

    jobject get_classloader_from_class(JNIEnv *env, jclass clazz) {
        if (clazz == nullptr) {
            env->FatalError("clazz == null in get_classloader_from_class");
            return nullptr;
        }
        jobject result_classloader = env->CallObjectMethod(clazz, get_classloader_method);
        if (env->ExceptionCheck()) {
            return nullptr;
        }
        return result_classloader;
    }

    jobject get_lookup(JNIEnv *env, jclass clazz) {
        jobject lookup = env->NewObject(methodhandles_lookup_class, lookup_init_method, clazz);
        if (env->ExceptionCheck()) {
            return nullptr;
        }
        return lookup;
    }

    void clear_refs(JNIEnv *env, LocalRefSet &refs) {
        // Avoid eagerly deleting local refs that might still be referenced via
        // cstack slots. Let the JVM clear locals at the end of the native call.
        // This prevents accidental invalidation of receiver/argument objects
        // used by subsequent INVOKEVIRTUAL calls in the generated state machine.
        (void)env;
        refs.clear();
    }

    jstring get_interned(JNIEnv *env, jstring value) {
        jstring result = (jstring) env->CallObjectMethod(value, string_intern_method);
        if (env->ExceptionCheck())
            return nullptr;
        return result;
    }

    void ensure_initialized(JNIEnv *env, jobject classloader, const char *class_name_dot) {
        // Use Class.forName(name, true, loader) to trigger class initialization.
        jclass class_class = env->FindClass("java/lang/Class");
        if (env->ExceptionCheck()) return;
        jmethodID for_name = env->GetStaticMethodID(class_class, "forName",
                                                    "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
        if (env->ExceptionCheck()) { env->DeleteLocalRef(class_class); return; }
        jstring name_str = env->NewStringUTF(class_name_dot);
        if (env->ExceptionCheck()) { env->DeleteLocalRef(class_class); return; }
        jobject ignore = env->CallStaticObjectMethod(class_class, for_name, name_str, JNI_TRUE, classloader);
        (void)ignore; // ignore result
        env->DeleteLocalRef(name_str);
        env->DeleteLocalRef(class_class);
        if (env->ExceptionCheck()) return;
    }

    void ensure_initialized(JNIEnv *env, jobject classloader, jstring class_name_dot) {
        jclass class_class = env->FindClass("java/lang/Class");
        if (env->ExceptionCheck()) return;
        jmethodID for_name = env->GetStaticMethodID(class_class, "forName",
                                                    "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
        if (env->ExceptionCheck()) { env->DeleteLocalRef(class_class); return; }
        jobject ignore = env->CallStaticObjectMethod(class_class, for_name, class_name_dot, JNI_TRUE, classloader);
        (void)ignore;
        env->DeleteLocalRef(class_class);
        if (env->ExceptionCheck()) return;
    }
}
