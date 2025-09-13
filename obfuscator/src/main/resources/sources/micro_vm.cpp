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

static thread_local std::unordered_map<std::string, jweak> class_cache{};
static thread_local size_t class_lookup_calls = 0;

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
}

size_t get_class_cache_calls() {
    return class_lookup_calls;
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
    std::vector<char> arg_types;
    char ret;
    parse_method_sig(ref->method_sig, arg_types, ret);
    size_t num = arg_types.size();
    if (sp < num + ((op == OP_INVOKESTATIC || op == OP_INVOKEDYNAMIC) ? 0 : 1)) {
        sp = 0;
        return;
    }
    std::vector<jvalue> jargs(num);
    for (size_t i = 0; i < num; ++i) {
        char t = arg_types[num - 1 - i];
        switch (t) {
            case 'Z': case 'B': case 'C': case 'S': case 'I':
                jargs[num - 1 - i].i = static_cast<jint>(stack[--sp]);
                break;
            case 'J':
                jargs[num - 1 - i].j = static_cast<jlong>(stack[--sp]);
                break;
            case 'F': {
                int32_t bits = static_cast<int32_t>(stack[--sp]);
                jfloat v;
                std::memcpy(&v, &bits, sizeof(float));
                jargs[num - 1 - i].f = v;
                break;
            }
            case 'D': {
                int64_t bits = stack[--sp];
                jdouble v;
                std::memcpy(&v, &bits, sizeof(double));
                jargs[num - 1 - i].d = v;
                break;
            }
            default:
                jargs[num - 1 - i].l = reinterpret_cast<jobject>(stack[--sp]);
                break;
        }
    }
    jobject obj = nullptr;
    if (op != OP_INVOKESTATIC && op != OP_INVOKEDYNAMIC) {
        obj = reinterpret_cast<jobject>(stack[--sp]);
        if (!obj) {
            jclass npe = env->FindClass("java/lang/NullPointerException");
            if (npe) {
                env->ThrowNew(npe, "null");
                if (env->ExceptionCheck()) {
                    env->DeleteLocalRef(npe);
                    return;
                }
                env->DeleteLocalRef(npe);
            }
            return;
        }
    }
    jclass clazz = get_cached_class(env, ref->class_name);
    if (!clazz) {
        return;
    }
    jmethodID mid;
    if (op == OP_INVOKESTATIC || op == OP_INVOKEDYNAMIC) {
        mid = env->GetStaticMethodID(clazz, ref->method_name, ref->method_sig);
    } else {
        mid = env->GetMethodID(clazz, ref->method_name, ref->method_sig);
    }
    if (env->ExceptionCheck() || !mid) {
        env->DeleteLocalRef(clazz);
        return;
    }
    switch (ret) {
        case 'V':
            if (op == OP_INVOKESTATIC || op == OP_INVOKEDYNAMIC)
                env->CallStaticVoidMethodA(clazz, mid, jargs.data());
            else if (op == OP_INVOKESPECIAL)
                env->CallNonvirtualVoidMethodA(obj, clazz, mid, jargs.data());
            else
                env->CallVoidMethodA(obj, mid, jargs.data());
            if (env->ExceptionCheck()) {
                env->DeleteLocalRef(clazz);
                return;
            }
            break;
        case 'Z': case 'B': case 'C': case 'S': case 'I': {
            jint r;
            if (op == OP_INVOKESTATIC || op == OP_INVOKEDYNAMIC)
                r = env->CallStaticIntMethodA(clazz, mid, jargs.data());
            else if (op == OP_INVOKESPECIAL)
                r = env->CallNonvirtualIntMethodA(obj, clazz, mid, jargs.data());
            else
                r = env->CallIntMethodA(obj, mid, jargs.data());
            if (env->ExceptionCheck()) {
                env->DeleteLocalRef(clazz);
                return;
            }
            stack[sp++] = static_cast<int64_t>(r);
            break;
        }
        case 'J': {
            jlong r;
            if (op == OP_INVOKESTATIC || op == OP_INVOKEDYNAMIC)
                r = env->CallStaticLongMethodA(clazz, mid, jargs.data());
            else if (op == OP_INVOKESPECIAL)
                r = env->CallNonvirtualLongMethodA(obj, clazz, mid, jargs.data());
            else
                r = env->CallLongMethodA(obj, mid, jargs.data());
            if (env->ExceptionCheck()) {
                env->DeleteLocalRef(clazz);
                return;
            }
            stack[sp++] = static_cast<int64_t>(r);
            break;
        }
        case 'F': {
            jfloat r;
            if (op == OP_INVOKESTATIC || op == OP_INVOKEDYNAMIC)
                r = env->CallStaticFloatMethodA(clazz, mid, jargs.data());
            else if (op == OP_INVOKESPECIAL)
                r = env->CallNonvirtualFloatMethodA(obj, clazz, mid, jargs.data());
            else
                r = env->CallFloatMethodA(obj, mid, jargs.data());
            if (env->ExceptionCheck()) {
                env->DeleteLocalRef(clazz);
                return;
            }
            int32_t bits;
            std::memcpy(&bits, &r, sizeof(float));
            stack[sp++] = static_cast<int64_t>(bits);
            break;
        }
        case 'D': {
            jdouble r;
            if (op == OP_INVOKESTATIC || op == OP_INVOKEDYNAMIC)
                r = env->CallStaticDoubleMethodA(clazz, mid, jargs.data());
            else if (op == OP_INVOKESPECIAL)
                r = env->CallNonvirtualDoubleMethodA(obj, clazz, mid, jargs.data());
            else
                r = env->CallDoubleMethodA(obj, mid, jargs.data());
            if (env->ExceptionCheck()) {
                env->DeleteLocalRef(clazz);
                return;
            }
            int64_t bits;
            std::memcpy(&bits, &r, sizeof(double));
            stack[sp++] = bits;
            break;
        }
        default: {
            jobject r;
            if (op == OP_INVOKESTATIC || op == OP_INVOKEDYNAMIC)
                r = env->CallStaticObjectMethodA(clazz, mid, jargs.data());
            else if (op == OP_INVOKESPECIAL)
                r = env->CallNonvirtualObjectMethodA(obj, clazz, mid, jargs.data());
            else
                r = env->CallObjectMethodA(obj, mid, jargs.data());
            if (env->ExceptionCheck()) {
                env->DeleteLocalRef(clazz);
                return;
            }
            stack[sp++] = reinterpret_cast<int64_t>(r);
            break;
        }
    }
    env->DeleteLocalRef(clazz);
}

void init_key(uint64_t seed) {
    std::random_device rd;
    std::mt19937_64 gen(rd() ^ seed);
    KEY = gen();

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
        uint64_t mix = state ^ code[pc].nonce;
        uint8_t mapped = static_cast<uint8_t>(code[pc].op ^ static_cast<uint8_t>(mix));
        mapped ^= static_cast<uint8_t>(code[pc].nonce);
        mapped = inv_op_map2[mapped];
        OpCode op = inv_op_map[mapped];
        int64_t operand = code[pc].operand ^ static_cast<int64_t>(mix * 0x9E3779B97F4A7C15ULL);
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
        operand ^ static_cast<int64_t>(mix * 0x9E3779B97F4A7C15ULL),
        nonce
    };
}

int64_t execute(JNIEnv* env, const Instruction* code, size_t length,
                int64_t* locals, size_t locals_length, uint64_t seed) {
    int64_t stack[256];
    size_t sp = 0;
    size_t pc = 0;
    int64_t tmp = 0;
    uint64_t state = KEY ^ seed;
    OpCode op = OP_NOP;
    uint64_t mask = 0;

    goto dispatch; // start of the threaded interpreter

// Main dispatch loop
dispatch:
    if (env->ExceptionCheck()) goto halt;
    state = (state + KEY) ^ (KEY >> 3); // evolve state
    if (pc >= length) goto halt;
    // XOR promotes to int; cast back to uint8_t before converting to OpCode
    {
        uint64_t mix = state ^ code[pc].nonce;
        uint8_t mapped = static_cast<uint8_t>(code[pc].op ^ static_cast<uint8_t>(mix));
        mapped ^= static_cast<uint8_t>(code[pc].nonce);
        mapped = inv_op_map2[mapped];
        op = inv_op_map[mapped];
        tmp = code[pc].operand ^ static_cast<int64_t>(mix * 0x9E3779B97F4A7C15ULL);
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
        case OP_NOP:   goto junk;   // never executed by valid programs
        case OP_JUNK1: goto do_junk1;
        case OP_JUNK2: goto do_junk2;
        case OP_SWAP:  goto do_swap;
        case OP_DUP:   goto do_dup;
        case OP_POP:   goto do_pop;
        case OP_POP2:  goto do_pop2;
        case OP_DUP_X1: goto do_dup_x1;
        case OP_DUP_X2: goto do_dup_x2;
        case OP_DUP2:  goto do_dup2;
        case OP_DUP2_X1: goto do_dup2_x1;
        case OP_DUP2_X2: goto do_dup2_x2;
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
        case OP_BALOAD: goto do_baload;
        case OP_CALOAD: goto do_caload;
        case OP_SALOAD: goto do_saload;
        case OP_IASTORE: goto do_iastore;
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
        case OP_LDC:
        case OP_LDC_W:
        case OP_LDC2_W: goto do_push;
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
            jclass ex = env->FindClass("java/lang/ArithmeticException");
            if (ex) {
                env->ThrowNew(ex, "/ by zero");
                if (env->ExceptionCheck()) {
                    env->DeleteLocalRef(ex);
                    goto halt;
                }
                env->DeleteLocalRef(ex);
            }
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
    if (sp >= 1) --sp;
    goto dispatch;

do_pop2:
    if (sp >= 2) sp -= 2;
    goto dispatch;

do_dup_x1:
    if (sp >= 2 && sp < 256) {
        int64_t v1 = stack[sp - 1];
        int64_t v2 = stack[sp - 2];
        stack[sp - 2] = v1;
        stack[sp - 1] = v2;
        stack[sp++] = v1;
    }
    goto dispatch;

do_dup_x2:
    if (sp >= 3 && sp < 256) {
        int64_t v1 = stack[sp - 1];
        int64_t v2 = stack[sp - 2];
        int64_t v3 = stack[sp - 3];
        stack[sp - 3] = v1;
        stack[sp - 2] = v3;
        stack[sp - 1] = v2;
        stack[sp++] = v1;
    }
    goto dispatch;

do_dup2:
    if (sp >= 2 && sp < 255) {
        stack[sp] = stack[sp - 2];
        stack[sp + 1] = stack[sp - 1];
        sp += 2;
    }
    goto dispatch;

do_dup2_x1:
    if (sp >= 3 && sp < 255) {
        int64_t v1 = stack[sp - 1];
        int64_t v2 = stack[sp - 2];
        int64_t v3 = stack[sp - 3];
        stack[sp - 3] = v2;
        stack[sp - 2] = v1;
        stack[sp - 1] = v3;
        stack[sp] = v2;
        stack[sp + 1] = v1;
        sp += 2;
    }
    goto dispatch;

do_dup2_x2:
    if (sp >= 4 && sp < 255) {
        int64_t v1 = stack[sp - 1];
        int64_t v2 = stack[sp - 2];
        int64_t v3 = stack[sp - 3];
        int64_t v4 = stack[sp - 4];
        stack[sp - 4] = v2;
        stack[sp - 3] = v1;
        stack[sp - 2] = v4;
        stack[sp - 1] = v3;
        stack[sp] = v2;
        stack[sp + 1] = v1;
        sp += 2;
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
        auto* ts = reinterpret_cast<TableSwitch*>(tmp);
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
        auto* ls = reinterpret_cast<LookupSwitch*>(tmp);
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
        int64_t index = stack[--sp];
        jobjectArray arr = reinterpret_cast<jobjectArray>(stack[--sp]);
        jobject val = env->GetObjectArrayElement(arr, static_cast<jsize>(index));
        stack[sp++] = reinterpret_cast<int64_t>(val);
        env->DeleteLocalRef(val);
    }
    goto dispatch;

do_aastore:
    if (sp >= 3) {
        jobject value = reinterpret_cast<jobject>(stack[--sp]);
        jsize index = static_cast<jsize>(stack[--sp]);
        jobjectArray arr = reinterpret_cast<jobjectArray>(stack[--sp]);
        env->SetObjectArrayElement(arr, index, value);
    }
    goto dispatch;

do_iaload:
    if (sp >= 2) {
        jsize index = static_cast<jsize>(stack[--sp]);
        jintArray arr = reinterpret_cast<jintArray>(stack[--sp]);
        jint val;
        env->GetIntArrayRegion(arr, index, 1, &val);
        stack[sp++] = val;
    }
    goto dispatch;

do_baload:
    if (sp >= 2) {
        jsize index = static_cast<jsize>(stack[--sp]);
        jbyteArray arr = reinterpret_cast<jbyteArray>(stack[--sp]);
        jbyte val;
        env->GetByteArrayRegion(arr, index, 1, &val);
        stack[sp++] = val;
    }
    goto dispatch;

do_caload:
    if (sp >= 2) {
        jsize index = static_cast<jsize>(stack[--sp]);
        jcharArray arr = reinterpret_cast<jcharArray>(stack[--sp]);
        jchar val;
        env->GetCharArrayRegion(arr, index, 1, &val);
        stack[sp++] = val;
    }
    goto dispatch;

do_saload:
    if (sp >= 2) {
        jsize index = static_cast<jsize>(stack[--sp]);
        jshortArray arr = reinterpret_cast<jshortArray>(stack[--sp]);
        jshort val;
        env->GetShortArrayRegion(arr, index, 1, &val);
        stack[sp++] = val;
    }
    goto dispatch;

do_iastore:
    if (sp >= 3) {
        jint value = static_cast<jint>(stack[--sp]);
        jsize index = static_cast<jsize>(stack[--sp]);
        jintArray arr = reinterpret_cast<jintArray>(stack[--sp]);
        env->SetIntArrayRegion(arr, index, 1, &value);
    }
    goto dispatch;

do_bastore:
    if (sp >= 3) {
        jbyte value = static_cast<jbyte>(stack[--sp]);
        jsize index = static_cast<jsize>(stack[--sp]);
        jbyteArray arr = reinterpret_cast<jbyteArray>(stack[--sp]);
        env->SetByteArrayRegion(arr, index, 1, &value);
    }
    goto dispatch;

do_castore:
    if (sp >= 3) {
        jchar value = static_cast<jchar>(stack[--sp]);
        jsize index = static_cast<jsize>(stack[--sp]);
        jcharArray arr = reinterpret_cast<jcharArray>(stack[--sp]);
        env->SetCharArrayRegion(arr, index, 1, &value);
    }
    goto dispatch;

do_sastore:
    if (sp >= 3) {
        jshort value = static_cast<jshort>(stack[--sp]);
        jsize index = static_cast<jsize>(stack[--sp]);
        jshortArray arr = reinterpret_cast<jshortArray>(stack[--sp]);
        env->SetShortArrayRegion(arr, index, 1, &value);
    }
    goto dispatch;

do_new:
    if (sp < 256) {
        const char* name = reinterpret_cast<const char*>(tmp);
        jclass clazz = env->FindClass(name);
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
        jclass clazz = env->FindClass(name);
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
        jint dims = static_cast<jint>(tmp & 0xFFFFFFFF);
        const char* name = reinterpret_cast<const char*>(tmp >> 32);
        std::vector<jint> sizes(dims);
        for (int i = dims - 1; i >= 0 && sp > 0; --i) {
            sizes[i] = static_cast<jint>(stack[--sp]);
        }
        jobjectArray arr = nullptr;
        if (dims == 1) {
            jclass clazz = env->FindClass(name);
            if (clazz) {
                arr = env->NewObjectArray(sizes[0], clazz, nullptr);
                env->DeleteLocalRef(clazz);
            }
        } else if (dims > 1) {
            jclass clazz = env->FindClass(name);
            if (clazz) {
                arr = env->NewObjectArray(sizes[0], clazz, nullptr);
                env->DeleteLocalRef(clazz);
            }
        }
        stack[sp++] = reinterpret_cast<int64_t>(arr);
    }
    goto dispatch;

do_checkcast:
    if (sp >= 1) {
        jobject obj = reinterpret_cast<jobject>(stack[sp - 1]);
        if (obj != nullptr) {
            const char* name = reinterpret_cast<const char*>(tmp);
            jclass clazz = env->FindClass(name);
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
        jclass clazz = env->FindClass(name);
        jboolean res = obj && clazz && env->IsInstanceOf(obj, clazz);
        if (clazz) env->DeleteLocalRef(clazz);
        stack[sp++] = res ? 1 : 0;
    }
    goto dispatch;

do_getstatic:
    if (sp < 256) {
        auto* ref = reinterpret_cast<FieldRef*>(tmp);
        jclass clazz = get_cached_class(env, ref->class_name);
        if (clazz) {
            jfieldID fid = env->GetStaticFieldID(clazz, ref->field_name, ref->field_sig);
            if (fid) {
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
            env->DeleteLocalRef(clazz);
        }
    }
    goto dispatch;

do_putstatic:
    if (sp >= 1) {
        auto* ref = reinterpret_cast<FieldRef*>(tmp);
        jclass clazz = get_cached_class(env, ref->class_name);
        if (clazz) {
            jfieldID fid = env->GetStaticFieldID(clazz, ref->field_name, ref->field_sig);
            if (fid) {
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
                --sp; // consume value even if fid not found
            }
            env->DeleteLocalRef(clazz);
        } else {
            --sp;
        }
    }
    goto dispatch;

do_getfield:
    if (sp >= 1 && sp < 256) {
        auto* ref = reinterpret_cast<FieldRef*>(tmp);
        jobject obj = reinterpret_cast<jobject>(stack[--sp]);
        if (!obj) {
            jclass npe = env->FindClass("java/lang/NullPointerException");
            if (npe) {
                env->ThrowNew(npe, "null");
                if (env->ExceptionCheck()) {
                    env->DeleteLocalRef(npe);
                    goto halt;
                }
                env->DeleteLocalRef(npe);
            }
            goto halt;
        }
        jclass clazz = get_cached_class(env, ref->class_name);
        if (clazz) {
            jfieldID fid = env->GetFieldID(clazz, ref->field_name, ref->field_sig);
            if (fid) {
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
            env->DeleteLocalRef(clazz);
        }
    }
    goto dispatch;

do_putfield:
    if (sp >= 2) {
        auto* ref = reinterpret_cast<FieldRef*>(tmp);
        int64_t value = stack[--sp];
        jobject obj = reinterpret_cast<jobject>(stack[--sp]);
        if (!obj) {
            jclass npe = env->FindClass("java/lang/NullPointerException");
            if (npe) {
                env->ThrowNew(npe, "null");
                if (env->ExceptionCheck()) {
                    env->DeleteLocalRef(npe);
                    goto halt;
                }
                env->DeleteLocalRef(npe);
            }
            goto halt;
        }
        jclass clazz = get_cached_class(env, ref->class_name);
        if (clazz) {
            jfieldID fid = env->GetFieldID(clazz, ref->field_name, ref->field_sig);
            if (fid) {
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
            env->DeleteLocalRef(clazz);
        }
    } else {
        sp = 0;
    }
    goto dispatch;

do_invokestatic:
    invoke_method(env, OP_INVOKESTATIC, reinterpret_cast<MethodRef*>(tmp), stack, sp);
    goto dispatch;

do_invokevirtual:
    invoke_method(env, OP_INVOKEVIRTUAL, reinterpret_cast<MethodRef*>(tmp), stack, sp);
    goto dispatch;

do_invokespecial:
    invoke_method(env, OP_INVOKESPECIAL, reinterpret_cast<MethodRef*>(tmp), stack, sp);
    goto dispatch;

do_invokeinterface:
    invoke_method(env, OP_INVOKEINTERFACE, reinterpret_cast<MethodRef*>(tmp), stack, sp);
    goto dispatch;

do_invokedynamic:
    invoke_method(env, OP_INVOKEDYNAMIC, reinterpret_cast<MethodRef*>(tmp), stack, sp);
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
                    int64_t* locals, size_t locals_length, uint64_t seed) {
    ensure_init(seed);
    auto it = jit_cache.find(code);
    if (it != jit_cache.end()) {
        return it->second.func(env, locals, locals_length, seed, it->second.ctx);
    }
    size_t& cnt = exec_counts[code];
    if (++cnt > HOT_THRESHOLD) {
        JitCompiled compiled = compile(code, length, seed);
        it = jit_cache.emplace(code, compiled).first;
        return it->second.func(env, locals, locals_length, seed, it->second.ctx);
    }
    return execute(env, code, length, locals, locals_length, seed);
}

static int64_t execute_variant(JNIEnv* env, const Instruction* code, size_t length,
                               int64_t* locals, size_t locals_length, uint64_t seed) {
    volatile uint64_t noise = KEY ^ seed;
    noise ^= noise << 13;
    // noise is intentionally unused to introduce a distinct entry
    return execute(env, code, length, locals, locals_length, seed);
}

int64_t run_arith_vm(JNIEnv* env, OpCode op, int64_t lhs, int64_t rhs, uint64_t seed) {
    ensure_init(seed);
    std::vector<Instruction> program;
    program.reserve(16);
    uint64_t state = KEY ^ seed;
    std::mt19937_64 rng(KEY ^ (seed << 1));

    auto emit = [&](OpCode opcode, int64_t operand) {
        state = (state + KEY) ^ (KEY >> 3);
        uint64_t nonce = rng() ^ state;
        program.push_back(encode(opcode, operand, state, nonce));
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

    using ExecFn = int64_t(*)(JNIEnv*, const Instruction*, size_t, int64_t*, size_t, uint64_t);
    std::uniform_int_distribution<int> entry_dist(0, 1);
    ExecFn entries[2] = {execute, execute_variant};
    return entries[entry_dist(rng)](env, program.data(), program.size(), nullptr, 0, seed);
}

int64_t run_unary_vm(JNIEnv* env, OpCode op, int64_t value, uint64_t seed) {
    ensure_init(seed);
    std::vector<Instruction> program;
    program.reserve(8);
    uint64_t state = KEY ^ seed;
    std::mt19937_64 rng(KEY ^ (seed << 1));

    auto emit = [&](OpCode opcode, int64_t operand) {
        state = (state + KEY) ^ (KEY >> 3);
        uint64_t nonce = rng() ^ state;
        program.push_back(encode(opcode, operand, state, nonce));
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

    return execute(env, program.data(), program.size(), nullptr, 0, seed);
}

} // namespace native_jvm::vm
// NOLINTEND
