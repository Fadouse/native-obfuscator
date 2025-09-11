// NOLINTBEGIN - this file intentionally contains unusual control flow for obfuscation purposes
#pragma once
#include <cstdint>
#include <cstddef>

namespace native_jvm::vm {

// Simple instruction set for the micro VM.  The values are still
// sequential to keep the encoder small, but an additional dummy
// instruction is introduced to complicate static analysis.
enum OpCode : uint8_t {
    OP_PUSH  = 0,
    OP_ADD   = 1,
    OP_SUB   = 2,
    OP_MUL   = 3,
    OP_DIV   = 4,
    OP_PRINT = 5,
    OP_HALT  = 6,
    OP_NOP   = 7, // never used, keeps the decoder busy
};

// Every field of an instruction is lightly encrypted and decoded at
// runtime.  This makes it significantly harder to recover the bytecode
// statically.
struct Instruction {
    uint8_t op;      // encrypted opcode
    int64_t operand; // encrypted operand
};

// Helper that produces an encoded instruction using the same state
// evolution as the runtime interpreter. The seed must match the value
// passed to execute().
Instruction encode(OpCode op, int64_t operand, uint64_t seed);

// Executes a program encoded as an array of Instructions.  The interpreter
// uses a stack based execution model with two evolving internal state
// registers.  Every instruction is decoded dynamically which complicates
// static analysis of the resulting native code.
void execute(const Instruction* code, size_t length, uint64_t seed = 0);

} // namespace native_jvm::vm

// NOLINTEND

