#include "micro_vm.hpp"
#include <iostream>
#include <random>
#include <algorithm>
#include <array>
#include <vector>

// NOLINTBEGIN - obfuscated control flow by design
namespace native_jvm::vm {

static uint64_t KEY = 0;
static std::array<uint8_t, OP_COUNT> op_map{};     // maps logical opcodes to shuffled values
static std::array<OpCode, OP_COUNT> inv_op_map{}; // reverse map

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
}

Instruction encode(OpCode op, int64_t operand, uint64_t key, uint64_t nonce) {
    uint8_t mapped = op_map[static_cast<uint8_t>(op)];
    uint64_t mix = key ^ nonce;
    return Instruction{
        static_cast<uint8_t>(mapped ^ static_cast<uint8_t>(mix)),
        operand ^ static_cast<int64_t>(mix * 0x9E3779B97F4A7C15ULL),
        nonce
    };
}

int64_t execute(JNIEnv* env, const Instruction* code, size_t length,
                const int64_t* locals, size_t locals_length, uint64_t seed) {
    int64_t stack[256];
    size_t sp = 0;
    size_t pc = 0;
    int64_t tmp = 0;
    uint64_t state = KEY ^ seed;
    OpCode op = OP_NOP;

    goto dispatch; // start of the threaded interpreter

// Main dispatch loop
dispatch:
    state = (state + KEY) ^ (KEY >> 3); // evolve state
    if (pc >= length) goto halt;
    // XOR promotes to int; cast back to uint8_t before converting to OpCode
    {
        uint64_t mix = state ^ code[pc].nonce;
        uint8_t mapped = static_cast<uint8_t>(code[pc].op ^ static_cast<uint8_t>(mix));
        op = inv_op_map[mapped];
        tmp = code[pc].operand ^ static_cast<int64_t>(mix * 0x9E3779B97F4A7C15ULL);
    }
    ++pc;
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
        case OP_IF_ICMPEQ: goto do_if_icmpeq;
        case OP_IF_ICMPNE: goto do_if_icmpne;
        case OP_GOTO:  goto do_goto;
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

// Dummy branch used only to confuse decompilers
junk:
    state ^= KEY << 7;
    goto dispatch;

// Exit point
halt:
    return (sp > 0) ? stack[sp - 1] : 0;
}

void encode_program(Instruction* code, size_t length, uint64_t seed) {
    uint64_t state = KEY ^ seed;
    std::mt19937_64 rng(KEY ^ (seed << 1));
    for (size_t i = 0; i < length; ++i) {
        state = (state + KEY) ^ (KEY >> 3);
        uint64_t nonce = rng();
        code[i] = encode(static_cast<OpCode>(code[i].op), code[i].operand, state, nonce);
    }
}

int64_t run_arith_vm(JNIEnv* env, OpCode op, int64_t lhs, int64_t rhs, uint64_t seed) {
    std::vector<Instruction> program;
    program.reserve(16);
    uint64_t state = KEY ^ seed;
    std::mt19937_64 rng(KEY ^ (seed << 1));

    auto emit = [&](OpCode opcode, int64_t operand) {
        state = (state + KEY) ^ (KEY >> 3);
        uint64_t nonce = rng();
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

    emit(OP_PUSH, lhs);
    emit_junk();
    emit(OP_PUSH, rhs);
    emit_junk();
    emit(op, 0);
    emit_junk();
    emit(OP_HALT, 0);

    return execute(env, program.data(), program.size(), nullptr, 0, seed);
}

} // namespace native_jvm::vm
// NOLINTEND
