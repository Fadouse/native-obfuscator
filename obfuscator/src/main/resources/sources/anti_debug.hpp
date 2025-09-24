#include "jni.h"

#ifndef ANTI_DEBUG_HPP_GUARD
#define ANTI_DEBUG_HPP_GUARD

namespace native_jvm::anti_debug {

    /**
     * Initialize anti-debug protection systems
     * @param env JNI environment pointer
     * @param enable_ghot_struct_nullification Enable gHotSpotVMStructs nullification
     * @param enable_debugger_detection Enable debugger presence detection
     * @param enable_vm_protection Enable VM-level protection mechanisms
     * @param enable_anti_tamper Enable anti-tampering checks
     * @return true if initialization successful
     */
    bool init_anti_debug(JNIEnv *env,
                        bool enable_ghot_struct_nullification,
                        bool enable_debugger_detection,
                        bool enable_vm_protection,
                        bool enable_anti_tamper);

    /**
     * Nullify gHotSpotVMStructs to prevent debugging tools from accessing JVM internals
     * This is particularly effective against tools that rely on HotSpot's internal structures
     * @return true if successful, false otherwise
     */
    bool nullify_ghotspot_vm_structs();

    /**
     * Detect if a debugger is attached to the current process
     * Uses multiple detection methods for maximum effectiveness
     * @return true if debugger detected
     */
    bool detect_debugger();

    /**
     * Perform VM protection checks
     * Validates that the VM environment hasn't been tampered with
     * @param env JNI environment pointer
     * @return true if VM appears to be clean
     */
    bool check_vm_protection(JNIEnv *env);

    /**
     * Check for signs of code tampering or patching
     * @return true if tampering detected
     */
    bool detect_tampering();

    /**
     * Execute anti-debug checks during runtime
     * Should be called periodically from protected methods
     * @param env JNI environment pointer
     * @return true if execution should continue normally
     */
    bool runtime_anti_debug_check(JNIEnv *env);

    /**
     * Generate random delay to make timing analysis more difficult
     */
    void anti_timing_delay();

    /**
     * Obfuscated exit function to terminate if debugging is detected
     * @param exit_code Exit code to use
     */
    void protected_exit(int exit_code);

    /**
     * Hook JVMTI functions to prevent agent attachment
     * @param jvm JavaVM instance pointer
     * @return true if hooks installed successfully
     */
    bool install_jvmti_hooks(JavaVM *jvm);

    /**
     * Check for agent attachment attempts
     * @return true if agent attachment detected
     */
    bool detect_agent_attachment();

    /**
     * Block agent loading by monitoring JVM function calls
     * @param env JNI environment pointer
     * @return true if no agent loading detected
     */
    bool monitor_agent_loading(JNIEnv *env);

    /**
     * Internal helper functions
     */
    namespace internal {
        bool is_windows();
        bool is_debugger_present_windows();
        bool is_debugger_present_linux();
        bool check_ptrace_protection();
        void corrupt_debug_registers();
        bool validate_code_sections();

        // JVMTI hook functions
        bool hook_jvm_getenv(JavaVM *jvm);
        bool hook_agent_onattach();
        void debug_print(JNIEnv *env, const char* message);

        // Original function pointers
        extern jint (*original_GetEnv)(JavaVM *vm, void **penv, jint version);
        extern void* original_agent_onattach;
    }

} // namespace native_jvm::anti_debug

#endif // ANTI_DEBUG_HPP_GUARD