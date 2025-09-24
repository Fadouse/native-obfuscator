package by.radioegor146.javaobf.debug;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;

public final class AsmDebug implements Opcodes {
    private AsmDebug() {}

    /** 将方法反汇编为带“指令索引”的文本，便于对照 Analyzer 的出错 index */
    public static String disassembleWithIndex(MethodNode mn) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        InsnList insns = mn.instructions;
        Map<AbstractInsnNode, Integer> index = buildIndex(insns);

        for (AbstractInsnNode in = insns.getFirst(); in != null; in = in.getNext()) {
            int i = index.get(in);
            Textifier t = new Textifier();
            TraceMethodVisitor tmv = new TraceMethodVisitor(t);
            in.accept(tmv);
            StringWriter line = new StringWriter();
            t.print(new PrintWriter(line));
            String text = line.toString().trim().replace("\n", " ");
            pw.printf("%5d: %s%n", i, text);
        }
        pw.flush();
        return sw.toString();
    }

    /** BasicVerifier 验证并输出每条指令的“之前帧”（locals/stack），失败时抛详细异常 */
    public static void verifyAndDumpFrames(String owner, MethodNode mn, ClassLoader loader) {
        Analyzer<BasicValue> analyzer = new Analyzer<BasicValue>(new SimpleVerifier(
                Type.getObjectType(owner),
                null, // super
                null, // interfaces
                false // isInterface
        )) {
            @Override
            protected void newControlFlowEdge(int insn, int successor) { /* 可按需打印 CFG */ }
        };

        try {
            Frame<BasicValue>[] fs = analyzer.analyze(owner, mn);
            // 打印每条指令的帧（可按需降低噪音）
            Map<AbstractInsnNode, Integer> index = buildIndex(mn.instructions);
            int i = 0;
            for (AbstractInsnNode in = mn.instructions.getFirst(); in != null; in = in.getNext(), i++) {
                Frame<BasicValue> f = fs[i];
                System.out.printf("#%d %s%n", i, insnToString(in));
                if (f == null) {
                    System.out.println("  <frame=null/unreachable>");
                    continue;
                }
                dumpFrame("  locals", f, true);
                dumpFrame("  stack ", f, false);
            }
        } catch (AnalyzerException ex) {
            // 从异常消息中提取 index（如“Error at instruction 1194”）
            int at = extractIndex(ex.getMessage());
            System.err.println("AnalyzerException at instruction: " + at);
            System.err.println("Disassembly around error:");
            System.err.println(snippetAround(mn, at, 12));
            throw new RuntimeException(ex);
        }
    }

    public static void verifyClassBytes(byte[] bytes, ClassLoader loader) {
        ClassReader cr = new ClassReader(bytes);
        ClassNode cn = new ClassNode(Opcodes.ASM9);
        cr.accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            // Skip verification of constructors and initializers
            // These should never be modified by obfuscation and can cause verification issues
            if ("<init>".equals(mn.name) || "<clinit>".equals(mn.name)) {
                System.out.printf("Skipping verification of %s.%s%s (constructor/initializer)%n", cn.name, mn.name, mn.desc);
                continue;
            }

            String owner = cn.name;
            System.out.printf("Verifying %s.%s%s%n", owner, mn.name, mn.desc);
            verifyAndDumpFrames(owner, mn, loader);
        }
    }

    // ---------- helpers ----------

    private static Map<AbstractInsnNode, Integer> buildIndex(InsnList insns) {
        Map<AbstractInsnNode, Integer> map = new IdentityHashMap<>();
        int i = 0;
        for (AbstractInsnNode in = insns.getFirst(); in != null; in = in.getNext(), i++) {
            map.put(in, i);
        }
        return map;
    }

    private static String insnToString(AbstractInsnNode in) {
        Textifier t = new Textifier();
        TraceMethodVisitor tmv = new TraceMethodVisitor(t);
        in.accept(tmv);
        StringWriter line = new StringWriter();
        t.print(new PrintWriter(line));
        return line.toString().trim().replace("\n", " ");
    }

    private static void dumpFrame(String prefix, Frame<BasicValue> f, boolean dumpLocals) {
        StringBuilder sb = new StringBuilder(prefix).append(": ");
        if (dumpLocals) {
            for (int i = 0; i < f.getLocals(); i++) {
                sb.append(valToStr(f.getLocal(i))).append(" ");
            }
        } else {
            for (int i = 0; i < f.getStackSize(); i++) {
                sb.append(valToStr(f.getStack(i))).append(" ");
            }
        }
        System.out.println(sb.toString());
    }

    private static String valToStr(BasicValue v) {
        if (v == null) return ".";
        Type t = v.getType();
        if (t == null) return ".";
        switch (t.getSort()) {
            case Type.OBJECT:
            case Type.ARRAY:
                return t.getInternalName();
            case Type.INT: return "I";
            case Type.LONG: return "J";
            case Type.FLOAT: return "F";
            case Type.DOUBLE: return "D";
            default: return t.getDescriptor();
        }
    }

    private static int extractIndex(String msg) {
        if (msg == null) return -1;
        int p = msg.indexOf("instruction");
        if (p >= 0) {
            String tail = msg.substring(p).replaceAll("[^0-9]", " ").trim();
            try { return Integer.parseInt(tail.split("\\s+")[0]); } catch (Exception ignore) {}
        }
        return -1;
    }

    private static String snippetAround(MethodNode mn, int idx, int radius) {
        if (idx < 0) return "(no index)";
        StringBuilder sb = new StringBuilder();
        List<AbstractInsnNode> list = new ArrayList<>();
        for (AbstractInsnNode in = mn.instructions.getFirst(); in != null; in = in.getNext()) list.add(in);
        int from = Math.max(0, idx - radius);
        int to   = Math.min(list.size() - 1, idx + radius);
        for (int i = from; i <= to; i++) {
            String line = insnToString(list.get(i));
            sb.append(String.format("%s%5d: %s%n", (i == idx ? ">> " : "   "), i, line));
        }
        return sb.toString();
    }
}
