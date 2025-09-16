package by.radioegor146;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Control flow flattening utility for native code generation.
 * This class provides methods to generate obfuscated control flow structures
 * that make the generated C++ code harder to analyze and reverse engineer.
 */
public class ControlFlowFlattener {

    /**
     * Generates a flattened switch-case structure around the provided code block.
     * The code is wrapped in a randomized state machine that executes the actual logic.
     *
     * @param originalCode The original code to be flattened
     * @param methodName The name of the method (used for unique state generation)
     * @return The flattened code wrapped in obfuscated control flow
     */
    public static String flattenControlFlow(String originalCode, String methodName) {
        return flattenControlFlow(originalCode, methodName, "void");
    }

    /**
     * Generates a flattened switch-case structure around the provided code block.
     * The code is wrapped in a randomized state machine that executes the actual logic.
     *
     * @param originalCode The original code to be flattened
     * @param methodName The name of the method (used for unique state generation)
     * @param returnType The return type of the method (for generating proper fallback return statement)
     * @return The flattened code wrapped in obfuscated control flow
     */
    public static String flattenControlFlow(String originalCode, String methodName, String returnType) {
        if (originalCode == null || originalCode.trim().isEmpty()) {
            return originalCode;
        }

        long seed = ThreadLocalRandom.current().nextLong();
        int realState = generateStateId(methodName, seed);
        int[] dummyStates = generateDummyStates(realState, 3 + ThreadLocalRandom.current().nextInt(5));

        StringBuilder flattened = new StringBuilder();

        // Generate state variable initialization with obfuscation
        flattened.append(String.format("    volatile int __ngen_state = %d ^ 0x%08X;\n",
                realState ^ 0x12345678, 0x12345678));
        flattened.append("    volatile bool __ngen_flow_continue = true;\n");

        // Start the flattened control flow
        flattened.append("    while (__ngen_flow_continue) {\n");
        flattened.append("        switch (__ngen_state) {\n");

        // Add the real case with the original code
        flattened.append(String.format("        case %d: {\n", realState));
        flattened.append("            // Real execution path\n");

        // Indent the original code properly
        String[] lines = originalCode.split("\n");
        for (String line : lines) {
            flattened.append("            ").append(line).append("\n");
        }

        flattened.append("            __ngen_flow_continue = false;\n");
        flattened.append("            break;\n");
        flattened.append("        }\n");

        // Add dummy cases for obfuscation
        for (int dummyState : dummyStates) {
            flattened.append(String.format("        case %d: {\n", dummyState));
            flattened.append("            // Dummy path - never executed\n");
            flattened.append(generateDummyCode());
            flattened.append("            __ngen_state = ").append(realState).append(";\n");
            flattened.append("            break;\n");
            flattened.append("        }\n");
        }

        // Default case
        flattened.append("        default: {\n");
        flattened.append("            // Fallback to real execution\n");
        flattened.append("            __ngen_state = ").append(realState).append(";\n");
        flattened.append("            break;\n");
        flattened.append("        }\n");

        flattened.append("        }\n"); // End switch
        flattened.append("    }\n");     // End while

        // Add unreachable return statement to satisfy MSVC C4715 warning
        // This code should never be reached but prevents compiler warning
        if (!"void".equals(returnType)) {
            flattened.append("    // Unreachable fallback return to prevent C4715 warning\n");
            flattened.append("    return ");
            flattened.append(getDefaultReturnValue(returnType));
            flattened.append(";\n");
        }

        return flattened.toString();
    }

    /**
     * Generates a unique state ID based on method name and seed.
     */
    private static int generateStateId(String methodName, long seed) {
        return Math.abs((methodName.hashCode() ^ (int)seed) % 1000000) + 1000;
    }

    /**
     * Generates dummy state IDs that are different from the real state.
     */
    private static int[] generateDummyStates(int realState, int count) {
        int[] dummyStates = new int[count];
        for (int i = 0; i < count; i++) {
            do {
                dummyStates[i] = ThreadLocalRandom.current().nextInt(100000) + 2000;
            } while (dummyStates[i] == realState);
        }
        return dummyStates;
    }

    /**
     * Generates dummy C++ code that does not affect the execution but adds complexity.
     */
    private static String generateDummyCode() {
        StringBuilder dummy = new StringBuilder();

        // Always declare the variables first to avoid undeclared variable errors
        dummy.append("            volatile int __dummy = 0x")
             .append(Integer.toHexString(ThreadLocalRandom.current().nextInt()))
             .append(";\n");
        dummy.append("            volatile jlong __temp = 0;\n");

        // Generate random dummy operations that use these variables
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

    /**
     * Gets the default return value for a given return type to prevent C4715 warnings.
     * This return statement should never be reached but satisfies the compiler.
     */
    private static String getDefaultReturnValue(String returnType) {
        if (returnType == null || returnType.trim().isEmpty() || "void".equals(returnType)) {
            return "";
        }

        // Handle C++ return types commonly used in JNI
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
                // For pointer types or complex types, return nullptr
                if (returnType.contains("*") || returnType.startsWith("j")) {
                    return "nullptr";
                }
                // For other types, try to return zero-initialized value
                return "{}";
        }
    }

    /**
     * Applies basic obfuscation to variable names in the code.
     */
    public static String obfuscateVariableNames(String code) {
        // Simple variable name obfuscation - replace common patterns
        return code
            .replaceAll("\\btemp\\b", "__ngen_tmp_" + ThreadLocalRandom.current().nextInt(1000))
            .replaceAll("\\bindex\\b", "__ngen_idx_" + ThreadLocalRandom.current().nextInt(1000))
            .replaceAll("\\bresult\\b", "__ngen_res_" + ThreadLocalRandom.current().nextInt(1000));
    }
}