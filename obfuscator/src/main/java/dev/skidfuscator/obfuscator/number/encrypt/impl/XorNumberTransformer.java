package dev.skidfuscator.obfuscator.number.encrypt.impl;

import dev.skidfuscator.obfuscator.number.encrypt.NumberTransformer;
import dev.skidfuscator.obfuscator.predicate.factory.PredicateFlowGetter;
import dev.skidfuscator.obfuscator.util.RandomUtil;
import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.expr.ArithmeticExpr;
import org.mapleir.ir.code.expr.ConstantExpr;
import org.objectweb.asm.Type;

/**
 * @author Ghast
 * @since 09/03/2021
 * SkidfuscatorV2 © 2021
 */
public class XorNumberTransformer implements NumberTransformer {
    private static final ArithmeticExpr.Operator[] MIDDLE_OPERATORS = new ArithmeticExpr.Operator[]{
            ArithmeticExpr.Operator.XOR,
            ArithmeticExpr.Operator.ADD,
            ArithmeticExpr.Operator.SUB,
            ArithmeticExpr.Operator.MUL,
            ArithmeticExpr.Operator.OR,
            ArithmeticExpr.Operator.AND,
            ArithmeticExpr.Operator.SHL,
            ArithmeticExpr.Operator.SHR,
            ArithmeticExpr.Operator.USHR
    };

    private static final ArithmeticExpr.Operator[] FINAL_OPERATORS = new ArithmeticExpr.Operator[]{
            ArithmeticExpr.Operator.XOR,
            ArithmeticExpr.Operator.ADD,
            ArithmeticExpr.Operator.SUB
    };

    @Override
    public Expr getNumber(
            final int outcome,
            final int starting,
            final BasicBlock vertex,
            final PredicateFlowGetter startingExpr) {
        Expr expr = startingExpr.get(vertex);
        int simulated = starting;

        final int stageCount = 3 + RandomUtil.nextInt(3); // between 3 and 5 stages

        for (int i = 0; i < stageCount - 1; i++) {
            final ArithmeticExpr.Operator operator = MIDDLE_OPERATORS[RandomUtil.nextInt(MIDDLE_OPERATORS.length)];
            final int constant = generateConstant(operator);

            simulated = apply(simulated, constant, operator);
            expr = new ArithmeticExpr(
                    new ConstantExpr(constant, Type.INT_TYPE),
                    expr,
                    operator
            );
        }

        final ArithmeticExpr.Operator finalOperator = FINAL_OPERATORS[RandomUtil.nextInt(FINAL_OPERATORS.length)];
        int finalConstant = computeFinalConstant(simulated, outcome, finalOperator);

        /*
         * Very small chance that the solved constant collapses to zero for XOR,
         * which would reduce the expression to the previous stage. In that case,
         * perturb the constant slightly while keeping correctness.
         */
        if (finalOperator == ArithmeticExpr.Operator.XOR && finalConstant == 0) {
            finalConstant = 0xA5A5A5A5;
            simulated = apply(simulated, finalConstant, ArithmeticExpr.Operator.XOR);
        }

        expr = new ArithmeticExpr(
                new ConstantExpr(finalConstant, Type.INT_TYPE),
                expr,
                finalOperator
        );

        return expr;
    }

    private static int computeFinalConstant(int simulated, int outcome, ArithmeticExpr.Operator operator) {
        return switch (operator) {
            case XOR -> simulated ^ outcome;
            case ADD -> outcome - simulated;
            case SUB -> simulated - outcome;
            default -> throw new IllegalStateException("Unsupported final operator: " + operator);
        };
    }

    private static int apply(int value, int constant, ArithmeticExpr.Operator operator) {
        return switch (operator) {
            case XOR -> value ^ constant;
            case ADD -> value + constant;
            case SUB -> value - constant;
            case MUL -> value * constant;
            case OR -> value | constant;
            case AND -> value & constant;
            case SHL -> value << (constant & 31);
            case SHR -> value >> (constant & 31);
            case USHR -> value >>> (constant & 31);
            default -> throw new IllegalStateException("Unsupported operator: " + operator);
        };
    }

    private static int generateConstant(ArithmeticExpr.Operator operator) {
        return switch (operator) {
            case XOR -> nonZeroInt();
            case ADD, SUB -> randomInt();
            case MUL -> randomOddMultiplier();
            case OR -> ensureNonZero(randomInt());
            case AND -> ensureNonTrivialMask(randomInt());
            case SHL, SHR, USHR -> 1 + RandomUtil.nextInt(31);
            default -> randomInt();
        };
    }

    private static int randomInt() {
        return (int) RandomUtil.nextLong();
    }

    private static int nonZeroInt() {
        int value;
        do {
            value = randomInt();
        } while (value == 0);
        return value;
    }

    private static int ensureNonZero(int candidate) {
        if (candidate == 0) {
            candidate = 0x7f4a7c15;
        }
        return candidate;
    }

    private static int ensureNonTrivialMask(int candidate) {
        candidate |= 1 << (RandomUtil.nextInt(5) + 3);
        if (candidate == -1 || candidate == 0) {
            candidate ^= 0xA5A5A5A5;
        }
        return candidate;
    }

    private static int randomOddMultiplier() {
        int value;
        do {
            value = randomInt() | 1;
        } while (value == 1 || value == -1);
        return value;
    }
}
