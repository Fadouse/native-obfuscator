package by.radioegor146;

import by.radioegor146.instructions.VmTranslator;
import by.radioegor146.instructions.VmTranslator.Instruction;
import by.radioegor146.instructions.VmTranslator.VmOpcodes;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

public class VmTranslatorConstLoadTest {

    static class ConstSamples {
        static int intConst() { return 123456; }
        static long longConst() { return 1L; }
        static long longLdc2() { return 0x1122334455667788L; }
        static float floatConst() { return 2.0f; }
        static float floatLdc() { return 3.5f; }
        static double doubleConst() { return 1.0; }
        static double doubleLdc() { return 6.5; }
    }

    private Instruction[] translate(String name) throws Exception {
        ClassReader cr = new ClassReader(ConstSamples.class.getName());
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        MethodNode mn = cn.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
        VmTranslator translator = new VmTranslator();
        Instruction[] code = translator.translate(mn);
        assertNotNull(code);
        return code;
    }

    private long run(Instruction[] code) {
        long[] stack = new long[256];
        int sp = 0;
        for (Instruction ins : code) {
            switch (ins.opcode) {
                case VmOpcodes.OP_LDC:
                case VmOpcodes.OP_LDC_W:
                case VmOpcodes.OP_LDC2_W:
                case VmOpcodes.OP_PUSH:
                    stack[sp++] = ins.operand;
                    break;
                case VmOpcodes.OP_FCONST_0:
                    stack[sp++] = 0;
                    break;
                case VmOpcodes.OP_FCONST_1:
                    stack[sp++] = Float.floatToIntBits(1.0f);
                    break;
                case VmOpcodes.OP_FCONST_2:
                    stack[sp++] = Float.floatToIntBits(2.0f);
                    break;
                case VmOpcodes.OP_DCONST_0:
                    stack[sp++] = 0;
                    break;
                case VmOpcodes.OP_DCONST_1:
                    stack[sp++] = Double.doubleToLongBits(1.0);
                    break;
                case VmOpcodes.OP_LCONST_0:
                    stack[sp++] = 0;
                    break;
                case VmOpcodes.OP_LCONST_1:
                    stack[sp++] = 1;
                    break;
                case VmOpcodes.OP_HALT:
                    return stack[sp - 1];
            }
        }
        return stack[sp - 1];
    }

    @Test
    public void testIntLdc() throws Exception {
        Instruction[] code = translate("intConst");
        assertEquals(VmOpcodes.OP_LDC, code[0].opcode);
        assertEquals(123456L, run(code));
    }

    @Test
    public void testLongConstants() throws Exception {
        Instruction[] code1 = translate("longConst");
        assertEquals(VmOpcodes.OP_LCONST_1, code1[0].opcode);
        assertEquals(1L, run(code1));

        Instruction[] code2 = translate("longLdc2");
        assertEquals(VmOpcodes.OP_LDC2_W, code2[0].opcode);
        assertEquals(0x1122334455667788L, run(code2));
    }

    @Test
    public void testFloatConstants() throws Exception {
        Instruction[] code1 = translate("floatConst");
        assertEquals(VmOpcodes.OP_FCONST_2, code1[0].opcode);
        assertEquals(Float.floatToIntBits(2.0f), (int) run(code1));

        Instruction[] code2 = translate("floatLdc");
        assertEquals(VmOpcodes.OP_LDC, code2[0].opcode);
        assertEquals(Float.floatToIntBits(3.5f), (int) run(code2));
    }

    @Test
    public void testDoubleConstants() throws Exception {
        Instruction[] code1 = translate("doubleConst");
        assertEquals(VmOpcodes.OP_DCONST_1, code1[0].opcode);
        assertEquals(Double.doubleToLongBits(1.0), run(code1));

        Instruction[] code2 = translate("doubleLdc");
        assertEquals(VmOpcodes.OP_LDC2_W, code2[0].opcode);
        assertEquals(Double.doubleToLongBits(6.5), run(code2));
    }
}
