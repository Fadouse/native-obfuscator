package dev.skidfuscator.obfuscator.transform.impl.flow.condition;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.event.annotation.Listen;
import dev.skidfuscator.obfuscator.event.impl.transform.method.RunMethodTransformEvent;
import dev.skidfuscator.obfuscator.number.hash.HashTransformer;
import dev.skidfuscator.obfuscator.number.hash.SkiddedHash;
import dev.skidfuscator.obfuscator.skidasm.SkidMethodNode;
import dev.skidfuscator.obfuscator.skidasm.cfg.SkidBlock;
import dev.skidfuscator.obfuscator.skidasm.cfg.SkidControlFlowGraph;
import dev.skidfuscator.obfuscator.skidasm.fake.FakeConditionalJumpStmt;
import dev.skidfuscator.obfuscator.transform.AbstractTransformer;
import dev.skidfuscator.obfuscator.transform.Transformer;
import dev.skidfuscator.obfuscator.transform.exempt.MethodExempt;
import dev.skidfuscator.obfuscator.transform.impl.flow.FlowObfuscationProfile;
import dev.skidfuscator.obfuscator.util.RandomUtil;
import org.mapleir.flowgraph.edges.ConditionalJumpEdge;
import org.mapleir.flowgraph.edges.UnconditionalJumpEdge;
import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.Stmt;
import org.mapleir.ir.code.expr.ArithmeticExpr;
import org.mapleir.ir.code.expr.ConstantExpr;
import org.mapleir.ir.code.stmt.ConditionalJumpStmt;
import org.mapleir.ir.code.stmt.UnconditionalJumpStmt;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.*;

public class BasicConditionTransformer extends AbstractTransformer {
    private final FlowObfuscationProfile profile;

    public BasicConditionTransformer(Skidfuscator skidfuscator) {
        this(skidfuscator, Collections.emptyList());
    }

    public BasicConditionTransformer(Skidfuscator skidfuscator, List<Transformer> children) {
        super(skidfuscator,"Flow Condition", children);
        this.profile = FlowObfuscationProfile.fromConfig(skidfuscator.getTsConfig());
    }

    @Listen
    void handle(final RunMethodTransformEvent event) {
        final SkidMethodNode methodNode = event.getMethodNode();

        if (methodNode.isAbstract() || methodNode.isInit()) {
            this.skip();
            return;
        }

        if (methodNode.isExempt(MethodExempt.INTERFACE)) {
            this.skip();
            return;
        }

        final SkidControlFlowGraph cfg = methodNode.getCfg();

        if (cfg == null) {
            this.fail();
            return;
        }

        for (BasicBlock parent : new HashSet<>(cfg.vertices())) {
            if (parent.size() == 0)
                continue;

            if (parent.isFlagSet(SkidBlock.FLAG_NO_OPAQUE))
                continue;

            if (this.heuristicSizeSkip(methodNode, 8.f)) {
                continue;
            }

            final Stmt stmt = parent.get(parent.size() - 1);

            if (!(stmt instanceof ConditionalJumpStmt) || stmt instanceof FakeConditionalJumpStmt) {
                continue;
            }

            final ConditionalJumpEdge<BasicBlock> edge = cfg
                    .getEdges(parent)
                    .stream()
                    .filter(e -> e instanceof ConditionalJumpEdge)
                    .map(e -> (ConditionalJumpEdge) e)
                    .filter(e -> e.dst() == ((ConditionalJumpStmt) stmt).getTrueSuccessor())
                    .findFirst()
                    .orElse(null);

            if (edge == null)
                continue;

            final ConditionalJumpStmt jump = (ConditionalJumpStmt) stmt;
            final List<SkidBlock> guards = createGuardChain(cfg, methodNode, jump.getTrueSuccessor());

            if (guards.isEmpty()) {
                continue;
            }

            cfg.removeEdge(edge);
            cfg.addEdge(new ConditionalJumpEdge<>(
                    edge.src(),
                    guards.get(0),
                    edge.opcode
            ));

            jump.setTrueSuccessor(guards.get(0));

            event.tick();
        }

        this.success();
    }

    private List<SkidBlock> createGuardChain(final SkidControlFlowGraph cfg,
                                             final SkidMethodNode methodNode,
                                             final BasicBlock finalTarget) {
        final int depth = profile.guardDepth();
        if (depth <= 0) {
            return Collections.emptyList();
        }

        final BasicBlock exception = cfg.getFuckup();
        final List<SkidBlock> guards = new ArrayList<>(depth);
        BasicBlock nextTarget = finalTarget;

        for (int index = depth - 1; index >= 0; index--) {
            final SkidBlock guard = new SkidBlock(cfg);
            cfg.addVertex(guard);

            final GuardMutation mutation = GuardMutation.random(profile);
            final HashTransformer transformer = skidfuscator.getVmHasher();
            final SkiddedHash hash = transformer.hash(
                    methodNode.getBlockPredicate(guard),
                    guard,
                    methodNode.getFlowPredicate().getGetter()
            );

            Expr left = mutation.apply(hash.getExpr());
            final int expected = mutation.apply(hash.getHash());

            final ConditionalJumpStmt guardStmt = new ConditionalJumpStmt(
                    left,
                    new ConstantExpr(expected, Type.INT_TYPE),
                    nextTarget,
                    ConditionalJumpStmt.ComparisonType.EQ
            );
            guard.add(guardStmt);

            cfg.addEdge(new ConditionalJumpEdge<>(
                    guard,
                    nextTarget,
                    Opcodes.IFEQ
            ));

            appendTrap(cfg, guard, exception);

            guards.add(0, guard);
            nextTarget = guard;
        }

        return guards;
    }

    private void appendTrap(final SkidControlFlowGraph cfg,
                            final SkidBlock guard,
                            final BasicBlock exception) {
        final UnconditionalJumpEdge<BasicBlock> trapEdge = new UnconditionalJumpEdge<>(guard, exception);
        guard.add(new UnconditionalJumpStmt(exception, trapEdge));
        cfg.addEdge(trapEdge);
    }

    private static final class GuardMutation {
        private final List<MutationOp> operations;

        private GuardMutation(List<MutationOp> operations) {
            this.operations = operations;
        }

        static GuardMutation random(FlowObfuscationProfile profile) {
            final int opCount = profile.randomMutationCount();
            final List<MutationOp> ops = new ArrayList<>(opCount);
            for (int i = 0; i < opCount; i++) {
                final FlowObfuscationProfile.MutationType type = profile.pickMutationType();
                final int operand = nonZeroInt();
                ops.add(new MutationOp(type, operand));
            }
            return new GuardMutation(ops);
        }

        Expr apply(Expr expr) {
            Expr current = expr;
            for (MutationOp op : operations) {
                current = new ArithmeticExpr(
                        current,
                        new ConstantExpr(op.operand(), Type.INT_TYPE),
                        op.operator()
                );
            }
            return current;
        }

        int apply(int value) {
            int result = value;
            for (MutationOp op : operations) {
                result = op.apply(result);
            }
            return result;
        }

        private static int nonZeroInt() {
            int value;
            do {
                value = (int) RandomUtil.nextLong();
            } while (value == 0);
            return value;
        }
    }

        private record MutationOp(FlowObfuscationProfile.MutationType type, int operand) {
            ArithmeticExpr.Operator operator() {
                return switch (type) {
                    case XOR -> ArithmeticExpr.Operator.XOR;
                    case ADD -> ArithmeticExpr.Operator.ADD;
                };
            }

            int apply(int value) {
                return switch (type) {
                    case XOR -> value ^ operand;
                    case ADD -> value + operand;
                };
            }
        }
}
