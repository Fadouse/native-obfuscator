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
    OP_POP   = 12, // pop top value from stack
    OP_POP2  = 13, // pop top one or two values from stack
    OP_LOAD  = 14, // load local variable onto the stack
    OP_IF_ICMPEQ = 15, // compare two ints and jump if equal
    OP_IF_ICMPNE = 16, // compare two ints and jump if not equal
    OP_GOTO = 17, // unconditional jump
    OP_STORE = 18, // store top of stack into local variable
    OP_AND   = 19, // bitwise and
    OP_OR    = 20, // bitwise or
    OP_XOR   = 21, // bitwise xor
    OP_SHL   = 22, // shift left
    OP_SHR   = 23, // arithmetic shift right
    OP_USHR  = 24, // logical shift right
    OP_IF_ICMPLT = 25, // compare two ints and jump if less than
    OP_IF_ICMPLE = 26, // compare two ints and jump if <=
    OP_IF_ICMPGT = 27, // compare two ints and jump if >
    OP_IF_ICMPGE = 28, // compare two ints and jump if >=
    OP_I2L  = 29, // convert int to long
    OP_I2B  = 30, // convert int to byte
    OP_I2C  = 31, // convert int to char
    OP_I2S  = 32, // convert int to short
    OP_NEG  = 33, // negate int
    OP_ALOAD = 34, // load object local
    OP_ASTORE = 35, // store object local
    OP_AALOAD = 36, // load from object array
    OP_AASTORE = 37, // store into object array
    OP_INVOKESTATIC = 38, // invoke static java method (simplified)
    OP_LLOAD = 39, // load long local
    OP_FLOAD = 40, // load float local
    OP_DLOAD = 41, // load double local
    OP_LSTORE = 42, // store long local
    OP_FSTORE = 43, // store float local
    OP_DSTORE = 44, // store double local
    OP_LADD = 45, // long add
    OP_LSUB = 46, // long sub
    OP_LMUL = 47, // long mul
    OP_LDIV = 48, // long div
    OP_FADD = 49, // float add
    OP_FSUB = 50, // float sub
    OP_FMUL = 51, // float mul
    OP_FDIV = 52, // float div
    OP_DADD = 53, // double add
    OP_DSUB = 54, // double sub
    OP_DMUL = 55, // double mul
    OP_DDIV = 56, // double div
    OP_LDC = 57, // load constant (int/float)
    OP_LDC_W = 58, // load wide constant (int/float)
    OP_LDC2_W = 59, // load long/double constant
    OP_FCONST_0 = 60, // push float 0.0
    OP_FCONST_1 = 61, // push float 1.0
    OP_FCONST_2 = 62, // push float 2.0
    OP_DCONST_0 = 63, // push double 0.0
    OP_DCONST_1 = 64, // push double 1.0
    OP_LCONST_0 = 65, // push long 0
    OP_LCONST_1 = 66, // push long 1
    OP_IINC = 67,  // increment int local by constant
    OP_LAND = 68,  // long bitwise and
    OP_LOR  = 69,  // long bitwise or
    OP_LXOR = 70,  // long bitwise xor
    OP_LSHL = 71,  // long shift left
    OP_LSHR = 72,  // long arithmetic shift right
    OP_LUSHR = 73, // long logical shift right
    OP_I2F = 74,   // convert int to float
    OP_I2D = 75,   // convert int to double
    OP_L2I = 76,   // convert long to int
    OP_L2F = 77,   // convert long to float
    OP_L2D = 78,   // convert long to double
    OP_F2I = 79,   // convert float to int
    OP_F2L = 80,   // convert float to long
    OP_F2D = 81,   // convert float to double
    OP_D2I = 82,   // convert double to int
    OP_D2L = 83,   // convert double to long
    OP_D2F = 84,   // convert double to float
    OP_IALOAD = 85, // load from int array
    OP_LALOAD = 86, // load from long array
    OP_FALOAD = 87, // load from float array
    OP_DALOAD = 88, // load from double array
    OP_BALOAD = 89, // load from byte array
    OP_CALOAD = 90, // load from char array
    OP_SALOAD = 91, // load from short array
    OP_IASTORE = 92, // store into int array
    OP_LASTORE = 93, // store into long array
    OP_FASTORE = 94, // store into float array
    OP_DASTORE = 95, // store into double array
    OP_BASTORE = 96, // store into byte array
    OP_CASTORE = 97, // store into char array
    OP_SASTORE = 98, // store into short array
    OP_NEW = 99, // allocate object
    OP_ANEWARRAY = 100, // allocate object array
    OP_NEWARRAY = 101, // allocate primitive array
    OP_MULTIANEWARRAY = 102, // allocate multi-dimensional array
    OP_CHECKCAST = 103, // perform checkcast
    OP_INSTANCEOF = 104, // perform instanceof
    OP_GETSTATIC = 105, // read static field
    OP_PUTSTATIC = 106, // write static field
    OP_GETFIELD = 107, // read instance field
    OP_PUTFIELD = 108, // write instance field
    OP_INVOKEVIRTUAL = 109, // invoke virtual method
    OP_INVOKESPECIAL = 110, // invoke special method
    OP_INVOKEINTERFACE = 111, // invoke interface method
    OP_INVOKEDYNAMIC = 112, // invoke dynamic call site
    OP_IFNULL = 113,       // jump if reference is null
    OP_IFNONNULL = 114,    // jump if reference not null
    OP_IF_ACMPEQ = 115,    // compare refs and jump if equal
    OP_IF_ACMPNE = 116,    // compare refs and jump if not equal
    OP_TABLESWITCH = 117,  // jump using table switch
    OP_LOOKUPSWITCH = 118, // jump using lookup switch
    OP_GOTO_W = 119,       // wide unconditional jump
    OP_IFNULL_W = 120,     // wide null check
    OP_IFNONNULL_W = 121,  // wide non-null check
    OP_IF_ACMPEQ_W = 122,  // wide reference compare eq
    OP_IF_ACMPNE_W = 123,  // wide reference compare ne
    OP_IF_ICMPEQ_W = 124,  // wide int compare eq
    OP_IF_ICMPNE_W = 125,  // wide int compare ne
    OP_IF_ICMPLT_W = 126,  // wide int compare lt
    OP_IF_ICMPLE_W = 127,  // wide int compare le
    OP_IF_ICMPGT_W = 128,  // wide int compare gt
    OP_IF_ICMPGE_W = 129,  // wide int compare ge
    OP_DUP_X1 = 130,       // duplicate top value and insert below second value
    OP_DUP_X2 = 131,       // duplicate top value and insert below third value
    OP_DUP2 = 132,         // duplicate top two values
    OP_DUP2_X1 = 133,      // duplicate top two values and insert below third value
    OP_DUP2_X2 = 134,      // duplicate top two values and insert below fourth/fifth value
    OP_ATHROW = 135,       // throw exception from stack top
    OP_TRY_START = 136,    // start try block and setup exception handling
    OP_CATCH_HANDLER = 137,// exception catch handler jump
    OP_FINALLY_HANDLER = 138, // finally block handler
    OP_EXCEPTION_CHECK = 139, // check if exception occurred and handle
    OP_EXCEPTION_CLEAR = 140, // clear pending exception
    OP_COUNT = 141         // helper constant with number of opcodes
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

struct MethodRef {
    const char* class_name;
    const char* method_name;
    const char* method_sig;
};

struct ConstantPoolEntry {
    enum Type : uint8_t {
        TYPE_INTEGER = 0,
        TYPE_FLOAT = 1,
        TYPE_LONG = 2,
        TYPE_DOUBLE = 3,
        TYPE_STRING = 4,
        TYPE_CLASS = 5,
        TYPE_METHOD_HANDLE = 6,
        TYPE_METHOD_TYPE = 7
    } type;

    union {
        int32_t i_value;
        float f_value;
        int64_t l_value;
        double d_value;
        const char* str_value;
        const char* class_name;
    };
};

struct TableSwitch {
    int32_t low;
    int32_t high;
    size_t default_target;
    const size_t* targets;
};

struct LookupSwitch {
    int32_t count;
    const int32_t* keys;
    const size_t* targets;
    size_t default_target;
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
                int64_t* locals, size_t locals_length, uint64_t seed,
                const ConstantPoolEntry* constant_pool = nullptr, size_t constant_pool_size = 0);

// JIT-enabled variant that caches translated machine code for hot sequences
// and executes them directly. Falls back to the interpreter for cold code.
int64_t execute_jit(JNIEnv* env, const Instruction* code, size_t length,
                    int64_t* locals, size_t locals_length, uint64_t seed,
                    const ConstantPoolEntry* constant_pool = nullptr, size_t constant_pool_size = 0);

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

