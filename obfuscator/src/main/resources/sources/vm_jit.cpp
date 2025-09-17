#include "vm_jit.hpp"
#include <iostream>
#include <cstring>

namespace native_jvm::vm {

struct Program {
    std::vector<DecodedInstruction> ins;
};

static bool is_supported_for_jit(OpCode op) {
    switch (op) {
        case OP_PUSH:
        case OP_LDC:
        case OP_LDC_W:
        case OP_LDC2_W:
        case OP_ADD:
        case OP_SUB:
        case OP_MUL:
        case OP_DIV:
        case OP_PRINT:
        case OP_NOP:
        case OP_JUNK1:
        case OP_JUNK2:
        case OP_SWAP:
        case OP_DUP:
        case OP_DUP_X1:
        case OP_DUP_X2:
        case OP_DUP2:
        case OP_DUP2_X1:
        case OP_DUP2_X2:
        case OP_ATHROW:
        case OP_TRY_START:
        case OP_CATCH_HANDLER:
        case OP_FINALLY_HANDLER:
        case OP_EXCEPTION_CHECK:
        case OP_EXCEPTION_CLEAR:
        case OP_LOAD:
        case OP_LLOAD:
        case OP_FLOAD:
        case OP_DLOAD:
        case OP_STORE:
        case OP_LSTORE:
        case OP_FSTORE:
        case OP_DSTORE:
        case OP_IF_ICMPEQ:
        case OP_IF_ICMPNE:
        case OP_GOTO:
        case OP_AND:
        case OP_OR:
        case OP_XOR:
        case OP_SHL:
        case OP_SHR:
        case OP_USHR:
        case OP_IF_ICMPLT:
        case OP_IF_ICMPLE:
        case OP_IF_ICMPGT:
        case OP_IF_ICMPGE:
        case OP_I2L:
        case OP_I2B:
        case OP_I2C:
        case OP_I2S:
        case OP_I2F:
        case OP_I2D:
        case OP_L2I:
        case OP_L2F:
        case OP_L2D:
        case OP_F2I:
        case OP_F2L:
        case OP_F2D:
        case OP_D2I:
        case OP_D2L:
        case OP_D2F:
        case OP_NEG:
        case OP_ALOAD:
        case OP_ASTORE:
        case OP_AALOAD:
        case OP_AASTORE:
        case OP_IALOAD:
        case OP_BALOAD:
        case OP_CALOAD:
        case OP_SALOAD:
        case OP_IASTORE:
        case OP_BASTORE:
        case OP_CASTORE:
        case OP_SASTORE:
        case OP_INVOKESTATIC:
        case OP_FCONST_0:
        case OP_FCONST_1:
        case OP_FCONST_2:
        case OP_DCONST_0:
        case OP_DCONST_1:
        case OP_LCONST_0:
        case OP_LCONST_1:
        case OP_HALT:
            return true;
        default:
            return false;
    }
}

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
            case OP_LDC:
            case OP_LDC_W:
            case OP_LDC2_W:
                if (sp + 1 <= 256) stack[sp++] = ins.operand;
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
                    int64_t b = stack[sp-1];
                    int64_t a = stack[sp-2];
                    if (b == 0) {
                        env->ThrowNew(env->FindClass("java/lang/ArithmeticException"), "/ by zero");
                        return 0;
                    }
                    stack[sp-2] = a / b;
                    --sp;
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
                if (sp >= 1 && sp + 1 <= 256) stack[sp++] = stack[sp-1];
                break;
            case OP_DUP_X1:
                if (sp >= 2 && sp + 1 <= 256) {
                    int64_t value1 = stack[sp - 1];
                    int64_t value2 = stack[sp - 2];
                    stack[sp - 2] = value1;
                    stack[sp - 1] = value2;
                    stack[sp++] = value1;
                }
                break;
            case OP_DUP_X2:
                if (sp >= 3 && sp + 1 <= 256) {
                    int64_t value1 = stack[sp - 1];
                    int64_t value2 = stack[sp - 2];
                    int64_t value3 = stack[sp - 3];
                    stack[sp - 3] = value1;
                    stack[sp - 2] = value3;
                    stack[sp - 1] = value2;
                    stack[sp++] = value1;
                }
                break;
            case OP_DUP2:
                if (sp >= 2 && sp + 2 <= 256) {
                    int64_t value1 = stack[sp - 1];
                    int64_t value2 = stack[sp - 2];
                    stack[sp++] = value2;
                    stack[sp++] = value1;
                }
                break;
            case OP_DUP2_X1:
                if (sp >= 3 && sp + 2 <= 256) {
                    int64_t value1 = stack[sp - 1];
                    int64_t value2 = stack[sp - 2];
                    int64_t value3 = stack[sp - 3];
                    stack[sp - 3] = value2;
                    stack[sp - 2] = value1;
                    stack[sp - 1] = value3;
                    stack[sp++] = value2;
                    stack[sp++] = value1;
                }
                break;
            case OP_DUP2_X2:
                if (sp >= 4 && sp + 2 <= 256) {
                    int64_t value1 = stack[sp - 1];
                    int64_t value2 = stack[sp - 2];
                    int64_t value3 = stack[sp - 3];
                    int64_t value4 = stack[sp - 4];
                    stack[sp - 4] = value2;
                    stack[sp - 3] = value1;
                    stack[sp - 2] = value4;
                    stack[sp - 1] = value3;
                    stack[sp++] = value2;
                    stack[sp++] = value1;
                }
                break;
            case OP_ATHROW:
                if (sp >= 1) {
                    jobject exception = reinterpret_cast<jobject>(stack[sp - 1]);
                    if (exception == nullptr) {
                        if (env != nullptr) {
                            jclass npeClass = env->FindClass("java/lang/NullPointerException");
                            if (npeClass) {
                                env->ThrowNew(npeClass, "Cannot throw null exception");
                            }
                        }
                    } else {
                        if (env != nullptr) {
                            env->Throw(static_cast<jthrowable>(exception));
                        }
                    }
                    --sp;
                    return 0; // Exception terminates execution
                }
                break;
            case OP_TRY_START:
                // Setup exception handling context
                break;
            case OP_CATCH_HANDLER:
                // Jump to catch block
                if (ins.operand >= 0 && static_cast<size_t>(ins.operand) < prog->ins.size()) {
                    pc = static_cast<size_t>(ins.operand); // Set PC to handler location
                }
                break;
            case OP_FINALLY_HANDLER:
                // Jump to finally block
                if (ins.operand >= 0 && static_cast<size_t>(ins.operand) < prog->ins.size()) {
                    pc = static_cast<size_t>(ins.operand); // Set PC to handler location
                }
                break;
            case OP_EXCEPTION_CHECK:
                // Check and handle JNI exception
                if (env != nullptr && env->ExceptionCheck()) {
                    jthrowable exception = env->ExceptionOccurred();
                    if (exception && sp < 256) {
                        stack[sp++] = reinterpret_cast<int64_t>(exception);
                        env->ExceptionClear();
                        if (ins.operand >= 0 && static_cast<size_t>(ins.operand) < prog->ins.size()) {
                            pc = static_cast<size_t>(ins.operand); // Jump to handler
                        }
                    }
                }
                break;
            case OP_EXCEPTION_CLEAR:
                // Clear JNI exception
                if (env != nullptr && env->ExceptionCheck()) {
                    env->ExceptionClear();
                }
                break;
            case OP_LOAD:
                if (sp < 256 && ins.operand >= 0 && static_cast<size_t>(ins.operand) < locals_len)
                    stack[sp++] = locals[static_cast<size_t>(ins.operand)];
                break;
            case OP_STORE:
                if (sp >= 1 && ins.operand >= 0 && static_cast<size_t>(ins.operand) < locals_len && locals != nullptr)
                    locals[static_cast<size_t>(ins.operand)] = stack[--sp];
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
            case OP_I2F:
                if (sp >= 1) {
                    float f = (float)(int32_t)stack[sp-1];
                    int32_t bits; std::memcpy(&bits, &f, sizeof(float));
                    stack[sp-1] = bits;
                }
                break;
            case OP_I2D:
                if (sp >= 1) {
                    double d = (double)(int32_t)stack[sp-1];
                    int64_t bits; std::memcpy(&bits, &d, sizeof(double));
                    stack[sp-1] = bits;
                }
                break;
            case OP_L2I:
                if (sp >= 1) stack[sp-1] = (int32_t)stack[sp-1];
                break;
            case OP_L2F:
                if (sp >= 1) {
                    float f = (float)stack[sp-1];
                    int32_t bits; std::memcpy(&bits, &f, sizeof(float));
                    stack[sp-1] = bits;
                }
                break;
            case OP_L2D:
                if (sp >= 1) {
                    double d = (double)stack[sp-1];
                    int64_t bits; std::memcpy(&bits, &d, sizeof(double));
                    stack[sp-1] = bits;
                }
                break;
            case OP_F2I:
                if (sp >= 1) {
                    float f; int32_t bits = (int32_t)stack[sp-1];
                    std::memcpy(&f, &bits, sizeof(float));
                    stack[sp-1] = (int32_t)f;
                }
                break;
            case OP_F2L:
                if (sp >= 1) {
                    float f; int32_t bits = (int32_t)stack[sp-1];
                    std::memcpy(&f, &bits, sizeof(float));
                    stack[sp-1] = (int64_t)f;
                }
                break;
            case OP_F2D:
                if (sp >= 1) {
                    float f; int32_t bits = (int32_t)stack[sp-1];
                    std::memcpy(&f, &bits, sizeof(float));
                    double d = (double)f; int64_t dbits; std::memcpy(&dbits, &d, sizeof(double));
                    stack[sp-1] = dbits;
                }
                break;
            case OP_D2I:
                if (sp >= 1) {
                    double d; std::memcpy(&d, &stack[sp-1], sizeof(double));
                    stack[sp-1] = (int32_t)d;
                }
                break;
            case OP_D2L:
                if (sp >= 1) {
                    double d; std::memcpy(&d, &stack[sp-1], sizeof(double));
                    stack[sp-1] = (int64_t)d;
                }
                break;
            case OP_D2F:
                if (sp >= 1) {
                    double d; std::memcpy(&d, &stack[sp-1], sizeof(double));
                    float f = (float)d; int32_t fbits; std::memcpy(&fbits, &f, sizeof(float));
                    stack[sp-1] = fbits;
                }
                break;
            case OP_NEG:
                if (sp >= 1) stack[sp-1] = -stack[sp-1];
                break;
            case OP_ALOAD:
                if (sp < 256 && ins.operand >= 0 && static_cast<size_t>(ins.operand) < locals_len)
                    stack[sp++] = locals[static_cast<size_t>(ins.operand)];
                break;
            case OP_ASTORE:
                if (sp >= 1 && ins.operand >= 0 && static_cast<size_t>(ins.operand) < locals_len && locals != nullptr)
                    locals[static_cast<size_t>(ins.operand)] = stack[--sp];
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
            case OP_IALOAD:
                if (sp >= 2) {
                    jsize index = static_cast<jsize>(stack[--sp]);
                    jintArray arr = reinterpret_cast<jintArray>(stack[--sp]);
                    jint val;
                    env->GetIntArrayRegion(arr, index, 1, &val);
                    stack[sp++] = val;
                }
                break;
            case OP_BALOAD:
                if (sp >= 2) {
                    jsize index = static_cast<jsize>(stack[--sp]);
                    jbyteArray arr = reinterpret_cast<jbyteArray>(stack[--sp]);
                    jbyte val;
                    env->GetByteArrayRegion(arr, index, 1, &val);
                    stack[sp++] = val;
                }
                break;
            case OP_CALOAD:
                if (sp >= 2) {
                    jsize index = static_cast<jsize>(stack[--sp]);
                    jcharArray arr = reinterpret_cast<jcharArray>(stack[--sp]);
                    jchar val;
                    env->GetCharArrayRegion(arr, index, 1, &val);
                    stack[sp++] = val;
                }
                break;
            case OP_SALOAD:
                if (sp >= 2) {
                    jsize index = static_cast<jsize>(stack[--sp]);
                    jshortArray arr = reinterpret_cast<jshortArray>(stack[--sp]);
                    jshort val;
                    env->GetShortArrayRegion(arr, index, 1, &val);
                    stack[sp++] = val;
                }
                break;
            case OP_IASTORE:
                if (sp >= 3) {
                    jint value = static_cast<jint>(stack[--sp]);
                    jsize index = static_cast<jsize>(stack[--sp]);
                    jintArray arr = reinterpret_cast<jintArray>(stack[--sp]);
                    env->SetIntArrayRegion(arr, index, 1, &value);
                }
                break;
            case OP_BASTORE:
                if (sp >= 3) {
                    jbyte value = static_cast<jbyte>(stack[--sp]);
                    jsize index = static_cast<jsize>(stack[--sp]);
                    jbyteArray arr = reinterpret_cast<jbyteArray>(stack[--sp]);
                    env->SetByteArrayRegion(arr, index, 1, &value);
                }
                break;
            case OP_CASTORE:
                if (sp >= 3) {
                    jchar value = static_cast<jchar>(stack[--sp]);
                    jsize index = static_cast<jsize>(stack[--sp]);
                    jcharArray arr = reinterpret_cast<jcharArray>(stack[--sp]);
                    env->SetCharArrayRegion(arr, index, 1, &value);
                }
                break;
            case OP_SASTORE:
                if (sp >= 3) {
                    jshort value = static_cast<jshort>(stack[--sp]);
                    jsize index = static_cast<jsize>(stack[--sp]);
                    jshortArray arr = reinterpret_cast<jshortArray>(stack[--sp]);
                    env->SetShortArrayRegion(arr, index, 1, &value);
                }
                break;
            case OP_INVOKESTATIC:
                // simplified: treat as no-op
                break;
            case OP_FCONST_0:
                if (sp < 256) stack[sp++] = 0;
                break;
            case OP_FCONST_1:
                if (sp < 256) {
                    int32_t bits; float v = 1.0f; std::memcpy(&bits, &v, sizeof(float));
                    stack[sp++] = bits;
                }
                break;
            case OP_FCONST_2:
                if (sp < 256) {
                    int32_t bits; float v = 2.0f; std::memcpy(&bits, &v, sizeof(float));
                    stack[sp++] = bits;
                }
                break;
            case OP_DCONST_0:
                if (sp < 256) stack[sp++] = 0;
                break;
            case OP_DCONST_1:
                if (sp < 256) {
                    int64_t bits; double v = 1.0; std::memcpy(&bits, &v, sizeof(double));
                    stack[sp++] = bits;
                }
                break;
            case OP_LCONST_0:
                if (sp < 256) stack[sp++] = 0;
                break;
            case OP_LCONST_1:
                if (sp < 256) stack[sp++] = 1;
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
    for (const auto& ins : prog->ins) {
        if (!is_supported_for_jit(ins.op)) {
            delete prog;
            return {};
        }
    }
    return { run_program, prog };
}

void free(JitCompiled& compiled) {
    delete reinterpret_cast<Program*>(compiled.ctx);
    compiled.ctx = nullptr;
    compiled.func = nullptr;
}

} // namespace native_jvm::vm
