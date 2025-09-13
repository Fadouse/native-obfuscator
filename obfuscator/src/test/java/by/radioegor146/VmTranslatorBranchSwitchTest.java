package by.radioegor146;

import by.radioegor146.instructions.VmTranslator;
import by.radioegor146.instructions.VmTranslator.Instruction;
import by.radioegor146.instructions.VmTranslator.VmOpcodes;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests covering newly added object comparisons and switch statements
 * in the VM translator and opcode set.
 */
public class VmTranslatorBranchSwitchTest {

    static class Sample {
        static int nullCheck(Object o) {
            if (o == null) return 1; else return 2;
        }

        static int nonNullCheck(Object o) {
            if (o != null) return 1; else return 0;
        }

        static int refCompare(Object a, Object b) {
            if (a != b) return 0; else return 1;
        }

        static int tableSwitch(int i) {
            switch (i) {
                case 0: return 10;
                case 1: return 20;
                case 2: return 30;
                default: return 40;
            }
        }

        static int lookupSwitch(int i) {
            switch (i) {
                case 10: return 1;
                case 20: return 2;
                default: return 0;
            }
        }
    }

    private long run(VmTranslator tr, Instruction[] code, long[] locals) {
        long[] stack = new long[256];
        int sp = 0;
        int pc = 0;
        while (pc < code.length) {
            Instruction ins = code[pc++];
            switch (ins.opcode) {
                case VmOpcodes.OP_PUSH:
                    stack[sp++] = ins.operand; break;
                case VmOpcodes.OP_LOAD:
                case VmOpcodes.OP_ALOAD:
                    stack[sp++] = locals[(int) ins.operand]; break;
                case VmOpcodes.OP_GOTO:
                case VmOpcodes.OP_GOTO_W:
                    pc = (int) ins.operand; break;
                case VmOpcodes.OP_IFNULL:
                case VmOpcodes.OP_IFNULL_W:
                    if (stack[--sp] == 0) pc = (int) ins.operand; break;
                case VmOpcodes.OP_IFNONNULL:
                case VmOpcodes.OP_IFNONNULL_W:
                    if (stack[--sp] != 0) pc = (int) ins.operand; break;
                case VmOpcodes.OP_IF_ACMPEQ:
                case VmOpcodes.OP_IF_ACMPEQ_W: {
                    long b = stack[--sp]; long a = stack[--sp];
                    if (a == b) pc = (int) ins.operand; break;
                }
                case VmOpcodes.OP_IF_ACMPNE:
                case VmOpcodes.OP_IF_ACMPNE_W: {
                    long b = stack[--sp]; long a = stack[--sp];
                    if (a != b) pc = (int) ins.operand; break;
                }
                case VmOpcodes.OP_TABLESWITCH: {
                    VmTranslator.TableSwitchInfo t = tr.getTableSwitches().get((int) ins.operand);
                    int v = (int) stack[--sp];
                    if (v < t.low || v > t.high) pc = t.defaultLabel;
                    else pc = t.labels[v - t.low];
                    break;
                }
                case VmOpcodes.OP_LOOKUPSWITCH: {
                    VmTranslator.LookupSwitchInfo l = tr.getLookupSwitches().get((int) ins.operand);
                    int key = (int) stack[--sp];
                    pc = l.defaultLabel;
                    for (int i = 0; i < l.keys.length; i++) {
                        if (l.keys[i] == key) { pc = l.labels[i]; break; }
                    }
                    break;
                }
                case VmOpcodes.OP_HALT:
                    return sp > 0 ? stack[sp - 1] : 0;
                default:
                    throw new IllegalStateException("Unknown opcode: " + ins.opcode);
            }
        }
        return sp > 0 ? stack[sp - 1] : 0;
    }

    @Test
    public void testNullAndRefChecks() throws Exception {
        VmTranslator translator = new VmTranslator();
        ClassReader cr = new ClassReader(Sample.class.getName());
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        // nonNullCheck method (uses IFNULL)
        {
            MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("nonNullCheck")).findFirst().orElseThrow();
            Instruction[] code = translator.translate(mn);
            assertNotNull(code);
            assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_IFNULL));

            long[] locals = new long[1];
            locals[0] = 0;
            assertEquals(0, run(translator, code, locals));
            locals[0] = 0x1234;
            assertEquals(1, run(translator, code, locals));
        }

        // nullCheck method (uses IFNONNULL)
        {
            MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("nullCheck")).findFirst().orElseThrow();
            Instruction[] code = translator.translate(mn);
            assertNotNull(code);
            assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_IFNONNULL));

            long[] locals = new long[1];
            locals[0] = 0;
            assertEquals(1, run(translator, code, locals));
            locals[0] = 0x1234;
            assertEquals(2, run(translator, code, locals));
        }

        // refCompare method
        {
            MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("refCompare")).findFirst().orElseThrow();
            Instruction[] code = translator.translate(mn);
            assertNotNull(code);
            assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_IF_ACMPEQ));

            long same = 0x1111;
            long[] locals = new long[2];
            locals[0] = same; locals[1] = same;
            assertEquals(1, run(translator, code, locals));
            locals[1] = 0x2222;
            assertEquals(0, run(translator, code, locals));
        }
    }

    @Test
    public void testSwitchStatements() throws Exception {
        VmTranslator translator = new VmTranslator();
        ClassReader cr = new ClassReader(Sample.class.getName());
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        // table switch
        {
            MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("tableSwitch")).findFirst().orElseThrow();
            Instruction[] code = translator.translate(mn);
            assertNotNull(code);
            assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_TABLESWITCH));

            long[] locals = new long[1];
            locals[0] = 1;
            assertEquals(20, run(translator, code, locals));
        }

        // lookup switch
        {
            MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("lookupSwitch")).findFirst().orElseThrow();
            Instruction[] code = translator.translate(mn);
            assertNotNull(code);
            assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_LOOKUPSWITCH));

            long[] locals = new long[1];
            locals[0] = 20;
            assertEquals(2, run(translator, code, locals));
        }
    }
}

