#include "micro_vm.hpp"
#include <iostream>

// NOLINTBEGIN - obfuscated control flow by design
namespace native_jvm::vm {

static constexpr uint64_t KEY = 0x5F3759DFB3E8A1C5ULL; // mixed 32/64-bit key

Instruction encode(OpCode op, int64_t operand, uint64_t key) {
    return Instruction{
        static_cast<uint8_t>(static_cast<uint8_t>(op) ^ static_cast<uint8_t>(key)),
        operand ^ static_cast<int64_t>(key * 0x9E3779B97F4A7C15ULL)
    };
}

int64_t execute(JNIEnv* env, const Instruction* code, size_t length, uint64_t seed) {
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
    op = static_cast<OpCode>(static_cast<uint8_t>(code[pc].op ^ static_cast<uint8_t>(state)));
    tmp = code[pc].operand ^ static_cast<int64_t>(state * 0x9E3779B97F4A7C15ULL);
    ++pc;
    switch (op) {
        case OP_PUSH:  goto do_push;
        case OP_ADD:   goto do_add;
        case OP_SUB:   goto do_sub;
        case OP_MUL:   goto do_mul;
        case OP_DIV:   goto do_div;
        case OP_PRINT: goto do_print;
        case OP_NOP:   goto junk; // never executed by valid programs
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

// Dummy branch used only to confuse decompilers
junk:
    state ^= KEY << 7;
    goto dispatch;

// Exit point
halt:
    return (sp > 0) ? stack[sp - 1] : 0;
}

int64_t run_arith_vm(JNIEnv* env, OpCode op, int64_t lhs, int64_t rhs, uint64_t seed) {
    Instruction program[4];
    uint64_t state = KEY ^ seed;

    // Encode PUSH lhs
    state = (state + KEY) ^ (KEY >> 3);
    program[0] = encode(OP_PUSH, lhs, state);
    // Encode PUSH rhs
    state = (state + KEY) ^ (KEY >> 3);
    program[1] = encode(OP_PUSH, rhs, state);
    // Encode operation
    state = (state + KEY) ^ (KEY >> 3);
    program[2] = encode(op, 0, state);
    // Encode HALT
    state = (state + KEY) ^ (KEY >> 3);
    program[3] = encode(OP_HALT, 0, state);

    return execute(env, program, 4, seed);
}

} // namespace native_jvm::vm
// NOLINTEND
