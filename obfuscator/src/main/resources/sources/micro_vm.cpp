#include "micro_vm.hpp"
#include <iostream>

namespace native_jvm::vm {

void execute(const Instruction* code, size_t length) {
    int32_t stack[256];
    size_t sp = 0;
    size_t pc = 0;
    int32_t tmp = 0;
    constexpr uint32_t KEY = 0x5F3759DFu; // obfuscation key
    uint32_t state = KEY ^ 0u; // initial state

    while (true) {
        switch (state) {
            case KEY ^ 0u: { // fetch & dispatch
                if (pc >= length) {
                    state = KEY ^ 7u;
                    break;
                }
                OpCode op = code[pc].op;
                tmp = code[pc].operand;
                ++pc;
                switch (op) {
                    case OP_PUSH: state = KEY ^ 1u; break;
                    case OP_ADD:  state = KEY ^ 2u; break;
                    case OP_SUB:  state = KEY ^ 3u; break;
                    case OP_MUL:  state = KEY ^ 4u; break;
                    case OP_DIV:  state = KEY ^ 5u; break;
                    case OP_PRINT:state = KEY ^ 6u; break;
                    case OP_HALT: default: state = KEY ^ 7u; break;
                }
                break;
            }
            case KEY ^ 1u: { // push
                if (sp < 256) {
                    stack[sp++] = tmp;
                }
                state = KEY ^ 0u;
                break;
            }
            case KEY ^ 2u: { // add
                if (sp >= 2) {
                    stack[sp - 2] += stack[sp - 1];
                    --sp;
                }
                state = KEY ^ 0u;
                break;
            }
            case KEY ^ 3u: { // sub
                if (sp >= 2) {
                    stack[sp - 2] -= stack[sp - 1];
                    --sp;
                }
                state = KEY ^ 0u;
                break;
            }
            case KEY ^ 4u: { // mul
                if (sp >= 2) {
                    stack[sp - 2] *= stack[sp - 1];
                    --sp;
                }
                state = KEY ^ 0u;
                break;
            }
            case KEY ^ 5u: { // div
                if (sp >= 2) {
                    int32_t b = stack[sp - 1];
                    if (b != 0) {
                        stack[sp - 2] /= b;
                    }
                    --sp;
                }
                state = KEY ^ 0u;
                break;
            }
            case KEY ^ 6u: { // print
                if (sp >= 1) {
                    std::cout << stack[sp - 1] << std::endl;
                    --sp;
                }
                state = KEY ^ 0u;
                break;
            }
            case KEY ^ 7u:
            default:
                return; // halt
        }
    }
}

} // namespace native_jvm::vm

