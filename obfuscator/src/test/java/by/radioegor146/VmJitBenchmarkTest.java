package by.radioegor146;

import by.radioegor146.instructions.VmTranslator;
import by.radioegor146.instructions.VmTranslator.Instruction;
import by.radioegor146.instructions.VmTranslator.VmOpcodes;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple benchmark comparing interpreted versus JIT style execution using
 * the translated VM instructions. This does not assert a specific speedup
 * but exercises both paths to ensure semantics remain identical.
 */
public class VmJitBenchmarkTest {

    static class Sample {
        static int add(int a, int b) {
            return a + b;
        }
    }

    private long run(Instruction[] code, long[] locals) {
        long[] stack = new long[256];
        int sp = 0;
        int pc = 0;
        while (pc < code.length) {
            Instruction ins = code[pc++];
            switch (ins.opcode) {
                case VmOpcodes.OP_PUSH:
                    stack[sp++] = ins.operand;
                    break;
                case VmOpcodes.OP_LOAD:
                    stack[sp++] = locals[(int) ins.operand];
                    break;
                case VmOpcodes.OP_ADD:
                    stack[sp - 2] += stack[sp - 1];
                    sp--;
                    break;
                case VmOpcodes.OP_HALT:
                    return sp > 0 ? stack[sp - 1] : 0;
                default:
                    throw new IllegalStateException("Unsupported opcode " + ins.opcode);
            }
        }
        return sp > 0 ? stack[sp - 1] : 0;
    }

    @Test
    public void benchmark() throws Exception {
        ClassReader cr = new ClassReader(Sample.class.getName());
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        MethodNode mn = cn.methods.stream()
                .filter(m -> m.name.equals("add"))
                .findFirst()
                .orElse(null);
        assertNotNull(mn);

        VmTranslator interp = new VmTranslator(false);
        Instruction[] interpCode = interp.translate(mn);
        assertNotNull(interpCode);

        VmTranslator jit = new VmTranslator(true);
        Instruction[] jitCode = jit.translate(mn);
        assertNotNull(jitCode);

        long[] locals = new long[] {3, 4};

        int iterations = 100000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            run(interpCode, locals.clone());
        }
        long interpTime = System.nanoTime() - start;

        start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            run(jitCode, locals.clone());
        }
        long jitTime = System.nanoTime() - start;

        System.out.println("interp=" + interpTime + " jit=" + jitTime);
        assertEquals(run(interpCode, locals.clone()), run(jitCode, locals.clone()));
    }
}
