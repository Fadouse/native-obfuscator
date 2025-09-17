package by.radioegor146;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Control flow flattening utility for native code generation.
 * This class provides methods to generate obfuscated control flow structures
 * that make the generated C++ code harder to analyze and reverse engineer.
 */
public class ControlFlowFlattener {

    private static final Pattern STATE_ASSIGNMENT_PATTERN = Pattern.compile("__ngen_state\\s*=\\s*(-?\\d+)");

    /**
     * Strategy factory used to produce control-flow state encoders.
     * External users can provide their own implementations to leverage
     * custom hardware instructions or domain-specific mixing functions.
     */
    public interface StateObfuscationStrategy {
        StateObfuscation create(String methodName);
    }

    /**
     * Encapsulates the logic required to encode raw control-flow states into
     * the obfuscated representation used inside the generated switch statement.
     */
    public interface StateObfuscation {
        void appendPrologue(StringBuilder out, String indent);

        int encodeCase(int rawState);

        String generateEncodeExpression(int rawState);

        default void appendStateAssignment(StringBuilder out, String indent, String stateVarName, int rawState) {
            out.append(indent)
               .append(stateVarName)
               .append(" = ")
               .append(generateEncodeExpression(rawState))
               .append(";\n");
        }
    }

    private static final class DefaultStateObfuscationStrategy implements StateObfuscationStrategy {
        @Override
        public StateObfuscation create(String methodName) {
            return new DefaultStateObfuscation();
        }
    }

    private static final class DefaultStateObfuscation implements StateObfuscation {
        private final int xorMask;
        private final int multiplier;
        private final int bias;
        private final int rotation;

        private DefaultStateObfuscation() {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            this.xorMask = random.nextInt();
            int candidate;
            do {
                candidate = random.nextInt();
            } while ((candidate & 1) == 0);
            this.multiplier = candidate;
            this.bias = random.nextInt();
            this.rotation = 1 + random.nextInt(31);
        }

        @Override
        public void appendPrologue(StringBuilder out, String indent) {
            out.append(indent)
                    .append("alignas(16) static volatile jint __ngen_state_params[4] = { ")
                    .append(String.format("static_cast<jint>(0x%08X)", xorMask)).append(", ")
                    .append(String.format("static_cast<jint>(0x%08X)", multiplier)).append(", ")
                    .append(String.format("static_cast<jint>(0x%08X)", bias)).append(", ")
                    .append(rotation)
                    .append(" };\n");
            out.append(indent).append("auto __ngen_encode_state = [&](int __ngen_raw_state) -> int {\n");
            out.append(indent).append("    volatile const jint* __ngen_params = __ngen_state_params;\n");
            out.append(indent).append("    uint32_t __ngen_val = static_cast<uint32_t>(__ngen_raw_state);\n");
            out.append(indent).append("    uint32_t __ngen_mul = static_cast<uint32_t>(__ngen_params[1]);\n");
            out.append(indent).append("    uint32_t __ngen_mask = static_cast<uint32_t>(__ngen_params[0]);\n");
            out.append(indent).append("    uint32_t __ngen_bias = static_cast<uint32_t>(__ngen_params[2]);\n");
            out.append(indent).append("    uint32_t __ngen_rot = static_cast<uint32_t>(__ngen_params[3]) & 31U;\n");
            out.append(indent).append("    __ngen_val = (__ngen_val * __ngen_mul) ^ __ngen_mask;\n");
            out.append(indent).append("    uint32_t __ngen_left = __ngen_val << __ngen_rot;\n");
            out.append(indent).append("    uint32_t __ngen_right = (__ngen_rot == 0U) ? __ngen_val : (__ngen_val >> (32U - __ngen_rot));\n");
            out.append(indent).append("    __ngen_val = (__ngen_left | __ngen_right) ^ __ngen_bias;\n");
            out.append(indent).append("    return static_cast<int>(__ngen_val);\n");
            out.append(indent).append("};\n");
        }

        @Override
        public int encodeCase(int rawState) {
            return encodeInternal(rawState);
        }

        @Override
        public String generateEncodeExpression(int rawState) {
            return "__ngen_encode_state(" + rawState + ")";
        }

        private int encodeInternal(int rawState) {
            long value = Integer.toUnsignedLong(rawState);
            long mul = Integer.toUnsignedLong(multiplier);
            long mask = Integer.toUnsignedLong(xorMask);
            long biasValue = Integer.toUnsignedLong(bias);
            value = (value * mul) & 0xFFFFFFFFL;
            value ^= mask;
            int rotated = Integer.rotateLeft((int) value, rotation);
            long result = Integer.toUnsignedLong(rotated) ^ biasValue;
            return (int) result;
        }
    }

    private static volatile StateObfuscationStrategy stateObfuscationStrategy = new DefaultStateObfuscationStrategy();

    public static void setStateObfuscationStrategy(StateObfuscationStrategy strategy) {
        stateObfuscationStrategy = Objects.requireNonNull(strategy, "strategy");
    }

    public static StateObfuscation createObfuscation(String methodName) {
        return stateObfuscationStrategy.create(methodName);
    }

    public static void appendStateTransition(StringBuilder out, String indent, String stateVarName,
                                             int rawState, StateObfuscation obfuscation) {
        Objects.requireNonNull(obfuscation, "obfuscation");
        obfuscation.appendStateAssignment(out, indent, stateVarName, rawState);
        out.append(indent).append("break;\n");
    }

    public static String obfuscateStateAssignments(String code, StateObfuscation obfuscation) {
        if (code == null || code.isEmpty() || obfuscation == null) {
            return code;
        }
        Matcher matcher = STATE_ASSIGNMENT_PATTERN.matcher(code);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            int rawState = Integer.parseInt(matcher.group(1));
            String replacement = "__ngen_state = " + obfuscation.generateEncodeExpression(rawState);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static String generateStateMachine(String methodName, String returnType, int initialState,
                                              Map<Integer, StringBuilder> stateBlocks,
                                              String defaultBlock,
                                              StateObfuscation obfuscation) {
        if (stateBlocks == null || stateBlocks.isEmpty()) {
            return "";
        }
        StateObfuscation stateObfuscation = obfuscation != null ? obfuscation : createObfuscation(methodName);
        StringBuilder flattened = new StringBuilder();
        stateObfuscation.appendPrologue(flattened, "    ");
        flattened.append("    volatile int __ngen_state = ")
                .append(stateObfuscation.generateEncodeExpression(initialState))
                .append(";\n");
        flattened.append("    while (true) {\n");
        flattened.append("        switch (__ngen_state) {\n");

        for (Map.Entry<Integer, StringBuilder> entry : stateBlocks.entrySet()) {
            flattened.append("        case ")
                    .append(stateObfuscation.encodeCase(entry.getKey()))
                    .append(": {\n");
            String block = entry.getValue().toString();
            if (!block.isEmpty()) {
                flattened.append(block);
                if (block.charAt(block.length() - 1) != '\n') {
                    flattened.append('\n');
                }
            }
            flattened.append("        }\n");
        }

        if (defaultBlock != null && !defaultBlock.isEmpty()) {
            flattened.append("        default: {\n");
            flattened.append(defaultBlock);
            if (!defaultBlock.endsWith("\n")) {
                flattened.append('\n');
            }
            flattened.append("        }\n");
        } else {
            flattened.append("        default: {\n");
            flattened.append("            break;\n");
            flattened.append("        }\n");
        }

        flattened.append("        }\n");
        flattened.append("    }\n\n");
        return flattened.toString();
    }

    public static String flattenControlFlow(String originalCode, String methodName) {
        return flattenControlFlow(originalCode, methodName, "void");
    }

    public static String flattenControlFlow(String originalCode, String methodName, String returnType) {
        if (originalCode == null || originalCode.trim().isEmpty()) {
            return originalCode;
        }

        long seed = ThreadLocalRandom.current().nextLong();
        int realState = generateStateId(methodName, seed);
        int[] dummyStates = generateDummyStates(realState, 3 + ThreadLocalRandom.current().nextInt(5));

        StateObfuscation stateObfuscation = createObfuscation(methodName);
        StringBuilder flattened = new StringBuilder();
        stateObfuscation.appendPrologue(flattened, "    ");
        flattened.append("    volatile int __ngen_state = ")
                .append(stateObfuscation.generateEncodeExpression(realState))
                .append(";\n");
        flattened.append("    volatile bool __ngen_flow_continue = true;\n");

        flattened.append("    while (__ngen_flow_continue) {\n");
        flattened.append("        switch (__ngen_state) {\n");

        flattened.append("        case ")
                .append(stateObfuscation.encodeCase(realState))
                .append(": {\n");
        flattened.append("            // Real execution path\n");
        String[] lines = originalCode.split("\n");
        for (String line : lines) {
            flattened.append("            ").append(line).append("\n");
        }
        flattened.append("            __ngen_flow_continue = false;\n");
        flattened.append("            break;\n");
        flattened.append("        }\n");

        for (int dummyState : dummyStates) {
            flattened.append("        case ")
                    .append(stateObfuscation.encodeCase(dummyState))
                    .append(": {\n");
            flattened.append("            // Dummy path - never executed\n");
            flattened.append(generateDummyCode());
            appendStateTransition(flattened, "            ", "__ngen_state", realState, stateObfuscation);
            flattened.append("        }\n");
        }

        flattened.append("        default: {\n");
        appendStateTransition(flattened, "            ", "__ngen_state", realState, stateObfuscation);
        flattened.append("        }\n");

        flattened.append("        }\n");
        flattened.append("    }\n");

        if (!"void".equals(returnType)) {
            flattened.append("    // Unreachable fallback return to prevent C4715 warning\n");
            flattened.append("    return ");
            flattened.append(getDefaultReturnValue(returnType));
            flattened.append(";\n");
        }

        return flattened.toString();
    }

    private static int generateStateId(String methodName, long seed) {
        return Math.abs((methodName.hashCode() ^ (int) seed) % 1000000) + 1000;
    }

    private static int[] generateDummyStates(int realState, int count) {
        int[] dummyStates = new int[count];
        for (int i = 0; i < count; i++) {
            do {
                dummyStates[i] = ThreadLocalRandom.current().nextInt(100000) + 2000;
            } while (dummyStates[i] == realState);
        }
        return dummyStates;
    }

    private static String generateDummyCode() {
        StringBuilder dummy = new StringBuilder();

        dummy.append("            volatile int __dummy = 0x")
             .append(Integer.toHexString(ThreadLocalRandom.current().nextInt()))
             .append(";\n");
        dummy.append("            volatile jlong __temp = 0;\n");

        String[] dummyOperations = {
            "            __temp = (jlong)(__dummy ^ 0xDEADBEEF);\n",
            "            __dummy = (int)(__temp & 0xFFFFFFFF);\n",
            "            if (__dummy == 0x12345678) { __dummy ^= 0x87654321; }\n",
            "            __dummy = __dummy ^ 0x" + Integer.toHexString(ThreadLocalRandom.current().nextInt()) + ";\n",
            "            __temp = __temp + (__dummy & 0xFF);\n"
        };

        int numOps = 2 + ThreadLocalRandom.current().nextInt(3);
        for (int i = 0; i < numOps; i++) {
            dummy.append(dummyOperations[ThreadLocalRandom.current().nextInt(dummyOperations.length)]);
        }
        return dummy.toString();
    }

    private static String getDefaultReturnValue(String returnType) {
        if (returnType == null || returnType.trim().isEmpty() || "void".equals(returnType)) {
            return "";
        }

        switch (returnType.toLowerCase().trim()) {
            case "jint":
            case "jlong":
            case "jshort":
            case "jbyte":
            case "jchar":
            case "int":
            case "long":
            case "short":
            case "byte":
            case "char":
                return "0";
            case "jfloat":
            case "jdouble":
            case "float":
            case "double":
                return "0.0";
            case "jboolean":
            case "bool":
            case "boolean":
                return "false";
            case "jobject":
            case "jstring":
            case "jarray":
            case "jclass":
            case "jthrowable":
                return "nullptr";
            default:
                if (returnType.contains("*") || returnType.startsWith("j")) {
                    return "nullptr";
                }
                return "{}";
        }
    }

    public static String obfuscateVariableNames(String code) {
        return code
            .replaceAll("\\btemp\\b", "__ngen_tmp_" + ThreadLocalRandom.current().nextInt(1000))
            .replaceAll("\\bindex\\b", "__ngen_idx_" + ThreadLocalRandom.current().nextInt(1000))
            .replaceAll("\\bresult\\b", "__ngen_res_" + ThreadLocalRandom.current().nextInt(1000));
    }
}
