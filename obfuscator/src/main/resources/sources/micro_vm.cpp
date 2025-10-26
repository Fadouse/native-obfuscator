#include "micro_vm.hpp"
#include "vm_jit.hpp"
#include "native_jvm.hpp"
#include <iostream>
#include <random>
#include <algorithm>
#include <array>
#include <vector>
#include <unordered_map>
#include <string>
#include <cstring>
#include <cmath>
#include <cstdio>

// NOLINTBEGIN - obfuscated control flow by design
namespace native_jvm::vm {

static thread_local uint64_t KEY = 0;
static thread_local std::array<uint8_t, OP_COUNT> op_map{};     // first mapping layer
static thread_local std::array<uint8_t, OP_COUNT> op_map2{};    // second mapping layer
static thread_local std::array<uint8_t, OP_COUNT> inv_op_map2{}; // reverse second layer
static thread_local std::array<OpCode, OP_COUNT> inv_op_map{};  // reverse first layer
static thread_local bool vm_state_initialized = false;
static thread_local std::unordered_map<const Instruction*, JitCompiled> jit_cache{};
static thread_local std::unordered_map<const Instruction*, size_t> exec_counts{};
static constexpr size_t HOT_THRESHOLD = 10;
static constexpr uint64_t OPERAND_XOR_CONST = 0x9E3779B97F4A7C15ULL;

struct ArithKey {
    OpCode op;
    uint64_t seed;

    bool operator==(const ArithKey& other) const noexcept {
        return op == other.op && seed == other.seed;
    }
};

struct ArithKeyHash {
    size_t operator()(const ArithKey& key) const noexcept {
        size_t seed_hash = static_cast<size_t>(key.seed) ^ static_cast<size_t>(key.seed >> 32);
        return (static_cast<size_t>(key.op) * 1315423911u) ^ seed_hash;
    }
};

struct OperandSlot {
    size_t index = 0;
    uint64_t mix = 0;
    uint64_t nonce = 0;
    uint8_t encoded_op = 0;
};

struct CachedArithProgram {
    std::vector<Instruction> program;
    OperandSlot lhs_slot{};
    OperandSlot rhs_slot{};
    bool has_lhs = false;
    bool has_rhs = false;
    bool use_variant = false;
};

struct CachedUnaryProgram {
    std::vector<Instruction> program;
    OperandSlot value_slot{};
    bool has_slot = false;
};

static thread_local std::unordered_map<ArithKey, CachedArithProgram, ArithKeyHash> arith_program_cache{};
static thread_local std::unordered_map<ArithKey, CachedUnaryProgram, ArithKeyHash> unary_program_cache{};
static void clear_jit_state() {
    for (auto &entry : jit_cache) {
        if (entry.second.func != nullptr) {
            free(entry.second);
        }
    }
    jit_cache.clear();
    exec_counts.clear();
}

static void clear_cached_programs() {
    arith_program_cache.clear();
    unary_program_cache.clear();
}

struct ParsedMethodSignature {
    std::vector<char> arg_types;
    char return_type = 'V';
    bool parsed = false;
};

static thread_local std::unordered_map<const char*, ParsedMethodSignature> signature_cache{};
static thread_local std::vector<jvalue> jarg_buffer{};

static thread_local std::unordered_map<std::string, jweak> class_cache{};
static thread_local size_t class_lookup_calls = 0;

struct CachedMethodEntry {
    jclass clazz = nullptr;
    jmethodID method = nullptr;
};

struct CachedFieldEntry {
    jclass clazz = nullptr;
    jfieldID field = nullptr;
};

static thread_local std::unordered_map<const MethodRef*, CachedMethodEntry> static_method_cache{};
static thread_local std::unordered_map<const MethodRef*, CachedMethodEntry> instance_method_cache{};
static thread_local std::unordered_map<const FieldRef*, CachedFieldEntry> static_field_cache{};
static thread_local std::unordered_map<const FieldRef*, CachedFieldEntry> instance_field_cache{};

enum class PrimitiveArrayKind {
    BOOLEAN,
    BYTE,
    CHAR,
    SHORT,
    INT,
    LONG,
    FLOAT,
    DOUBLE
};

struct PrimitiveArrayCacheEntry {
    jarray array = nullptr;
    void* elements = nullptr;
    jsize length = 0;
    bool modified = false;
    PrimitiveArrayKind kind = PrimitiveArrayKind::INT;
};

struct PrimitiveArrayCacheHash {
    size_t operator()(jarray array) const noexcept {
        return std::hash<void*>{}(array);
    }
};

class PrimitiveArrayCache {
public:
    explicit PrimitiveArrayCache(JNIEnv* env) : env(env) {}
    PrimitiveArrayCache(const PrimitiveArrayCache&) = delete;
    PrimitiveArrayCache& operator=(const PrimitiveArrayCache&) = delete;

    ~PrimitiveArrayCache() {
        release_all();
    }

    template <typename ArrayType, typename ElementType, PrimitiveArrayKind Kind>
    ElementType* get(ArrayType array, bool write, PrimitiveArrayCacheEntry** out_entry) {
        if (array == nullptr) {
            return nullptr;
        }
        auto key = reinterpret_cast<jarray>(array);
        auto [it, inserted] = entries.try_emplace(key);
        PrimitiveArrayCacheEntry& entry = it->second;
        if (inserted) {
            entry.array = key;
            entry.length = env->GetArrayLength(array);
            entry.elements = env->GetPrimitiveArrayCritical(array, nullptr);
            entry.modified = write;
            entry.kind = Kind;
            if (entry.elements == nullptr) {
                entries.erase(it);
                return nullptr;
            }
        } else {
            if (entry.kind != Kind) {
                return nullptr;
            }
            if (write) {
                entry.modified = true;
            }
        }
        if (out_entry != nullptr) {
            *out_entry = &entry;
        }
        return static_cast<ElementType*>(entry.elements);
    }

    void release_all() {
        for (auto& kv : entries) {
            auto& entry = kv.second;
            if (entry.elements != nullptr) {
                env->ReleasePrimitiveArrayCritical(entry.array, entry.elements, entry.modified ? 0 : JNI_ABORT);
                entry.elements = nullptr;
            }
        }
        entries.clear();
    }

private:
    JNIEnv* env;
    std::unordered_map<jarray, PrimitiveArrayCacheEntry, PrimitiveArrayCacheHash> entries{};
};

static void throw_null_array(JNIEnv* env) {
    jclass npe = env->FindClass("java/lang/NullPointerException");
    if (npe != nullptr) {
        env->ThrowNew(npe, "null");
        env->DeleteLocalRef(npe);
    }
}

static void throw_array_index_oob(JNIEnv* env, jsize index, jsize length) {
    jclass oob = env->FindClass("java/lang/ArrayIndexOutOfBoundsException");
    if (oob != nullptr) {
        char buffer[96];
        std::snprintf(buffer, sizeof(buffer), "Index %d out of bounds for length %d", index, length);
        env->ThrowNew(oob, buffer);
        env->DeleteLocalRef(oob);
    }
}

struct ObjectArrayCacheKey {
    jobjectArray array = nullptr;
    jsize index = 0;

    bool operator==(const ObjectArrayCacheKey& other) const noexcept {
        return array == other.array && index == other.index;
    }
};

struct ObjectArrayCacheKeyHash {
    size_t operator()(const ObjectArrayCacheKey& key) const noexcept {
        size_t base = std::hash<void*>{}(key.array);
        return base ^ (static_cast<size_t>(key.index) << 1);
    }
};

class ObjectArrayCache {
public:
    explicit ObjectArrayCache(JNIEnv* env) : env(env) {}
    ObjectArrayCache(const ObjectArrayCache&) = delete;
    ObjectArrayCache& operator=(const ObjectArrayCache&) = delete;

    ~ObjectArrayCache() {
        clear();
    }

    jobject get(jobjectArray array, jsize index) {
        if (array == nullptr) {
            throw_null_array(env);
            return nullptr;
        }
        ObjectArrayCacheKey key{array, index};
        auto it = cache.find(key);
        if (it != cache.end()) {
            return it->second;
        }
        jsize length = env->GetArrayLength(array);
        if (index < 0 || index >= length) {
            throw_array_index_oob(env, index, length);
            return nullptr;
        }
        jobject local = env->GetObjectArrayElement(array, index);
        if (local == nullptr) {
            return nullptr;
        }
        jobject global = env->NewGlobalRef(local);
        env->DeleteLocalRef(local);
        if (global == nullptr) {
            return nullptr;
        }
        cache.emplace(key, global);
        return global;
    }

    void invalidate(jobjectArray array, jsize index) {
        ObjectArrayCacheKey key{array, index};
        auto it = cache.find(key);
        if (it != cache.end()) {
            env->DeleteGlobalRef(it->second);
            cache.erase(it);
        }
    }

private:
    void clear() {
        for (auto& entry : cache) {
            env->DeleteGlobalRef(entry.second);
        }
        cache.clear();
    }

    JNIEnv* env;
    std::unordered_map<ObjectArrayCacheKey, jobject, ObjectArrayCacheKeyHash> cache{};
};

static jclass get_cached_class(JNIEnv* env, const char* name) {
    auto it = class_cache.find(name);
    if (it != class_cache.end()) {
        jclass clazz = reinterpret_cast<jclass>(env->NewLocalRef(it->second));
        if (clazz) {
            return clazz;
        }
        env->DeleteWeakGlobalRef(it->second);
        class_cache.erase(it);
    }
    jclass clazz = env->FindClass(name);
    ++class_lookup_calls;
    if (clazz) {
        jweak weak = env->NewWeakGlobalRef(clazz);
        class_cache.emplace(name, weak);
    }
    return clazz;
}

void clear_class_cache(JNIEnv* env) {
    for (auto& kv : class_cache) {
        env->DeleteWeakGlobalRef(kv.second);
    }
    class_cache.clear();
    class_lookup_calls = 0;

    auto release_method_cache = [env](auto& cache) {
        for (auto& entry : cache) {
            if (entry.second.clazz) {
                env->DeleteGlobalRef(entry.second.clazz);
            }
        }
        cache.clear();
    };
    release_method_cache(static_method_cache);
    release_method_cache(instance_method_cache);

    auto release_field_cache = [env](auto& cache) {
        for (auto& entry : cache) {
            if (entry.second.clazz) {
                env->DeleteGlobalRef(entry.second.clazz);
            }
        }
        cache.clear();
    };
    release_field_cache(static_field_cache);
    release_field_cache(instance_field_cache);
}

size_t get_class_cache_calls() {
    return class_lookup_calls;
}

static CachedMethodEntry* resolve_method(JNIEnv* env, const MethodRef* ref, bool is_static) {
    if (!ref) {
        return nullptr;
    }

    auto& cache = is_static ? static_method_cache : instance_method_cache;
    auto it = cache.find(ref);
    if (it != cache.end() && it->second.method != nullptr && it->second.clazz != nullptr) {
        return &it->second;
    }

    jclass clazz = get_cached_class(env, ref->class_name);
    if (!clazz) {
        return nullptr;
    }

    jmethodID resolved = is_static
            ? env->GetStaticMethodID(clazz, ref->method_name, ref->method_sig)
            : env->GetMethodID(clazz, ref->method_name, ref->method_sig);
    if (!resolved) {
        env->DeleteLocalRef(clazz);
        return nullptr;
    }

    jclass global_clazz = reinterpret_cast<jclass>(env->NewGlobalRef(clazz));
    env->DeleteLocalRef(clazz);
    if (!global_clazz) {
        return nullptr;
    }

    auto [iter, inserted] = cache.emplace(ref, CachedMethodEntry{global_clazz, resolved});
    if (!inserted) {
        if (iter->second.clazz && iter->second.clazz != global_clazz) {
            env->DeleteGlobalRef(iter->second.clazz);
        }
        iter->second.clazz = global_clazz;
        iter->second.method = resolved;
    }
    return &iter->second;
}

static CachedFieldEntry* resolve_field(JNIEnv* env, const FieldRef* ref, bool is_static) {
    if (!ref) {
        return nullptr;
    }

    auto& cache = is_static ? static_field_cache : instance_field_cache;
    auto it = cache.find(ref);
    if (it != cache.end() && it->second.field != nullptr && (!is_static || it->second.clazz != nullptr)) {
        return &it->second;
    }

    jclass clazz = get_cached_class(env, ref->class_name);
    if (!clazz) {
        return nullptr;
    }

    jfieldID resolved = is_static
            ? env->GetStaticFieldID(clazz, ref->field_name, ref->field_sig)
            : env->GetFieldID(clazz, ref->field_name, ref->field_sig);
    if (!resolved) {
        env->DeleteLocalRef(clazz);
        return nullptr;
    }

    jclass global_clazz = nullptr;
    if (is_static) {
        global_clazz = reinterpret_cast<jclass>(env->NewGlobalRef(clazz));
        if (!global_clazz) {
            env->DeleteLocalRef(clazz);
            return nullptr;
        }
    }
    env->DeleteLocalRef(clazz);

    auto [iter, inserted] = cache.emplace(ref, CachedFieldEntry{global_clazz, resolved});
    if (!inserted) {
        if (iter->second.clazz && iter->second.clazz != global_clazz) {
            env->DeleteGlobalRef(iter->second.clazz);
        }
        iter->second.clazz = global_clazz;
        iter->second.field = resolved;
    }
    return &iter->second;
}

static void parse_method_sig(const char* sig, std::vector<char>& args, char& ret) {
    args.clear();
    const char* p = sig;
    if (*p == '(') ++p;
    while (*p && *p != ')') {
        char c = *p++;
        if (c == 'L') {
            while (*p && *p != ';') ++p;
            if (*p == ';') ++p;
            args.push_back('L');
        } else if (c == '[') {
            while (*p == '[') ++p;
            if (*p == 'L') {
                while (*p && *p != ';') ++p;
                if (*p == ';') ++p;
            } else {
                ++p; // primitive array
            }
            args.push_back('L');
        } else {
            args.push_back(c);
        }
    }
    if (*p == ')') ++p;
    ret = *p;
}

static void invoke_method(JNIEnv* env, OpCode op, MethodRef* ref,
                          int64_t* stack, size_t& sp) {
    if (!ref) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "Null method reference");
        return;
    }
    if (!ref->class_name || !ref->method_name || !ref->method_sig) {
        char error_msg[256];
        snprintf(error_msg, sizeof(error_msg), "Invalid method reference: class=%p name=%p sig=%p",
                 ref->class_name, ref->method_name, ref->method_sig);
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), error_msg);
        return;
    }
    const char* sig_key = ref->method_sig;
    ParsedMethodSignature& parsed_sig = signature_cache[sig_key];
    if (!parsed_sig.parsed) {
        parse_method_sig(ref->method_sig, parsed_sig.arg_types, parsed_sig.return_type);
        parsed_sig.parsed = true;
    }
    const auto& arg_types = parsed_sig.arg_types;
    char ret = parsed_sig.return_type;
    size_t num = arg_types.size();
    if (sp < num + ((op == OP_INVOKESTATIC || op == OP_INVOKEDYNAMIC) ? 0 : 1)) {
        sp = 0;
        return;
    }
    if (jarg_buffer.size() < num) {
        jarg_buffer.resize(num);
    }
    jvalue* args_ptr = num ? jarg_buffer.data() : nullptr;
    for (size_t i = 0; i < num; ++i) {
        char t = arg_types[num - 1 - i];
        switch (t) {
            case 'Z': case 'B': case 'C': case 'S': case 'I':
                jarg_buffer[num - 1 - i].i = static_cast<jint>(stack[--sp]);
                break;
            case 'J':
                jarg_buffer[num - 1 - i].j = static_cast<jlong>(stack[--sp]);
                break;
            case 'F': {
                int32_t bits = static_cast<int32_t>(stack[--sp]);
                jfloat v;
                std::memcpy(&v, &bits, sizeof(float));
                jarg_buffer[num - 1 - i].f = v;
                break;
            }
            case 'D': {
                int64_t bits = stack[--sp];
                jdouble v;
                std::memcpy(&v, &bits, sizeof(double));
                jarg_buffer[num - 1 - i].d = v;
                break;
            }
            default:
                jarg_buffer[num - 1 - i].l = reinterpret_cast<jobject>(stack[--sp]);
                break;
        }
    }
    jobject obj = nullptr;
    if (op != OP_INVOKESTATIC && op != OP_INVOKEDYNAMIC) {
        obj = reinterpret_cast<jobject>(stack[--sp]);
        if (!obj) {
            env->ThrowNew(env->FindClass("java/lang/NullPointerException"), "null");
            return;
        }
    }
    bool is_static = (op == OP_INVOKESTATIC || op == OP_INVOKEDYNAMIC);
    CachedMethodEntry* cached_method = resolve_method(env, ref, is_static);
    if (!cached_method) {
        return;
    }
    jclass clazz = cached_method->clazz;
    jmethodID mid = cached_method->method;
    // Save VM decode state to survive nested obfuscated calls that reinitialize it
    struct VmStateSnapshot {
        uint64_t KEY;
        std::array<uint8_t, OP_COUNT> op_map;
        std::array<uint8_t, OP_COUNT> op_map2;
        std::array<uint8_t, OP_COUNT> inv_op_map2;
        std::array<OpCode, OP_COUNT> inv_op_map;
        bool vm_state_initialized;
    } snapshot{};
    snapshot.KEY = KEY;
    snapshot.op_map = op_map;
    snapshot.op_map2 = op_map2;
    snapshot.inv_op_map2 = inv_op_map2;
    snapshot.inv_op_map = inv_op_map;
    snapshot.vm_state_initialized = vm_state_initialized;

    switch (ret) {
        case 'V':
            if (op == OP_INVOKESTATIC || op == OP_INVOKEDYNAMIC)
                env->CallStaticVoidMethodA(clazz, mid, args_ptr);
            else if (op == OP_INVOKESPECIAL)
                env->CallNonvirtualVoidMethodA(obj, clazz, mid, args_ptr);
            else
                env->CallVoidMethodA(obj, mid, args_ptr);
            break;
        case 'Z': case 'B': case 'C': case 'S': case 'I': {
            jint r;
            if (op == OP_INVOKESTATIC || op == OP_INVOKEDYNAMIC)
                r = env->CallStaticIntMethodA(clazz, mid, args_ptr);
            else if (op == OP_INVOKESPECIAL)
                r = env->CallNonvirtualIntMethodA(obj, clazz, mid, args_ptr);
            else
                r = env->CallIntMethodA(obj, mid, args_ptr);
            stack[sp++] = static_cast<int64_t>(r);
            break;
        }
        case 'J': {
            jlong r;
            if (op == OP_INVOKESTATIC || op == OP_INVOKEDYNAMIC)
                r = env->CallStaticLongMethodA(clazz, mid, args_ptr);
            else if (op == OP_INVOKESPECIAL)
                r = env->CallNonvirtualLongMethodA(obj, clazz, mid, args_ptr);
            else
                r = env->CallLongMethodA(obj, mid, args_ptr);
            stack[sp++] = static_cast<int64_t>(r);
            break;
        }
        case 'F': {
            jfloat r;
            if (op == OP_INVOKESTATIC || op == OP_INVOKEDYNAMIC)
                r = env->CallStaticFloatMethodA(clazz, mid, args_ptr);
            else if (op == OP_INVOKESPECIAL)
                r = env->CallNonvirtualFloatMethodA(obj, clazz, mid, args_ptr);
            else
                r = env->CallFloatMethodA(obj, mid, args_ptr);
            int32_t bits;
            std::memcpy(&bits, &r, sizeof(float));
            stack[sp++] = static_cast<int64_t>(bits);
            break;
        }
        case 'D': {
            jdouble r;
            if (op == OP_INVOKESTATIC || op == OP_INVOKEDYNAMIC)
                r = env->CallStaticDoubleMethodA(clazz, mid, args_ptr);
            else if (op == OP_INVOKESPECIAL)
                r = env->CallNonvirtualDoubleMethodA(obj, clazz, mid, args_ptr);
            else
                r = env->CallDoubleMethodA(obj, mid, args_ptr);
            int64_t bits;
            std::memcpy(&bits, &r, sizeof(double));
            stack[sp++] = bits;
            break;
        }
        default: {
            jobject r;
            if (op == OP_INVOKESTATIC || op == OP_INVOKEDYNAMIC)
                r = env->CallStaticObjectMethodA(clazz, mid, args_ptr);
            else if (op == OP_INVOKESPECIAL)
                r = env->CallNonvirtualObjectMethodA(obj, clazz, mid, args_ptr);
            else
                r = env->CallObjectMethodA(obj, mid, args_ptr);
            stack[sp++] = reinterpret_cast<int64_t>(r);
            break;
        }
    }

    // Restore VM decode state after potential nested obfuscated calls
    KEY = snapshot.KEY;
    op_map = snapshot.op_map;
    op_map2 = snapshot.op_map2;
    inv_op_map2 = snapshot.inv_op_map2;
    inv_op_map = snapshot.inv_op_map;
    vm_state_initialized = snapshot.vm_state_initialized;
}

void init_key(uint64_t seed) {
    std::random_device rd;
    std::mt19937_64 gen(rd() ^ seed);
    KEY = gen();

    clear_jit_state();
    clear_cached_programs();

    std::array<uint8_t, OP_COUNT> values{};
    for (uint8_t i = 0; i < OP_COUNT; ++i) values[i] = i;
    std::shuffle(values.begin(), values.end(), gen);
    for (uint8_t i = 0; i < OP_COUNT; ++i) {
        op_map[i] = values[i];
        inv_op_map[values[i]] = static_cast<OpCode>(i);
    }

    std::array<uint8_t, OP_COUNT> values2{};
    for (uint8_t i = 0; i < OP_COUNT; ++i) values2[i] = i;
    std::shuffle(values2.begin(), values2.end(), gen);
    for (uint8_t i = 0; i < OP_COUNT; ++i) {
        op_map2[i] = values2[i];
        inv_op_map2[values2[i]] = i;
    }
    vm_state_initialized = true;
}

void ensure_init(uint64_t seed) {
    if (!vm_state_initialized) {
        init_key(seed);
    }
}

void decode_for_jit(const Instruction* code, size_t length, uint64_t seed,
                    std::vector<DecodedInstruction>& out) {
    ensure_init(seed);
    out.clear();
    out.reserve(length);
    uint64_t state = KEY ^ seed;
    for (size_t pc = 0; pc < length; ++pc) {
        state = (state + KEY) ^ (KEY >> 3);
        OpCode op;
        int64_t operand;

        if (code[pc].nonce == 0) {
            // Plain instructions (not encrypted) - used by generated VM code
            op = static_cast<OpCode>(code[pc].op);
            operand = code[pc].operand;
        } else {
            // Encrypted instructions - normal path
            uint64_t mix = state ^ code[pc].nonce;
            uint8_t mapped = static_cast<uint8_t>(code[pc].op ^ static_cast<uint8_t>(mix));
            mapped ^= static_cast<uint8_t>(code[pc].nonce);
            mapped = inv_op_map2[mapped];
            op = inv_op_map[mapped];
            operand = code[pc].operand ^ static_cast<int64_t>(mix * OPERAND_XOR_CONST);
        }
        out.push_back({op, operand});
    }
}

Instruction encode(OpCode op, int64_t operand, uint64_t key, uint64_t nonce) {
    uint8_t mapped = op_map[static_cast<uint8_t>(op)];
    mapped = op_map2[mapped];
    mapped ^= static_cast<uint8_t>(nonce);
    uint64_t mix = key ^ nonce;
    return Instruction{
        static_cast<uint8_t>(mapped ^ static_cast<uint8_t>(mix)),
        operand ^ static_cast<int64_t>(mix * OPERAND_XOR_CONST),
        nonce
    };
}

int64_t execute(JNIEnv* env, const Instruction* code, size_t length,
                int64_t* locals, size_t locals_length, uint64_t seed,
                const ConstantPoolEntry* constant_pool, size_t constant_pool_size,
                const MethodRef* method_refs, size_t method_refs_size,
                const FieldRef* field_refs, size_t field_refs_size,
                const MultiArrayInfo* multi_refs, size_t multi_refs_size,
                const TableSwitch* table_refs, size_t table_refs_size,
                const LookupSwitch* lookup_refs, size_t lookup_refs_size) {
    int64_t stack[256];
    size_t sp = 0;
    size_t pc = 0;
    int64_t tmp = 0;
    uint64_t state = KEY ^ seed;
    OpCode op = OP_NOP;
    uint64_t mask = 0;
    PrimitiveArrayCache array_cache(env);
    ObjectArrayCache object_cache(env);

    goto dispatch; // start of the threaded interpreter

// Main dispatch loop
dispatch:
    state = (state + KEY) ^ (KEY >> 3); // evolve state
    if (pc >= length) goto halt;
    // XOR promotes to int; cast back to uint8_t before converting to OpCode
    {
        if (code[pc].nonce == 0) {
            // Plain instructions (not encrypted) - used by generated VM code
            op = static_cast<OpCode>(code[pc].op);
            tmp = code[pc].operand;
        } else {
            // Encrypted instructions - normal path
            uint64_t mix = state ^ code[pc].nonce;
            uint8_t mapped = static_cast<uint8_t>(code[pc].op ^ static_cast<uint8_t>(mix));
            mapped ^= static_cast<uint8_t>(code[pc].nonce);
            mapped = inv_op_map2[mapped];
            op = inv_op_map[mapped];
            tmp = code[pc].operand ^ static_cast<int64_t>(mix * OPERAND_XOR_CONST);
        }
    }
    ++pc;
    static thread_local uint64_t chaos = 0;
    mask = state ^ KEY ^ chaos;
    if ((mask & 1ULL) == 0) {
        chaos ^= mask + pc;
        op_map[0] ^= static_cast<uint8_t>(chaos);
        op_map[0] ^= static_cast<uint8_t>(chaos); // undo to keep semantics
    } else {
        chaos += mask ^ pc;
    }
    switch (op) {
        case OP_PUSH:  goto do_push;
        case OP_ADD:   goto do_add;
        case OP_SUB:   goto do_sub;
        case OP_MUL:   goto do_mul;
        case OP_DIV:   goto do_div;
        case OP_PRINT: goto do_print;
        case OP_HALT:  goto halt;
        case OP_NOP:   goto junk;   // never executed by valid programs
        case OP_JUNK1: goto do_junk1;
        case OP_JUNK2: goto do_junk2;
        case OP_SWAP:  goto do_swap;
        case OP_DUP:   goto do_dup;
        case OP_POP:   goto do_pop;
        case OP_POP2:  goto do_pop2;
        case OP_LOAD:  goto do_load;
        case OP_LLOAD:
        case OP_FLOAD:
        case OP_DLOAD: goto do_load;
        case OP_STORE: goto do_store;
        case OP_LSTORE:
        case OP_FSTORE:
        case OP_DSTORE: goto do_store;
        case OP_IF_ICMPEQ: goto do_if_icmpeq;
        case OP_IF_ICMPNE: goto do_if_icmpne;
        case OP_IFNULL: goto do_ifnull;
        case OP_IFNONNULL: goto do_ifnonnull;
        case OP_IF_ACMPEQ: goto do_if_acmpeq;
        case OP_IF_ACMPNE: goto do_if_acmpne;
        case OP_TABLESWITCH: goto do_tableswitch;
        case OP_LOOKUPSWITCH: goto do_lookupswitch;
        case OP_GOTO:  goto do_goto;
        case OP_GOTO_W: goto do_goto;
        case OP_IFNULL_W: goto do_ifnull;
        case OP_IFNONNULL_W: goto do_ifnonnull;
        case OP_IF_ACMPEQ_W: goto do_if_acmpeq;
        case OP_IF_ACMPNE_W: goto do_if_acmpne;
        case OP_IF_ICMPEQ_W: goto do_if_icmpeq;
        case OP_IF_ICMPNE_W: goto do_if_icmpne;
        case OP_IF_ICMPLT_W: goto do_if_icmplt;
        case OP_IF_ICMPLE_W: goto do_if_icmple;
        case OP_IF_ICMPGT_W: goto do_if_icmpgt;
        case OP_IF_ICMPGE_W: goto do_if_icmpge;
        case OP_AND:  goto do_and;
        case OP_OR:   goto do_or;
        case OP_XOR:  goto do_xor;
        case OP_SHL:  goto do_shl;
        case OP_SHR:  goto do_shr;
        case OP_USHR: goto do_ushr;
        case OP_IF_ICMPLT: goto do_if_icmplt;
        case OP_IF_ICMPLE: goto do_if_icmple;
        case OP_IF_ICMPGT: goto do_if_icmpgt;
        case OP_IF_ICMPGE: goto do_if_icmpge;
        case OP_I2L: goto do_i2l;
        case OP_I2B: goto do_i2b;
        case OP_I2C: goto do_i2c;
        case OP_I2S: goto do_i2s;
        case OP_I2F: goto do_i2f;
        case OP_I2D: goto do_i2d;
        case OP_L2I: goto do_l2i;
        case OP_L2F: goto do_l2f;
        case OP_L2D: goto do_l2d;
        case OP_F2I: goto do_f2i;
        case OP_F2L: goto do_f2l;
        case OP_F2D: goto do_f2d;
        case OP_D2I: goto do_d2i;
        case OP_D2L: goto do_d2l;
        case OP_D2F: goto do_d2f;
        case OP_NEG: goto do_neg;
        case OP_ALOAD: goto do_aload;
        case OP_ASTORE: goto do_astore;
        case OP_AALOAD: goto do_aaload;
        case OP_AASTORE: goto do_aastore;
        case OP_IALOAD: goto do_iaload;
        case OP_LALOAD: goto do_laload;
        case OP_FALOAD: goto do_faload;
        case OP_DALOAD: goto do_daload;
        case OP_BALOAD: goto do_baload;
        case OP_CALOAD: goto do_caload;
        case OP_SALOAD: goto do_saload;
        case OP_IASTORE: goto do_iastore;
        case OP_LASTORE: goto do_lastore;
        case OP_FASTORE: goto do_fastore;
        case OP_DASTORE: goto do_dastore;
        case OP_BASTORE: goto do_bastore;
        case OP_CASTORE: goto do_castore;
        case OP_SASTORE: goto do_sastore;
        case OP_NEW: goto do_new;
        case OP_ANEWARRAY: goto do_anewarray;
        case OP_NEWARRAY: goto do_newarray;
        case OP_MULTIANEWARRAY: goto do_multianewarray;
        case OP_CHECKCAST: goto do_checkcast;
        case OP_INSTANCEOF: goto do_instanceof;
        case OP_GETSTATIC: goto do_getstatic;
        case OP_PUTSTATIC: goto do_putstatic;
        case OP_GETFIELD: goto do_getfield;
        case OP_PUTFIELD: goto do_putfield;
        case OP_LADD: goto do_add;
        case OP_LSUB: goto do_sub;
        case OP_LMUL: goto do_mul;
        case OP_LDIV: goto do_div;
        case OP_FADD: goto do_fadd;
        case OP_FSUB: goto do_fsub;
        case OP_FMUL: goto do_fmul;
        case OP_FDIV: goto do_fdiv;
        case OP_DADD: goto do_dadd;
        case OP_DSUB: goto do_dsub;
        case OP_DMUL: goto do_dmul;
        case OP_DDIV: goto do_ddiv;
        case OP_LDC: goto do_ldc;
        case OP_LDC_W: goto do_ldc;
        case OP_LDC2_W: goto do_ldc2_w;
        case OP_FCONST_0: goto do_fconst_0;
        case OP_FCONST_1: goto do_fconst_1;
        case OP_FCONST_2: goto do_fconst_2;
        case OP_DCONST_0: goto do_dconst_0;
        case OP_DCONST_1: goto do_dconst_1;
        case OP_LCONST_0: goto do_lconst_0;
        case OP_LCONST_1: goto do_lconst_1;
        case OP_IINC: goto do_iinc;
        case OP_LAND: goto do_and;
        case OP_LOR: goto do_or;
        case OP_LXOR: goto do_xor;
        case OP_LSHL: goto do_shl;
        case OP_LSHR: goto do_shr;
        case OP_LUSHR: goto do_ushr;
        case OP_INVOKESTATIC: goto do_invokestatic;
        case OP_INVOKEVIRTUAL: goto do_invokevirtual;
        case OP_INVOKESPECIAL: goto do_invokespecial;
        case OP_INVOKEINTERFACE: goto do_invokeinterface;
        case OP_INVOKEDYNAMIC: goto do_invokedynamic;
        case OP_DUP_X1: goto do_dup_x1;
        case OP_DUP_X2: goto do_dup_x2;
        case OP_DUP2: goto do_dup2;
        case OP_DUP2_X1: goto do_dup2_x1;
        case OP_DUP2_X2: goto do_dup2_x2;
        case OP_ATHROW: goto do_athrow;
        case OP_TRY_START: goto do_try_start;
        case OP_CATCH_HANDLER: goto do_catch_handler;
        case OP_FINALLY_HANDLER: goto do_finally_handler;
        case OP_EXCEPTION_CHECK: goto do_exception_check;
        case OP_EXCEPTION_CLEAR: goto do_exception_clear;
        case OP_IREM: goto do_irem;
        case OP_LREM: goto do_lrem;
        case OP_FREM: goto do_frem;
        case OP_DREM: goto do_drem;
        case OP_LNEG: goto do_lneg;
        case OP_FNEG: goto do_fneg;
        case OP_DNEG: goto do_dneg;
        case OP_LCMP: goto do_lcmp;
        case OP_FCMPL: goto do_fcmpl;
        case OP_FCMPG: goto do_fcmpg;
        case OP_DCMPL: goto do_dcmpl;
        case OP_DCMPG: goto do_dcmpg;
        default:       goto halt;
    }

// Actual operations
// Each block returns to dispatch via an explicit goto to hide
// structured control-flow patterns from static analysis.
do_push:
    if (sp < 256) stack[sp++] = tmp;
    goto dispatch;

do_fconst_0:
    if (sp < 256) stack[sp++] = 0;
    goto dispatch;

do_fconst_1:
    if (sp < 256) {
        float v = 1.0f;
        int32_t bits;
        std::memcpy(&bits, &v, sizeof(float));
        stack[sp++] = static_cast<int64_t>(bits);
    }
    goto dispatch;

do_fconst_2:
    if (sp < 256) {
        float v = 2.0f;
        int32_t bits;
        std::memcpy(&bits, &v, sizeof(float));
        stack[sp++] = static_cast<int64_t>(bits);
    }
    goto dispatch;

do_dconst_0:
    if (sp < 256) stack[sp++] = 0;
    goto dispatch;

do_dconst_1:
    if (sp < 256) {
        double v = 1.0;
        int64_t bits;
        std::memcpy(&bits, &v, sizeof(double));
        stack[sp++] = bits;
    }
    goto dispatch;

do_lconst_0:
    if (sp < 256) stack[sp++] = 0;
    goto dispatch;

do_lconst_1:
    if (sp < 256) stack[sp++] = 1;
    goto dispatch;

do_add:
    if (sp >= 2) { stack[sp - 2] += stack[sp - 1]; --sp; }
    goto dispatch;

do_sub:
    if (sp >= 2) { stack[sp - 2] -= stack[sp - 1]; --sp; }
    goto dispatch;

do_mul:
    if (sp >= 2) { stack[sp - 2] *= stack[sp - 1]; --sp; }
    goto dispatch;

do_div:
    if (sp >= 2) {
        int64_t b = stack[sp - 1];
        if (b == 0) {
            env->ThrowNew(env->FindClass("java/lang/ArithmeticException"), "/ by zero");
            goto halt;
        }
        stack[sp - 2] /= b;
        --sp;
    }
    goto dispatch;

do_fadd:
    if (sp >= 2) {
        float a, b, r;
        int32_t ba = static_cast<int32_t>(stack[sp - 2]);
        int32_t bb = static_cast<int32_t>(stack[sp - 1]);
        std::memcpy(&a, &ba, sizeof(float));
        std::memcpy(&b, &bb, sizeof(float));
        r = a + b;
        std::memcpy(&ba, &r, sizeof(float));
        stack[sp - 2] = static_cast<int64_t>(ba);
        --sp;
    }
    goto dispatch;

do_fsub:
    if (sp >= 2) {
        float a, b, r;
        int32_t ba = static_cast<int32_t>(stack[sp - 2]);
        int32_t bb = static_cast<int32_t>(stack[sp - 1]);
        std::memcpy(&a, &ba, sizeof(float));
        std::memcpy(&b, &bb, sizeof(float));
        r = a - b;
        std::memcpy(&ba, &r, sizeof(float));
        stack[sp - 2] = static_cast<int64_t>(ba);
        --sp;
    }
    goto dispatch;

do_fmul:
    if (sp >= 2) {
        float a, b, r;
        int32_t ba = static_cast<int32_t>(stack[sp - 2]);
        int32_t bb = static_cast<int32_t>(stack[sp - 1]);
        std::memcpy(&a, &ba, sizeof(float));
        std::memcpy(&b, &bb, sizeof(float));
        r = a * b;
        std::memcpy(&ba, &r, sizeof(float));
        stack[sp - 2] = static_cast<int64_t>(ba);
        --sp;
    }
    goto dispatch;

do_fdiv:
    if (sp >= 2) {
        float a, b, r;
        int32_t ba = static_cast<int32_t>(stack[sp - 2]);
        int32_t bb = static_cast<int32_t>(stack[sp - 1]);
        std::memcpy(&a, &ba, sizeof(float));
        std::memcpy(&b, &bb, sizeof(float));
        r = a / b;
        std::memcpy(&ba, &r, sizeof(float));
        stack[sp - 2] = static_cast<int64_t>(ba);
        --sp;
    }
    goto dispatch;

do_dadd:
    if (sp >= 2) {
        double a, b, r;
        int64_t ba = stack[sp - 2];
        int64_t bb = stack[sp - 1];
        std::memcpy(&a, &ba, sizeof(double));
        std::memcpy(&b, &bb, sizeof(double));
        r = a + b;
        std::memcpy(&ba, &r, sizeof(double));
        stack[sp - 2] = ba;
        --sp;
    }
    goto dispatch;

do_dsub:
    if (sp >= 2) {
        double a, b, r;
        int64_t ba = stack[sp - 2];
        int64_t bb = stack[sp - 1];
        std::memcpy(&a, &ba, sizeof(double));
        std::memcpy(&b, &bb, sizeof(double));
        r = a - b;
        std::memcpy(&ba, &r, sizeof(double));
        stack[sp - 2] = ba;
        --sp;
    }
    goto dispatch;

do_dmul:
    if (sp >= 2) {
        double a, b, r;
        int64_t ba = stack[sp - 2];
        int64_t bb = stack[sp - 1];
        std::memcpy(&a, &ba, sizeof(double));
        std::memcpy(&b, &bb, sizeof(double));
        r = a * b;
        std::memcpy(&ba, &r, sizeof(double));
        stack[sp - 2] = ba;
        --sp;
    }
    goto dispatch;

do_ddiv:
    if (sp >= 2) {
        double a, b, r;
        int64_t ba = stack[sp - 2];
        int64_t bb = stack[sp - 1];
        std::memcpy(&a, &ba, sizeof(double));
        std::memcpy(&b, &bb, sizeof(double));
        r = a / b;
        std::memcpy(&ba, &r, sizeof(double));
        stack[sp - 2] = ba;
        --sp;
    }
    goto dispatch;

do_print:
    if (sp >= 1) {
        std::cout << stack[sp - 1] << std::endl;
        --sp;
    }
    goto dispatch;

do_junk1:
    tmp ^= (KEY << 5); // operate on temp only
    goto dispatch;

do_junk2:
    tmp ^= state >> 7; // operate on temp only
    goto dispatch;

do_swap:
    if (sp >= 2) std::swap(stack[sp - 1], stack[sp - 2]);
    goto dispatch;

do_dup:
    if (sp >= 1 && sp < 256) stack[sp++] = stack[sp - 1];
    goto dispatch;

do_pop:
    // Pop single value from stack
    if (sp >= 1) --sp;
    goto dispatch;

do_pop2:
    // Pop top one or two values from stack
    // If top value is long/double (category 2), pop one slot
    // Otherwise pop two slots (two category 1 values)
    if (sp >= 1) {
        --sp;
        if (sp >= 1) --sp; // Always pop second slot for simplicity in micro VM
    }
    goto dispatch;

do_dup_x1:
    // Duplicate top value and insert below second value
    // Stack: ..., value2, value1 -> ..., value1, value2, value1
    if (sp >= 2 && sp < 256) {
        int64_t value1 = stack[sp - 1];
        int64_t value2 = stack[sp - 2];
        stack[sp - 2] = value1;
        stack[sp - 1] = value2;
        stack[sp++] = value1;
    }
    goto dispatch;

do_dup_x2:
    // Duplicate top value and insert below third value
    // Stack: ..., value3, value2, value1 -> ..., value1, value3, value2, value1
    if (sp >= 3 && sp < 256) {
        int64_t value1 = stack[sp - 1];
        int64_t value2 = stack[sp - 2];
        int64_t value3 = stack[sp - 3];
        stack[sp - 3] = value1;
        stack[sp - 2] = value3;
        stack[sp - 1] = value2;
        stack[sp++] = value1;
    }
    goto dispatch;

do_dup2:
    // Duplicate top two values
    // Stack: ..., value2, value1 -> ..., value2, value1, value2, value1
    if (sp >= 2 && sp + 1 < 256) {
        int64_t value1 = stack[sp - 1];
        int64_t value2 = stack[sp - 2];
        stack[sp++] = value2;
        stack[sp++] = value1;
    }
    goto dispatch;

do_dup2_x1:
    // Duplicate top two values and insert below third value
    // Stack: ..., value3, value2, value1 -> ..., value2, value1, value3, value2, value1
    if (sp >= 3 && sp + 1 < 256) {
        int64_t value1 = stack[sp - 1];
        int64_t value2 = stack[sp - 2];
        int64_t value3 = stack[sp - 3];
        stack[sp - 3] = value2;
        stack[sp - 2] = value1;
        stack[sp - 1] = value3;
        stack[sp++] = value2;
        stack[sp++] = value1;
    }
    goto dispatch;

do_dup2_x2:
    // Duplicate top two values and insert below fourth/fifth value
    // Stack: ..., value4, value3, value2, value1 -> ..., value2, value1, value4, value3, value2, value1
    if (sp >= 4 && sp + 1 < 256) {
        int64_t value1 = stack[sp - 1];
        int64_t value2 = stack[sp - 2];
        int64_t value3 = stack[sp - 3];
        int64_t value4 = stack[sp - 4];
        stack[sp - 4] = value2;
        stack[sp - 3] = value1;
        stack[sp - 2] = value4;
        stack[sp - 1] = value3;
        stack[sp++] = value2;
        stack[sp++] = value1;
    }
    goto dispatch;

do_athrow:
    // Throw exception - get exception object from stack top
    if (sp >= 1) {
        jobject exception = reinterpret_cast<jobject>(stack[sp - 1]);
        if (exception == nullptr) {
            // Null exception - throw NullPointerException
            if (env != nullptr) {
                jclass npeClass = env->FindClass("java/lang/NullPointerException");
                if (npeClass) {
                    env->ThrowNew(npeClass, "Cannot throw null exception");
                }
            }
        } else {
            // Throw the actual exception
            if (env != nullptr) {
                env->Throw(static_cast<jthrowable>(exception));
            }
        }
        --sp; // Pop exception object from stack
    }
    goto halt; // Exception throwing terminates execution

do_try_start:
    // Setup exception handling context
    // This would typically save current state for exception handling
    // For simplicity, we just continue execution
    goto dispatch;

do_catch_handler:
    // Exception catch handler - jump to catch block
    // The operand contains the catch block target
    if (tmp >= 0 && static_cast<size_t>(tmp) < 256) {
        pc = static_cast<size_t>(tmp);
    }
    goto dispatch;

do_finally_handler:
    // Finally block handler - always executed
    // The operand contains the finally block target
    if (tmp >= 0 && static_cast<size_t>(tmp) < 256) {
        pc = static_cast<size_t>(tmp);
    }
    goto dispatch;

do_exception_check:
    // Check if JNI exception occurred and handle it
    if (env != nullptr && env->ExceptionCheck()) {
        jthrowable exception = env->ExceptionOccurred();
        if (exception && sp < 256) {
            // Push exception onto stack
            stack[sp++] = reinterpret_cast<int64_t>(exception);
            env->ExceptionClear(); // Clear the JNI exception

            // Jump to exception handler (operand contains handler target)
            if (tmp >= 0 && static_cast<size_t>(tmp) < 256) {
                pc = static_cast<size_t>(tmp);
            }
        }
    }
    goto dispatch;

do_exception_clear:
    // Clear pending JNI exception
    if (env != nullptr && env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    goto dispatch;

do_irem:
    // Integer remainder (modulo)
    if (sp >= 2) {
        int64_t b = stack[--sp];
        int64_t a = stack[--sp];
        if (b != 0) {
            stack[sp++] = static_cast<int32_t>(a) % static_cast<int32_t>(b);
        } else {
            // Division by zero - Java would throw ArithmeticException
            stack[sp++] = 0; // Simplified handling
        }
    }
    goto dispatch;

do_lrem:
    // Long remainder (modulo)
    if (sp >= 2) {
        int64_t b = stack[--sp];
        int64_t a = stack[--sp];
        if (b != 0) {
            stack[sp++] = a % b;
        } else {
            // Division by zero - Java would throw ArithmeticException
            stack[sp++] = 0; // Simplified handling
        }
    }
    goto dispatch;

do_frem:
    // Float remainder (modulo)
    if (sp >= 2) {
        int64_t b_bits = stack[--sp];
        int64_t a_bits = stack[--sp];
        float b, a;
        std::memcpy(&b, &b_bits, sizeof(float));
        std::memcpy(&a, &a_bits, sizeof(float));
        float result = fmodf(a, b);
        int32_t result_bits;
        std::memcpy(&result_bits, &result, sizeof(float));
        stack[sp++] = static_cast<int64_t>(result_bits);
    }
    goto dispatch;

do_drem:
    // Double remainder (modulo)
    if (sp >= 2) {
        int64_t b_bits = stack[--sp];
        int64_t a_bits = stack[--sp];
        double b, a;
        std::memcpy(&b, &b_bits, sizeof(double));
        std::memcpy(&a, &a_bits, sizeof(double));
        double result = fmod(a, b);
        int64_t result_bits;
        std::memcpy(&result_bits, &result, sizeof(double));
        stack[sp++] = result_bits;
    }
    goto dispatch;

do_lneg:
    // Long negate
    if (sp >= 1) {
        int64_t a = stack[--sp];
        stack[sp++] = -a;
    }
    goto dispatch;

do_fneg:
    // Float negate
    if (sp >= 1) {
        int64_t a_bits = stack[--sp];
        float a;
        std::memcpy(&a, &a_bits, sizeof(float));
        float result = -a;
        int32_t result_bits;
        std::memcpy(&result_bits, &result, sizeof(float));
        stack[sp++] = static_cast<int64_t>(result_bits);
    }
    goto dispatch;

do_dneg:
    // Double negate
    if (sp >= 1) {
        int64_t a_bits = stack[--sp];
        double a;
        std::memcpy(&a, &a_bits, sizeof(double));
        double result = -a;
        int64_t result_bits;
        std::memcpy(&result_bits, &result, sizeof(double));
        stack[sp++] = result_bits;
    }
    goto dispatch;

do_lcmp:
    // Long compare: returns -1, 0, or 1
    if (sp >= 2) {
        int64_t b = stack[--sp];
        int64_t a = stack[--sp];
        if (a > b) stack[sp++] = 1;
        else if (a < b) stack[sp++] = -1;
        else stack[sp++] = 0;
    }
    goto dispatch;

do_fcmpl:
    // Float compare with NaN -> -1
    if (sp >= 2) {
        int64_t b_bits = stack[--sp];
        int64_t a_bits = stack[--sp];
        float b, a;
        std::memcpy(&b, &b_bits, sizeof(float));
        std::memcpy(&a, &a_bits, sizeof(float));
        if (std::isnan(a) || std::isnan(b)) {
            stack[sp++] = -1; // NaN handling for FCMPL
        } else if (a > b) {
            stack[sp++] = 1;
        } else if (a < b) {
            stack[sp++] = -1;
        } else {
            stack[sp++] = 0;
        }
    }
    goto dispatch;

do_fcmpg:
    // Float compare with NaN -> 1
    if (sp >= 2) {
        int64_t b_bits = stack[--sp];
        int64_t a_bits = stack[--sp];
        float b, a;
        std::memcpy(&b, &b_bits, sizeof(float));
        std::memcpy(&a, &a_bits, sizeof(float));
        if (std::isnan(a) || std::isnan(b)) {
            stack[sp++] = 1; // NaN handling for FCMPG
        } else if (a > b) {
            stack[sp++] = 1;
        } else if (a < b) {
            stack[sp++] = -1;
        } else {
            stack[sp++] = 0;
        }
    }
    goto dispatch;

do_dcmpl:
    // Double compare with NaN -> -1
    if (sp >= 2) {
        int64_t b_bits = stack[--sp];
        int64_t a_bits = stack[--sp];
        double b, a;
        std::memcpy(&b, &b_bits, sizeof(double));
        std::memcpy(&a, &a_bits, sizeof(double));
        if (std::isnan(a) || std::isnan(b)) {
            stack[sp++] = -1; // NaN handling for DCMPL
        } else if (a > b) {
            stack[sp++] = 1;
        } else if (a < b) {
            stack[sp++] = -1;
        } else {
            stack[sp++] = 0;
        }
    }
    goto dispatch;

do_dcmpg:
    // Double compare with NaN -> 1
    if (sp >= 2) {
        int64_t b_bits = stack[--sp];
        int64_t a_bits = stack[--sp];
        double b, a;
        std::memcpy(&b, &b_bits, sizeof(double));
        std::memcpy(&a, &a_bits, sizeof(double));
        if (std::isnan(a) || std::isnan(b)) {
            stack[sp++] = 1; // NaN handling for DCMPG
        } else if (a > b) {
            stack[sp++] = 1;
        } else if (a < b) {
            stack[sp++] = -1;
        } else {
            stack[sp++] = 0;
        }
    }
    goto dispatch;

do_load:
    if (sp < 256 && tmp >= 0 && static_cast<size_t>(tmp) < locals_length) {
        stack[sp++] = locals[tmp];
    }
    goto dispatch;

do_store:
    if (sp >= 1 && tmp >= 0 && static_cast<size_t>(tmp) < locals_length && locals != nullptr) {
        locals[tmp] = stack[sp - 1];
        --sp;
    }
    goto dispatch;

do_iinc:
    if (locals != nullptr) {
        uint32_t idx = static_cast<uint32_t>(tmp & 0xFFFFFFFFULL);
        int32_t inc = static_cast<int32_t>(tmp >> 32);
        if (idx < locals_length) {
            int32_t val = static_cast<int32_t>(locals[idx]);
            val += inc;
            locals[idx] = static_cast<int64_t>(val);
        }
    }
    goto dispatch;

do_if_icmpeq:
    if (sp >= 2) {
        int64_t b = stack[sp - 1];
        int64_t a = stack[sp - 2];
        sp -= 2;
        if (a == b) pc = static_cast<size_t>(tmp);
    }
    goto dispatch;

do_if_icmpne:
    if (sp >= 2) {
        int64_t b = stack[sp - 1];
        int64_t a = stack[sp - 2];
        sp -= 2;
        if (a != b) pc = static_cast<size_t>(tmp);
    }
    goto dispatch;

do_goto:
    pc = static_cast<size_t>(tmp);
    goto dispatch;

do_and:
    if (sp >= 2) { stack[sp - 2] &= stack[sp - 1]; --sp; }
    goto dispatch;

do_or:
    if (sp >= 2) { stack[sp - 2] |= stack[sp - 1]; --sp; }
    goto dispatch;

do_xor:
    if (sp >= 2) { stack[sp - 2] ^= stack[sp - 1]; --sp; }
    goto dispatch;

do_shl:
    if (sp >= 2) { stack[sp - 2] <<= stack[sp - 1]; --sp; }
    goto dispatch;

do_shr:
    if (sp >= 2) { stack[sp - 2] >>= stack[sp - 1]; --sp; }
    goto dispatch;

do_ushr:
    if (sp >= 2) { stack[sp - 2] = static_cast<int64_t>(static_cast<uint64_t>(stack[sp - 2]) >> stack[sp - 1]); --sp; }
    goto dispatch;

do_if_icmplt:
    if (sp >= 2) {
        int64_t b = stack[sp - 1];
        int64_t a = stack[sp - 2];
        sp -= 2;
        if (a < b) pc = static_cast<size_t>(tmp);
    }
    goto dispatch;

do_if_icmple:
    if (sp >= 2) {
        int64_t b = stack[sp - 1];
        int64_t a = stack[sp - 2];
        sp -= 2;
        if (a <= b) pc = static_cast<size_t>(tmp);
    }
    goto dispatch;

do_if_icmpgt:
    if (sp >= 2) {
        int64_t b = stack[sp - 1];
        int64_t a = stack[sp - 2];
        sp -= 2;
        if (a > b) pc = static_cast<size_t>(tmp);
    }
    goto dispatch;

do_if_icmpge:
    if (sp >= 2) {
        int64_t b = stack[sp - 1];
        int64_t a = stack[sp - 2];
        sp -= 2;
        if (a >= b) pc = static_cast<size_t>(tmp);
    }
    goto dispatch;

do_ifnull:
    if (sp >= 1) {
        int64_t a = stack[--sp];
        if (a == 0) pc = static_cast<size_t>(tmp);
    }
    goto dispatch;

do_ifnonnull:
    if (sp >= 1) {
        int64_t a = stack[--sp];
        if (a != 0) pc = static_cast<size_t>(tmp);
    }
    goto dispatch;

do_if_acmpeq:
    if (sp >= 2) {
        int64_t b = stack[sp - 1];
        int64_t a = stack[sp - 2];
        sp -= 2;
        if (a == b) pc = static_cast<size_t>(tmp);
    }
    goto dispatch;

do_if_acmpne:
    if (sp >= 2) {
        int64_t b = stack[sp - 1];
        int64_t a = stack[sp - 2];
        sp -= 2;
        if (a != b) pc = static_cast<size_t>(tmp);
    }
    goto dispatch;

do_tableswitch:
    if (sp >= 1) {
        auto* ts = &table_refs[tmp];
        int32_t idx = static_cast<int32_t>(stack[--sp]);
        if (idx < ts->low || idx > ts->high) {
            pc = ts->default_target;
        } else {
            pc = ts->targets[idx - ts->low];
        }
    }
    goto dispatch;

do_lookupswitch:
    if (sp >= 1) {
        auto* ls = &lookup_refs[tmp];
        int32_t key = static_cast<int32_t>(stack[--sp]);
        pc = ls->default_target;
        for (int32_t i = 0; i < ls->count; ++i) {
            if (ls->keys[i] == key) {
                pc = ls->targets[i];
                break;
            }
        }
    }
    goto dispatch;

do_i2l:
    if (sp >= 1) stack[sp - 1] = static_cast<int64_t>(static_cast<int32_t>(stack[sp - 1]));
    goto dispatch;

do_i2b:
    if (sp >= 1) stack[sp - 1] = static_cast<int64_t>(static_cast<int8_t>(stack[sp - 1]));
    goto dispatch;

do_i2c:
    if (sp >= 1) stack[sp - 1] = static_cast<int64_t>(static_cast<uint16_t>(stack[sp - 1]));
    goto dispatch;

do_i2s:
    if (sp >= 1) stack[sp - 1] = static_cast<int64_t>(static_cast<int16_t>(stack[sp - 1]));
    goto dispatch;

do_i2f:
    if (sp >= 1) {
        float f = static_cast<float>(static_cast<int32_t>(stack[sp - 1]));
        int32_t bits;
        std::memcpy(&bits, &f, sizeof(float));
        stack[sp - 1] = static_cast<int64_t>(bits);
    }
    goto dispatch;

do_i2d:
    if (sp >= 1) {
        double d = static_cast<double>(static_cast<int32_t>(stack[sp - 1]));
        int64_t bits;
        std::memcpy(&bits, &d, sizeof(double));
        stack[sp - 1] = bits;
    }
    goto dispatch;

do_l2i:
    if (sp >= 1) stack[sp - 1] = static_cast<int64_t>(static_cast<int32_t>(stack[sp - 1]));
    goto dispatch;

do_l2f:
    if (sp >= 1) {
        float f = static_cast<float>(stack[sp - 1]);
        int32_t bits;
        std::memcpy(&bits, &f, sizeof(float));
        stack[sp - 1] = static_cast<int64_t>(bits);
    }
    goto dispatch;

do_l2d:
    if (sp >= 1) {
        double d = static_cast<double>(stack[sp - 1]);
        int64_t bits;
        std::memcpy(&bits, &d, sizeof(double));
        stack[sp - 1] = bits;
    }
    goto dispatch;

do_f2i:
    if (sp >= 1) {
        float f;
        int32_t bits = static_cast<int32_t>(stack[sp - 1]);
        std::memcpy(&f, &bits, sizeof(float));
        stack[sp - 1] = static_cast<int64_t>(static_cast<int32_t>(f));
    }
    goto dispatch;

do_f2l:
    if (sp >= 1) {
        float f;
        int32_t bits = static_cast<int32_t>(stack[sp - 1]);
        std::memcpy(&f, &bits, sizeof(float));
        stack[sp - 1] = static_cast<int64_t>(static_cast<int64_t>(f));
    }
    goto dispatch;

do_f2d:
    if (sp >= 1) {
        float f;
        int32_t bits = static_cast<int32_t>(stack[sp - 1]);
        std::memcpy(&f, &bits, sizeof(float));
        double d = static_cast<double>(f);
        int64_t dbits;
        std::memcpy(&dbits, &d, sizeof(double));
        stack[sp - 1] = dbits;
    }
    goto dispatch;

do_d2i:
    if (sp >= 1) {
        double d;
        std::memcpy(&d, &stack[sp - 1], sizeof(double));
        stack[sp - 1] = static_cast<int64_t>(static_cast<int32_t>(d));
    }
    goto dispatch;

do_d2l:
    if (sp >= 1) {
        double d;
        std::memcpy(&d, &stack[sp - 1], sizeof(double));
        stack[sp - 1] = static_cast<int64_t>(static_cast<int64_t>(d));
    }
    goto dispatch;

do_d2f:
    if (sp >= 1) {
        double d;
        std::memcpy(&d, &stack[sp - 1], sizeof(double));
        float f = static_cast<float>(d);
        int32_t fbits;
        std::memcpy(&fbits, &f, sizeof(float));
        stack[sp - 1] = static_cast<int64_t>(fbits);
    }
    goto dispatch;

do_neg:
    if (sp >= 1) stack[sp - 1] = -stack[sp - 1];
    goto dispatch;

do_aload:
    if (sp < 256 && tmp >= 0 && static_cast<size_t>(tmp) < locals_length) {
        stack[sp++] = locals[tmp];
    }
    goto dispatch;

do_astore:
    if (sp >= 1 && tmp >= 0 && static_cast<size_t>(tmp) < locals_length && locals != nullptr) {
        locals[tmp] = stack[--sp];
    }
    goto dispatch;

do_aaload:
    if (sp >= 2) {
        jsize index = static_cast<jsize>(stack[--sp]);
        jobjectArray arr = reinterpret_cast<jobjectArray>(stack[--sp]);
        jobject val = object_cache.get(arr, index);
        if (val != nullptr) {
            stack[sp++] = reinterpret_cast<int64_t>(val);
        }
    }
    goto dispatch;

do_aastore:
    if (sp >= 3) {
        jobject value = reinterpret_cast<jobject>(stack[--sp]);
        jsize index = static_cast<jsize>(stack[--sp]);
        jobjectArray arr = reinterpret_cast<jobjectArray>(stack[--sp]);
        env->SetObjectArrayElement(arr, index, value);
        object_cache.invalidate(arr, index);
    }
    goto dispatch;

do_iaload:
    if (sp >= 2) {
        jsize index = static_cast<jsize>(stack[--sp]);
        jintArray arr = reinterpret_cast<jintArray>(stack[--sp]);
        if (arr == nullptr) {
            throw_null_array(env);
        } else {
            PrimitiveArrayCacheEntry* entry = nullptr;
            jint* elems = array_cache.get<jintArray, jint, PrimitiveArrayKind::INT>(arr, false, &entry);
            if (elems != nullptr && entry != nullptr) {
                if (index < 0 || index >= entry->length) {
                    throw_array_index_oob(env, index, entry->length);
                } else {
                    stack[sp++] = elems[index];
                }
            }
        }
    }
    goto dispatch;

do_laload:
    if (sp >= 2) {
        jsize index = static_cast<jsize>(stack[--sp]);
        jlongArray arr = reinterpret_cast<jlongArray>(stack[--sp]);
        if (arr == nullptr) {
            throw_null_array(env);
        } else {
            PrimitiveArrayCacheEntry* entry = nullptr;
            jlong* elems = array_cache.get<jlongArray, jlong, PrimitiveArrayKind::LONG>(arr, false, &entry);
            if (elems != nullptr && entry != nullptr) {
                if (index < 0 || index >= entry->length) {
                    throw_array_index_oob(env, index, entry->length);
                } else {
                    stack[sp++] = elems[index];
                }
            }
        }
    }
    goto dispatch;

do_faload:
    if (sp >= 2) {
        jsize index = static_cast<jsize>(stack[--sp]);
        jfloatArray arr = reinterpret_cast<jfloatArray>(stack[--sp]);
        if (arr == nullptr) {
            throw_null_array(env);
        } else {
            PrimitiveArrayCacheEntry* entry = nullptr;
            jfloat* elems = array_cache.get<jfloatArray, jfloat, PrimitiveArrayKind::FLOAT>(arr, false, &entry);
            if (elems != nullptr && entry != nullptr) {
                if (index < 0 || index >= entry->length) {
                    throw_array_index_oob(env, index, entry->length);
                } else {
                    int32_t bits;
                    std::memcpy(&bits, &elems[index], sizeof(float));
                    stack[sp++] = static_cast<int64_t>(bits);
                }
            }
        }
    }
    goto dispatch;

do_daload:
    if (sp >= 2) {
        jsize index = static_cast<jsize>(stack[--sp]);
        jdoubleArray arr = reinterpret_cast<jdoubleArray>(stack[--sp]);
        if (arr == nullptr) {
            throw_null_array(env);
        } else {
            PrimitiveArrayCacheEntry* entry = nullptr;
            jdouble* elems = array_cache.get<jdoubleArray, jdouble, PrimitiveArrayKind::DOUBLE>(arr, false, &entry);
            if (elems != nullptr && entry != nullptr) {
                if (index < 0 || index >= entry->length) {
                    throw_array_index_oob(env, index, entry->length);
                } else {
                    int64_t bits;
                    std::memcpy(&bits, &elems[index], sizeof(double));
                    stack[sp++] = bits;
                }
            }
        }
    }
    goto dispatch;

do_baload:
    if (sp >= 2) {
        jsize index = static_cast<jsize>(stack[--sp]);
        jbyteArray arr = reinterpret_cast<jbyteArray>(stack[--sp]);
        if (arr == nullptr) {
            throw_null_array(env);
        } else {
            PrimitiveArrayCacheEntry* entry = nullptr;
            jbyte* elems = array_cache.get<jbyteArray, jbyte, PrimitiveArrayKind::BYTE>(arr, false, &entry);
            if (elems != nullptr && entry != nullptr) {
                if (index < 0 || index >= entry->length) {
                    throw_array_index_oob(env, index, entry->length);
                } else {
                    stack[sp++] = elems[index];
                }
            }
        }
    }
    goto dispatch;

do_caload:
    if (sp >= 2) {
        jsize index = static_cast<jsize>(stack[--sp]);
        jcharArray arr = reinterpret_cast<jcharArray>(stack[--sp]);
        if (arr == nullptr) {
            throw_null_array(env);
        } else {
            PrimitiveArrayCacheEntry* entry = nullptr;
            jchar* elems = array_cache.get<jcharArray, jchar, PrimitiveArrayKind::CHAR>(arr, false, &entry);
            if (elems != nullptr && entry != nullptr) {
                if (index < 0 || index >= entry->length) {
                    throw_array_index_oob(env, index, entry->length);
                } else {
                    stack[sp++] = elems[index];
                }
            }
        }
    }
    goto dispatch;

do_saload:
    if (sp >= 2) {
        jsize index = static_cast<jsize>(stack[--sp]);
        jshortArray arr = reinterpret_cast<jshortArray>(stack[--sp]);
        if (arr == nullptr) {
            throw_null_array(env);
        } else {
            PrimitiveArrayCacheEntry* entry = nullptr;
            jshort* elems = array_cache.get<jshortArray, jshort, PrimitiveArrayKind::SHORT>(arr, false, &entry);
            if (elems != nullptr && entry != nullptr) {
                if (index < 0 || index >= entry->length) {
                    throw_array_index_oob(env, index, entry->length);
                } else {
                    stack[sp++] = elems[index];
                }
            }
        }
    }
    goto dispatch;

do_iastore:
    if (sp >= 3) {
        jint value = static_cast<jint>(stack[--sp]);
        jsize index = static_cast<jsize>(stack[--sp]);
        jintArray arr = reinterpret_cast<jintArray>(stack[--sp]);
        if (arr == nullptr) {
            throw_null_array(env);
        } else {
            PrimitiveArrayCacheEntry* entry = nullptr;
            jint* elems = array_cache.get<jintArray, jint, PrimitiveArrayKind::INT>(arr, true, &entry);
            if (elems != nullptr && entry != nullptr) {
                if (index < 0 || index >= entry->length) {
                    throw_array_index_oob(env, index, entry->length);
                } else {
                    elems[index] = value;
                }
            }
        }
    }
    goto dispatch;

do_lastore:
    if (sp >= 3) {
        jlong value = static_cast<jlong>(stack[--sp]);
        jsize index = static_cast<jsize>(stack[--sp]);
        jlongArray arr = reinterpret_cast<jlongArray>(stack[--sp]);
        if (arr == nullptr) {
            throw_null_array(env);
        } else {
            PrimitiveArrayCacheEntry* entry = nullptr;
            jlong* elems = array_cache.get<jlongArray, jlong, PrimitiveArrayKind::LONG>(arr, true, &entry);
            if (elems != nullptr && entry != nullptr) {
                if (index < 0 || index >= entry->length) {
                    throw_array_index_oob(env, index, entry->length);
                } else {
                    elems[index] = value;
                }
            }
        }
    }
    goto dispatch;

do_fastore:
    if (sp >= 3) {
        // Extract float value from int bits
        int32_t bits = static_cast<int32_t>(stack[--sp]);
        jfloat value;
        std::memcpy(&value, &bits, sizeof(float));
        jsize index = static_cast<jsize>(stack[--sp]);
        jfloatArray arr = reinterpret_cast<jfloatArray>(stack[--sp]);
        if (arr == nullptr) {
            throw_null_array(env);
        } else {
            PrimitiveArrayCacheEntry* entry = nullptr;
            jfloat* elems = array_cache.get<jfloatArray, jfloat, PrimitiveArrayKind::FLOAT>(arr, true, &entry);
            if (elems != nullptr && entry != nullptr) {
                if (index < 0 || index >= entry->length) {
                    throw_array_index_oob(env, index, entry->length);
                } else {
                    elems[index] = value;
                }
            }
        }
    }
    goto dispatch;

do_dastore:
    if (sp >= 3) {
        // Extract double value from long bits
        int64_t bits = stack[--sp];
        jdouble value;
        std::memcpy(&value, &bits, sizeof(double));
        jsize index = static_cast<jsize>(stack[--sp]);
        jdoubleArray arr = reinterpret_cast<jdoubleArray>(stack[--sp]);
        if (arr == nullptr) {
            throw_null_array(env);
        } else {
            PrimitiveArrayCacheEntry* entry = nullptr;
            jdouble* elems = array_cache.get<jdoubleArray, jdouble, PrimitiveArrayKind::DOUBLE>(arr, true, &entry);
            if (elems != nullptr && entry != nullptr) {
                if (index < 0 || index >= entry->length) {
                    throw_array_index_oob(env, index, entry->length);
                } else {
                    elems[index] = value;
                }
            }
        }
    }
    goto dispatch;

do_bastore:
    if (sp >= 3) {
        jbyte value = static_cast<jbyte>(stack[--sp]);
        jsize index = static_cast<jsize>(stack[--sp]);
        jbyteArray arr = reinterpret_cast<jbyteArray>(stack[--sp]);
        if (arr == nullptr) {
            throw_null_array(env);
        } else {
            PrimitiveArrayCacheEntry* entry = nullptr;
            jbyte* elems = array_cache.get<jbyteArray, jbyte, PrimitiveArrayKind::BYTE>(arr, true, &entry);
            if (elems != nullptr && entry != nullptr) {
                if (index < 0 || index >= entry->length) {
                    throw_array_index_oob(env, index, entry->length);
                } else {
                    elems[index] = value;
                }
            }
        }
    }
    goto dispatch;

do_castore:
    if (sp >= 3) {
        jchar value = static_cast<jchar>(stack[--sp]);
        jsize index = static_cast<jsize>(stack[--sp]);
        jcharArray arr = reinterpret_cast<jcharArray>(stack[--sp]);
        if (arr == nullptr) {
            throw_null_array(env);
        } else {
            PrimitiveArrayCacheEntry* entry = nullptr;
            jchar* elems = array_cache.get<jcharArray, jchar, PrimitiveArrayKind::CHAR>(arr, true, &entry);
            if (elems != nullptr && entry != nullptr) {
                if (index < 0 || index >= entry->length) {
                    throw_array_index_oob(env, index, entry->length);
                } else {
                    elems[index] = value;
                }
            }
        }
    }
    goto dispatch;

do_sastore:
    if (sp >= 3) {
        jshort value = static_cast<jshort>(stack[--sp]);
        jsize index = static_cast<jsize>(stack[--sp]);
        jshortArray arr = reinterpret_cast<jshortArray>(stack[--sp]);
        if (arr == nullptr) {
            throw_null_array(env);
        } else {
            PrimitiveArrayCacheEntry* entry = nullptr;
            jshort* elems = array_cache.get<jshortArray, jshort, PrimitiveArrayKind::SHORT>(arr, true, &entry);
            if (elems != nullptr && entry != nullptr) {
                if (index < 0 || index >= entry->length) {
                    throw_array_index_oob(env, index, entry->length);
                } else {
                    elems[index] = value;
                }
            }
        }
    }
    goto dispatch;

do_new:
    if (sp < 256) {
        const char* name = reinterpret_cast<const char*>(tmp);
        jclass clazz = get_cached_class(env, name);
        if (clazz) {
            jobject obj = env->AllocObject(clazz);
            stack[sp++] = reinterpret_cast<int64_t>(obj);
            env->DeleteLocalRef(clazz);
        }
    }
    goto dispatch;

do_anewarray:
    if (sp >= 1) {
        jint length = static_cast<jint>(stack[--sp]);
        const char* name = reinterpret_cast<const char*>(tmp);
        jclass clazz = get_cached_class(env, name);
        jobjectArray arr = nullptr;
        if (clazz) {
            arr = env->NewObjectArray(length, clazz, nullptr);
            env->DeleteLocalRef(clazz);
        }
        stack[sp++] = reinterpret_cast<int64_t>(arr);
    }
    goto dispatch;

do_newarray:
    if (sp >= 1) {
        jint length = static_cast<jint>(stack[--sp]);
        jarray arr = nullptr;
        switch (tmp) {
            case 4: arr = env->NewBooleanArray(length); break;
            case 5: arr = env->NewCharArray(length); break;
            case 6: arr = env->NewFloatArray(length); break;
            case 7: arr = env->NewDoubleArray(length); break;
            case 8: arr = env->NewByteArray(length); break;
            case 9: arr = env->NewShortArray(length); break;
            case 10: arr = env->NewIntArray(length); break;
            case 11: arr = env->NewLongArray(length); break;
            default: break;
        }
        stack[sp++] = reinterpret_cast<int64_t>(arr);
    }
    goto dispatch;

do_multianewarray:
    {
        auto* info = &multi_refs[tmp];
        jint dims = info->dims;
        const char* name = info->class_name;
        std::vector<jint> sizes(dims);
        for (int i = dims - 1; i >= 0 && sp > 0; --i) {
            sizes[i] = static_cast<jint>(stack[--sp]);
        }
        jobjectArray arr = nullptr;
        jclass clazz = get_cached_class(env, name);
        if (clazz) {
            arr = env->NewObjectArray(dims > 0 ? sizes[0] : 0, clazz, nullptr);
            env->DeleteLocalRef(clazz);
        }
        stack[sp++] = reinterpret_cast<int64_t>(arr);
    }
    goto dispatch;

do_checkcast:
    if (sp >= 1) {
        jobject obj = reinterpret_cast<jobject>(stack[sp - 1]);
        if (obj != nullptr) {
            const char* name = reinterpret_cast<const char*>(tmp);
            jclass clazz = get_cached_class(env, name);
            if (clazz) {
                if (!env->IsInstanceOf(obj, clazz)) {
                    jclass ex = env->FindClass("java/lang/ClassCastException");
                    if (ex) env->ThrowNew(ex, "checkcast failed");
                }
                env->DeleteLocalRef(clazz);
            }
        }
    }
    goto dispatch;

do_instanceof:
    if (sp >= 1) {
        jobject obj = reinterpret_cast<jobject>(stack[--sp]);
        const char* name = reinterpret_cast<const char*>(tmp);
        jclass clazz = get_cached_class(env, name);
        jboolean res = obj && clazz && env->IsInstanceOf(obj, clazz);
        if (clazz) env->DeleteLocalRef(clazz);
        stack[sp++] = res ? 1 : 0;
    }
    goto dispatch;

do_getstatic:
    if (sp < 256) {
        auto* ref = &field_refs[tmp];
        CachedFieldEntry* cached_field = resolve_field(env, ref, true);
        if (cached_field && cached_field->clazz && cached_field->field) {
            jclass clazz = cached_field->clazz;
            jfieldID fid = cached_field->field;
            switch (ref->field_sig[0]) {
                case 'Z': case 'B': case 'C': case 'S': case 'I': {
                    jint v = env->GetStaticIntField(clazz, fid);
                    stack[sp++] = static_cast<int64_t>(v);
                    break;
                }
                case 'F': {
                    jfloat v = env->GetStaticFloatField(clazz, fid);
                    int32_t bits;
                    std::memcpy(&bits, &v, sizeof(float));
                    stack[sp++] = static_cast<int64_t>(bits);
                    break;
                }
                case 'J': {
                    jlong v = env->GetStaticLongField(clazz, fid);
                    stack[sp++] = static_cast<int64_t>(v);
                    break;
                }
                case 'D': {
                    jdouble v = env->GetStaticDoubleField(clazz, fid);
                    int64_t bits;
                    std::memcpy(&bits, &v, sizeof(double));
                    stack[sp++] = bits;
                    break;
                }
                default: {
                    jobject v = env->GetStaticObjectField(clazz, fid);
                    stack[sp++] = reinterpret_cast<int64_t>(v);
                    break;
                }
            }
        }
    }
    goto dispatch;

do_putstatic:
    if (sp >= 1) {
        auto* ref = &field_refs[tmp];
        CachedFieldEntry* cached_field = resolve_field(env, ref, true);
        if (cached_field && cached_field->clazz && cached_field->field) {
            jclass clazz = cached_field->clazz;
            jfieldID fid = cached_field->field;
            switch (ref->field_sig[0]) {
                case 'Z': case 'B': case 'C': case 'S': case 'I': {
                    jint v = static_cast<jint>(stack[--sp]);
                    env->SetStaticIntField(clazz, fid, v);
                    break;
                }
                case 'F': {
                    int32_t bits = static_cast<int32_t>(stack[--sp]);
                    jfloat v;
                    std::memcpy(&v, &bits, sizeof(float));
                    env->SetStaticFloatField(clazz, fid, v);
                    break;
                }
                case 'J': {
                    jlong v = static_cast<jlong>(stack[--sp]);
                    env->SetStaticLongField(clazz, fid, v);
                    break;
                }
                case 'D': {
                    int64_t bits = stack[--sp];
                    jdouble v;
                    std::memcpy(&v, &bits, sizeof(double));
                    env->SetStaticDoubleField(clazz, fid, v);
                    break;
                }
                default: {
                    jobject v = reinterpret_cast<jobject>(stack[--sp]);
                    env->SetStaticObjectField(clazz, fid, v);
                    break;
                }
            }
        } else {
            --sp; // consume value even if field resolution failed
        }
    }
    goto dispatch;

do_getfield:
    if (sp >= 1 && sp < 256) {
        auto* ref = &field_refs[tmp];
        jobject obj = reinterpret_cast<jobject>(stack[--sp]);
        if (!obj) {
            env->ThrowNew(env->FindClass("java/lang/NullPointerException"), "null");
            goto halt;
        }
        CachedFieldEntry* cached_field = resolve_field(env, ref, false);
        if (cached_field && cached_field->field) {
            jfieldID fid = cached_field->field;
            switch (ref->field_sig[0]) {
                case 'Z': case 'B': case 'C': case 'S': case 'I': {
                    jint v = env->GetIntField(obj, fid);
                    stack[sp++] = static_cast<int64_t>(v);
                    break;
                }
                case 'F': {
                    jfloat v = env->GetFloatField(obj, fid);
                    int32_t bits;
                    std::memcpy(&bits, &v, sizeof(float));
                    stack[sp++] = static_cast<int64_t>(bits);
                    break;
                }
                case 'J': {
                    jlong v = env->GetLongField(obj, fid);
                    stack[sp++] = static_cast<int64_t>(v);
                    break;
                }
                case 'D': {
                    jdouble v = env->GetDoubleField(obj, fid);
                    int64_t bits;
                    std::memcpy(&bits, &v, sizeof(double));
                    stack[sp++] = bits;
                    break;
                }
                default: {
                    jobject v = env->GetObjectField(obj, fid);
                    stack[sp++] = reinterpret_cast<int64_t>(v);
                    break;
                }
            }
        }
    }
    goto dispatch;

do_putfield:
    if (sp >= 2) {
        auto* ref = &field_refs[tmp];
        int64_t value = stack[--sp];
        jobject obj = reinterpret_cast<jobject>(stack[--sp]);
        if (!obj) {
            env->ThrowNew(env->FindClass("java/lang/NullPointerException"), "null");
            goto halt;
        }
        CachedFieldEntry* cached_field = resolve_field(env, ref, false);
        if (cached_field && cached_field->field) {
            jfieldID fid = cached_field->field;
            switch (ref->field_sig[0]) {
                case 'Z': case 'B': case 'C': case 'S': case 'I': {
                    env->SetIntField(obj, fid, static_cast<jint>(value));
                    break;
                }
                case 'F': {
                    jfloat v;
                    int32_t bits = static_cast<int32_t>(value);
                    std::memcpy(&v, &bits, sizeof(float));
                    env->SetFloatField(obj, fid, v);
                    break;
                }
                case 'J': {
                    env->SetLongField(obj, fid, static_cast<jlong>(value));
                    break;
                }
                case 'D': {
                    jdouble v;
                    int64_t bits = value;
                    std::memcpy(&v, &bits, sizeof(double));
                    env->SetDoubleField(obj, fid, v);
                    break;
                }
                default: {
                    jobject v = reinterpret_cast<jobject>(value);
                    env->SetObjectField(obj, fid, v);
                    break;
                }
            }
        }
    } else {
        sp = 0;
    }
    goto dispatch;

do_invokestatic:
    if (method_refs && static_cast<size_t>(tmp) < method_refs_size) {
        invoke_method(env, OP_INVOKESTATIC, const_cast<MethodRef*>(&method_refs[tmp]), stack, sp);
    } else {
        // Method reference not found - this shouldn't happen in valid code
        char debug_msg[256];
        snprintf(debug_msg, sizeof(debug_msg), "Method reference not found: index=%lld, size=%zu", (long long)tmp, method_refs_size);
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), debug_msg);
        goto halt;
    }
    goto dispatch;

do_invokevirtual:
    if (method_refs && static_cast<size_t>(tmp) < method_refs_size) {
        invoke_method(env, OP_INVOKEVIRTUAL, const_cast<MethodRef*>(&method_refs[tmp]), stack, sp);
    } else {
        // Method reference not found - this shouldn't happen in valid code
        char debug_msg[256];
        snprintf(debug_msg, sizeof(debug_msg), "Method reference not found: index=%lld, size=%zu", (long long)tmp, method_refs_size);
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), debug_msg);
        goto halt;
    }
    goto dispatch;

do_invokespecial:
    if (method_refs && static_cast<size_t>(tmp) < method_refs_size) {
        invoke_method(env, OP_INVOKESPECIAL, const_cast<MethodRef*>(&method_refs[tmp]), stack, sp);
    } else {
        // Method reference not found - include index/size for diagnostics
        char debug_msg[256];
        snprintf(debug_msg, sizeof(debug_msg), "Method reference not found: index=%lld, size=%zu", (long long)tmp, method_refs_size);
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), debug_msg);
        goto halt;
    }
    goto dispatch;

do_invokeinterface:
    if (method_refs && static_cast<size_t>(tmp) < method_refs_size) {
        invoke_method(env, OP_INVOKEINTERFACE, const_cast<MethodRef*>(&method_refs[tmp]), stack, sp);
    } else {
        // Method reference not found - include index/size for diagnostics
        char debug_msg[256];
        snprintf(debug_msg, sizeof(debug_msg), "Method reference not found: index=%lld, size=%zu", (long long)tmp, method_refs_size);
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), debug_msg);
        goto halt;
    }
    goto dispatch;

do_invokedynamic:
    if (method_refs && static_cast<size_t>(tmp) < method_refs_size) {
        invoke_method(env, OP_INVOKEDYNAMIC, const_cast<MethodRef*>(&method_refs[tmp]), stack, sp);
    } else {
        // Method reference not found - include index/size for diagnostics
        char debug_msg[256];
        snprintf(debug_msg, sizeof(debug_msg), "Method reference not found: index=%lld, size=%zu", (long long)tmp, method_refs_size);
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), debug_msg);
        goto halt;
    }
    goto dispatch;

do_ldc:
    // Load constant from constant pool (1-word constants: int, float, string, class)
    if (sp < 256 && constant_pool && static_cast<size_t>(tmp) < constant_pool_size) {
        const ConstantPoolEntry& entry = constant_pool[tmp];
        switch (entry.type) {
            case ConstantPoolEntry::TYPE_INTEGER:
                stack[sp++] = static_cast<int64_t>(entry.i_value);
                break;
            case ConstantPoolEntry::TYPE_FLOAT: {
                int32_t bits;
                std::memcpy(&bits, &entry.f_value, sizeof(float));
                stack[sp++] = static_cast<int64_t>(bits);
                break;
            }
            case ConstantPoolEntry::TYPE_STRING: {
                // Create Java String object from C string
                jstring str = env->NewStringUTF(entry.str_value);
                stack[sp++] = reinterpret_cast<int64_t>(str);
                break;
            }
            case ConstantPoolEntry::TYPE_CLASS: {
                // Load Class object
                jclass clazz = get_cached_class(env, entry.class_name);
                stack[sp++] = reinterpret_cast<int64_t>(clazz);
                break;
            }
            case ConstantPoolEntry::TYPE_METHOD_TYPE: {
                // Create MethodType object from descriptor string
                jstring desc = env->NewStringUTF(entry.str_value);
                jclass mt_class = get_cached_class(env, "java/lang/invoke/MethodType");
                jmethodID fromDesc = env->GetStaticMethodID(mt_class, "fromMethodDescriptorString",
                    "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;");
                jobject mt = env->CallStaticObjectMethod(mt_class, fromDesc, desc, nullptr);
                env->DeleteLocalRef(desc);
                stack[sp++] = reinterpret_cast<int64_t>(mt);
                break;
            }
            case ConstantPoolEntry::TYPE_METHOD_HANDLE: {
                // Parse MethodHandle format: "tag:owner:name:desc"
                std::string handle_str(entry.str_value);
                size_t pos1 = handle_str.find(':');
                size_t pos2 = handle_str.find(':', pos1 + 1);
                size_t pos3 = handle_str.find(':', pos2 + 1);

                if (pos1 == std::string::npos || pos2 == std::string::npos || pos3 == std::string::npos) {
                    env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "Invalid MethodHandle format");
                    goto halt;
                }

                int tag = std::stoi(handle_str.substr(0, pos1));
                std::string owner = handle_str.substr(pos1 + 1, pos2 - pos1 - 1);
                std::string name = handle_str.substr(pos2 + 1, pos3 - pos2 - 1);
                std::string desc = handle_str.substr(pos3 + 1);

                // Create MethodHandle using Lookup
                jclass lookup_class = get_cached_class(env, "java/lang/invoke/MethodHandles$Lookup");
                jmethodID lookup_method = env->GetStaticMethodID(lookup_class, "lookup",
                    "()Ljava/lang/invoke/MethodHandles$Lookup;");
                jobject lookup = env->CallStaticObjectMethod(lookup_class, lookup_method);

                jclass target_class = get_cached_class(env, owner.c_str());
                jstring method_name = env->NewStringUTF(name.c_str());
                jstring method_desc = env->NewStringUTF(desc.c_str());

                jobject method_handle = nullptr;
                switch (tag) {
                    case 6: { // H_INVOKESTATIC
                        jclass mt_class = get_cached_class(env, "java/lang/invoke/MethodType");
                        jmethodID fromDesc = env->GetStaticMethodID(mt_class, "fromMethodDescriptorString",
                            "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;");
                        jobject mt = env->CallStaticObjectMethod(mt_class, fromDesc, method_desc, nullptr);
                        jmethodID findStatic = env->GetMethodID(lookup_class, "findStatic",
                            "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
                        method_handle = env->CallObjectMethod(lookup, findStatic, target_class, method_name, mt);
                        env->DeleteLocalRef(mt);
                        break;
                    }
                    case 5: { // H_INVOKEVIRTUAL
                        jclass mt_class = get_cached_class(env, "java/lang/invoke/MethodType");
                        jmethodID fromDesc = env->GetStaticMethodID(mt_class, "fromMethodDescriptorString",
                            "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;");
                        jobject mt = env->CallStaticObjectMethod(mt_class, fromDesc, method_desc, nullptr);
                        jmethodID findVirtual = env->GetMethodID(lookup_class, "findVirtual",
                            "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
                        method_handle = env->CallObjectMethod(lookup, findVirtual, target_class, method_name, mt);
                        env->DeleteLocalRef(mt);
                        break;
                    }
                    case 7: { // H_INVOKESPECIAL
                        jclass mt_class = get_cached_class(env, "java/lang/invoke/MethodType");
                        jmethodID fromDesc = env->GetStaticMethodID(mt_class, "fromMethodDescriptorString",
                            "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;");
                        jobject mt = env->CallStaticObjectMethod(mt_class, fromDesc, method_desc, nullptr);
                        jmethodID findSpecial = env->GetMethodID(lookup_class, "findSpecial",
                            "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;");
                        method_handle = env->CallObjectMethod(lookup, findSpecial, target_class, method_name, mt, target_class);
                        env->DeleteLocalRef(mt);
                        break;
                    }
                    default:
                        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "Unsupported MethodHandle tag");
                        goto halt;
                }

                env->DeleteLocalRef(method_name);
                env->DeleteLocalRef(method_desc);
                env->DeleteLocalRef(lookup);

                stack[sp++] = reinterpret_cast<int64_t>(method_handle);
                break;
            }
            default:
                // Unsupported constant type in LDC
                goto halt;
        }
    }
    goto dispatch;

do_ldc2_w:
    // Load 2-word constant from constant pool (long, double, MethodHandle, MethodType)
    if (sp < 256 && constant_pool && static_cast<size_t>(tmp) < constant_pool_size) {
        const ConstantPoolEntry& entry = constant_pool[tmp];
        switch (entry.type) {
            case ConstantPoolEntry::TYPE_LONG:
                stack[sp++] = entry.l_value;
                break;
            case ConstantPoolEntry::TYPE_DOUBLE: {
                int64_t bits;
                std::memcpy(&bits, &entry.d_value, sizeof(double));
                stack[sp++] = bits;
                break;
            }
            case ConstantPoolEntry::TYPE_METHOD_TYPE: {
                // Create MethodType object from descriptor string
                jstring desc = env->NewStringUTF(entry.str_value);
                jclass mt_class = get_cached_class(env, "java/lang/invoke/MethodType");
                jmethodID fromDesc = env->GetStaticMethodID(mt_class, "fromMethodDescriptorString",
                    "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;");
                jobject mt = env->CallStaticObjectMethod(mt_class, fromDesc, desc, nullptr);
                env->DeleteLocalRef(desc);
                stack[sp++] = reinterpret_cast<int64_t>(mt);
                break;
            }
            case ConstantPoolEntry::TYPE_METHOD_HANDLE: {
                // Parse MethodHandle format: "tag:owner:name:desc"
                std::string handle_str(entry.str_value);
                size_t pos1 = handle_str.find(':');
                size_t pos2 = handle_str.find(':', pos1 + 1);
                size_t pos3 = handle_str.find(':', pos2 + 1);

                if (pos1 == std::string::npos || pos2 == std::string::npos || pos3 == std::string::npos) {
                    env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "Invalid MethodHandle format");
                    goto halt;
                }

                int tag = std::stoi(handle_str.substr(0, pos1));
                std::string owner = handle_str.substr(pos1 + 1, pos2 - pos1 - 1);
                std::string name = handle_str.substr(pos2 + 1, pos3 - pos2 - 1);
                std::string desc = handle_str.substr(pos3 + 1);

                // Create MethodHandle using Lookup
                jclass lookup_class = get_cached_class(env, "java/lang/invoke/MethodHandles$Lookup");
                jmethodID lookup_method = env->GetStaticMethodID(lookup_class, "lookup",
                    "()Ljava/lang/invoke/MethodHandles$Lookup;");
                jobject lookup = env->CallStaticObjectMethod(lookup_class, lookup_method);

                jclass target_class = get_cached_class(env, owner.c_str());
                jstring method_name = env->NewStringUTF(name.c_str());
                jstring method_desc = env->NewStringUTF(desc.c_str());

                jobject method_handle = nullptr;
                switch (tag) {
                    case 6: { // H_INVOKESTATIC
                        jclass mt_class = get_cached_class(env, "java/lang/invoke/MethodType");
                        jmethodID fromDesc = env->GetStaticMethodID(mt_class, "fromMethodDescriptorString",
                            "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;");
                        jobject mt = env->CallStaticObjectMethod(mt_class, fromDesc, method_desc, nullptr);
                        jmethodID findStatic = env->GetMethodID(lookup_class, "findStatic",
                            "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
                        method_handle = env->CallObjectMethod(lookup, findStatic, target_class, method_name, mt);
                        env->DeleteLocalRef(mt);
                        break;
                    }
                    case 5: { // H_INVOKEVIRTUAL
                        jclass mt_class = get_cached_class(env, "java/lang/invoke/MethodType");
                        jmethodID fromDesc = env->GetStaticMethodID(mt_class, "fromMethodDescriptorString",
                            "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;");
                        jobject mt = env->CallStaticObjectMethod(mt_class, fromDesc, method_desc, nullptr);
                        jmethodID findVirtual = env->GetMethodID(lookup_class, "findVirtual",
                            "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
                        method_handle = env->CallObjectMethod(lookup, findVirtual, target_class, method_name, mt);
                        env->DeleteLocalRef(mt);
                        break;
                    }
                    case 7: { // H_INVOKESPECIAL
                        jclass mt_class = get_cached_class(env, "java/lang/invoke/MethodType");
                        jmethodID fromDesc = env->GetStaticMethodID(mt_class, "fromMethodDescriptorString",
                            "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;");
                        jobject mt = env->CallStaticObjectMethod(mt_class, fromDesc, method_desc, nullptr);
                        jmethodID findSpecial = env->GetMethodID(lookup_class, "findSpecial",
                            "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;");
                        method_handle = env->CallObjectMethod(lookup, findSpecial, target_class, method_name, mt, target_class);
                        env->DeleteLocalRef(mt);
                        break;
                    }
                    default:
                        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "Unsupported MethodHandle tag");
                        goto halt;
                }

                env->DeleteLocalRef(method_name);
                env->DeleteLocalRef(method_desc);
                env->DeleteLocalRef(lookup);

                stack[sp++] = reinterpret_cast<int64_t>(method_handle);
                break;
            }
            default:
                // Invalid constant type for LDC2_W
                goto halt;
        }
    }
    goto dispatch;

// Dummy branch used only to confuse decompilers
junk:
    // toggle and restore state so decoding stays in sync
    state ^= KEY << 7;
    state ^= KEY << 7;
    goto dispatch;

// Exit point
halt:
    return (sp > 0) ? stack[sp - 1] : 0;
}

void encode_program(Instruction* code, size_t length, uint64_t seed) {
    ensure_init(seed);
    uint64_t state = KEY ^ seed;
    std::mt19937_64 rng(KEY ^ (seed << 1));
    for (size_t i = 0; i < length; ++i) {
        state = (state + KEY) ^ (KEY >> 3);
        uint64_t nonce = rng() ^ state;
        code[i] = encode(static_cast<OpCode>(code[i].op), code[i].operand, state, nonce);
    }
}

int64_t execute_jit(JNIEnv* env, const Instruction* code, size_t length,
                    int64_t* locals, size_t locals_length, uint64_t seed,
                    const ConstantPoolEntry* constant_pool, size_t constant_pool_size,
                    const MethodRef* method_refs, size_t method_refs_size,
                    const FieldRef* field_refs, size_t field_refs_size,
                    const MultiArrayInfo* multi_refs, size_t multi_refs_size,
                    const TableSwitch* table_refs, size_t table_refs_size,
                    const LookupSwitch* lookup_refs, size_t lookup_refs_size) {
    ensure_init(seed);
    auto it = jit_cache.find(code);
    if (it != jit_cache.end()) {
        if (it->second.func != nullptr) {
            return it->second.func(env, locals, locals_length, seed, it->second.ctx);
        }
        return execute(env, code, length, locals, locals_length, seed,
                       constant_pool, constant_pool_size,
                       method_refs, method_refs_size,
                       field_refs, field_refs_size,
                       multi_refs, multi_refs_size,
                       table_refs, table_refs_size,
                       lookup_refs, lookup_refs_size);
    }
    size_t& cnt = exec_counts[code];
    if (++cnt > HOT_THRESHOLD) {
        JitCompiled compiled = compile(code, length, seed);
        it = jit_cache.emplace(code, compiled).first;
        if (it->second.func != nullptr) {
            return it->second.func(env, locals, locals_length, seed, it->second.ctx);
        }
        return execute(env, code, length, locals, locals_length, seed,
                       constant_pool, constant_pool_size,
                       method_refs, method_refs_size,
                       field_refs, field_refs_size,
                       multi_refs, multi_refs_size,
                       table_refs, table_refs_size,
                       lookup_refs, lookup_refs_size);
    }
    return execute(env, code, length, locals, locals_length, seed, constant_pool, constant_pool_size, method_refs, method_refs_size, field_refs, field_refs_size, multi_refs, multi_refs_size, table_refs, table_refs_size, lookup_refs, lookup_refs_size);
}

static int64_t execute_variant(JNIEnv* env, const Instruction* code, size_t length,
                               int64_t* locals, size_t locals_length, uint64_t seed) {
    volatile uint64_t noise = KEY ^ seed;
    noise ^= noise << 13;
    // noise is intentionally unused to introduce a distinct entry
    return execute(env, code, length, locals, locals_length, seed, nullptr, 0, nullptr, 0, nullptr, 0, nullptr, 0, nullptr, 0, nullptr, 0);
}

int64_t run_arith_vm(JNIEnv* env, OpCode op, int64_t lhs, int64_t rhs, uint64_t seed) {
    ensure_init(seed);
    ArithKey key{op, seed};
    auto res = arith_program_cache.emplace(key, CachedArithProgram{});
    auto& cached = res.first->second;
    if (res.second) {
        cached.program.reserve(16);
        uint64_t state = KEY ^ seed;
        std::mt19937_64 rng(KEY ^ (seed << 1));
        size_t push_count = 0;

        auto emit = [&](OpCode opcode, int64_t operand) {
            state = (state + KEY) ^ (KEY >> 3);
            uint64_t nonce = rng() ^ state;
            uint64_t mix = state ^ nonce;
            Instruction encoded = encode(opcode, operand, state, nonce);
            size_t index = cached.program.size();
            cached.program.push_back(encoded);
            if (opcode == OP_PUSH) {
                OperandSlot slot{index, mix, nonce, encoded.op};
                if (push_count == 0) {
                    cached.lhs_slot = slot;
                    cached.has_lhs = true;
                } else if (push_count == 1) {
                    cached.rhs_slot = slot;
                    cached.has_rhs = true;
                }
                ++push_count;
            }
        };

        auto emit_junk = [&]() {
            std::uniform_int_distribution<int> count_dist(0, 3);
            std::uniform_int_distribution<int> choice_dist(0, 2);
            int count = count_dist(rng);
            for (int i = 0; i < count; ++i) {
                int choice = choice_dist(rng);
                OpCode junk = choice == 0 ? OP_JUNK1 : (choice == 1 ? OP_JUNK2 : OP_NOP);
                emit(junk, 0);
            }
        };

        emit_junk();
        emit(OP_PUSH, lhs);
        emit_junk();
        emit(OP_PUSH, rhs);
        emit_junk();
        emit(op, 0);
        emit_junk();
        emit(OP_HALT, 0);

        std::uniform_int_distribution<int> entry_dist(0, 1);
        cached.use_variant = entry_dist(rng) != 0;
    }

    if (cached.has_lhs) {
        Instruction& inst = cached.program[cached.lhs_slot.index];
        inst.op = cached.lhs_slot.encoded_op;
        inst.operand = lhs ^ static_cast<int64_t>(cached.lhs_slot.mix * OPERAND_XOR_CONST);
        inst.nonce = cached.lhs_slot.nonce;
    }
    if (cached.has_rhs) {
        Instruction& inst = cached.program[cached.rhs_slot.index];
        inst.op = cached.rhs_slot.encoded_op;
        inst.operand = rhs ^ static_cast<int64_t>(cached.rhs_slot.mix * OPERAND_XOR_CONST);
        inst.nonce = cached.rhs_slot.nonce;
    }

    if (cached.use_variant) {
        return execute_variant(env, cached.program.data(), cached.program.size(), nullptr, 0, seed);
    }
    return execute(env, cached.program.data(), cached.program.size(), nullptr, 0, seed, nullptr, 0, nullptr, 0, nullptr, 0, nullptr, 0, nullptr, 0, nullptr, 0);
}

int64_t run_unary_vm(JNIEnv* env, OpCode op, int64_t value, uint64_t seed) {
    ensure_init(seed);
    ArithKey key{op, seed};
    auto res = unary_program_cache.emplace(key, CachedUnaryProgram{});
    auto& cached = res.first->second;
    if (res.second) {
        cached.program.reserve(8);
        uint64_t state = KEY ^ seed;
        std::mt19937_64 rng(KEY ^ (seed << 1));

        auto emit = [&](OpCode opcode, int64_t operand) {
            state = (state + KEY) ^ (KEY >> 3);
            uint64_t nonce = rng() ^ state;
            uint64_t mix = state ^ nonce;
            Instruction encoded = encode(opcode, operand, state, nonce);
            size_t index = cached.program.size();
            cached.program.push_back(encoded);
            if (opcode == OP_PUSH && !cached.has_slot) {
                cached.value_slot = OperandSlot{index, mix, nonce, encoded.op};
                cached.has_slot = true;
            }
        };

        auto emit_junk = [&]() {
            std::uniform_int_distribution<int> count_dist(0, 2);
            std::uniform_int_distribution<int> choice_dist(0, 1);
            int count = count_dist(rng);
            for (int i = 0; i < count; ++i) {
                OpCode junk = choice_dist(rng) ? OP_JUNK1 : OP_JUNK2;
                emit(junk, 0);
            }
        };

        emit(OP_PUSH, value);
        emit_junk();
        emit(op, 0);
        emit_junk();
        emit(OP_HALT, 0);
    }

    if (cached.has_slot) {
        Instruction& inst = cached.program[cached.value_slot.index];
        inst.op = cached.value_slot.encoded_op;
        inst.operand = value ^ static_cast<int64_t>(cached.value_slot.mix * OPERAND_XOR_CONST);
        inst.nonce = cached.value_slot.nonce;
    }

    return execute(env, cached.program.data(), cached.program.size(), nullptr, 0, seed, nullptr, 0, nullptr, 0, nullptr, 0, nullptr, 0, nullptr, 0, nullptr, 0);
}

} // namespace native_jvm::vm
// NOLINTEND
