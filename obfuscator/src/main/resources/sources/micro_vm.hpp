// NOLINTBEGIN - this file intentionally contains unusual control flow for obfuscation purposes
#pragma once
#include <cstdint>
#include <cstddef>
#include <jni.h>

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
    OP_NOP   = 7,  // never used, keeps the decoder busy
    OP_JUNK1 = 8,  // pseudo-op operating on temp only
    OP_JUNK2 = 9,  // another harmless operation
    OP_SWAP  = 10, // swap two top stack values
    OP_DUP   = 11, // duplicate top stack value
    OP_LOAD  = 12, // load local variable onto the stack
    OP_IF_ICMPEQ = 13, // compare two ints and jump if equal
    OP_IF_ICMPNE = 14, // compare two ints and jump if not equal
    OP_GOTO = 15, // unconditional jump
    OP_STORE = 16, // store top of stack into local variable
    OP_AND   = 17, // bitwise and
    OP_OR    = 18, // bitwise or
    OP_XOR   = 19, // bitwise xor
    OP_SHL   = 20, // shift left
    OP_SHR   = 21, // arithmetic shift right
    OP_USHR  = 22, // logical shift right
    OP_IF_ICMPLT = 23, // compare two ints and jump if less than
    OP_IF_ICMPLE = 24, // compare two ints and jump if <=
    OP_IF_ICMPGT = 25, // compare two ints and jump if >
    OP_IF_ICMPGE = 26, // compare two ints and jump if >=
    OP_I2L  = 27, // convert int to long
    OP_I2B  = 28, // convert int to byte
    OP_I2C  = 29, // convert int to char
    OP_I2S  = 30, // convert int to short
    OP_NEG  = 31, // negate int
    OP_IALOAD = 32, // load int from array
    OP_IASTORE = 33, // store int into array
    OP_CALL = 34, // invoke static int method with one int arg
    OP_COUNT = 35  // helper constant with number of opcodes
};

// Every field of an instruction is lightly encrypted and decoded at
// runtime.  This makes it significantly harder to recover the bytecode
// statically.
struct Instruction {
    uint8_t op;      // encrypted opcode
    int64_t operand; // encrypted operand
    uint64_t nonce;  // per-instruction random nonce
};

// Helper that produces an encoded instruction using the global key.
Instruction encode(OpCode op, int64_t operand, uint64_t key, uint64_t nonce);

// Initializes the global KEY used for encoding/decoding instructions.
// Must be called before executing any VM code.
void init_key(uint64_t seed);

// Executes a program encoded as an array of Instructions.  The
// interpreter uses a stack based execution model and performs dynamic
// decoding of every instruction.  The return value is the top of the
// stack after the program halts which allows host code to retrieve
// computed values. Locals should point to an array of initial local
// variables for OP_LOAD/OP_STORE instructions.
int64_t execute(JNIEnv* env, const Instruction* code, size_t length,
                int64_t* locals, size_t locals_length, uint64_t seed);

// Encodes a program in-place using the internal key so that it can be
// executed by the VM.  The seed should be the same value passed to
// execute.
void encode_program(Instruction* code, size_t length, uint64_t seed);

// Helper utility used by the obfuscator to perform simple arithmetic
// through the VM.  It encodes a tiny program that evaluates
//    result = lhs (op) rhs
// for one of the arithmetic operations and returns the computed value.
int64_t run_arith_vm(JNIEnv* env, OpCode op, int64_t lhs, int64_t rhs, uint64_t seed);

// Executes a unary operation (conversion or negation) through the VM.
int64_t run_unary_vm(JNIEnv* env, OpCode op, int64_t value, uint64_t seed);

} // namespace native_jvm::vm

// NOLINTEND

