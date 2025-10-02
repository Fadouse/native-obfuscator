package by.radioegor146.source;

import by.radioegor146.CachedFieldInfo;
import by.radioegor146.CachedMethodInfo;
import by.radioegor146.HiddenCppMethod;
import by.radioegor146.NodeCache;
import by.radioegor146.Util;
import org.objectweb.asm.tree.ClassNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ClassSourceBuilder implements AutoCloseable {

    private final Path cppFile;
    private final Path hppFile;
    private final BufferedWriter cppWriter;
    private final BufferedWriter hppWriter;
    private final String className;
    private final String filename;

    private final StringPool stringPool;

    public ClassSourceBuilder(Path cppOutputDir, String className, int classIndex, StringPool stringPool) throws IOException {
        this.className = className;
        this.stringPool = stringPool;
        filename = String.format("%s_%d", Util.escapeCppNameString(className.replace('/', '_')), classIndex);

        cppFile = cppOutputDir.resolve(filename.concat(".cpp"));
        hppFile = cppOutputDir.resolve(filename.concat(".hpp"));
        cppWriter = Files.newBufferedWriter(cppFile, StandardCharsets.UTF_8);
        hppWriter = Files.newBufferedWriter(hppFile, StandardCharsets.UTF_8);
    }

    public void addHeader(int strings, int classes, int methods, int fields) throws IOException {
        cppWriter.append("#include \"../native_jvm.hpp\"\n");
        cppWriter.append("#include \"../string_pool.hpp\"\n");
        cppWriter.append("#include \"../micro_vm.hpp\"\n");
        cppWriter.append("#include \"").append(getHppFilename()).append("\"\n");
        cppWriter.append("\n");
        cppWriter.append("// ").append(Util.escapeCommentString(className)).append("\n");
        cppWriter.append("namespace native_jvm::classes::__ngen_").append(filename).append(" {\n\n");
        cppWriter.append("    char *string_pool;\n\n");

        if (strings > 0) {
            cppWriter.append(String.format("    jstring cstrings[%d];\n", strings));
        }
        if (classes > 0) {
            cppWriter.append(String.format("    std::mutex cclasses_mtx[%d];\n", classes));
            cppWriter.append(String.format("    jclass cclasses[%d];\n", classes));
            cppWriter.append(String.format("    std::atomic_bool cclasses_initialized[%d] = {};\n", classes));
        }
        if (methods > 0) {
            cppWriter.append(String.format("    jmethodID cmethods[%d];\n", methods));
        }
        if (fields > 0) {
            cppWriter.append(String.format("    jfieldID cfields[%d];\n", fields));
        }

        cppWriter.append("\n");
        cppWriter.append("    ");


        hppWriter.append("#include \"../native_jvm.hpp\"\n");
        hppWriter.append("#include \"../micro_vm.hpp\"\n");
        hppWriter.append("\n");
        hppWriter.append("#ifndef ").append(filename.concat("_hpp").toUpperCase()).append("_GUARD\n");
        hppWriter.append("\n");
        hppWriter.append("#define ").append(filename.concat("_hpp").toUpperCase()).append("_GUARD\n");
        hppWriter.append("\n");
        hppWriter.append("// ").append(Util.escapeCommentString(className)).append("\n");
        hppWriter.append("namespace native_jvm::classes::__ngen_")
                .append(filename)
                .append(" {\n\n");
    }

    public void addPrototypes(Iterable<String> prototypes) throws IOException {
        boolean wroteAny = false;
        for (String prototype : prototypes) {
            cppWriter.append("    ").append(prototype).append('\n');
            wroteAny = true;
        }
        if (wroteAny) {
            cppWriter.append('\n');
        }
    }

    public void addInstructions(String instructions) throws IOException {
        cppWriter.append(instructions);
        cppWriter.append("\n");
    }

    public void registerMethods(NodeCache<String> strings, NodeCache<String> classes,
                                NodeCache<CachedMethodInfo> methods, NodeCache<CachedFieldInfo> fields,
                                String nativeMethods, List<HiddenCppMethod> hiddenMethods) throws IOException {
        cppWriter.append("    void __ngen_register_methods(JNIEnv *env, jclass clazz) {\n");
        cppWriter.append("        string_pool = string_pool::get_pool();\n\n");

        Map<Integer, String> classIdToName = new HashMap<>();
        Map<String, Integer> classNameToId = new HashMap<>();
        for (Map.Entry<String, Integer> entry : classes.getCache().entrySet()) {
            classIdToName.put(entry.getValue(), entry.getKey());
            classNameToId.put(entry.getKey(), entry.getValue());
        }
        Map<String, Integer> stringNameToId = new HashMap<>(strings.getCache());

        for (Map.Entry<String, Integer> string : strings.getCache().entrySet()) {
            cppWriter.append("        if (jstring str = env->NewStringUTF(").append(stringPool.get(string.getKey())).append(")) { if (jstring int_str = utils::get_interned(env, str)) { ")
                    .append(String.format("cstrings[%d] = ", string.getValue()))
                    .append("(jstring) env->NewGlobalRef(int_str); env->DeleteLocalRef(str); env->DeleteLocalRef(int_str); } }\n");
        }

        if (!classes.isEmpty()) {
            cppWriter.append("\n");
        }

        if (!nativeMethods.isEmpty()) {
            cppWriter.append("        JNINativeMethod __ngen_methods[] = {\n");
            cppWriter.append(nativeMethods);
            cppWriter.append("        };\n\n");
            cppWriter.append("        if (clazz) env->RegisterNatives(clazz, __ngen_methods, sizeof(__ngen_methods) / sizeof(__ngen_methods[0]));\n");
            cppWriter.append("        if (env->ExceptionCheck()) { fprintf(stderr, \"Exception occured while registering native_jvm for %s\\n\", ")
                    .append(stringPool.get(className.replace('/', '.')))
                    .append("); fflush(stderr); env->ExceptionDescribe(); env->ExceptionClear(); }\n");
            cppWriter.append("\n");
        }

        if (!hiddenMethods.isEmpty()) {
            HashMap<ClassNode, List<HiddenCppMethod>> sortedHiddenMethods = new HashMap<>();
            for (HiddenCppMethod method : hiddenMethods) {
                sortedHiddenMethods.computeIfAbsent(method.getHiddenMethod().getClassNode(), unused -> new ArrayList<>()).add(method);
            }

            for (ClassNode hiddenClazz : sortedHiddenMethods.keySet()) {
                cppWriter.append("        {\n");
                cppWriter.append("            jclass hidden_class = env->FindClass(").append(stringPool.get(hiddenClazz.name)).append(");\n");
                cppWriter.append("            JNINativeMethod __ngen_hidden_methods[] = {\n");
                for (HiddenCppMethod method : sortedHiddenMethods.get(hiddenClazz)) {
                    cppWriter.append(String.format("                { %s, %s, (void *)&%s },\n",
                            stringPool.get(method.getHiddenMethod().getMethodNode().name),
                            stringPool.get(method.getHiddenMethod().getMethodNode().desc),
                            method.getCppName()));
                }
                cppWriter.append("            };\n");
                cppWriter.append("            if (hidden_class) env->RegisterNatives(hidden_class, __ngen_hidden_methods, sizeof(__ngen_hidden_methods) / sizeof(__ngen_hidden_methods[0]));\n");
                cppWriter.append("            if (env->ExceptionCheck()) { fprintf(stderr, \"Exception occured while registering native_jvm for %s\\n\", ")
                        .append(stringPool.get(hiddenClazz.name.replace('/', '.')))
                        .append("); fflush(stderr); env->ExceptionDescribe(); env->ExceptionClear(); }\n");
                cppWriter.append("            env->DeleteLocalRef(hidden_class);\n");
                cppWriter.append("        }\n");

            }
        }

        Set<Integer> staticInitClassIds = new LinkedHashSet<>();
        methods.getCache().forEach((info, id) -> {
            if (info.isStatic()) {
                Integer classId = classNameToId.get(info.getClazz());
                if (classId != null) {
                    staticInitClassIds.add(classId);
                }
            }
        });
        fields.getCache().forEach((info, id) -> {
            if (info.isStatic()) {
                Integer classId = classNameToId.get(info.getClazz());
                if (classId != null) {
                    staticInitClassIds.add(classId);
                }
            }
        });

        boolean needsPrefetch = !classIdToName.isEmpty() || !methods.isEmpty() || !fields.isEmpty();
        if (needsPrefetch) {
            cppWriter.append("        jobject classloader = utils::get_classloader_from_class(env, clazz);\n");
            cppWriter.append("        if (env->ExceptionCheck()) { return; }\n");
            cppWriter.append("        if (classloader == nullptr) { env->FatalError(")
                    .append(stringPool.get("classloader == null")).append("); return; }\n\n");
        }

        int tempStringCounter = 0;
        for (Map.Entry<Integer, String> entry : classIdToName.entrySet()) {
            int classId = entry.getKey();
            String owner = entry.getValue();
            cppWriter.append(String.format("        if (!cclasses[%d]) {\n", classId));
            cppWriter.append(String.format("            cclasses_mtx[%d].lock();\n", classId));
            cppWriter.append(String.format("            if (!cclasses[%d]) {\n", classId));

            if (owner.startsWith("[")) {
                cppWriter.append(String.format("                jclass resolved = env->FindClass(%s);\n", stringPool.get(owner)));
            } else {
                String dotted = owner;
                if (dotted.startsWith("L") && dotted.endsWith(";")) {
                    dotted = dotted.substring(1, dotted.length() - 1);
                }
                dotted = dotted.replace('/', '.');
                Integer cachedStringId = stringNameToId.get(dotted);
                if (cachedStringId != null) {
                    cppWriter.append(String.format("                jclass resolved = utils::find_class_wo_static(env, classloader, (cstrings[%d]));\n", cachedStringId));
                } else {
                    String tempVar = "__ngen_tmp_str_" + tempStringCounter++;
                    cppWriter.append(String.format("                jstring %s = env->NewStringUTF(%s);\n", tempVar, stringPool.get(dotted)));
                    cppWriter.append(String.format("                if (env->ExceptionCheck()) { cclasses_mtx[%d].unlock(); return; }\n", classId));
                    cppWriter.append(String.format("                jclass resolved = utils::find_class_wo_static(env, classloader, %s);\n", tempVar));
                    cppWriter.append(String.format("                env->DeleteLocalRef(%s);\n", tempVar));
                }
            }

            cppWriter.append("                if (env->ExceptionCheck()) { cclasses_mtx[" + classId + "].unlock(); return; }\n");
            cppWriter.append("                if (resolved) {\n");
            cppWriter.append(String.format("                    cclasses[%d] = (jclass) env->NewGlobalRef(resolved);\n", classId));
            cppWriter.append("                    env->DeleteLocalRef(resolved);\n");
            cppWriter.append("                }\n");
            cppWriter.append("            }\n");
            cppWriter.append(String.format("            cclasses_mtx[%d].unlock();\n", classId));
            cppWriter.append("            if (env->ExceptionCheck()) { return; }\n");
            cppWriter.append("        }\n");
        }

        if (!staticInitClassIds.isEmpty()) {
            cppWriter.append("\n");
            for (Integer classId : staticInitClassIds) {
                String owner = classIdToName.get(classId);
                if (owner == null || owner.startsWith("[")) {
                    continue;
                }
                String dotted = owner;
                if (dotted.startsWith("L") && dotted.endsWith(";")) {
                    dotted = dotted.substring(1, dotted.length() - 1);
                }
                dotted = dotted.replace('/', '.');
                String dottedPtr = stringPool.get(dotted);
                cppWriter.append(String.format("        if (cclasses[%d] && !cclasses_initialized[%d].load()) {\n", classId, classId));
                cppWriter.append(String.format("            utils::ensure_initialized(env, classloader, %s);\n", dottedPtr));
                cppWriter.append("            if (env->ExceptionCheck()) { return; }\n");
                cppWriter.append(String.format("            cclasses_initialized[%d].store(true);\n", classId));
                cppWriter.append("        }\n");
            }
        }

        if (!methods.isEmpty()) {
            cppWriter.append("\n");
            for (Map.Entry<CachedMethodInfo, Integer> entry : methods.getCache().entrySet()) {
                CachedMethodInfo info = entry.getKey();
                int methodId = entry.getValue();
                Integer classId = classNameToId.get(info.getClazz());
                if (classId == null) {
                    continue;
                }
                String classRef = String.format("cclasses[%d]", classId);
                String methodNamePtr = stringPool.get(info.getName());
                String methodDescPtr = stringPool.get(info.getDesc());
                cppWriter.append(String.format("        if (!cmethods[%d] && %s) {\n", methodId, classRef));
                cppWriter.append(String.format("            cmethods[%d] = env->Get%sMethodID(%s, %s, %s);\n",
                        methodId,
                        info.isStatic() ? "Static" : "",
                        classRef,
                        methodNamePtr,
                        methodDescPtr));
                cppWriter.append("            if (env->ExceptionCheck()) { return; }\n");
                cppWriter.append("        }\n");
            }
        }

        if (!fields.isEmpty()) {
            cppWriter.append("\n");
            for (Map.Entry<CachedFieldInfo, Integer> entry : fields.getCache().entrySet()) {
                CachedFieldInfo info = entry.getKey();
                int fieldId = entry.getValue();
                Integer classId = classNameToId.get(info.getClazz());
                if (classId == null) {
                    continue;
                }
                String classRef = String.format("cclasses[%d]", classId);
                String fieldNamePtr = stringPool.get(info.getName());
                String fieldDescPtr = stringPool.get(info.getDesc());
                cppWriter.append(String.format("        if (!cfields[%d] && %s) {\n", fieldId, classRef));
                cppWriter.append(String.format("            cfields[%d] = env->Get%sFieldID(%s, %s, %s);\n",
                        fieldId,
                        info.isStatic() ? "Static" : "",
                        classRef,
                        fieldNamePtr,
                        fieldDescPtr));
                cppWriter.append("            if (env->ExceptionCheck()) { return; }\n");
                cppWriter.append("        }\n");
            }
        }

        cppWriter.append("    }\n");
        cppWriter.append("}");

        hppWriter.append("    void __ngen_register_methods(JNIEnv *env, jclass clazz);\n");
        hppWriter.append("}\n\n#endif");
    }

    public String getFilename() {
        return filename;
    }

    public String getHppFilename() {
        return hppFile.getFileName().toString();
    }

    public String getCppFilename() {
        return cppFile.getFileName().toString();
    }

    @Override
    public void close() throws IOException {
        try {
            cppWriter.close();
        } finally {
            hppWriter.close();
        }
    }
}
