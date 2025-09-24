#include "anti_debug.hpp"

#ifdef _WIN32
#include <windows.h>
#include <psapi.h>
#include <tlhelp32.h>
#include <winternl.h>
#pragma comment(lib, "psapi.lib")
#pragma comment(lib, "ntdll.lib")
#else
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <unistd.h>
#include <signal.h>
#include <fcntl.h>
#include <string.h>
#include <sys/stat.h>
#include <dirent.h>
#include <dlfcn.h>
#include <sys/mman.h>
#endif

#include <cstdlib>
#include <ctime>
#include <thread>
#include <chrono>
#include <random>
#include <atomic>

namespace native_jvm::anti_debug {

    // Global anti-debug configuration
    static bool g_ghot_struct_nullification = false;
    static bool g_debugger_detection = false;
    static bool g_vm_protection = false;
    static bool g_anti_tamper = false;
    static bool g_initialized = false;
    static bool g_jvmti_hooks_installed = false;
    static std::atomic<int> g_agent_attachment_attempts{0};

    bool init_anti_debug(JNIEnv *env,
                        bool enable_ghot_struct_nullification,
                        bool enable_debugger_detection,
                        bool enable_vm_protection,
                        bool enable_anti_tamper) {

        if (g_initialized) {
            return true; // Already initialized
        }

        g_ghot_struct_nullification = enable_ghot_struct_nullification;
        g_debugger_detection = enable_debugger_detection;
        g_vm_protection = enable_vm_protection;
        g_anti_tamper = enable_anti_tamper;

        // Install JVMTI hooks early in initialization
        if (env != nullptr) {
            JavaVM *jvm = nullptr;
            if (env->GetJavaVM(&jvm) == JNI_OK && jvm != nullptr) {
                if (install_jvmti_hooks(jvm)) {
                    internal::debug_print(env, "[Anti-Debug] JVMTI hooks installed successfully");
                } else {
                    internal::debug_print(env, "[Anti-Debug] JVMTI hooks installation failed");
                }
            }
        }

        // Apply gHotSpotVMStructs nullification early if enabled
        if (g_ghot_struct_nullification) {
            bool result = nullify_ghotspot_vm_structs();
            // Test output using JNI to call System.out.println
            if (env != nullptr) {
                jclass systemClass = env->FindClass("java/lang/System");
                if (systemClass != nullptr) {
                    jfieldID outFieldID = env->GetStaticFieldID(systemClass, "out", "Ljava/io/PrintStream;");
                    if (outFieldID != nullptr) {
                        jobject outObject = env->GetStaticObjectField(systemClass, outFieldID);
                        if (outObject != nullptr) {
                            jclass printStreamClass = env->FindClass("java/io/PrintStream");
                            if (printStreamClass != nullptr) {
                                jmethodID printlnMethod = env->GetMethodID(printStreamClass, "println", "(Ljava/lang/String;)V");
                                if (printlnMethod != nullptr) {
                                    jstring message = env->NewStringUTF(result ?
                                        "[Anti-Debug] gHotSpotVMStructs nullification: SUCCESS" :
                                        "[Anti-Debug] gHotSpotVMStructs nullification: FAILED");
                                    env->CallVoidMethod(outObject, printlnMethod, message);
                                    env->DeleteLocalRef(message);
                                }
                                env->DeleteLocalRef(printStreamClass);
                            }
                            env->DeleteLocalRef(outObject);
                        }
                    }
                    env->DeleteLocalRef(systemClass);
                }
            }
        }

        // Perform initial debugger detection
        if (g_debugger_detection && detect_debugger()) {
            // Debugger detected on initialization - terminate
            protected_exit(1);
            return false;
        }

        // Set up VM protection checks
        if (g_vm_protection && !check_vm_protection(env)) {
            // VM tampering detected
            protected_exit(2);
            return false;
        }

        // Initial tampering check
        if (g_anti_tamper && detect_tampering()) {
            // Code tampering detected
            protected_exit(3);
            return false;
        }

        g_initialized = true;
        return true;
    }

    bool nullify_ghotspot_vm_structs() {
#ifdef _WIN32
        // Get handle to jvm.dll module
        HMODULE hJvm = GetModuleHandleA("jvm.dll");
        if (hJvm == NULL) {
            return false; // jvm.dll not found
        }

        // Find the address of the gHotSpotVMStructs symbol
        void* pGHotSpotVMStructs = (void*)GetProcAddress(hJvm, "gHotSpotVMStructs");
        if (pGHotSpotVMStructs == NULL) {
            return false; // Symbol not found
        }

        // Change memory protection to allow writing
        DWORD oldProtect;
        if (VirtualProtect(pGHotSpotVMStructs, sizeof(void*), PAGE_READWRITE, &oldProtect)) {
            // Overwrite the pointer with NULL
            *(void**)pGHotSpotVMStructs = NULL;

            // Restore original memory protection
            DWORD temp;
            VirtualProtect(pGHotSpotVMStructs, sizeof(void*), oldProtect, &temp);

            return true;
        }
        return false;
#else
        // Linux/Unix implementation would require different approach
        // This could involve dlsym to find the symbol and similar memory protection changes
        // For now, return false on non-Windows platforms
        return false;
#endif
    }

    bool detect_debugger() {
        if (!g_debugger_detection) return false;

        // Use multiple detection methods for robustness
        bool debugger_found = false;

        if (internal::is_windows()) {
            debugger_found = internal::is_debugger_present_windows();
        } else {
            debugger_found = internal::is_debugger_present_linux();
        }

        // Additional generic checks
        if (!debugger_found) {
            debugger_found = !internal::check_ptrace_protection();
        }

        if (debugger_found) {
            // Add some obfuscation to make analysis harder
            anti_timing_delay();
        }

        return debugger_found;
    }

    bool check_vm_protection(JNIEnv *env) {
        if (!g_vm_protection || env == nullptr) return true;

        // Check JNI function table integrity
        if (env->functions == nullptr) {
            return false;
        }

        // Validate some critical JNI functions haven't been hooked
        void* expected_funcs[] = {
            (void*)env->functions->FindClass,
            (void*)env->functions->GetMethodID,
            (void*)env->functions->CallObjectMethod
        };

        for (void* func : expected_funcs) {
            if (func == nullptr) {
                return false;
            }
        }

        return true;
    }

    bool detect_tampering() {
        if (!g_anti_tamper) return false;

        // Check for code section modifications
        return !internal::validate_code_sections();
    }

    bool runtime_anti_debug_check(JNIEnv *env) {
        if (!g_initialized) return true;

        // Monitor for agent loading attempts
        if (!monitor_agent_loading(env)) {
            internal::debug_print(env, "[Anti-Debug] Agent loading detected - terminating!");
            protected_exit(7);
            return false;
        }

        // Periodic debugger detection
        if (g_debugger_detection && detect_debugger()) {
            protected_exit(4);
            return false;
        }

        // VM protection check
        if (g_vm_protection && !check_vm_protection(env)) {
            protected_exit(5);
            return false;
        }

        // Tampering check
        if (g_anti_tamper && detect_tampering()) {
            protected_exit(6);
            return false;
        }

        return true;
    }

    void anti_timing_delay() {
        // Generate random delay between 1-10ms to make timing analysis harder
        static std::random_device rd;
        static std::mt19937 gen(rd());
        std::uniform_int_distribution<> dis(1, 10);

        int delay_ms = dis(gen);
        std::this_thread::sleep_for(std::chrono::milliseconds(delay_ms));
    }

    void protected_exit(int exit_code) {
        // Obfuscated exit to make it harder to patch
        volatile int exit_val = exit_code;

        // Corrupt some memory before exiting to make debugging harder
        static volatile char corruption_buffer[1024];
        for (int i = 0; i < 1024; i++) {
            corruption_buffer[i] = (char)(rand() % 256);
        }

        // Use multiple exit methods
        std::exit(exit_val);
    }

    bool install_jvmti_hooks(JavaVM *jvm) {
        if (g_jvmti_hooks_installed || jvm == nullptr) {
            return false;
        }

        bool success = false;

        // Hook JVM GetEnv function
        if (internal::hook_jvm_getenv(jvm)) {
            success = true;
        }

        // Hook potential Agent_OnAttach entry points
        if (internal::hook_agent_onattach()) {
            success = true;
        }

        if (success) {
            g_jvmti_hooks_installed = true;
        }

        return success;
    }

    bool detect_agent_attachment() {
        return g_agent_attachment_attempts.load() > 0;
    }

    bool monitor_agent_loading(JNIEnv *env) {
        if (env == nullptr) return true;

        // Check for Instrumentation class loading
        jclass instrClass = env->FindClass("sun/instrument/InstrumentationImpl");
        if (instrClass != nullptr) {
            env->DeleteLocalRef(instrClass);
            internal::debug_print(env, "[Anti-Debug] JVMTI: Instrumentation class detected!");
            g_agent_attachment_attempts.fetch_add(1);
            return false;
        }

        // Check for JVMTI environment creation
        if (detect_agent_attachment()) {
            internal::debug_print(env, "[Anti-Debug] JVMTI: Agent attachment attempt detected!");
            return false;
        }

        return true;
    }

    namespace internal {

        // Original function pointers for JVMTI hooks
        jint (*original_GetEnv)(JavaVM *vm, void **penv, jint version) = nullptr;
        void* original_agent_onattach = nullptr;

        // Hooked GetEnv function
        jint JNICALL hooked_GetEnv(JavaVM *vm, void **penv, jint version) {
            // Debug output
             if (penv != nullptr && ((static_cast<jint>(version) & 0xFF000000) == 0x30000000)) { // JVMTI version 1.0
                // Get JNI environment for debug output
                JNIEnv *env = nullptr;
                if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK && env != nullptr) {
                    debug_print(env, "[Anti-Debug] JVMTI: GetEnv() called - BLOCKED!");
                }
                g_agent_attachment_attempts.fetch_add(1);
                return JNI_EVERSION; // Return error to prevent JVMTI access
            }

            // Allow other environment types (JNI, etc.)
            if (original_GetEnv != nullptr) {
                return original_GetEnv(vm, penv, version);
            }
            return JNI_EVERSION;
        }

        bool is_windows() {
#ifdef _WIN32
            return true;
#else
            return false;
#endif
        }

        bool is_debugger_present_windows() {
#ifdef _WIN32
            // Method 1: IsDebuggerPresent API
            if (IsDebuggerPresent()) {
                return true;
            }

            // Method 2: CheckRemoteDebuggerPresent
            BOOL remote_debugger = FALSE;
            CheckRemoteDebuggerPresent(GetCurrentProcess(), &remote_debugger);
            if (remote_debugger) {
                return true;
            }

            // Method 3: NtQueryInformationProcess
            typedef NTSTATUS (NTAPI *pfnNtQueryInformationProcess)(
                HANDLE ProcessHandle,
                ULONG ProcessInformationClass,
                PVOID ProcessInformation,
                ULONG ProcessInformationLength,
                PULONG ReturnLength
            );

            HMODULE hNtdll = GetModuleHandleA("ntdll.dll");
            if (hNtdll) {
                pfnNtQueryInformationProcess NtQueryInformationProcess =
                    (pfnNtQueryInformationProcess)GetProcAddress(hNtdll, "NtQueryInformationProcess");

                if (NtQueryInformationProcess) {
                    DWORD debugPort = 0;
                    NTSTATUS status = NtQueryInformationProcess(
                        GetCurrentProcess(),
                        7, // ProcessDebugPort
                        &debugPort,
                        sizeof(debugPort),
                        NULL
                    );

                    if (status == 0 && debugPort != 0) {
                        return true;
                    }
                }
            }

            // Method 4: Check for debugger processes
            HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
            if (snapshot != INVALID_HANDLE_VALUE) {
                PROCESSENTRY32 entry;
                entry.dwSize = sizeof(PROCESSENTRY32);

                if (Process32First(snapshot, &entry)) {
                    do {
                        // List of common debugger process names
                        const char* debugger_names[] = {
                            "ollydbg.exe", "x64dbg.exe", "x32dbg.exe", "windbg.exe",
                            "ida.exe", "ida64.exe", "idaq.exe", "idaq64.exe",
                            "immunitydebugger.exe", "cheatengine-x86_64.exe"
                        };

                        for (const char* name : debugger_names) {
                            if (_stricmp(entry.szExeFile, name) == 0) {
                                CloseHandle(snapshot);
                                return true;
                            }
                        }
                    } while (Process32Next(snapshot, &entry));
                }
                CloseHandle(snapshot);
            }
#endif
            return false;
        }

        bool is_debugger_present_linux() {
#ifndef _WIN32
            // Method 1: Check TracerPid in /proc/self/status
            FILE* status_file = fopen("/proc/self/status", "r");
            if (status_file) {
                char line[256];
                while (fgets(line, sizeof(line), status_file)) {
                    if (strncmp(line, "TracerPid:", 10) == 0) {
                        int tracer_pid = 0;
                        sscanf(line + 10, "%d", &tracer_pid);
                        fclose(status_file);
                        return tracer_pid != 0;
                    }
                }
                fclose(status_file);
            }

            // Method 2: Try to ptrace ourselves
            pid_t child_pid = fork();
            if (child_pid == 0) {
                // Child process
                if (ptrace(PTRACE_TRACEME, 0, 1, 0) < 0) {
                    exit(1); // Already being traced
                } else {
                    exit(0); // Not being traced
                }
            } else if (child_pid > 0) {
                // Parent process
                int status;
                waitpid(child_pid, &status, 0);
                return WEXITSTATUS(status) != 0;
            }
#endif
            return false;
        }

        bool check_ptrace_protection() {
#ifndef _WIN32
            // Try to use ptrace on ourselves - if it fails, we might be debugged
            return ptrace(PTRACE_TRACEME, 0, 1, 0) >= 0;
#endif
            return true;
        }

        void corrupt_debug_registers() {
#ifdef _WIN32
            // Attempt to corrupt debug registers (requires specific privileges)
            CONTEXT context;
            context.ContextFlags = CONTEXT_DEBUG_REGISTERS;
            if (GetThreadContext(GetCurrentThread(), &context)) {
                context.Dr0 = 0;
                context.Dr1 = 0;
                context.Dr2 = 0;
                context.Dr3 = 0;
                context.Dr7 = 0;
                SetThreadContext(GetCurrentThread(), &context);
            }
#endif
        }

        bool validate_code_sections() {
            // Simple code integrity check - in a real implementation,
            // this would check checksums of critical code sections
            // For now, just return true
            return true;
        }

        void debug_print(JNIEnv *env, const char* message) {
            if (env == nullptr || message == nullptr) return;

            jclass systemClass = env->FindClass("java/lang/System");
            if (systemClass != nullptr) {
                jfieldID outFieldID = env->GetStaticFieldID(systemClass, "out", "Ljava/io/PrintStream;");
                if (outFieldID != nullptr) {
                    jobject outObject = env->GetStaticObjectField(systemClass, outFieldID);
                    if (outObject != nullptr) {
                        jclass printStreamClass = env->FindClass("java/io/PrintStream");
                        if (printStreamClass != nullptr) {
                            jmethodID printlnMethod = env->GetMethodID(printStreamClass, "println", "(Ljava/lang/String;)V");
                            if (printlnMethod != nullptr) {
                                jstring jmessage = env->NewStringUTF(message);
                                env->CallVoidMethod(outObject, printlnMethod, jmessage);
                                env->DeleteLocalRef(jmessage);
                            }
                            env->DeleteLocalRef(printStreamClass);
                        }
                        env->DeleteLocalRef(outObject);
                    }
                }
                env->DeleteLocalRef(systemClass);
            }
        }

        bool hook_jvm_getenv(JavaVM *jvm) {
            if (jvm == nullptr) return false;

#ifdef _WIN32
            // Get the JVM interface table
            JNIInvokeInterface_ *invoke_interface = const_cast<JNIInvokeInterface_ *>(jvm->functions);
            if (invoke_interface == nullptr) return false;

            // Store original GetEnv function pointer
            original_GetEnv = invoke_interface->GetEnv;
            if (original_GetEnv == nullptr) return false;

            // Change memory protection to allow modification
            DWORD oldProtect;
            if (VirtualProtect(&invoke_interface->GetEnv, sizeof(void*), PAGE_READWRITE, &oldProtect)) {
                // Replace with our hooked function
                invoke_interface->GetEnv = hooked_GetEnv;

                // Restore memory protection
                DWORD temp;
                VirtualProtect(&invoke_interface->GetEnv, sizeof(void*), oldProtect, &temp);

                return true;
            }
#else
            // Linux implementation using mprotect
            JNIInvokeInterface_ *invoke_interface = const_cast<JNIInvokeInterface_ *>(jvm->functions);
            if (invoke_interface == nullptr) return false;

            // Store original GetEnv function pointer
            original_GetEnv = invoke_interface->GetEnv;
            if (original_GetEnv == nullptr) return false;

            // Get page size and align address
            size_t page_size = getpagesize();
            void *page_addr = (void*)((uintptr_t)&invoke_interface->GetEnv & ~(page_size - 1));

            // Change memory protection
            if (mprotect(page_addr, page_size, PROT_READ | PROT_WRITE) == 0) {
                // Replace with our hooked function
                invoke_interface->GetEnv = hooked_GetEnv;

                // Restore memory protection
                mprotect(page_addr, page_size, PROT_READ | PROT_EXEC);

                return true;
            }
#endif
            return false;
        }

        bool hook_agent_onattach() {
#ifdef _WIN32
            // Try to find and hook common agent attachment points
            HMODULE hJvm = GetModuleHandleA("jvm.dll");
            if (hJvm == NULL) return false;

            // Look for Agent_OnAttach export
            FARPROC agent_onattach = GetProcAddress(hJvm, "Agent_OnAttach");
            if (agent_onattach != NULL) {
                // Store original pointer
                original_agent_onattach = (void*)agent_onattach;

                // For now, just log that we found it
                return true;
            }

            // Check for dynamic agent loading functions
            FARPROC jvm_attach = GetProcAddress(hJvm, "JVM_Attach");
            if (jvm_attach != NULL) {
                // Found attachment function
                return true;
            }
#else
            // Linux implementation - check for agent libraries
            void *handle = dlopen("libjvm.so", RTLD_LAZY | RTLD_NOLOAD);
            if (handle != NULL) {
                void *agent_onattach = dlsym(handle, "Agent_OnAttach");
                if (agent_onattach != NULL) {
                    original_agent_onattach = agent_onattach;
                    // For demonstration, we're just logging the detection
                    return true;
                }
                dlclose(handle);
            }
#endif
            return false;
        }
    }

} // namespace native_jvm::anti_debug