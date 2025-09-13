#include "vm_jit.hpp"
#include <iostream>

namespace native_jvm::vm {

struct Program {
    std::vector<DecodedInstruction> ins;
};

static int64_t run_program(JNIEnv* env, int64_t* locals, size_t locals_len,
                           uint64_t /*seed*/, void* ctx) {
    auto* prog = reinterpret_cast<Program*>(ctx);
    int64_t stack[256];
    size_t sp = 0;
    size_t pc = 0;
    while (pc < prog->ins.size()) {
        const auto& ins = prog->ins[pc++];
        switch (ins.op) {
            case OP_PUSH:
                if (sp < 256) stack[sp++] = ins.operand;
                break;
            case OP_ADD:
                if (sp >= 2) { stack[sp-2] += stack[sp-1]; --sp; }
                break;
            case OP_SUB:
                if (sp >= 2) { stack[sp-2] -= stack[sp-1]; --sp; }
                break;
            case OP_MUL:
                if (sp >= 2) { stack[sp-2] *= stack[sp-1]; --sp; }
                break;
            case OP_DIV:
                if (sp >= 2) {
                    int64_t b = stack[--sp];
                    int64_t a = stack[sp-1];
                    if (b == 0) {
                        env->ThrowNew(env->FindClass("java/lang/ArithmeticException"), "/ by zero");
                        return 0;
                    }
                    stack[sp-1] = a / b;
                }
                break;
            case OP_PRINT:
                if (sp >= 1) { std::cout << stack[sp-1] << std::endl; --sp; }
                break;
            case OP_NOP:
            case OP_JUNK1:
            case OP_JUNK2:
                break;
            case OP_SWAP:
                if (sp >= 2) std::swap(stack[sp-1], stack[sp-2]);
                break;
            case OP_DUP:
                if (sp >= 1 && sp < 256) stack[sp++] = stack[sp-1];
                break;
            case OP_LOAD:
                if (sp < 256 && ins.operand >= 0 && static_cast<size_t>(ins.operand) < locals_len)
                    stack[sp++] = locals[ins.operand];
                break;
            case OP_STORE:
                if (sp >= 1 && ins.operand >= 0 && static_cast<size_t>(ins.operand) < locals_len && locals != nullptr)
                    locals[ins.operand] = stack[--sp];
                break;
            case OP_IF_ICMPEQ:
                if (sp >= 2) {
                    int64_t b = stack[--sp];
                    if (stack[--sp] == b) pc = static_cast<size_t>(ins.operand);
                }
                break;
            case OP_IF_ICMPNE:
                if (sp >= 2) {
                    int64_t b = stack[--sp];
                    if (stack[--sp] != b) pc = static_cast<size_t>(ins.operand);
                }
                break;
            case OP_GOTO:
                pc = static_cast<size_t>(ins.operand);
                break;
            case OP_AND:
                if (sp >= 2) { stack[sp-2] &= stack[sp-1]; --sp; }
                break;
            case OP_OR:
                if (sp >= 2) { stack[sp-2] |= stack[sp-1]; --sp; }
                break;
            case OP_XOR:
                if (sp >= 2) { stack[sp-2] ^= stack[sp-1]; --sp; }
                break;
            case OP_SHL:
                if (sp >= 2) { stack[sp-2] <<= stack[sp-1]; --sp; }
                break;
            case OP_SHR:
                if (sp >= 2) { stack[sp-2] >>= stack[sp-1]; --sp; }
                break;
            case OP_USHR:
                if (sp >= 2) { stack[sp-2] = (uint64_t)stack[sp-2] >> stack[sp-1]; --sp; }
                break;
            case OP_IF_ICMPLT:
                if (sp >= 2) {
                    int64_t b = stack[--sp];
                    if (stack[--sp] < b) pc = static_cast<size_t>(ins.operand);
                }
                break;
            case OP_IF_ICMPLE:
                if (sp >= 2) {
                    int64_t b = stack[--sp];
                    if (stack[--sp] <= b) pc = static_cast<size_t>(ins.operand);
                }
                break;
            case OP_IF_ICMPGT:
                if (sp >= 2) {
                    int64_t b = stack[--sp];
                    if (stack[--sp] > b) pc = static_cast<size_t>(ins.operand);
                }
                break;
            case OP_IF_ICMPGE:
                if (sp >= 2) {
                    int64_t b = stack[--sp];
                    if (stack[--sp] >= b) pc = static_cast<size_t>(ins.operand);
                }
                break;
            case OP_I2L:
                if (sp >= 1) stack[sp-1] = (long)(int)stack[sp-1];
                break;
            case OP_I2B:
                if (sp >= 1) stack[sp-1] = (int8_t)stack[sp-1];
                break;
            case OP_I2C:
                if (sp >= 1) stack[sp-1] = (uint16_t)stack[sp-1];
                break;
            case OP_I2S:
                if (sp >= 1) stack[sp-1] = (int16_t)stack[sp-1];
                break;
            case OP_NEG:
                if (sp >= 1) stack[sp-1] = -stack[sp-1];
                break;
            case OP_ALOAD:
                if (sp < 256 && ins.operand >= 0 && static_cast<size_t>(ins.operand) < locals_len)
                    stack[sp++] = locals[ins.operand];
                break;
            case OP_ASTORE:
                if (sp >= 1 && ins.operand >= 0 && static_cast<size_t>(ins.operand) < locals_len && locals != nullptr)
                    locals[ins.operand] = stack[--sp];
                break;
            case OP_AALOAD:
                if (sp >= 2) {
                    int64_t index = stack[--sp];
                    jobjectArray arr = reinterpret_cast<jobjectArray>(stack[--sp]);
                    jobject val = env->GetObjectArrayElement(arr, static_cast<jsize>(index));
                    stack[sp++] = reinterpret_cast<int64_t>(val);
                    env->DeleteLocalRef(val);
                }
                break;
            case OP_AASTORE:
                if (sp >= 3) {
                    jobject value = reinterpret_cast<jobject>(stack[--sp]);
                    jsize index = static_cast<jsize>(stack[--sp]);
                    jobjectArray arr = reinterpret_cast<jobjectArray>(stack[--sp]);
                    env->SetObjectArrayElement(arr, index, value);
                }
                break;
            case OP_INVOKESTATIC:
                // simplified: treat as no-op
                break;
            case OP_HALT:
                return (sp > 0) ? stack[sp-1] : 0;
        }
    }
    return (sp > 0) ? stack[sp-1] : 0;
}

JitCompiled compile(const Instruction* code, size_t length, uint64_t seed) {
    auto* prog = new Program();
    decode_for_jit(code, length, seed, prog->ins);
    return { run_program, prog };
}

void free(JitCompiled& compiled) {
    delete reinterpret_cast<Program*>(compiled.ctx);
    compiled.ctx = nullptr;
    compiled.func = nullptr;
}

} // namespace native_jvm::vm
