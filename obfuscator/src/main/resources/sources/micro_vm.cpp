#include "micro_vm.hpp"
#include <iostream>

// NOLINTBEGIN - obfuscated control flow by design
namespace native_jvm::vm {

static constexpr uint64_t KEY = 0x5F3759DFB3E8A1C5ULL; // mixed 32/64-bit key

Instruction encode(OpCode op, int64_t operand, uint64_t seed) {
    static uint64_t state = 0;
    static uint64_t state2 = 0;
    static uint64_t last_seed = ~0ULL;
    if (seed != last_seed) {
        state = KEY ^ seed;
        state2 = (KEY << 7) | (seed >> 3);
        last_seed = seed;
    }
    uint8_t mix = static_cast<uint8_t>(state ^ (state2 >> 24));
    Instruction inst{
        static_cast<uint8_t>(static_cast<uint8_t>(op) ^ mix),
        operand ^ static_cast<int64_t>((state + state2) * 0x9E3779B97F4A7C15ULL)
    };
    state = (state + KEY) ^ (state2 >> 3);
    state2 = (state2 ^ KEY) + (state << 1);
    return inst;
}

void execute(const Instruction* code, size_t length, uint64_t seed) {
    int64_t stack[256];
    size_t sp = 0;
    size_t pc = 0;
    int64_t tmp = 0;
    uint64_t state = KEY ^ seed;
    uint64_t state2 = (KEY << 7) | (seed >> 3); // extra evolving state
    OpCode op = OP_NOP;

    goto dispatch; // start of the threaded interpreter

// Main dispatch loop
dispatch:
    state = (state + KEY) ^ (state2 >> 3); // evolve states
    state2 = (state2 ^ KEY) + (state << 1);
    uint8_t mix;
    if (pc >= length) goto halt;
    mix = static_cast<uint8_t>(state ^ (state2 >> 24));
    // XOR promotes to int; cast back to uint8_t before converting to OpCode
    op = static_cast<OpCode>(static_cast<uint8_t>(code[pc].op ^ mix));
    tmp = code[pc].operand ^ static_cast<int64_t>((state + state2) * 0x9E3779B97F4A7C15ULL);
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
        if (b != 0) stack[sp - 2] /= b;
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
    state2 += state ^ (KEY >> 5);
    goto dispatch;

// Exit point
halt:
    return;
}

} // namespace native_jvm::vm
// NOLINTEND
