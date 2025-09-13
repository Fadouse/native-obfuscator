#include "micro_vm.hpp"
#include "vm_jit.hpp"
#include <iostream>
#include <random>
#include <algorithm>
#include <array>
#include <vector>
#include <unordered_map>

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
        case OP_LOAD:  goto do_load;
        case OP_STORE: goto do_store;
        case OP_IF_ICMPEQ: goto do_if_icmpeq;
        case OP_IF_ICMPNE: goto do_if_icmpne;
        case OP_GOTO:  goto do_goto;
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
        case OP_NEG: goto do_neg;
        case OP_ALOAD: goto do_aload;
        case OP_ASTORE: goto do_astore;
        case OP_AALOAD: goto do_aaload;
        case OP_AASTORE: goto do_aastore;
        case OP_INVOKESTATIC: goto do_invokestatic;
        default:       goto halt;
    }

// Actual operations
// Each block returns to dispatch via an explicit goto to hide
// structured control-flow patterns from static analysis.
do_push:
    if (sp < 256) stack[sp++] = tmp;
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

do_invokestatic:
    // simplified: treat as identity function on top of stack
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
