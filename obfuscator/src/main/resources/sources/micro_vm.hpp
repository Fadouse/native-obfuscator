#pragma once
#include <cstdint>
#include <cstddef>

namespace native_jvm::vm {

// Simple instruction set for the micro VM
enum OpCode : uint8_t {
    OP_PUSH = 0,
    OP_ADD = 1,
    OP_SUB = 2,
    OP_MUL = 3,
    OP_DIV = 4,
    OP_PRINT = 5,
    OP_HALT = 6,
};

struct Instruction {
    OpCode op;
    int32_t operand;
};

// Executes a program encoded as an array of Instructions.
// The interpreter uses a stack based execution model.
void execute(const Instruction* code, size_t length);

} // namespace native_jvm::vm

