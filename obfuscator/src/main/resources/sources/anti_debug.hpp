#include "jni.h"

#ifndef ANTI_DEBUG_HPP_GUARD
#define ANTI_DEBUG_HPP_GUARD

namespace native_jvm::anti_debug {

    struct AntiDebugRuntimeConfig {
        bool enableGHotSpotVMStructNullification = false;
        bool enableDebuggerDetection = false;
        bool enableDebuggerApiChecks = false;
        bool enableDebuggerTracerCheck = false;
        bool enableDebuggerPtraceCheck = false;
        bool enableDebuggerProcessScan = false;
        bool enableDebuggerModuleScan = false;
        bool enableDebuggerEnvironmentScan = false;
        bool enableDebuggerTimingCheck = false;
        bool enableVmIntegrityChecks = false;
        bool enableJvmtiAgentBlocking = false;
        bool enableAntiTamper = false;
        bool enableDebugRegisterScrubbing = false;
        bool enableDebugLogging = false;
    };

    bool init_anti_debug(JNIEnv *env, const AntiDebugRuntimeConfig &config);

    bool init_anti_debug(JNIEnv *env,
                        bool enable_ghot_struct_nullification,
                        bool enable_debugger_detection,
                        bool enable_vm_protection,
                        bool enable_anti_tamper);

    bool nullify_ghotspot_vm_structs();
    bool detect_debugger(JNIEnv *env);
    bool check_vm_protection(JNIEnv *env);
    bool detect_tampering();
    bool runtime_anti_debug_check(JNIEnv *env);
    void anti_timing_delay();
    void protected_exit(int exit_code);
    bool install_jvmti_hooks(JavaVM *jvm);
    bool detect_agent_attachment();
    bool monitor_agent_loading(JNIEnv *env);

    namespace internal {
        bool is_windows();
        void corrupt_debug_registers();
        bool validate_code_sections();
        bool check_debugger_api();
        bool check_tracer_pid();
        bool check_ptrace_self_test();
        bool check_debugger_processes();
        bool check_suspicious_modules();
        bool check_debug_environment();
        bool check_timing_anomaly();

        bool hook_jvm_getenv(JavaVM *jvm);
        bool hook_agent_onattach();
        void debug_print(JNIEnv *env, const char* message);

        extern jint (*original_GetEnv)(JavaVM *vm, void **penv, jint version);
        extern void* original_agent_onattach;
    }

} // namespace native_jvm::anti_debug

#endif // ANTI_DEBUG_HPP_GUARD
