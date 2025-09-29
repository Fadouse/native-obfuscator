package by.radioegor146;

import by.radioegor146.instructions.*;
import by.radioegor146.instructions.VmTranslator;
import by.radioegor146.special.ClInitSpecialMethodProcessor;
import by.radioegor146.special.DefaultSpecialMethodProcessor;
import by.radioegor146.special.SpecialMethodProcessor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import by.radioegor146.FastRandom;

public class MethodProcessor {

    public static final Map<Integer, String> INSTRUCTIONS = new HashMap<>();

    static {
        try {
            for (Field f : Opcodes.class.getFields()) {
                INSTRUCTIONS.put((int) f.get(null), f.getName());
            }
        } catch (IllegalArgumentException | IllegalAccessException ex) {
            throw new RuntimeException(ex);
        }
    }

    public static final String[] CPP_TYPES = {
            "void", // 0
            "jboolean", // 1
            "jchar", // 2
            "jbyte", // 3
            "jshort", // 4
            "jint", // 5
            "jfloat", // 6
            "jlong", // 7
            "jdouble", // 8
            "jarray", // 9
            "jobject", // 10
            "jobject" // 11
    };

    public static final int[] TYPE_TO_STACK = {
            1, 1, 1, 1, 1, 1, 1, 2, 2, 0, 0, 0
    };

    public static final int[] STACK_TO_STACK = {
            1, 1, 1, 2, 2, 0, 0, 0, 0
    };

    private final NativeObfuscator obfuscator;
    private final InstructionHandlerContainer<?>[] handlers;

    private static final Pattern STATE_ASSIGNMENT_PATTERN = Pattern.compile("__ngen_state\\s*=\\s*(-?\\d+);\\s*break;");
    private static final Pattern STANDALONE_BREAK_PATTERN = Pattern.compile("(?m)^\\s*break;\\s*$");

    public MethodProcessor(NativeObfuscator obfuscator) {
        this.obfuscator = obfuscator;

        handlers = new InstructionHandlerContainer[16];
        addHandler(AbstractInsnNode.INSN, new InsnHandler(), InsnNode.class);
        addHandler(AbstractInsnNode.INT_INSN, new IntHandler(), IntInsnNode.class);
        addHandler(AbstractInsnNode.VAR_INSN, new VarHandler(), VarInsnNode.class);
        addHandler(AbstractInsnNode.TYPE_INSN, new TypeHandler(), TypeInsnNode.class);
        addHandler(AbstractInsnNode.FIELD_INSN, new FieldHandler(), FieldInsnNode.class);
        addHandler(AbstractInsnNode.METHOD_INSN, new MethodHandler(), MethodInsnNode.class);
        addHandler(AbstractInsnNode.INVOKE_DYNAMIC_INSN, new InvokeDynamicHandler(), InvokeDynamicInsnNode.class);
        addHandler(AbstractInsnNode.JUMP_INSN, new JumpHandler(), JumpInsnNode.class);
        addHandler(AbstractInsnNode.LABEL, new LabelHandler(), LabelNode.class);
        addHandler(AbstractInsnNode.LDC_INSN, new LdcHandler(), LdcInsnNode.class);
        addHandler(AbstractInsnNode.IINC_INSN, new IincHandler(), IincInsnNode.class);
        addHandler(AbstractInsnNode.TABLESWITCH_INSN, new TableSwitchHandler(), TableSwitchInsnNode.class);
        addHandler(AbstractInsnNode.LOOKUPSWITCH_INSN, new LookupSwitchHandler(), LookupSwitchInsnNode.class);
        addHandler(AbstractInsnNode.MULTIANEWARRAY_INSN, new MultiANewArrayHandler(), MultiANewArrayInsnNode.class);
        addHandler(AbstractInsnNode.FRAME, new FrameHandler(), FrameNode.class);
        addHandler(AbstractInsnNode.LINE, new LineNumberHandler(), LineNumberNode.class);
    }

    private <T extends AbstractInsnNode> void addHandler(int id, InstructionTypeHandler<T> handler, Class<T> instructionClass) {
        handlers[id] = new InstructionHandlerContainer<>(handler, instructionClass);
    }

    private SpecialMethodProcessor getSpecialMethodProcessor(String name) {
        return switch (name) {
            case "<init>" -> null;
            case "<clinit>" -> new ClInitSpecialMethodProcessor();
            default -> new DefaultSpecialMethodProcessor();
        };
    }

    public static boolean shouldProcess(MethodNode method) {
        if (Util.getFlag(method.access, Opcodes.ACC_ABSTRACT)) return false;
        if (Util.getFlag(method.access, Opcodes.ACC_NATIVE)) return false;
        if (method.name.equals("<init>")) return false;
        // Skip lambda body methods to avoid subtle JVM/linkage issues in JDK8/21
        return !method.name.startsWith("lambda$");
    }

    public static String getClassGetter(MethodContext context, String desc) {
        if (desc.startsWith("[")) {
            return "env->FindClass(" + context.getStringPool().get(desc) + ")";
        }
        if (desc.endsWith(";")) {
            desc = desc.substring(1, desc.length() - 1);
        }
        return "utils::find_class_wo_static(env, classloader, " + context.getCachedStrings().getPointer(desc.replace('/', '.')) + ")";
    }

    public static String ensureVerifiedClass(MethodContext context, int classId, String owner,
                                             String trimmedTryCatchBlock) {
        String localName = context.verifiedClassLocals.get(classId);
        if (localName == null) {
            localName = "cclass_local" + context.verifiedClassLocals.size();
            context.verifiedClassLocals.put(classId, localName);

            String flagName = localName + "_cached";
            context.verifiedClassFlagNames.put(classId, flagName);
            context.verifiedClassPreamble.append("    jclass ").append(localName).append(" = nullptr;\n");
            context.verifiedClassPreamble.append("    bool ").append(flagName).append(" = false;\n");
        }

        String flagName = context.verifiedClassFlagNames.get(classId);

        if (!context.verifiedClassFlags.get(classId)) {
            context.verifiedClassFlags.set(classId);
            context.output.append(String.format(
                    "if (!%1$s) { %2$s = (jclass) env->NewLocalRef(cclasses[%3$d]); if (%2$s == nullptr) { cclasses_mtx[%3$d].lock(); "
                            + "if (!cclasses[%3$d] || env->IsSameObject(cclasses[%3$d], NULL)) { if (jclass clazz = %4$s) { cclasses[%3$d] = (jclass) env->NewWeakGlobalRef(clazz); env->DeleteLocalRef(clazz); } } "
                            + "cclasses_mtx[%3$d].unlock(); %5$s %2$s = (jclass) env->NewLocalRef(cclasses[%3$d]); %5$s } %1$s = true; } ",
                    flagName,
                    localName,
                    classId,
                    getClassGetter(context, owner),
                    trimmedTryCatchBlock));
        }

        return localName;
    }

    public void processMethod(MethodContext context) {
        MethodNode method = context.method;
        StringBuilder output = context.output;

        // Do not native-redirect methods of enum classes. Their initialization
        // and intrinsic methods (values/valueOf) are sensitive to init order
        // and lead to linkage errors if RegisterNatives is not invoked.
        if ((context.clazz.access & Opcodes.ACC_ENUM) != 0) {
            context.skipNative = true;
            return;
        }

        SpecialMethodProcessor specialMethodProcessor = getSpecialMethodProcessor(method.name);

        if (specialMethodProcessor == null) {
            throw new RuntimeException(String.format("Could not find special method processor for %s", method.name));
        }

        output.append("// ").append(Util.escapeCommentString(method.name)).append(Util.escapeCommentString(method.desc)).append("\n");

        String methodName = specialMethodProcessor.preProcess(context);
        // Some special processors may decide to keep the method as-is (no native redirection)
        // to preserve JVM semantics (e.g., certain <clinit> cases). In that case, bail out early.
        if (context.skipNative) {
            return;
        }
        methodName = "__ngen_" + methodName.replace('/', '_');
        methodName = Util.escapeCppNameString(methodName);
        context.cppNativeMethodName = methodName;

        boolean isStatic = Util.getFlag(method.access, Opcodes.ACC_STATIC);
        context.ret = Type.getReturnType(method.desc);
        Type[] args = Type.getArgumentTypes(method.desc);

        context.argTypes = new ArrayList<>(Arrays.asList(args));
        if (!isStatic) {
            context.argTypes.addFirst(Type.getType(Object.class));
        }

        if (context.proxyMethod != null) {
            context.nativeMethod = context.proxyMethod.getMethodNode();
            context.nativeMethod.access |= Opcodes.ACC_NATIVE;
        } else {
            context.nativeMethods.append(String.format("            { %s, %s, (void *)&%s },\n",
                    obfuscator.getStringPool().get(context.method.name),
                    obfuscator.getStringPool().get(method.desc), methodName));
        }

        output.append(String.format("%s JNICALL %s(JNIEnv *env, ", CPP_TYPES[context.ret.getSort()], methodName));
        if (context.proxyMethod != null) {
            output.append("jobject ignored_hidden, ");
        }
        output.append(isStatic ? "jclass clazz" : "jobject obj");

        ArrayList<String> argNames = new ArrayList<>();
        if (!isStatic) argNames.add("obj");

        for (int i = 0; i < args.length; i++) {
            argNames.add("arg" + i);
            output.append(String.format(", %s arg%d", CPP_TYPES[args[i].getSort()], i));
        }

        output.append(") {").append("\n");

        if (context.proxyMethod != null) {
            output.append("    env->DeleteLocalRef(ignored_hidden);\n");
        }

        VmTranslator.Instruction[] vmCode = null;
        List<VmTranslator.FieldRefInfo> fieldRefs = new ArrayList<>();
        List<String> classRefs = new ArrayList<>();
        List<VmTranslator.MultiArrayRefInfo> multiArrayRefs = new ArrayList<>();
        List<VmTranslator.MethodRefInfo> methodRefs = new ArrayList<>();
        List<VmTranslator.ConstantPoolEntry> constantPool = new ArrayList<>();
        List<VmTranslator.TableSwitchInfo> tableSwitches = new ArrayList<>();
        List<VmTranslator.LookupSwitchInfo> lookupSwitches = new ArrayList<>();
        VmTranslator vmTranslator = null;
        long vmKeySeed = 0;

        // Only use VM translation if virtualization is enabled
        if (context.protectionConfig.isVirtualizationEnabled()) {
            vmKeySeed = FastRandom.nextLong();
            output.append(String.format("    native_jvm::vm::init_key(%dLL);\n", vmKeySeed));

            boolean useJit = context.protectionConfig.isJitEnabled();
            vmTranslator = new VmTranslator(useJit);
            vmCode = vmTranslator.translate(method);
            // Avoid VM translation for interface methods to reduce risk of mismatched
            // dispatch (default/interface semantics) across JVM versions.
            if ((context.clazz.access & Opcodes.ACC_INTERFACE) != 0) {
                vmCode = null;
            }

            if (vmCode != null) {
                fieldRefs = vmTranslator.getFieldRefs();
                classRefs = vmTranslator.getClassRefs();
                multiArrayRefs = vmTranslator.getMultiArrayRefs();
                methodRefs = vmTranslator.getMethodRefs();
                // Be conservative: if the VM-translated method performs any method calls,
                // fall back to the regular state-machine codegen to avoid operand decode
                // mismatches across JVMs. Arithmetic/stack-only methods still benefit.
                if (!methodRefs.isEmpty()) {
                    vmCode = null;
                }
                constantPool = vmTranslator.getConstantPool();
                tableSwitches = vmTranslator.getTableSwitches();
                lookupSwitches = vmTranslator.getLookupSwitches();
            }
        }
        if (vmCode != null && vmCode.length > 0) {
            output.append(String.format("    native_jvm::vm::Instruction __ngen_vm_code[] = %s;\n",
                    VmTranslator.serialize(vmCode)));
            output.append(String.format("    jlong __ngen_vm_locals[%d] = {0};\n", Math.max(1, method.maxLocals)));
            // Initialize VM locals with exact bit patterns for primitives and raw pointers for refs
            for (int i = 0; i < context.argTypes.size(); i++) {
                final int sort = context.argTypes.get(i).getSort();
                final String aname = argNames.get(i);
                switch (sort) {
                    case Type.BOOLEAN:
                    case Type.CHAR:
                    case Type.BYTE:
                    case Type.SHORT:
                    case Type.INT: {
                        output.append(String.format("    __ngen_vm_locals[%d] = (jlong)(jint)%s;\n", i, aname));
                        break;
                    }
                    case Type.LONG: {
                        output.append(String.format("    __ngen_vm_locals[%d] = (jlong)%s;\n", i, aname));
                        break;
                    }
                    case Type.FLOAT: {
                        output.append(String.format(
                                "    { jint __fbits = 0; std::memcpy(&__fbits, &%s, sizeof(jfloat)); __ngen_vm_locals[%d] = (jlong)__fbits; }\n",
                                aname, i));
                        break;
                    }
                    case Type.DOUBLE: {
                        output.append(String.format(
                                "    { jlong __dbits = 0; std::memcpy(&__dbits, &%s, sizeof(jdouble)); __ngen_vm_locals[%d] = __dbits; }\n",
                                aname, i));
                        break;
                    }
                    case Type.ARRAY:
                    case Type.OBJECT: {
                        output.append(String.format("    __ngen_vm_locals[%d] = (jlong)%s;\n", i, aname));
                        break;
                    }
                    default: {
                        output.append(String.format("    __ngen_vm_locals[%d] = 0;\n", i));
                        break;
                    }
                }
            }
            if (!fieldRefs.isEmpty()) {
                output.append("    native_jvm::vm::FieldRef __ngen_vm_fields[] = {");
                for (int i = 0; i < fieldRefs.size(); i++) {
                    VmTranslator.FieldRefInfo fr = fieldRefs.get(i);
                    output.append(String.format("{ %s, %s, %s }",
                            context.getStringPool().get(fr.owner),
                            context.getStringPool().get(fr.name),
                            context.getStringPool().get(fr.desc)));
                    if (i + 1 < fieldRefs.size()) {
                        output.append(", ");
                    }
                }
                output.append(" };\n");
            }
            if (!methodRefs.isEmpty()) {
                output.append("    native_jvm::vm::MethodRef __ngen_vm_methods[] = {");
                for (int i = 0; i < methodRefs.size(); i++) {
                    VmTranslator.MethodRefInfo mr = methodRefs.get(i);
                    output.append(String.format("{ %s, %s, %s }",
                            context.getStringPool().get(mr.owner),
                            context.getStringPool().get(mr.name),
                            context.getStringPool().get(mr.desc)));
                    if (i + 1 < methodRefs.size()) {
                        output.append(", ");
                    }
                }
                output.append(" };\n");
            }
            if (!classRefs.isEmpty()) {
                output.append("    const char* __ngen_vm_classes[] = {");
                for (int i = 0; i < classRefs.size(); i++) {
                    output.append(context.getStringPool().get(classRefs.get(i)));
                    if (i + 1 < classRefs.size()) output.append(", ");
                }
                output.append(" };\n");
            }
            if (!multiArrayRefs.isEmpty()) {
                output.append("    native_jvm::vm::MultiArrayInfo __ngen_vm_multi[] = {");
                for (int i = 0; i < multiArrayRefs.size(); i++) {
                    VmTranslator.MultiArrayRefInfo mi = multiArrayRefs.get(i);
                    output.append(String.format("{ %s, %d }",
                            context.getStringPool().get(mi.desc), mi.dims));
                    if (i + 1 < multiArrayRefs.size()) output.append(", ");
                }
                output.append(" };\n");
            }
            if (!tableSwitches.isEmpty()) {
                for (int i = 0; i < tableSwitches.size(); i++) {
                    VmTranslator.TableSwitchInfo ts = tableSwitches.get(i);
                    output.append("    static const size_t __ngen_vm_table_targets_").append(i).append("[] = {");
                    for (int j = 0; j < ts.labels.length; j++) {
                        if (j > 0) output.append(", ");
                        output.append(ts.labels[j]);
                    }
                    output.append(" };\n");
                }
                output.append("    native_jvm::vm::TableSwitch __ngen_vm_tables[] = {");
                for (int i = 0; i < tableSwitches.size(); i++) {
                    VmTranslator.TableSwitchInfo ts = tableSwitches.get(i);
                    output.append(String.format("{ %d, %d, static_cast<size_t>(%d), __ngen_vm_table_targets_%d }",
                            ts.low, ts.high, ts.defaultLabel, i));
                    if (i + 1 < tableSwitches.size()) output.append(", ");
                }
                output.append(" };\n");
            }
            if (!lookupSwitches.isEmpty()) {
                List<String> lookupKeyVars = new ArrayList<>();
                List<String> lookupTargetVars = new ArrayList<>();
                for (int i = 0; i < lookupSwitches.size(); i++) {
                    VmTranslator.LookupSwitchInfo ls = lookupSwitches.get(i);
                    String keyVar = "nullptr";
                    String targetVar = "nullptr";
                    if (ls.keys.length > 0) {
                        keyVar = String.format("__ngen_vm_lookup_keys_%d", i);
                        targetVar = String.format("__ngen_vm_lookup_targets_%d", i);
                        output.append("    static const int32_t ").append(keyVar).append("[] = {");
                        for (int j = 0; j < ls.keys.length; j++) {
                            if (j > 0) output.append(", ");
                            output.append(ls.keys[j]);
                        }
                        output.append(" };\n");
                        output.append("    static const size_t ").append(targetVar).append("[] = {");
                        for (int j = 0; j < ls.labels.length; j++) {
                            if (j > 0) output.append(", ");
                            output.append(ls.labels[j]);
                        }
                        output.append(" };\n");
                    }
                    lookupKeyVars.add(keyVar);
                    lookupTargetVars.add(targetVar);
                }
                output.append("    native_jvm::vm::LookupSwitch __ngen_vm_lookups[] = {");
                for (int i = 0; i < lookupSwitches.size(); i++) {
                    VmTranslator.LookupSwitchInfo ls = lookupSwitches.get(i);
                    output.append(String.format("{ %d, %s, %s, static_cast<size_t>(%d) }",
                            ls.keys.length, lookupKeyVars.get(i), lookupTargetVars.get(i), ls.defaultLabel));
                    if (i + 1 < lookupSwitches.size()) output.append(", ");
                }
                output.append(" };\n");
            }
            // Generate constant pool array
            if (!constantPool.isEmpty()) {
                output.append("    native_jvm::vm::ConstantPoolEntry __ngen_vm_constants[").append(constantPool.size()).append("];\n");
                for (int i = 0; i < constantPool.size(); i++) {
                    VmTranslator.ConstantPoolEntry cp = constantPool.get(i);
                    output.append(String.format("    __ngen_vm_constants[%d].type = native_jvm::vm::ConstantPoolEntry::", i));
                    switch (cp.type) {
                        case INTEGER:
                            output.append("TYPE_INTEGER;\n");
                            output.append(String.format("    __ngen_vm_constants[%d].i_value = %d;\n", i, (Integer)cp.value));
                            break;
                        case FLOAT:
                            output.append("TYPE_FLOAT;\n");
                            // Print with enough precision to round-trip single-precision values
                            output.append(String.format(java.util.Locale.ROOT,
                                    "    __ngen_vm_constants[%d].f_value = %.9gF;\n", i, (Float)cp.value));
                            break;
                        case LONG:
                            output.append("TYPE_LONG;\n");
                            output.append(String.format("    __ngen_vm_constants[%d].l_value = %dLL;\n", i, (Long)cp.value));
                            break;
                        case DOUBLE:
                            output.append("TYPE_DOUBLE;\n");
                            // Print with enough precision to round-trip double-precision values
                            output.append(String.format(java.util.Locale.ROOT,
                                    "    __ngen_vm_constants[%d].d_value = %.17g;\n", i, (Double)cp.value));
                            break;
                        case STRING:
                            output.append("TYPE_STRING;\n");
                            output.append(String.format("    __ngen_vm_constants[%d].str_value = %s;\n", i,
                                    context.getStringPool().get((String)cp.value)));
                            break;
                        case CLASS:
                            output.append("TYPE_CLASS;\n");
                            output.append(String.format("    __ngen_vm_constants[%d].class_name = %s;\n", i,
                                    context.getStringPool().get((String)cp.value)));
                            break;
                        default:
                            // Unsupported types - should not reach here
                            output.append("TYPE_INTEGER;\n");
                            output.append(String.format("    __ngen_vm_constants[%d].i_value = 0;\n", i));
                            break;
                    }
                }
            }
            if (!fieldRefs.isEmpty() || !methodRefs.isEmpty() || !classRefs.isEmpty() || !multiArrayRefs.isEmpty()) {
                output.append("    for (auto &ins : __ngen_vm_code) {\n");
                output.append("        switch (ins.op) {\n");
                if (!fieldRefs.isEmpty()) {
                    output.append("            case native_jvm::vm::OP_GETSTATIC:\n");
                    output.append("            case native_jvm::vm::OP_PUTSTATIC:\n");
                    output.append("            case native_jvm::vm::OP_GETFIELD:\n");
                    output.append("            case native_jvm::vm::OP_PUTFIELD:\n");
                    // Keep operand as index; native VM indexes into __ngen_vm_fields
                    // (do not convert to pointer here)
                    output.append("                break;\n");
                }
                if (!methodRefs.isEmpty()) {
                    output.append("            case native_jvm::vm::OP_INVOKESTATIC:\n");
                    output.append("            case native_jvm::vm::OP_INVOKEVIRTUAL:\n");
                    output.append("            case native_jvm::vm::OP_INVOKESPECIAL:\n");
                    output.append("            case native_jvm::vm::OP_INVOKEINTERFACE:\n");
                    // Keep operand as index; native VM indexes into __ngen_vm_methods
                    // (do not convert to pointer here)
                    output.append("                break;\n");
                }
                if (!classRefs.isEmpty()) {
                    output.append("            case native_jvm::vm::OP_NEW:\n");
                    output.append("            case native_jvm::vm::OP_ANEWARRAY:\n");
                    output.append("            case native_jvm::vm::OP_CHECKCAST:\n");
                    output.append("            case native_jvm::vm::OP_INSTANCEOF:\n");
                    // Convert index -> actual const char* pointer value
                    // Native VM expects ins.operand to be a C string pointer
                    output.append("                ins.operand = reinterpret_cast<jlong>(__ngen_vm_classes[ins.operand]);\n");
                    output.append("                break;\n");
                }
                if (!multiArrayRefs.isEmpty()) {
                    output.append("            case native_jvm::vm::OP_MULTIANEWARRAY:\n");
                    // Keep operand as index; native VM indexes into __ngen_vm_multi
                    // (do not convert to pointer here)
                    output.append("                break;\n");
                }
                output.append("        }\n");
                output.append("    }\n");
            }
            output.append(String.format(
                    "    native_jvm::vm::encode_program(__ngen_vm_code, %d, %dLL);\n",
                    vmCode.length, vmKeySeed));
            // Determine constant pool parameters
            String constantPoolPtr = constantPool.isEmpty() ? "nullptr" : "__ngen_vm_constants";
            int constantPoolSize = constantPool.size();

            // Determine method references parameters
            String methodRefsPtr = methodRefs.isEmpty() ? "nullptr" : "__ngen_vm_methods";
            int methodRefsSize = methodRefs.size();

            // Determine field references parameters
            String fieldRefsPtr = fieldRefs.isEmpty() ? "nullptr" : "__ngen_vm_fields";
            int fieldRefsSize = fieldRefs.size();

            // Determine multi-array references parameters
            String multiRefsPtr = multiArrayRefs.isEmpty() ? "nullptr" : "__ngen_vm_multi";
            int multiRefsSize = multiArrayRefs.size();

            // Determine switch references parameters
            String tableRefsPtr = tableSwitches.isEmpty() ? "nullptr" : "__ngen_vm_tables";
            int tableRefsSize = tableSwitches.size();
            String lookupRefsPtr = lookupSwitches.isEmpty() ? "nullptr" : "__ngen_vm_lookups";
            int lookupRefsSize = lookupSwitches.size();

            // Execute micro VM and correctly convert the encoded top-of-stack value
            // back to the Java return type. The VM encodes values on a 64-bit stack:
            // - int/float use low 32 bits (float is raw IEEE754 bits)
            // - long/double use all 64 bits (double is raw IEEE754 bits)
            // - object/array are stored as their pointer cast to int64
            String vmCallFmt;
            if (vmTranslator.isUseJit()) {
                vmCallFmt = String.format(
                        "    auto __ngen_vm_ret = native_jvm::vm::execute_jit(env, __ngen_vm_code, %d, __ngen_vm_locals, %d, %dLL, %s, %d, %s, %d, %s, %d, %s, %d, %s, %d, %s, %d);\n",
                        vmCode.length, method.maxLocals, vmKeySeed, constantPoolPtr, constantPoolSize, methodRefsPtr, methodRefsSize, fieldRefsPtr, fieldRefsSize, multiRefsPtr, multiRefsSize, tableRefsPtr, tableRefsSize, lookupRefsPtr, lookupRefsSize);
            } else {
                vmCallFmt = String.format(
                        "    auto __ngen_vm_ret = native_jvm::vm::execute(env, __ngen_vm_code, %d, __ngen_vm_locals, %d, %dLL, %s, %d, %s, %d, %s, %d, %s, %d, %s, %d, %s, %d);\n",
                        vmCode.length, method.maxLocals, vmKeySeed, constantPoolPtr, constantPoolSize, methodRefsPtr, methodRefsSize, fieldRefsPtr, fieldRefsSize, multiRefsPtr, multiRefsSize, tableRefsPtr, tableRefsSize, lookupRefsPtr, lookupRefsSize);
            }
            output.append(vmCallFmt);
            switch (context.ret.getSort()) {
                case Type.DOUBLE: {
                    output.append("    jdouble __ngen_vm_ret_d; std::memcpy(&__ngen_vm_ret_d, &__ngen_vm_ret, sizeof(jdouble)); return __ngen_vm_ret_d;\n");
                    break;
                }
                case Type.FLOAT: {
                    output.append("    jint __ngen_vm_fbits = (jint)__ngen_vm_ret; jfloat __ngen_vm_ret_f; std::memcpy(&__ngen_vm_ret_f, &__ngen_vm_fbits, sizeof(jfloat)); return __ngen_vm_ret_f;\n");
                    break;
                }
                case Type.LONG: {
                    output.append("    return (jlong)__ngen_vm_ret;\n");
                    break;
                }
                case Type.INT:
                case Type.SHORT:
                case Type.CHAR:
                case Type.BYTE:
                case Type.BOOLEAN: {
                    output.append("    return (").append(CPP_TYPES[context.ret.getSort()]).append(") (jint)__ngen_vm_ret;\n");
                    break;
                }
                case Type.OBJECT:
                case Type.ARRAY: {
                    output.append("    return (").append(CPP_TYPES[context.ret.getSort()]).append(") (jlong)__ngen_vm_ret;\n");
                    break;
                }
                case Type.VOID: {
                    output.append("    (void)__ngen_vm_ret; return;\n");
                    break;
                }
                default:
                    output.append("    return (").append(CPP_TYPES[context.ret.getSort()]).append(") 0;\n");
                    break;
            }
            output.append("}\n");

            // Apply control flow flattening to virtualized methods if enabled
            if (context.protectionConfig.isControlFlowFlatteningEnabled()) {
                String methodBody = output.toString();
                int methodStart = methodBody.indexOf(") {\n") + 4;
                int methodEnd = methodBody.lastIndexOf("}\n");

                if (methodStart > 3 && methodEnd > methodStart) {
                    String methodSignature = methodBody.substring(0, methodStart);
                    String methodContent = methodBody.substring(methodStart, methodEnd);
                    String methodClosing = methodBody.substring(methodEnd);

                    String returnType = CPP_TYPES[context.ret.getSort()];
                    String flattenedContent = ControlFlowFlattener.flattenControlFlow(methodContent, method.name, returnType);
                    output.setLength(0);
                    output.append(methodSignature).append(flattenedContent).append(methodClosing);
                }
            }

            method.localVariables.clear();
            method.tryCatchBlocks.clear();

            specialMethodProcessor.postProcess(context);
            return;
        }

        if (!isStatic) {
            output.append("    jclass clazz = utils::get_class_from_object(env, obj);\n");
            output.append("    if (env->ExceptionCheck()) { ").append(String.format("return (%s) 0;",
                    CPP_TYPES[context.ret.getSort()])).append(" }\n");
        } else {
            // Be robust: some JVMs/paths may pass null clazz unexpectedly; try to resolve by name
            output.append("    if (env->IsSameObject(clazz, NULL)) { clazz = env->FindClass(")
                    .append(context.getStringPool().get(context.clazz.name))
                    .append("); if (env->ExceptionCheck()) { ")
                    .append(String.format("return (%s) 0;", CPP_TYPES[context.ret.getSort()]))
                    .append(" } }\n");
        }
        output.append("    jobject classloader = utils::get_classloader_from_class(env, clazz);\n");
        output.append("    if (env->ExceptionCheck()) { ").append(String.format("return (%s) 0;",
                CPP_TYPES[context.ret.getSort()])).append(" }\n");
        output.append("    if (classloader == nullptr) { env->FatalError(").append(context.getStringPool()
                .get("classloader == null")).append(String.format("); return (%s) 0; }\n", CPP_TYPES[context.ret.getSort()]));
        output.append("\n");
        if (!isStatic) {
            output.append("    env->DeleteLocalRef(clazz);\n");
            output.append("    clazz = utils::find_class_wo_static(env, classloader, ")
                    .append(context.getCachedStrings().getPointer(context.clazz.name.replace('/', '.')))
                    .append(");\n");
            output.append("    if (env->ExceptionCheck()) { ").append(String.format("return (%s) 0;",
                    CPP_TYPES[context.ret.getSort()])).append(" }\n");
        }
        output.append("    jobject lookup = nullptr;\n");

        if (method.tryCatchBlocks != null) {
            for (TryCatchBlockNode tryCatch : method.tryCatchBlocks) {
                context.getLabelPool().getName(tryCatch.start.getLabel());
                context.getLabelPool().getName(tryCatch.end.getLabel());
                context.getLabelPool().getName(tryCatch.handler.getLabel());
            }
            Set<String> classesForTryCatches = method.tryCatchBlocks.stream().filter((tryCatchBlock) -> (tryCatchBlock.type != null)).map(x -> x.type)
                    .collect(Collectors.toSet());
            classesForTryCatches.forEach((clazz) -> {
                int classId = context.getCachedClasses().getId(clazz);

                context.output.append(String.format("    // try-catch-class %s\n", Util.escapeCommentString(clazz)));
                context.output.append(String.format("    if (!cclasses[%d] || env->IsSameObject(cclasses[%d], NULL)) { cclasses_mtx[%d].lock(); "
                                + "if (!cclasses[%d] || env->IsSameObject(cclasses[%d], NULL)) { if (jclass clazz = %s) { cclasses[%d] = (jclass) env->NewWeakGlobalRef(clazz); env->DeleteLocalRef(clazz); } } "
                                + "cclasses_mtx[%d].unlock(); if (env->ExceptionCheck()) { return (%s) 0; } }\n",
                        classId,
                        classId,
                        classId,
                        classId,
                        classId,
                        getClassGetter(context, clazz),
                        classId,
                        classId,
                        CPP_TYPES[context.ret.getSort()]));
            });
        }

        if (method.maxStack > 0) {
            output.append("    jvalue ");
            for (int i = 0; i < method.maxStack; i++) {
                output.append(String.format("cstack%s = {}", i));
                if (i != method.maxStack - 1) {
                    output.append(", ");
                }
            }
            output.append(";\n");
        }

        if (method.maxLocals > 0) {
            output.append("    jvalue ");
            for (int i = 0; i < method.maxLocals; i++) {
                output.append(String.format("clocal%s = {}", i));
                if (i != method.maxLocals - 1) {
                    output.append(", ");
                }
            }
            output.append(";\n");
        }

        output.append("    std::unordered_set<jobject> refs;\n");
        output.append("\n");
        context.verifiedClassPreambleInsertionPoint = output.length();

        int localIndex = 0;
        for (int i = 0; i < context.argTypes.size(); ++i) {
            Type current = context.argTypes.get(i);
            output.append("    ").append(context.getSnippet(
                    "LOCAL_LOAD_ARG_" + current.getSort(), Util.createMap(
                            "index", localIndex,
                            "arg", argNames.get(i)
                    ))).append("\n");
            localIndex += current.getSize();
        }
        output.append("\n");

        context.argTypes.forEach(t -> context.locals.add(TYPE_TO_STACK[t.getSort()]));

        context.stackPointer = 0;
        context.dispatcherMode = true;
        boolean flattenControlFlow = context.protectionConfig.isControlFlowFlatteningEnabled();
        Set<Integer> referencedStates = new HashSet<>();

        int instructionCount = method.instructions.size();
        if (instructionCount == 0) {
            if (context.ret.getSort() == Type.VOID) {
                output.append("    return;\n");
            } else {
                output.append(String.format("    return (%s) 0;\n", CPP_TYPES[context.ret.getSort()]));
            }
            output.append("}\n");
            method.localVariables.clear();
            method.tryCatchBlocks.clear();
            specialMethodProcessor.postProcess(context);
            return;
        }

        int[] states = new int[instructionCount];
        for (int i = 0; i < instructionCount; i++) {
            states[i] = context.getLabelPool().generateStandaloneState();
            AbstractInsnNode node = method.instructions.get(i);
            if (node instanceof LabelNode) {
                context.getLabelPool().setState(((LabelNode) node).getLabel(), states[i]);
            }
        }
        int fakeState = flattenControlFlow ? context.getLabelPool().generateStandaloneState() : -1;

        ControlFlowFlattener.StateObfuscation stateObfuscation = null;
        if (flattenControlFlow) {
            stateObfuscation = ControlFlowFlattener.createObfuscation(method.name);
        }
        context.stateObfuscation = stateObfuscation;

        LinkedHashMap<Integer, StringBuilder> stateBlocks = new LinkedHashMap<>();

        for (int instruction = 0; instruction < instructionCount; ++instruction) {
            AbstractInsnNode node = method.instructions.get(instruction);
            int stateId = states[instruction];
            StringBuilder block = stateBlocks.computeIfAbsent(stateId, k -> new StringBuilder());

            block.append("        // ")
                    .append(Util.escapeCommentString(handlers[node.getType()].insnToString(context, node)))
                    .append("; Stack: ")
                    .append(context.stackPointer)
                    .append("\n");

            block.append("        ");
            int baseLength = output.length();
            handlers[node.getType()].accept(context, node);
            String handlerCode = output.substring(baseLength);
            output.setLength(baseLength);
            block.append(handlerCode);

            int newStackPointer = handlers[node.getType()].getNewStackPointer(node, context.stackPointer);
            block.append("        // New stack: ").append(newStackPointer).append("\n");
            context.stackPointer = newStackPointer;

            boolean changesFlow = node instanceof JumpInsnNode || node instanceof LookupSwitchInsnNode
                    || node instanceof TableSwitchInsnNode;
            int opcode = node.getOpcode();
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) changesFlow = true;
            if (opcode == Opcodes.ATHROW) changesFlow = true;
            switch (node) {
                case JumpInsnNode jumpInsnNode ->
                        referencedStates.add(parseStateId(context.getLabelPool().getName(jumpInsnNode.label.getLabel())));
                case TableSwitchInsnNode tableSwitch -> {
                    referencedStates.add(parseStateId(context.getLabelPool().getName(tableSwitch.dflt.getLabel())));
                    for (LabelNode labelNode : tableSwitch.labels) {
                        referencedStates.add(parseStateId(context.getLabelPool().getName(labelNode.getLabel())));
                    }
                }
                case LookupSwitchInsnNode lookupSwitch -> {
                    referencedStates.add(parseStateId(context.getLabelPool().getName(lookupSwitch.dflt.getLabel())));
                    for (LabelNode labelNode : lookupSwitch.labels) {
                        referencedStates.add(parseStateId(context.getLabelPool().getName(labelNode.getLabel())));
                    }
                }
                default -> {
                }
            }
            if (!changesFlow && flattenControlFlow) {
                int nextState = (instruction + 1 < instructionCount) ? states[instruction + 1] : fakeState;
                appendStateTransition(true, block, "            ", nextState, stateObfuscation);
            }
        }

        if (flattenControlFlow) {
            StringBuilder fakeBlock = stateBlocks.computeIfAbsent(fakeState, k -> new StringBuilder());
            appendStateTransition(true, fakeBlock, "            ", states[0], stateObfuscation);
        }

        boolean hasAddedNewBlocks = true;
        Set<CatchesBlock> proceedBlocks = new HashSet<>();

        while (hasAddedNewBlocks) {
            hasAddedNewBlocks = false;
            for (CatchesBlock catchBlock : new ArrayList<>(context.catches.keySet())) {
                if (proceedBlocks.contains(catchBlock)) {
                    continue;
                }
                proceedBlocks.add(catchBlock);
                int catchState = Integer.parseInt(context.catches.get(catchBlock));
                referencedStates.add(catchState);
                StringBuilder catchBody = stateBlocks.computeIfAbsent(catchState, k -> new StringBuilder());
                CatchesBlock.CatchBlock currentCatchBlock = catchBlock.getCatches().getFirst();
                if (currentCatchBlock.getClazz() == null) {
                    catchBody.append("            ")
                            .append(context.getSnippet("TRYCATCH_ANY_L", Util.createMap(
                                    "handler_block", context.getLabelPool().getName(currentCatchBlock.getHandler().getLabel())
                            )))
                            .append("\n");
                    referencedStates.add(parseStateId(context.getLabelPool().getName(currentCatchBlock.getHandler().getLabel())));
                    continue;
                }
                catchBody.append("            ")
                        .append(context.getSnippet("TRYCATCH_CHECK_STACK", Util.createMap(
                                "exception_class_ptr", context.getCachedClasses().getPointer(currentCatchBlock.getClazz()),
                                "handler_block", context.getLabelPool().getName(currentCatchBlock.getHandler().getLabel())
                        )))
                        .append("\n");
                referencedStates.add(parseStateId(context.getLabelPool().getName(currentCatchBlock.getHandler().getLabel())));
                if (catchBlock.getCatches().size() == 1) {
                    catchBody.append("            ")
                            .append(context.getSnippet("TRYCATCH_END_STACK", Util.createMap(
                                    "rettype", CPP_TYPES[context.ret.getSort()]
                            )))
                            .append("\n");
                    continue;
                }
                CatchesBlock nextCatchesBlock = new CatchesBlock(catchBlock.getCatches().stream().skip(1).collect(Collectors.toList()));
                if (context.catches.get(nextCatchesBlock) == null) {
                    context.catches.put(nextCatchesBlock, String.valueOf(context.getLabelPool().generateStandaloneState()));
                    hasAddedNewBlocks = true;
                }
                catchBody.append("            ")
                        .append(context.getSnippet("TRYCATCH_ANY_L", Util.createMap(
                                "handler_block", context.catches.get(nextCatchesBlock)
                        )))
                        .append("\n");
                referencedStates.add(parseStateId(context.catches.get(nextCatchesBlock)));
            }
        }

        if (context.verifiedClassPreamble.length() > 0 && context.verifiedClassPreambleInsertionPoint >= 0) {
            output.insert(context.verifiedClassPreambleInsertionPoint, context.verifiedClassPreamble.toString());
        }

        String defaultBlock = String.format("            return (%s) 0;\n", CPP_TYPES[context.ret.getSort()]);
        if (flattenControlFlow) {
            String stateMachine = ControlFlowFlattener.generateStateMachine(
                    method.name,
                    CPP_TYPES[context.ret.getSort()],
                    states[0],
                    stateBlocks,
                    defaultBlock,
                    stateObfuscation
            );
            output.append(stateMachine);
        } else {
            output.append(buildLinearControlFlow(stateBlocks, defaultBlock, referencedStates));
        }
        output.append("}\n");

        context.stateObfuscation = null;

        method.localVariables.clear();
        method.tryCatchBlocks.clear();

        specialMethodProcessor.postProcess(context);
    }

    public static String nameFromNode(MethodNode m, ClassNode cn) {
        return cn.name + '#' + m.name + '!' + m.desc;
    }

    private static void appendStateTransition(boolean flattenEnabled, StringBuilder block, String indent,
                                               int rawState, ControlFlowFlattener.StateObfuscation obfuscation) {
        if (flattenEnabled) {
            ControlFlowFlattener.appendStateTransition(block, indent, "__ngen_state", rawState, obfuscation);
        } else {
            block.append(indent)
                    .append("__ngen_state = ")
                    .append(rawState)
                    .append("; break;\n");
        }
    }

    private static String buildLinearControlFlow(LinkedHashMap<Integer, StringBuilder> stateBlocks, String defaultBlock,
                                                 Set<Integer> referencedStates) {
        StringBuilder linear = new StringBuilder();
        boolean firstBlock = true;
        for (Map.Entry<Integer, StringBuilder> entry : stateBlocks.entrySet()) {
            if (!firstBlock) {
                linear.append('\n');
            }
            firstBlock = false;
            boolean needLabel = referencedStates.contains(entry.getKey());
            if (needLabel) {
                linear.append("    ").append(getLinearLabelName(entry.getKey())).append(":\n");
            }
            String blockContent = linearizeStateAssignments(entry.getValue().toString());
            if (!blockContent.isEmpty()) {
                linear.append(blockContent);
                if (blockContent.charAt(blockContent.length() - 1) != '\n') {
                    linear.append('\n');
                }
            }
        }
        String fallback = linearizeStateAssignments(defaultBlock);
        if (!fallback.isEmpty()) {
            if (!linear.isEmpty() && linear.charAt(linear.length() - 1) != '\n') {
                linear.append('\n');
            }
            linear.append(fallback);
            if (linear.charAt(linear.length() - 1) != '\n') {
                linear.append('\n');
            }
        }
        return linear.toString();
    }

    private static String linearizeStateAssignments(String code) {
        if (code == null || code.isEmpty()) {
            return "";
        }
        Matcher matcher = STATE_ASSIGNMENT_PATTERN.matcher(code);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String rawState = matcher.group(1);
            matcher.appendReplacement(sb, Matcher.quoteReplacement("goto " + getLinearLabelName(rawState) + ";"));
        }
        matcher.appendTail(sb);
        return STANDALONE_BREAK_PATTERN.matcher(sb.toString()).replaceAll("");
    }

    private static String getLinearLabelName(int state) {
        return getLinearLabelName(Integer.toString(state));
    }

    private static String getLinearLabelName(String rawState) {
        if (rawState == null || rawState.isEmpty()) {
            return "__ngen_label_0";
        }
        boolean negative = rawState.startsWith("-");
        String digits = negative ? rawState.substring(1) : rawState;
        String sanitized = digits.replaceAll("[^0-9]", "_");
        if (sanitized.isEmpty()) {
            sanitized = "0";
        }
        return negative ? "__ngen_label_m" + sanitized : "__ngen_label_" + sanitized;
    }

    private static int parseStateId(String state) {
        if (state == null || state.isEmpty()) {
            throw new IllegalStateException("State id is null or empty");
        }
        try {
            return Integer.parseInt(state);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Unexpected state id: " + state, ex);
        }
    }

}
