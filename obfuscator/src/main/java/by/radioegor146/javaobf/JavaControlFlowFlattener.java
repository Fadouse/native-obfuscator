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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Advanced control flow flattener with switch-based state machine.
 * Implements true control flow flattening similar to skidfuscator.
 */
public final class JavaControlFlowFlattener implements Opcodes {

    private static final Logger logger = LoggerFactory.getLogger(JavaControlFlowFlattener.class);
    private static final Random RANDOM = new Random();
    private static final boolean DEBUG = false; // Disable for production
    private static ClassProvider classProvider = ClassProvider.ofClasspathFallback();
    private static final boolean ENABLE_SWITCH_STATE_MACHINE = true;

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

        // CRITICAL: Skip methods with try-catch blocks entirely
        // Both state machine and opaque predicates can break exception handling
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
            return false;
        }

        // CRITICAL: Skip methods that throw exceptions (ATHROW instruction)
        // State machine transformation breaks exception propagation semantics
        int realInsnCount = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (op >= 0) {
                realInsnCount++;
            }
            if (op == ATHROW) {
                return false; // Cannot safely transform methods that throw exceptions
            }
        }
        return realInsnCount >= 8; // Require at least 8 instructions
    }

    /**
     * Frame-safe switch-based state machine flattening:
     * Only rewrites at safe points where stack height == 0
     * IMPROVED: Uses in-place label replacement, proper frame cleanup, and try-catch support
     */
    private static boolean applySwitchStateMachineFlattening(MethodNode method, String ownerInternalName, String ownerSuperName, String[] ownerInterfaces) {
        boolean transformed = false;

        // 0) Conservative filtering
        if ((method.access & ACC_ABSTRACT) != 0 || (method.access & ACC_NATIVE) != 0) {
            if (DEBUG) logger.warn("[StateMachine] Skipping abstract/native method");
            return false;
        }
        // IMPORTANT: Skip methods with try-catch blocks for now
        // State machine transformation can break exception handler control flow
        // because the transformation changes basic block connectivity in ways that
        // make it difficult to preserve exception handler semantics correctly.
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
            if (DEBUG) logger.warn("[StateMachine] Skipping method with try-catch blocks");
            return false;
        }

        // Check for problematic instructions
        for (AbstractInsnNode p = method.instructions.getFirst(); p != null; p = p.getNext()) {
            int op = p.getOpcode();
            if (op == MONITORENTER || op == MONITOREXIT || op == JSR || op == RET) {
                if (DEBUG) logger.warn("[StateMachine] Skipping method with monitor/jsr/ret");
                return false;
            }
        }

        // Skip complex methods with backward edges (loops). The current state
        // machine implementation is intentionally conservative because
        // back-edges often require advanced stack modelling and additional
        // synthetic states. Until that support is implemented we bail out and
        // fall back to the legacy bogus jump strategy.
        Map<LabelNode, Integer> labelPositions = new HashMap<>();
        int insnIndex = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label) {
                labelPositions.put(label, insnIndex);
            }
            insnIndex++;
        }

        insnIndex = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof JumpInsnNode jump) {
                Integer target = labelPositions.get(jump.label);
                if (target != null && target <= insnIndex) {
                    if (DEBUG) logger.warn("[StateMachine] Skipping method with backward jump (loop)");
                    return false;
                }
            } else if (insn instanceof TableSwitchInsnNode tableSwitch) {
                Integer target = labelPositions.get(tableSwitch.dflt);
                if (target != null && target <= insnIndex) {
                    if (DEBUG) logger.warn("[StateMachine] Skipping method with backward tableswitch");
                    return false;
                }
                for (LabelNode label : tableSwitch.labels) {
                    Integer t = labelPositions.get(label);
                    if (t != null && t <= insnIndex) {
                        if (DEBUG) logger.warn("[StateMachine] Skipping method with backward tableswitch");
                        return false;
                    }
                }
            } else if (insn instanceof LookupSwitchInsnNode lookupSwitch) {
                Integer target = labelPositions.get(lookupSwitch.dflt);
                if (target != null && target <= insnIndex) {
                    if (DEBUG) logger.warn("[StateMachine] Skipping method with backward lookupswitch");
                    return false;
                }
                for (LabelNode label : lookupSwitch.labels) {
                    Integer t = labelPositions.get(label);
                    if (t != null && t <= insnIndex) {
                        if (DEBUG) logger.warn("[StateMachine] Skipping method with backward lookupswitch");
                        return false;
                    }
                }
            }
            insnIndex++;
        }

        // 1) Build basic blocks
        List<BlockInfo2> blocks = buildBasicBlocks(method);
        if (blocks.size() <= 1) {
            return false;
        }

        // Ensure every block has a tangible entry label so switch dispatch targets
        // point at stable instruction positions. Without this ASM may recycle or
        // inline synthetic labels, leading to bogus default fall-through and
        // ultimately infinite loops in the generated state machine.
        for (BlockInfo2 block : blocks) {
            ensureBlockHasStartLabel(block, method);
        }

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

        // 1.2.1) Handle try-catch blocks: update all try-catch label references
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
            for (TryCatchBlockNode tcb : method.tryCatchBlocks) {
                // Map old labels to new labels for try-catch blocks
                if (tcb.start != null && old2new.containsKey(tcb.start)) {
                    tcb.start = old2new.get(tcb.start);
                }
                if (tcb.end != null && old2new.containsKey(tcb.end)) {
                    tcb.end = old2new.get(tcb.end);
                }
                if (tcb.handler != null && old2new.containsKey(tcb.handler)) {
                    tcb.handler = old2new.get(tcb.handler);
                }
            }
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
                return false; // Skip if frame analysis is incomplete
            }

        } catch (AnalyzerException ae) {
            // Skip flattening if frame analysis fails
            return false;
        }

        // Ensure that every block is entered with an empty operand stack.
        int blockIdx = 0;
        for (BlockInfo2 block : blocks) {
            Frame<?> entryFrame = findEntryFrame(method, block, frames);
            if (entryFrame == null || entryFrame.getStackSize() != 0) {
                return false;
            }
            blockIdx++;
        }

        // Ensure we can rewrite every outgoing transition safely before mutating the method.
        if (!canRewriteAllTransitions(method, blocks, frames)) {
            return false;
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
        if (head == null) {
            return false;
        }

        // CRITICAL: Save prologue.getLast() BEFORE inserting, because insertion modifies the internal links
        AbstractInsnNode prologueLast = prologue.getLast();
        if (prologueLast == null) {
            return false;
        }

        method.instructions.insertBefore(head, prologue);
        method.instructions.insert(prologueLast, dispatcher);

        transformed = true;

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
                        Frame<?> tgtFrame = tgt == null ? null : findEntryFrame(method, tgt, frames);
                        if (tgt != null && tgtFrame != null && tgtFrame.getStackSize() == 0) {
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
                            Frame<?> tTrueFrame = tTrue == null ? null : findEntryFrame(method, tTrue, frames);
                            Frame<?> tFalseFrame = tFalse == null ? null : findEntryFrame(method, tFalse, frames);

                            if (tTrue != null && tFalse != null &&
                                    tTrueFrame != null && tTrueFrame.getStackSize() == 0 &&
                                    tFalseFrame != null && tFalseFrame.getStackSize() == 0) {
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
                        Frame<?> tFrame = t == null ? null : findEntryFrame(method, t, frames);
                        if (t != null && tFrame != null && tFrame.getStackSize() == 0) {
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
        return transformed;
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

        // Add exception handler labels as leaders
        if (m.tryCatchBlocks != null) {
            for (TryCatchBlockNode tcb : m.tryCatchBlocks) {
                if (tcb.handler != null) leaders.add(tcb.handler);
                if (tcb.start != null) leaders.add(tcb.start);
                // tcb.end is exclusive, so the instruction after it starts a new block
                if (tcb.end != null && tcb.end.getNext() != null) {
                    leaders.add(tcb.end);
                }
            }
        }

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

    private static Frame<?> findEntryFrame(MethodNode method, BlockInfo2 block, Frame<?>[] frames) {
        if (block == null) {
            return null;
        }

        // Start from block.begin and find first real instruction
        AbstractInsnNode cursor = block.begin;

        // If the block starts with a label/pseudo instruction, look through the entire block
        while (cursor != null && (cursor instanceof LabelNode || cursor instanceof LineNumberNode || cursor instanceof FrameNode)) {
            // Check if this cursor is still within the block's instructions
            if (!block.insns.contains(cursor)) {
                break; // We've left the block
            }
            cursor = cursor.getNext();
        }

        if (cursor == null || !block.insns.contains(cursor)) {
            // Block contains only pseudo-instructions, or we've left the block
            // Look for the first real instruction in the block's instruction list
            for (AbstractInsnNode insn : block.insns) {
                if (insn.getOpcode() >= 0) {
                    int idx = method.instructions.indexOf(insn);
                    if (idx >= 0 && idx < frames.length) {
                        return frames[idx];
                    }
                }
            }
            // Truly empty block - return null to skip
            return null;
        }

        int index = method.instructions.indexOf(cursor);
        if (index < 0 || index >= frames.length) {
            return null;
        }
        return frames[index];
    }

    private static boolean canRewriteAllTransitions(MethodNode method, List<BlockInfo2> blocks, Frame<?>[] frames) {
        java.util.function.Function<AbstractInsnNode, Frame<?>> frameAt =
                (insn) -> {
                    int idx = method.instructions.indexOf(insn);
                    if (idx < 0 || idx >= frames.length) return null;
                    return frames[idx];
                };

        for (int i = 0; i < blocks.size(); i++) {
            BlockInfo2 block = blocks.get(i);
            AbstractInsnNode end = block.endInclusive;
            AbstractInsnNode endReal = end;
            while (endReal != null && endReal.getOpcode() == -1) {
                endReal = endReal.getPrevious();
            }
            if (endReal == null) {
                continue;
            }

            int opEnd = endReal.getOpcode();

            if (endReal instanceof JumpInsnNode jump) {
                if (opEnd == GOTO) {
                    Frame<?> fIn = frameAt.apply(endReal);
                    BlockInfo2 target = jump == null ? null : findBlockByLabel(blocks, ((JumpInsnNode) endReal).label);
                    Frame<?> targetFrame = target == null ? null : findEntryFrame(method, target, frames);
                    if (fIn == null || fIn.getStackSize() != 0 || targetFrame == null || targetFrame.getStackSize() != 0) {
                        return false;
                    }
                } else if ((opEnd >= IFEQ && opEnd <= IF_ACMPNE) || opEnd == IFNULL || opEnd == IFNONNULL) {
                    AbstractInsnNode nextReal = jump.getNext();
                    while (nextReal != null && nextReal.getOpcode() == -1) {
                        nextReal = nextReal.getNext();
                    }
                    if (nextReal == null) {
                        return false;
                    }

                    Frame<?> afterIf = frameAt.apply(nextReal);
                    BlockInfo2 trueBlock = findBlockByLabel(blocks, jump.label);
                    BlockInfo2 falseBlock = nextFallthroughBlock(blocks, i, method);
                    Frame<?> trueFrame = trueBlock == null ? null : findEntryFrame(method, trueBlock, frames);
                    Frame<?> falseFrame = falseBlock == null ? null : findEntryFrame(method, falseBlock, frames);
                    if (afterIf == null || afterIf.getStackSize() != 0 ||
                            trueFrame == null || trueFrame.getStackSize() != 0 ||
                            falseFrame == null || falseFrame.getStackSize() != 0) {
                        return false;
                    }
                }
            } else if (!(endReal instanceof TableSwitchInsnNode) &&
                    !(endReal instanceof LookupSwitchInsnNode) &&
                    !((opEnd >= IRETURN && opEnd <= RETURN) || opEnd == ATHROW)) {
                AbstractInsnNode nextReal = endReal.getNext();
                while (nextReal != null && nextReal.getOpcode() == -1) {
                    nextReal = nextReal.getNext();
                }
                if (nextReal == null) {
                    return false;
                }

                Frame<?> after = frameAt.apply(nextReal);
                BlockInfo2 fallthrough = nextFallthroughBlock(blocks, i, method);
                Frame<?> fallthroughFrame = fallthrough == null ? null : findEntryFrame(method, fallthrough, frames);
                if (after == null || after.getStackSize() != 0 ||
                        fallthroughFrame == null || fallthroughFrame.getStackSize() != 0) {
                    return false;
                }
            }
        }

        // Additional check: analyze local variable usage across all blocks
        // State machine transformation can cause COMPUTE_FRAMES to fail when methods use
        // many local variables, because the transformed control flow makes it difficult
        // for ASM to infer correct local variable table states at all entry points.

        // Count max local variable index used in the method
        int maxLocalUsed = -1;
        for (BlockInfo2 block : blocks) {
            for (AbstractInsnNode insn : block.insns) {
                if (insn instanceof VarInsnNode) {
                    VarInsnNode varInsn = (VarInsnNode) insn;
                    if (varInsn.var > maxLocalUsed) {
                        maxLocalUsed = varInsn.var;
                    }
                } else if (insn instanceof IincInsnNode) {
                    IincInsnNode iinc = (IincInsnNode) insn;
                    if (iinc.var > maxLocalUsed) {
                        maxLocalUsed = iinc.var;
                    }
                }
            }
        }

        // If method uses more than 3 local variable slots (beyond 'this'), be conservative
        // This prevents frame inconsistencies where COMPUTE_FRAMES cannot infer
        // which locals are live at each block entry point after state machine transformation
        if (maxLocalUsed > 3) {
            return false;
        }

        return true;
    }

    private static BlockInfo2 findBlockByLabel(List<BlockInfo2> blocks, LabelNode label) {
        if (label == null) {
            return null;
        }
        for (BlockInfo2 block : blocks) {
            if (block.startLabel == label) {
                return block;
            }
        }
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

        // DEBUG: Confirm this method is called

        try {
            // Try state machine flattening first (works for all strength levels)
            boolean flattened = false;
            if (ENABLE_SWITCH_STATE_MACHINE) {
                flattened = applySwitchStateMachineFlattening(method, ownerInternalName, ownerSuperName, ownerInterfaces);
            }

            // If state machine flattening succeeded, optionally add light obfuscation on top
            if (flattened) {
                switch (strength) {
                    case LOW:
                        // State machine only, minimal additional obfuscation
                        break;
                    case MEDIUM:
                        // State machine + light opaque predicates
                        injectOpaquePredicates(method, 0.05);
                        break;
                    case HIGH:
                        // State machine + moderate opaque predicates
                        injectOpaquePredicates(method, 0.1);
                        break;
                }
            } else {
                // Fallback: use legacy obfuscation methods when state machine fails
                switch (strength) {
                    case LOW:
                        injectOpaquePredicates(method, 0.1);
                        break;
                    case MEDIUM:
                        injectOpaquePredicates(method, 0.2);
                        break;
                    case HIGH:
                        injectOpaquePredicates(method, 0.3);
                        injectDeadCode(method, 0.1);
                        break;
                }
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
        if (ENABLE_SWITCH_STATE_MACHINE) {
            try {
                boolean flattened = applySwitchStateMachineFlattening(method, ownerInternalName, ownerSuperName, ownerInterfaces);
                if (flattened) {
                    if (addOpaquePredicates) injectOpaquePredicates(method, 0.10);
                    return; // 成功即返回
                }
            } catch (Throwable t) {
                // 回退到原先的轻量方案
            }
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
        // Build set of labels that are inside try-catch blocks
        Set<LabelNode> tryCatchLabels = new HashSet<>();
        if (method.tryCatchBlocks != null) {
            for (TryCatchBlockNode tcb : method.tryCatchBlocks) {
                // Collect all labels between tcb.start and tcb.end
                boolean inRange = false;
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn == tcb.start) {
                        inRange = true;
                    }
                    if (inRange && insn instanceof LabelNode) {
                        tryCatchLabels.add((LabelNode) insn);
                    }
                    if (insn == tcb.end) {
                        inRange = false;
                    }
                }
                // Also mark handler labels
                if (tcb.handler != null) {
                    tryCatchLabels.add(tcb.handler);
                }
            }
        }

        List<AbstractInsnNode> safePoints = new ArrayList<>();

        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof LabelNode)) continue;

            // Skip labels inside try-catch blocks
            if (tryCatchLabels.contains((LabelNode) insn)) {
                continue;
            }

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
