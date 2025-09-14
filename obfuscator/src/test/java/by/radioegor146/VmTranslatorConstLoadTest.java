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
        static String stringConst() { return "Hello World"; }
        static Class<?> classConst() { return String.class; }
    }

    private VmTranslator translate(String name) throws Exception {
        ClassReader cr = new ClassReader(ConstSamples.class.getName());
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        MethodNode mn = cn.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
        VmTranslator translator = new VmTranslator();
        Instruction[] code = translator.translate(mn);
        assertNotNull(code);
        return translator;
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
        VmTranslator translator = translate("intConst");
        // Get the translated code from the translator's internal state
        ClassReader cr = new ClassReader(ConstSamples.class.getName());
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("intConst")).findFirst().orElseThrow();
        Instruction[] code = translator.translate(mn);

        // Test that LDC instruction now uses constant pool index instead of immediate value
        assertEquals(VmOpcodes.OP_LDC, code[0].opcode);
        // The operand should now be the constant pool index (0)
        assertEquals(0, code[0].operand);
        // Verify constant pool entry was created
        assertEquals(1, translator.getConstantPool().size());
        VmTranslator.ConstantPoolEntry entry = translator.getConstantPool().get(0);
        assertEquals(VmTranslator.ConstantPoolEntry.Type.INTEGER, entry.type);
        assertEquals(123456, entry.value);
    }

    @Test
    public void testLongConstants() throws Exception {
        // Note: These tests are kept simple to test existing functionality
        // For the old simple test approach, we need a simpler translate method
        ClassReader cr = new ClassReader(ConstSamples.class.getName());
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        MethodNode mn1 = cn.methods.stream().filter(m -> m.name.equals("longConst")).findFirst().orElseThrow();
        VmTranslator translator1 = new VmTranslator();
        Instruction[] code1 = translator1.translate(mn1);
        assertEquals(VmOpcodes.OP_LCONST_1, code1[0].opcode);

        MethodNode mn2 = cn.methods.stream().filter(m -> m.name.equals("longLdc2")).findFirst().orElseThrow();
        VmTranslator translator2 = new VmTranslator();
        Instruction[] code2 = translator2.translate(mn2);
        assertEquals(VmOpcodes.OP_LDC2_W, code2[0].opcode);
        // Verify constant pool was used
        assertEquals(1, translator2.getConstantPool().size());
        assertEquals(VmTranslator.ConstantPoolEntry.Type.LONG, translator2.getConstantPool().get(0).type);
    }

    @Test
    public void testFloatConstants() throws Exception {
        ClassReader cr = new ClassReader(ConstSamples.class.getName());
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        MethodNode mn1 = cn.methods.stream().filter(m -> m.name.equals("floatConst")).findFirst().orElseThrow();
        VmTranslator translator1 = new VmTranslator();
        Instruction[] code1 = translator1.translate(mn1);
        assertEquals(VmOpcodes.OP_FCONST_2, code1[0].opcode);

        MethodNode mn2 = cn.methods.stream().filter(m -> m.name.equals("floatLdc")).findFirst().orElseThrow();
        VmTranslator translator2 = new VmTranslator();
        Instruction[] code2 = translator2.translate(mn2);
        assertEquals(VmOpcodes.OP_LDC, code2[0].opcode);
        // Verify constant pool was used
        assertEquals(1, translator2.getConstantPool().size());
        assertEquals(VmTranslator.ConstantPoolEntry.Type.FLOAT, translator2.getConstantPool().get(0).type);
    }

    @Test
    public void testDoubleConstants() throws Exception {
        ClassReader cr = new ClassReader(ConstSamples.class.getName());
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        MethodNode mn1 = cn.methods.stream().filter(m -> m.name.equals("doubleConst")).findFirst().orElseThrow();
        VmTranslator translator1 = new VmTranslator();
        Instruction[] code1 = translator1.translate(mn1);
        assertEquals(VmOpcodes.OP_DCONST_1, code1[0].opcode);

        MethodNode mn2 = cn.methods.stream().filter(m -> m.name.equals("doubleLdc")).findFirst().orElseThrow();
        VmTranslator translator2 = new VmTranslator();
        Instruction[] code2 = translator2.translate(mn2);
        assertEquals(VmOpcodes.OP_LDC2_W, code2[0].opcode);
        // Verify constant pool was used
        assertEquals(1, translator2.getConstantPool().size());
        assertEquals(VmTranslator.ConstantPoolEntry.Type.DOUBLE, translator2.getConstantPool().get(0).type);
    }

    @Test
    public void testStringConstant() throws Exception {
        VmTranslator translator = new VmTranslator();
        ClassReader cr = new ClassReader(ConstSamples.class.getName());
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("stringConst")).findFirst().orElseThrow();
        Instruction[] code = translator.translate(mn);

        // String constants should use LDC instruction with constant pool index
        assertEquals(VmOpcodes.OP_LDC, code[0].opcode);
        assertEquals(0, code[0].operand); // First entry in constant pool

        // Verify constant pool entry
        assertEquals(1, translator.getConstantPool().size());
        VmTranslator.ConstantPoolEntry entry = translator.getConstantPool().get(0);
        assertEquals(VmTranslator.ConstantPoolEntry.Type.STRING, entry.type);
        assertEquals("Hello World", entry.value);
    }

    @Test
    public void testClassConstant() throws Exception {
        VmTranslator translator = new VmTranslator();
        ClassReader cr = new ClassReader(ConstSamples.class.getName());
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("classConst")).findFirst().orElseThrow();
        Instruction[] code = translator.translate(mn);

        // Class constants should use LDC instruction with constant pool index
        assertEquals(VmOpcodes.OP_LDC, code[0].opcode);
        assertEquals(0, code[0].operand); // First entry in constant pool

        // Verify constant pool entry
        assertEquals(1, translator.getConstantPool().size());
        VmTranslator.ConstantPoolEntry entry = translator.getConstantPool().get(0);
        assertEquals(VmTranslator.ConstantPoolEntry.Type.CLASS, entry.type);
        assertEquals("java/lang/String", entry.value);
    }

    @Test
    public void testConstantPoolDeduplication() throws Exception {
        // Test that duplicate constants reuse the same constant pool entry
        VmTranslator translator = new VmTranslator();

        // Manually create LDC instructions with the same string constant
        // This simulates what would happen if we had multiple LDC "Hello" instructions
        int index1 = translator.addToConstantPool("Hello");
        int index2 = translator.addToConstantPool("Hello");
        int index3 = translator.addToConstantPool("World");

        // Same string should reuse the same index
        assertEquals(0, index1);
        assertEquals(0, index2); // Should be the same as index1
        assertEquals(1, index3); // Should be different

        // Should only have 2 entries in constant pool
        assertEquals(2, translator.getConstantPool().size());
        assertEquals("Hello", translator.getConstantPool().get(0).value);
        assertEquals("World", translator.getConstantPool().get(1).value);
    }
}
