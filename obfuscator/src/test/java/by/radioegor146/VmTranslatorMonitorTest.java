package by.radioegor146;

import by.radioegor146.instructions.VmTranslator;
import by.radioegor146.instructions.VmTranslator.Instruction;
import by.radioegor146.instructions.VmTranslator.VmOpcodes;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests translation and execution of MONITORENTER/MONITOREXIT instructions via a synchronized block.
 */
public class VmTranslatorMonitorTest {

    static class Sample {
        static int sync(Object o) {
            synchronized (o) {
                return 42;
            }
        }
    }

    private long run(Instruction[] code, Object[] locals) throws Throwable {
        Object[] stack = new Object[256];
        int sp = 0;
        int pc = 0;
        Deque<Object> monitors = new ArrayDeque<>();
        while (pc < code.length) {
            Instruction ins = code[pc++];
            switch (ins.opcode) {
                case VmOpcodes.OP_ALOAD:
                    stack[sp++] = locals[(int) ins.operand];
                    break;
                case VmOpcodes.OP_ASTORE:
                    locals[(int) ins.operand] = stack[--sp];
                    break;
                case VmOpcodes.OP_DUP:
                    stack[sp] = stack[sp - 1];
                    sp++;
                    break;
                case VmOpcodes.OP_PUSH:
                    stack[sp++] = ins.operand;
                    break;
                case VmOpcodes.OP_GOTO:
                    pc = (int) ins.operand;
                    break;
                case VmOpcodes.OP_MONITORENTER: {
                    Object obj = stack[--sp];
                    if (obj == null) throw new NullPointerException();
                    monitors.push(obj);
                    break;
                }
                case VmOpcodes.OP_MONITOREXIT: {
                    --sp;
                    monitors.pop();
                    break;
                }
                case VmOpcodes.OP_ATHROW: {
                    Throwable t = (Throwable) stack[--sp];
                    monitors.clear();
                    throw t;
                }
                case VmOpcodes.OP_HALT:
                    long res = (Long) stack[--sp];
                    if (!monitors.isEmpty()) {
                        throw new IllegalStateException("monitor not released");
                    }
                    return res;
                default:
                    throw new IllegalStateException("Unknown opcode: " + ins.opcode);
            }
        }
        if (!monitors.isEmpty()) {
            throw new IllegalStateException("monitor not released");
        }
        return 0;
    }

    @Test
    public void testSynchronizedBlock() throws Throwable {
        ClassReader cr = new ClassReader(Sample.class.getName());
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        MethodNode mn = cn.methods.stream()
                .filter(m -> m.name.equals("sync"))
                .findFirst()
                .orElseThrow();

        VmTranslator translator = new VmTranslator();
        Instruction[] code = translator.translate(mn);
        assertNotNull(code);
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_MONITORENTER));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_MONITOREXIT));

        Object lock = new Object();
        Object[] locals = new Object[3];
        locals[0] = lock;
        long result = run(code, locals);
        assertEquals(42, result);
    }
}
