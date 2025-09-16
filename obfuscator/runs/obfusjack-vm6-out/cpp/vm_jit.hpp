#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>
#include <jni.h>
#include "micro_vm.hpp"

namespace native_jvm::vm {

struct DecodedInstruction {
    OpCode op;
    int64_t operand;
};

struct JitCompiled {
    using Func = int64_t(*)(JNIEnv*, int64_t*, size_t, uint64_t, void*);
    Func func{};
    void* ctx{};
};

void decode_for_jit(const Instruction* code, size_t length, uint64_t seed,
                    std::vector<DecodedInstruction>& out);

JitCompiled compile(const Instruction* code, size_t length, uint64_t seed);
void free(JitCompiled& compiled);

} // namespace native_jvm::vm
