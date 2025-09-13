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
    OP_ALOAD = 32, // load object local
    OP_ASTORE = 33, // store object local
    OP_AALOAD = 34, // load from object array
    OP_AASTORE = 35, // store into object array
    OP_INVOKESTATIC = 36, // invoke static java method (simplified)
    OP_LLOAD = 37, // load long local
    OP_FLOAD = 38, // load float local
    OP_DLOAD = 39, // load double local
    OP_LSTORE = 40, // store long local
    OP_FSTORE = 41, // store float local
    OP_DSTORE = 42, // store double local
    OP_LADD = 43, // long add
    OP_LSUB = 44, // long sub
    OP_LMUL = 45, // long mul
    OP_LDIV = 46, // long div
    OP_FADD = 47, // float add
    OP_FSUB = 48, // float sub
    OP_FMUL = 49, // float mul
    OP_FDIV = 50, // float div
    OP_DADD = 51, // double add
    OP_DSUB = 52, // double sub
    OP_DMUL = 53, // double mul
    OP_DDIV = 54, // double div
    OP_LDC = 55, // load constant (int/float)
    OP_LDC_W = 56, // load wide constant (int/float)
    OP_LDC2_W = 57, // load long/double constant
    OP_FCONST_0 = 58, // push float 0.0
    OP_FCONST_1 = 59, // push float 1.0
    OP_FCONST_2 = 60, // push float 2.0
    OP_DCONST_0 = 61, // push double 0.0
    OP_DCONST_1 = 62, // push double 1.0
    OP_LCONST_0 = 63, // push long 0
    OP_LCONST_1 = 64, // push long 1
    OP_IINC = 65,  // increment int local by constant
    OP_LAND = 66,  // long bitwise and
    OP_LOR  = 67,  // long bitwise or
    OP_LXOR = 68,  // long bitwise xor
    OP_LSHL = 69,  // long shift left
    OP_LSHR = 70,  // long arithmetic shift right
    OP_LUSHR = 71, // long logical shift right
    OP_I2F = 72,   // convert int to float
    OP_I2D = 73,   // convert int to double
    OP_L2I = 74,   // convert long to int
    OP_L2F = 75,   // convert long to float
    OP_L2D = 76,   // convert long to double
    OP_F2I = 77,   // convert float to int
    OP_F2L = 78,   // convert float to long
    OP_F2D = 79,   // convert float to double
    OP_D2I = 80,   // convert double to int
    OP_D2L = 81,   // convert double to long
    OP_D2F = 82,   // convert double to float
    OP_IALOAD = 83, // load from int array
    OP_BALOAD = 84, // load from byte array
    OP_CALOAD = 85, // load from char array
    OP_SALOAD = 86, // load from short array
    OP_IASTORE = 87, // store into int array
    OP_BASTORE = 88, // store into byte array
    OP_CASTORE = 89, // store into char array
    OP_SASTORE = 90, // store into short array
    OP_NEW = 91, // allocate object
    OP_ANEWARRAY = 92, // allocate object array
    OP_NEWARRAY = 93, // allocate primitive array
    OP_MULTIANEWARRAY = 94, // allocate multi-dimensional array
    OP_CHECKCAST = 95, // perform checkcast
    OP_INSTANCEOF = 96, // perform instanceof
    OP_GETSTATIC = 97, // read static field
    OP_PUTSTATIC = 98, // write static field
    OP_GETFIELD = 99, // read instance field
    OP_PUTFIELD = 100, // write instance field
    OP_COUNT = 101  // helper constant with number of opcodes
};

// Every field of an instruction is lightly encrypted and decoded at
// runtime.  This makes it significantly harder to recover the bytecode
// statically.
struct Instruction {
    uint8_t op;      // encrypted opcode
    int64_t operand; // encrypted operand
    uint64_t nonce;  // per-instruction random nonce
};

struct FieldRef {
    const char* class_name;
    const char* field_name;
    const char* field_sig;
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

// JIT-enabled variant that caches translated machine code for hot sequences
// and executes them directly. Falls back to the interpreter for cold code.
int64_t execute_jit(JNIEnv* env, const Instruction* code, size_t length,
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

void clear_class_cache(JNIEnv* env);
size_t get_class_cache_calls();

} // namespace native_jvm::vm

// NOLINTEND

