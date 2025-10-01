package by.radioegor146.javaobf;

import by.radioegor146.javaobf.frame.AsmSanity;
import by.radioegor146.javaobf.frame.ClassProvider;
import by.radioegor146.javaobf.frame.PreAnalyzer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.*;

/**
 * Advanced control flow flattener with switch-based state machine.
 * Implements true control flow flattening similar to skidfuscator.
 */
public final class JavaControlFlowFlattener implements Opcodes {

    private static final Random RANDOM = new Random();
    private static final boolean DEBUG = false;
    private static ClassProvider classProvider = ClassProvider.ofClasspathFallback();

    public static boolean canProcess(MethodNode method) {
        if (method == null || method.instructions == null || method.instructions.size() == 0) {
            return false;
        }
        if ((method.access & ACC_ABSTRACT) != 0 || (method.access & ACC_NATIVE) != 0) {
            return false;
        }
        if ("<init>".equals(method.name) || "<clinit>".equals(method.name)) {
            return false;
        }

        int realInsnCount = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() >= 0) {
                realInsnCount++;
            }
        }
        return realInsnCount >= 8; // Require at least 8 instructions
    }

    /**
     * Frame-safe switch-based state machine flattening:
     * Only rewrites at safe points where stack height == 0
     * IMPROVED: Uses in-place label replacement and proper frame cleanup
     */
    private static void applySwitchStateMachineFlattening(MethodNode method, String ownerInternalName, String ownerSuperName, String[] ownerInterfaces) {
        // 0) Conservative filtering
        if ((method.access & ACC_ABSTRACT) != 0 || (method.access & ACC_NATIVE) != 0) return;
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) return;

        // Check for problematic instructions
        for (AbstractInsnNode p = method.instructions.getFirst(); p != null; p = p.getNext()) {
            int op = p.getOpcode();
            if (op == MONITORENTER || op == MONITOREXIT || op == JSR || op == RET) return;
        }

        // 1) Build basic blocks
        List<BlockInfo2> blocks = buildBasicBlocks(method);
        if (blocks.size() <= 1) return;

        // 1.1) Assign state ID to each block
        Map<BlockInfo2, Integer> stateOf = new HashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            stateOf.put(blocks.get(i), i);
        }

        // 1.2) CRITICAL FIX: In-place label replacement to avoid "ghost labels"
        // Create new label for each block and use AsmSanity.replaceLabelEverywhere
        Map<LabelNode, LabelNode> old2new = new HashMap<>();
        Map<LabelNode, BlockInfo2> newLabel2block = new HashMap<>();

        for (BlockInfo2 b : blocks) {
            LabelNode oldLabel = b.startLabel;
            LabelNode freshLabel = new LabelNode();

            old2new.put(oldLabel, freshLabel);

            // Replace everywhere: jumps, switches, line numbers, LVT, try/catch
            AsmSanity.replaceLabelEverywhere(method, oldLabel, freshLabel);

            // Update our tracking
            b.startLabel = freshLabel;
            newLabel2block.put(freshLabel, b);
        }

        // 1.3) Pre-analyze frames for stack depth checking (safe points)
        final Frame<BasicValue>[] frames;
        try {
            boolean isStatic = (method.access & ACC_STATIC) != 0;
            frames = PreAnalyzer.analyzeWithFallback(
                    ownerInternalName,
                    ownerSuperName,
                    ownerInterfaces,
                    method,
                    classProvider,
                    isStatic
            );

            // Validate frames
            if (frames == null || frames.length != method.instructions.size()) {
                return; // Skip if frame analysis is incomplete
            }

        } catch (AnalyzerException ae) {
            // Skip flattening if frame analysis fails
            return;
        }

        // 2) Add state variable
        int stateLocal = method.maxLocals;
        method.maxLocals = stateLocal + 1;

        // 3) Create dispatcher infrastructure with unique labels
        LabelNode L_head     = new LabelNode();
        LabelNode L_dispatch = new LabelNode();
        LabelNode L_default  = new LabelNode();
        int entryState = stateOf.get(blocks.get(0));

        InsnList prologue = new InsnList();
        prologue.add(L_head);
        prologue.add(iconst(entryState));
        prologue.add(new VarInsnNode(ISTORE, stateLocal));
        prologue.add(new JumpInsnNode(GOTO, L_dispatch));

        // 4) Build dispatcher switch
        int n = blocks.size();
        int[] keys = new int[n];
        LabelNode[] labels = new LabelNode[n];
        for (BlockInfo2 b : blocks) {
            int k = stateOf.get(b);
            keys[k] = k;
            // Use the NEW label after replacement
            labels[k] = b.startLabel;
        }
        LookupSwitchInsnNode lsw = new LookupSwitchInsnNode(L_default, keys, labels);

        InsnList dispatcher = new InsnList();
        dispatcher.add(L_dispatch);
        dispatcher.add(new VarInsnNode(ILOAD, stateLocal));
        dispatcher.add(lsw);
        dispatcher.add(L_default);
        dispatcher.add(new InsnNode(NOP));

        // 5) Insert at method start
        AbstractInsnNode head = method.instructions.getFirst();
        if (head == null) return;
        method.instructions.insertBefore(head, prologue);
        method.instructions.insert(prologue.getLast(), dispatcher);

        // 6) Rewrite control flow only at safe points (stack height == 0)
        java.util.function.Function<AbstractInsnNode, Frame<?>> frameAt =
                (insn) -> {
                    int idx = method.instructions.indexOf(insn);
                    if (idx < 0 || idx >= frames.length) return null;
                    return frames[idx];
                };

        for (int i = 0; i < blocks.size(); i++) {
            BlockInfo2 b = blocks.get(i);
            AbstractInsnNode end = b.endInclusive;
            AbstractInsnNode endReal = end;

            while (endReal != null && endReal.getOpcode() == -1) {
                endReal = endReal.getPrevious();
            }
            if (endReal == null) continue;

            int opEnd = endReal.getOpcode();

            if (endReal instanceof JumpInsnNode) {
                JumpInsnNode j = (JumpInsnNode) endReal;
                if (opEnd == GOTO) {
                    Frame<?> fIn = frameAt.apply(endReal);
                    if (fIn != null && fIn.getStackSize() == 0) {
                        BlockInfo2 tgt = newLabel2block.get(j.label);
                        if (tgt != null) {
                            InsnList repl = makeSetStateAndGoto(stateLocal, stateOf.get(tgt), L_dispatch);
                            method.instructions.insertBefore(endReal, repl);
                            method.instructions.remove(endReal);
                        }
                    }
                } else if ((opEnd >= IFEQ && opEnd <= IF_ACMPNE) || opEnd == IFNULL || opEnd == IFNONNULL) {
                    AbstractInsnNode nextReal = j.getNext();
                    while (nextReal != null && nextReal.getOpcode() == -1) nextReal = nextReal.getNext();

                    if (nextReal != null) {
                        Frame<?> fAfterIf = frameAt.apply(nextReal);
                        if (fAfterIf != null && fAfterIf.getStackSize() == 0) {
                            BlockInfo2 tTrue  = newLabel2block.get(j.label);
                            BlockInfo2 tFalse = nextFallthroughBlock(blocks, i, method);

                            if (tTrue != null && tFalse != null) {
                                // CRITICAL: Always create fresh label for branch target
                                LabelNode L_setTrue = new LabelNode();
                                j.label = L_setTrue;

                                InsnList patch = new InsnList();
                                patch.add(makeSetStateAndGotoRaw(stateLocal, stateOf.get(tFalse), L_dispatch));
                                patch.add(L_setTrue);
                                patch.add(makeSetStateAndGotoRaw(stateLocal, stateOf.get(tTrue), L_dispatch));

                                method.instructions.insert(j, patch);
                            }
                        }
                    }
                }
            } else if (!(endReal instanceof TableSwitchInsnNode) &&
                    !(endReal instanceof LookupSwitchInsnNode) &&
                    !((opEnd >= IRETURN && opEnd <= RETURN) || opEnd == ATHROW)) {
                AbstractInsnNode nextReal = endReal.getNext();
                while (nextReal != null && nextReal.getOpcode() == -1) nextReal = nextReal.getNext();

                if (nextReal != null) {
                    Frame<?> fAfter = frameAt.apply(nextReal);
                    if (fAfter != null && fAfter.getStackSize() == 0) {
                        BlockInfo2 t = nextFallthroughBlock(blocks, i, method);
                        if (t != null) {
                            InsnList tail = makeSetStateAndGoto(stateLocal, stateOf.get(t), L_dispatch);
                            method.instructions.insert(endReal, tail);
                        }
                    }
                }
            }
        }

        // 7) CRITICAL: Clean up after transformation
        // Reset label indices to clear ASM's internal caches
        AsmSanity.resetAllLabels(method);

        // Strip all frame nodes - let COMPUTE_FRAMES recalculate everything
        AsmSanity.stripAllFrames(method);

        // maxLocals already updated above; maxStack will be recomputed by COMPUTE_MAXS
    }
    /* ------------------------ 工具与数据结构 ------------------------ */

    private static class BlockInfo2 {
        LabelNode startLabel;                   // 起始标签
        AbstractInsnNode begin;                 // 第一条指令
        AbstractInsnNode endInclusive;          // 末尾指令（包含）
        final List<AbstractInsnNode> insns = new ArrayList<>();
    }



    private static List<BlockInfo2> buildBasicBlocks(MethodNode m) {
        // 复用您已有的 leaders 发现逻辑，这里确保每个 leader 前有 LabelNode
        Set<AbstractInsnNode> leaders = new HashSet<>();
        AbstractInsnNode first = m.instructions.getFirst();
        if (first != null) leaders.add(first);

        for (AbstractInsnNode p = first; p != null; p = p.getNext()) {
            if (p instanceof JumpInsnNode) {
                leaders.add(((JumpInsnNode) p).label);
                if (p.getNext() != null) leaders.add(p.getNext());
            } else if (p instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode ts = (TableSwitchInsnNode) p;
                leaders.add(ts.dflt);
                for (LabelNode L : ts.labels) leaders.add(L);
                if (p.getNext() != null) leaders.add(p.getNext());
            } else if (p instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode ls = (LookupSwitchInsnNode) p;
                leaders.add(ls.dflt);
                for (LabelNode L : ls.labels) leaders.add(L);
                if (p.getNext() != null) leaders.add(p.getNext());
            }
        }

        List<BlockInfo2> out = new ArrayList<>();
        BlockInfo2 cur = null;

        for (AbstractInsnNode p = first; p != null; p = p.getNext()) {
            if (leaders.contains(p)) {
                if (cur != null && !cur.insns.isEmpty()) {
                    cur.endInclusive = cur.insns.get(cur.insns.size() - 1);
                    out.add(cur);
                }
                cur = new BlockInfo2();
                cur.begin = p;
            }
            if (cur == null) {
                cur = new BlockInfo2();
                cur.begin = p;
            }
            cur.insns.add(p);

            int op = p.getOpcode();
            if (op == GOTO || op == ATHROW ||
                    (op >= IRETURN && op <= RETURN) ||
                    p instanceof TableSwitchInsnNode || p instanceof LookupSwitchInsnNode) {
                cur.endInclusive = p;
                out.add(cur);
                cur = null;
            }
        }
        if (cur != null && !cur.insns.isEmpty()) {
            cur.endInclusive = cur.insns.get(cur.insns.size() - 1);
            out.add(cur);
        }

        return out;
    }

    private static void ensureBlockHasStartLabel(BlockInfo2 b, MethodNode m) {
        AbstractInsnNode first = b.begin;
        if (first instanceof LabelNode) {
            b.startLabel = (LabelNode) first;
            return;
        }
        LabelNode L = new LabelNode();
        m.instructions.insertBefore(first, L);
        b.startLabel = L;
    }

    private static boolean isUnconditionalGoto(int op) { return op == GOTO; }

    private static boolean isConditionalJump(int op) {
        return (op >= IFEQ && op <= IF_ACMPNE) || op == IFNULL || op == IFNONNULL;
    }

    private static boolean isReturnOrThrow(int op) {
        return (op >= IRETURN && op <= RETURN) || op == ATHROW;
    }

    private static BlockInfo2 nextFallthroughBlock(List<BlockInfo2> blocks, int idx, MethodNode m) {
        // 物理顺序上的下一个块即为落空目标（不跨方法结尾）
        if (idx + 1 < blocks.size()) return blocks.get(idx + 1);
        return null;
    }

    private static AbstractInsnNode iconst(int v) {
        if (v >= -1 && v <= 5) return new InsnNode(ICONST_0 + (v + 1));
        if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) return new IntInsnNode(BIPUSH, v);
        if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) return new IntInsnNode(SIPUSH, v);
        return new LdcInsnNode(v);
    }

    private static InsnList makeSetStateAndGoto(int stateLocal, int state, LabelNode L_dispatch) {
        InsnList il = new InsnList();
        il.add(iconst(state));
        il.add(new VarInsnNode(ISTORE, stateLocal));
        il.add(new JumpInsnNode(GOTO, L_dispatch));
        return il;
    }
    private static InsnList makeSetStateAndGotoRaw(int stateLocal, int state, LabelNode L_dispatch) {
        // 与上相同，便于内联拼装
        return makeSetStateAndGoto(stateLocal, state, L_dispatch);
    }

    public static void flatten(MethodNode method, String debugName, JavaObfuscationConfig.Strength strength, String ownerInternalName, String ownerSuperName, String[] ownerInterfaces) {
        if (!canProcess(method)) {
            return;
        }

        try {
            switch (strength) {
                case LOW:
                    // LOW: Basic opaque predicates only
                    injectOpaquePredicates(method, 0.1);
                    break;
                case MEDIUM:
                    // MEDIUM: Light flattening + opaque predicates
                    if (shouldFlatten(method, 0.3)) {
                        applyControlFlowFlattening(method, false, ownerInternalName, ownerSuperName, ownerInterfaces);
                    } else {
                        injectOpaquePredicates(method, 0.2);
                    }
                    break;
                case HIGH:
                    // HIGH: Full flattening + opaque predicates
                    if (shouldFlatten(method, 0.6)) {
                        applyControlFlowFlattening(method, true, ownerInternalName, ownerSuperName, ownerInterfaces);
                    } else {
                        injectOpaquePredicates(method, 0.3);
                        injectDeadCode(method, 0.1);
                    }
                    break;
            }
        } catch (Exception e) {
            if (DEBUG) {
                throw new RuntimeException("Failed to flatten method: " + debugName, e);
            }
            // Silently fail and leave method unchanged
        }
    }

    /**
     * Decide if we should apply full flattening based on method complexity
     */
    private static boolean shouldFlatten(MethodNode method, double probability) {
        // Don't flatten very simple or very complex methods
        int insnCount = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() >= 0) insnCount++;
        }

        if (insnCount < 10 || insnCount > 200) {
            return false;
        }

        // Check for complex constructs that are hard to flatten
        boolean hasTryCatch = method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty();
        boolean hasSwitch = false;

        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
                hasSwitch = true;
                break;
            }
        }

        // Be more conservative with complex methods
        if (hasTryCatch || hasSwitch) {
            probability *= 0.5;
        }

        return RANDOM.nextDouble() < probability;
    }

    /**
     * Apply frame-safe control flow flattening
     * Uses a partial flattening technique that maintains stack frame validity
     */
    private static void applyControlFlowFlattening(MethodNode method, boolean addOpaquePredicates, String ownerInternalName, String ownerSuperName, String[] ownerInterfaces) {
        // 先进行可处理性检查（保留原判定逻辑）
        List<BlockInfo> blocks = analyzeBlocks(method);
        if (blocks.size() <= 2) {
            if (addOpaquePredicates) injectOpaquePredicates(method, 0.15);
            return;
        }

        // 新：优先尝试状态机扁平化（支持 try/catch）
        try {
            applySwitchStateMachineFlattening(method, ownerInternalName, ownerSuperName, ownerInterfaces);
            if (addOpaquePredicates) injectOpaquePredicates(method, 0.10);
            return; // 成功即返回
        } catch (Throwable t) {
            // 回退到原先的轻量方案
        }

        // 旧：退化为 bogus 分支与不破坏帧的轻度扰动
        applyBogusJumps(method, blocks, addOpaquePredicates);
        if (addOpaquePredicates) injectOpaquePredicates(method, 0.10);
    }
    /**
     * Apply bogus jump obfuscation - adds fake conditional branches
     */
    private static void applyBogusJumps(MethodNode method, List<BlockInfo> blocks, boolean aggressive) {
        double probability = aggressive ? 0.4 : 0.2;

        for (BlockInfo block : blocks) {
            if (RANDOM.nextDouble() > probability) continue;

            // Find a good insertion point
            AbstractInsnNode insertPoint = findSafeInsertionPoint(block);
            if (insertPoint == null) continue;

            InsnList bogus = new InsnList();
            LabelNode realPath = new LabelNode();
            LabelNode fakePath = new LabelNode();

            // Create opaque condition: (x * 2) / 2 == x
            int x = RANDOM.nextInt(50) + 10;
            bogus.add(new LdcInsnNode(x));
            bogus.add(new InsnNode(DUP));
            bogus.add(new LdcInsnNode(2));
            bogus.add(new InsnNode(IMUL));
            bogus.add(new LdcInsnNode(2));
            bogus.add(new InsnNode(IDIV));
            bogus.add(new JumpInsnNode(IF_ICMPNE, fakePath)); // Always false

            // Real path
            bogus.add(realPath);
            bogus.add(new FrameNode(F_SAME, 0, null, 0, null));

            // Fake path (never taken)
            bogus.add(fakePath);
            bogus.add(new FrameNode(F_SAME, 0, null, 0, null));

            // Add some fake operations
            int fakeOps = RANDOM.nextInt(3) + 2;
            for (int i = 0; i < fakeOps; i++) {
                switch (RANDOM.nextInt(4)) {
                    case 0:
                        bogus.add(new LdcInsnNode(RANDOM.nextInt(1000)));
                        bogus.add(new InsnNode(POP));
                        break;
                    case 1:
                        bogus.add(new LdcInsnNode(RANDOM.nextInt(1000)));
                        bogus.add(new LdcInsnNode(RANDOM.nextInt(1000)));
                        bogus.add(new InsnNode(IADD));
                        bogus.add(new InsnNode(POP));
                        break;
                    case 2:
                        bogus.add(new InsnNode(ACONST_NULL));
                        bogus.add(new InsnNode(POP));
                        break;
                    case 3:
                        bogus.add(new TypeInsnNode(NEW, "java/lang/Object"));
                        bogus.add(new InsnNode(POP));
                        break;
                }
            }

            // Jump back to real path
            bogus.add(new JumpInsnNode(GOTO, realPath));

            try {
                method.instructions.insert(insertPoint, bogus);
            } catch (Exception e) {
                // Skip on error
            }
        }
    }

    /**
     * Find safe insertion point in a block
     */
    private static AbstractInsnNode findSafeInsertionPoint(BlockInfo block) {
        for (AbstractInsnNode insn : block.instructions) {
            if (insn instanceof LabelNode) {
                AbstractInsnNode next = insn.getNext();
                while (next != null && (next instanceof LineNumberNode || next instanceof FrameNode)) {
                    next = next.getNext();
                }

                if (next != null && isSafeForInsertion(next)) {
                    return insn;
                }
            }
        }
        return null;
    }

    /**
     * Check if instruction is safe for insertion before
     */
    private static boolean isSafeForInsertion(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        return (opcode >= ICONST_M1 && opcode <= DCONST_1) ||
               (opcode >= ILOAD && opcode <= ALOAD) ||
               opcode == GETSTATIC || opcode == NEW ||
               opcode == ACONST_NULL || opcode == LDC ||
               opcode == RETURN || opcode == ARETURN ||
               opcode == IRETURN || opcode == LRETURN ||
               opcode == FRETURN || opcode == DRETURN;
    }

    /**
     * Analyze method into blocks
     */
    private static List<BlockInfo> analyzeBlocks(MethodNode method) {
        List<BlockInfo> blocks = new ArrayList<>();
        Set<AbstractInsnNode> leaders = new HashSet<>();

        // Find block leaders
        AbstractInsnNode first = method.instructions.getFirst();
        if (first != null) {
            leaders.add(first);
        }

        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof JumpInsnNode) {
                leaders.add(((JumpInsnNode) insn).label);
                if (insn.getNext() != null) {
                    leaders.add(insn.getNext());
                }
            } else if (insn instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode tsw = (TableSwitchInsnNode) insn;
                leaders.add(tsw.dflt);
                for (LabelNode label : tsw.labels) {
                    leaders.add(label);
                }
                if (insn.getNext() != null) {
                    leaders.add(insn.getNext());
                }
            } else if (insn instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode lsw = (LookupSwitchInsnNode) insn;
                leaders.add(lsw.dflt);
                for (LabelNode label : lsw.labels) {
                    leaders.add(label);
                }
                if (insn.getNext() != null) {
                    leaders.add(insn.getNext());
                }
            }
        }

        // Build blocks
        BlockInfo currentBlock = null;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (leaders.contains(insn)) {
                if (currentBlock != null && !currentBlock.instructions.isEmpty()) {
                    blocks.add(currentBlock);
                }
                currentBlock = new BlockInfo();
            }

            if (currentBlock == null) {
                currentBlock = new BlockInfo();
            }

            currentBlock.instructions.add(insn);

            int opcode = insn.getOpcode();
            if (opcode == GOTO || opcode == ATHROW ||
                (opcode >= IRETURN && opcode <= RETURN) ||
                insn instanceof TableSwitchInsnNode ||
                insn instanceof LookupSwitchInsnNode) {
                if (!currentBlock.instructions.isEmpty()) {
                    blocks.add(currentBlock);
                }
                currentBlock = null;
            }
        }

        if (currentBlock != null && !currentBlock.instructions.isEmpty()) {
            blocks.add(currentBlock);
        }

        return blocks;
    }


    /**
     * Inject opaque predicates
     */
    private static void injectOpaquePredicates(MethodNode method, double probability) {
        List<AbstractInsnNode> safePoints = new ArrayList<>();

        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof LabelNode)) continue;

            AbstractInsnNode next = insn.getNext();
            while (next != null && (next instanceof LineNumberNode || next instanceof FrameNode)) {
                next = next.getNext();
            }

            if (next == null) continue;

            int opcode = next.getOpcode();
            boolean safe = (opcode >= ICONST_M1 && opcode <= DCONST_1) ||
                          (opcode >= ILOAD && opcode <= ALOAD) ||
                          opcode == GETSTATIC || opcode == NEW ||
                          opcode == ACONST_NULL || opcode == LDC ||
                          opcode == RETURN;

            if (safe && RANDOM.nextDouble() < probability) {
                safePoints.add(insn);
            }
        }

        for (AbstractInsnNode point : safePoints) {
            try {
                InsnList predicate = new InsnList();
                addOpaquePredicate(predicate);
                method.instructions.insert(point, predicate);
            } catch (Exception e) {
                // Skip
            }
        }
    }

    private static void addOpaquePredicate(InsnList list) {
        int x = RANDOM.nextInt(50) + 10;
        LabelNode continueLabel = new LabelNode();

        list.add(new LdcInsnNode(x));
        list.add(new InsnNode(DUP));
        list.add(new LdcInsnNode(2));
        list.add(new InsnNode(IMUL));
        list.add(new LdcInsnNode(2));
        list.add(new InsnNode(IDIV));
        list.add(new JumpInsnNode(IF_ICMPEQ, continueLabel));

        list.add(new TypeInsnNode(NEW, "java/lang/RuntimeException"));
        list.add(new InsnNode(DUP));
        list.add(new LdcInsnNode("Integrity check failed"));
        list.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/RuntimeException",
                                     "<init>", "(Ljava/lang/String;)V", false));
        list.add(new InsnNode(ATHROW));

        list.add(continueLabel);
    }

    /**
     * Inject dead code
     */
    private static void injectDeadCode(MethodNode method, double probability) {
        List<AbstractInsnNode> safePoints = new ArrayList<>();

        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof LabelNode)) continue;

            AbstractInsnNode next = insn.getNext();
            while (next != null && (next instanceof LineNumberNode || next instanceof FrameNode)) {
                next = next.getNext();
            }

            if (next == null) continue;

            int opcode = next.getOpcode();
            boolean safe = (opcode >= ICONST_M1 && opcode <= DCONST_1) ||
                          (opcode >= ILOAD && opcode <= ALOAD) ||
                          opcode == GETSTATIC || opcode == NEW ||
                          opcode == ACONST_NULL || opcode == LDC;

            if (safe && RANDOM.nextDouble() < probability) {
                safePoints.add(insn);
            }
        }

        for (AbstractInsnNode point : safePoints) {
            try {
                InsnList deadCode = new InsnList();
                LabelNode skipLabel = new LabelNode();

                deadCode.add(new LdcInsnNode(1));
                deadCode.add(new LdcInsnNode(2));
                deadCode.add(new JumpInsnNode(IF_ICMPEQ, skipLabel));

                int ops = RANDOM.nextInt(3) + 2;
                for (int i = 0; i < ops; i++) {
                    switch (RANDOM.nextInt(3)) {
                        case 0:
                            deadCode.add(new LdcInsnNode(RANDOM.nextInt(1000)));
                            deadCode.add(new InsnNode(POP));
                            break;
                        case 1:
                            deadCode.add(new LdcInsnNode(RANDOM.nextInt(1000)));
                            deadCode.add(new LdcInsnNode(RANDOM.nextInt(1000)));
                            deadCode.add(new InsnNode(IADD));
                            deadCode.add(new InsnNode(POP));
                            break;
                        case 2:
                            deadCode.add(new InsnNode(ACONST_NULL));
                            deadCode.add(new InsnNode(POP));
                            break;
                    }
                }

                deadCode.add(skipLabel);
                method.instructions.insert(point, deadCode);
            } catch (Exception e) {
                // Skip
            }
        }
    }

    private static class BlockInfo {
        List<AbstractInsnNode> instructions = new ArrayList<>();
    }
}
