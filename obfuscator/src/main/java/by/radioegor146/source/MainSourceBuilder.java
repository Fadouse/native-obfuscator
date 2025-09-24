package by.radioegor146.source;

import by.radioegor146.Util;
import by.radioegor146.AntiDebugConfig;

public class MainSourceBuilder {

    private final StringBuilder includes;
    private final StringBuilder registerMethods;

    public MainSourceBuilder() {
        includes = new StringBuilder();
        registerMethods = new StringBuilder();
    }

    public void addHeader(String hppFilename) {
        includes.append(String.format("#include \"output/%s\"\n", hppFilename));
    }

    public void registerClassMethods(int classId, String escapedClassName) {
        registerMethods.append(String.format(
                "        reg_methods[%d] = &(native_jvm::classes::__ngen_%s::__ngen_register_methods);\n",
                classId, escapedClassName));
    }

    public void registerDefine(String stringPooledClassName, String classFileName) {
        registerMethods.append(String.format(
                "        env->DeleteLocalRef(env->DefineClass(%s, nullptr, native_jvm::data::__ngen_%s::get_class_data(), native_jvm::data::__ngen_%s::get_class_data_length()));\n",
                stringPooledClassName,
                classFileName,
                classFileName
        ));
    }

    public String build(String nativeDir, int classCount) {
        String template = Util.readResource("sources/native_jvm_output.cpp");
        return Util.dynamicFormat(template, Util.createMap(
                "register_code", registerMethods,
                "includes", includes,
                "native_dir", nativeDir,
                "class_count", Math.max(1, classCount),
                "anti_debug_init", ""  // Default empty anti-debug init
        ));
    }

    public String build(String nativeDir, int classCount, AntiDebugConfig antiDebugConfig) {
        String template = Util.readResource("sources/native_jvm_output.cpp");
        String antiDebugInit = generateAntiDebugInit(antiDebugConfig);

        return Util.dynamicFormat(template, Util.createMap(
                "register_code", registerMethods,
                "includes", includes,
                "native_dir", nativeDir,
                "class_count", Math.max(1, classCount),
                "anti_debug_init", antiDebugInit
        ));
    }

    private String generateAntiDebugInit(AntiDebugConfig antiDebugConfig) {
        if (!antiDebugConfig.isAnyEnabled()) {
            return "";  // No anti-debug features enabled
        }

        StringBuilder antiDebugInit = new StringBuilder();
        antiDebugInit.append("        // Initialize anti-debug protection\n");
        antiDebugInit.append("        #ifdef ANTI_DEBUG_CONFIG_HPP_GUARD\n");
        antiDebugInit.append("        anti_debug::init_anti_debug(env, ");
        antiDebugInit.append(antiDebugConfig.isGHotSpotVMStructsNullificationEnabled() ? "true" : "false").append(", ");
        antiDebugInit.append(antiDebugConfig.isDebuggerDetectionEnabled() ? "true" : "false").append(", ");
        antiDebugInit.append(antiDebugConfig.isVmProtectionEnabled() ? "true" : "false").append(", ");
        antiDebugInit.append(antiDebugConfig.isAntiTamperEnabled() ? "true" : "false");
        antiDebugInit.append(");\n");
        antiDebugInit.append("        if (env->ExceptionCheck())\n");
        antiDebugInit.append("            return;\n");
        antiDebugInit.append("        #endif\n\n");

        return antiDebugInit.toString();
    }
}
