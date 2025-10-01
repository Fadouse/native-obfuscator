package by.radioegor146.javaobf.frame;

import org.objectweb.asm.tree.*;


public final class AsmSanity {
    private AsmSanity() {}

    /** 移除所有 FrameNode，交给 COMPUTE_FRAMES 完全重算 */
    public static void stripAllFrames(MethodNode mn) {
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; ) {
            AbstractInsnNode next = insn.getNext();
            if (insn instanceof FrameNode) {
                mn.instructions.remove(insn);
            }
            insn = next;
        }
        // 让 ASM 自行计算
        mn.maxStack = 0;
        // 但要保证 maxLocals >= 实际使用（如新增的 stateLocal）
    }

    /** 将 oldLbl 在整方法内安全替换为 newLbl，并移除 oldLbl */
    public static void replaceLabelEverywhere(MethodNode mn, LabelNode oldLbl, LabelNode newLbl) {
        if (oldLbl == newLbl) return;

        // 指令列表中的出现：先在 oldLbl 前插入 newLbl，再移除 oldLbl
        mn.instructions.insertBefore(oldLbl, newLbl);
        mn.instructions.remove(oldLbl);

        // 1) 所有跳转目标/开关标签
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof JumpInsnNode jin) {
                if (jin.label == oldLbl) jin.label = newLbl;
            } else if (insn instanceof TableSwitchInsnNode tsw) {
                if (tsw.dflt == oldLbl) tsw.dflt = newLbl;
                for (int i = 0; i < tsw.labels.size(); i++) {
                    if (tsw.labels.get(i) == oldLbl) tsw.labels.set(i, newLbl);
                }
            } else if (insn instanceof LookupSwitchInsnNode lsw) {
                if (lsw.dflt == oldLbl) lsw.dflt = newLbl;
                for (int i = 0; i < lsw.labels.size(); i++) {
                    if (lsw.labels.get(i) == oldLbl) lsw.labels.set(i, newLbl);
                }
            } else if (insn instanceof LineNumberNode ln) {
                if (ln.start == oldLbl) ln.start = newLbl;
            }
        }

        // 2) 局部变量表
        if (mn.localVariables != null) {
            for (LocalVariableNode lvn : mn.localVariables) {
                if (lvn.start == oldLbl) lvn.start = newLbl;
                if (lvn.end == oldLbl)   lvn.end   = newLbl;
            }
        }

        // 3) try/catch（虽然你已过滤掉，但通用修复保留）
        if (mn.tryCatchBlocks != null) {
            for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
                if (tcb.start == oldLbl)   tcb.start   = newLbl;
                if (tcb.end == oldLbl)     tcb.end     = newLbl;
                if (tcb.handler == oldLbl) tcb.handler = newLbl;
            }
        }
    }

    /** 在插入/替换大量标签后调用，确保 Label 内部索引重置，避免脏缓存 */
    public static void resetAllLabels(MethodNode mn) {
        mn.instructions.resetLabels();
    }
}
