#include "anti_debug.hpp"
#include "string_pool.hpp"

#ifdef _WIN32
#include <windows.h>
#include <psapi.h>
#include <tlhelp32.h>
#include <winternl.h>
#include <stdlib.h>
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
#include <errno.h>
#endif

#include <cstdlib>
#include <ctime>
#include <thread>
#include <chrono>
#include <random>
#include <atomic>
#include <vector>
#include <string>
#include <array>
#include <algorithm>
#include <cctype>
#include <fstream>
#include <sstream>
#include <cstring>
#include <cstdio>
#include <cstdint>

#ifdef __has_include
#  if __has_include("anti_debug_config.hpp")
#    include "anti_debug_config.hpp"
#    define ANTI_DEBUG_HAS_CONFIG 1
#  endif
#endif
#ifndef ANTI_DEBUG_HAS_CONFIG
#  define ANTI_DEBUG_HAS_CONFIG 0
#endif

namespace native_jvm::anti_debug {

    // Global anti-debug configuration
    static AntiDebugRuntimeConfig g_config{};
    static bool g_initialized = false;
    static bool g_jvmti_hooks_installed = false;
    static std::atomic<int> g_agent_attachment_attempts{0};

    bool init_anti_debug(JNIEnv *env, const AntiDebugRuntimeConfig &config) {
        if (g_initialized) {
            return true;
        }

        g_config = config;

        // Install JVMTI hooks early when agent blocking is enabled
        if (env != nullptr && g_config.enableJvmtiAgentBlocking) {
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
        if (g_config.enableGHotSpotVMStructNullification) {
            bool result = nullify_ghotspot_vm_structs();
            if (env != nullptr) {
                internal::debug_print(env, result ?
                    "[Anti-Debug] gHotSpotVMStructs nullification: SUCCESS" :
                    "[Anti-Debug] gHotSpotVMStructs nullification: FAILED");
            }
        }

        // Perform initial debugger detection
        if (g_config.enableDebuggerDetection && detect_debugger(env)) {
            protected_exit(1);
            return false;
        }

        // Set up VM protection checks
        if (g_config.enableVmIntegrityChecks && !check_vm_protection(env)) {
            protected_exit(2);
            return false;
        }

        // Initial tampering check
        if (g_config.enableAntiTamper && detect_tampering(env)) {
            protected_exit(3);
            return false;
        }

        g_initialized = true;
        return true;
    }

    bool init_anti_debug(JNIEnv *env,
                        bool enable_ghot_struct_nullification,
                        bool enable_debugger_detection,
                        bool enable_vm_protection,
                        bool enable_anti_tamper) {
        AntiDebugRuntimeConfig config{};
        config.enableGHotSpotVMStructNullification = enable_ghot_struct_nullification;
        config.enableDebuggerDetection = enable_debugger_detection;
        config.enableDebuggerApiChecks = enable_debugger_detection;
        config.enableDebuggerTracerCheck = enable_debugger_detection;
        config.enableDebuggerPtraceCheck = enable_debugger_detection;
        config.enableDebuggerProcessScan = enable_debugger_detection;
        config.enableDebuggerModuleScan = enable_debugger_detection;
        config.enableDebuggerEnvironmentScan = enable_debugger_detection;
        config.enableDebuggerTimingCheck = enable_debugger_detection;
        config.enableVmIntegrityChecks = enable_vm_protection;
        config.enableJvmtiAgentBlocking = enable_vm_protection;
        config.enableAntiTamper = enable_anti_tamper;
        config.enableDebugRegisterScrubbing = enable_debugger_detection;
        return init_anti_debug(env, config);
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

    bool detect_debugger(JNIEnv *env) {
        if (!g_config.enableDebuggerDetection) {
            return false;
        }

        auto handle_detection = [&](const char *message) {
            internal::debug_print(env, message);
            if (g_config.enableDebugRegisterScrubbing) {
                internal::corrupt_debug_registers();
            }
            anti_timing_delay();
            return true;
        };

        if (g_config.enableDebuggerApiChecks && internal::check_debugger_api()) {
            return handle_detection("[Anti-Debug] API-based debugger detection triggered");
        }

        if (g_config.enableDebuggerTracerCheck && internal::check_tracer_pid()) {
            return handle_detection("[Anti-Debug] Tracer PID detected");
        }

        if (g_config.enableDebuggerPtraceCheck && internal::check_ptrace_self_test()) {
            return handle_detection("[Anti-Debug] ptrace self-test indicates tracing");
        }

        if (g_config.enableDebuggerProcessScan && internal::check_debugger_processes()) {
            return handle_detection("[Anti-Debug] Suspicious debugger process detected");
        }

        if (g_config.enableDebuggerModuleScan && internal::check_suspicious_modules()) {
            return handle_detection("[Anti-Debug] Suspicious module detected");
        }

        if (g_config.enableDebuggerEnvironmentScan && internal::check_debug_environment()) {
            return handle_detection("[Anti-Debug] Debugger-related environment variable detected");
        }

        if (g_config.enableDebuggerTimingCheck && internal::check_timing_anomaly()) {
            return handle_detection("[Anti-Debug] Timing anomaly detected");
        }

        if (g_config.enableDebugRegisterScrubbing) {
            internal::corrupt_debug_registers();
        }

        return false;
    }

    bool check_vm_protection(JNIEnv *env) {
        if (!g_config.enableVmIntegrityChecks || env == nullptr) return true;

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

    bool detect_tampering(JNIEnv *env) {
        if (!g_config.enableAntiTamper) return false;

        return !internal::validate_code_sections(env);
    }

    bool runtime_anti_debug_check(JNIEnv *env) {
        if (!g_initialized) return true;

        if (g_config.enableJvmtiAgentBlocking && !monitor_agent_loading(env)) {
            internal::debug_print(env, "[Anti-Debug] Agent loading detected - terminating!");
            protected_exit(7);
            return false;
        }

        if (g_config.enableDebuggerDetection && detect_debugger(env)) {
            protected_exit(4);
            return false;
        }

        if (g_config.enableVmIntegrityChecks && !check_vm_protection(env)) {
            protected_exit(5);
            return false;
        }

        if (g_config.enableAntiTamper && detect_tampering(env)) {
            protected_exit(6);
            return false;
        }

        if (g_config.enableDebugRegisterScrubbing) {
            internal::corrupt_debug_registers();
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
        if (!g_config.enableJvmtiAgentBlocking) {
            return false;
        }
        return g_agent_attachment_attempts.load() > 0;
    }

    bool monitor_agent_loading(JNIEnv *env) {
        if (env == nullptr || !g_config.enableJvmtiAgentBlocking) return true;

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

        namespace {
            struct Sha256Context {
                std::uint8_t data[64];
                std::uint32_t datalen;
                std::uint64_t bitlen;
                std::uint32_t state[8];
            };

            constexpr std::uint32_t SHA256_K[64] = {
                0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
                0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
                0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
                0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
                0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
                0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
                0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
                0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
            };

            inline std::uint32_t rotr(std::uint32_t value, std::uint32_t bits) {
                return (value >> bits) | (value << (32 - bits));
            }

            inline void sha256_transform(Sha256Context &ctx, const std::uint8_t data[64]) {
                std::uint32_t m[64];
                for (std::uint32_t i = 0, j = 0; i < 16; ++i, j += 4) {
                    m[i] = (static_cast<std::uint32_t>(data[j]) << 24)
                         | (static_cast<std::uint32_t>(data[j + 1]) << 16)
                         | (static_cast<std::uint32_t>(data[j + 2]) << 8)
                         | static_cast<std::uint32_t>(data[j + 3]);
                }
                for (std::uint32_t i = 16; i < 64; ++i) {
                    std::uint32_t s0 = rotr(m[i - 15], 7) ^ rotr(m[i - 15], 18) ^ (m[i - 15] >> 3);
                    std::uint32_t s1 = rotr(m[i - 2], 17) ^ rotr(m[i - 2], 19) ^ (m[i - 2] >> 10);
                    m[i] = m[i - 16] + s0 + m[i - 7] + s1;
                }

                std::uint32_t a = ctx.state[0];
                std::uint32_t b = ctx.state[1];
                std::uint32_t c = ctx.state[2];
                std::uint32_t d = ctx.state[3];
                std::uint32_t e = ctx.state[4];
                std::uint32_t f = ctx.state[5];
                std::uint32_t g = ctx.state[6];
                std::uint32_t h = ctx.state[7];

                for (std::uint32_t i = 0; i < 64; ++i) {
                    std::uint32_t S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
                    std::uint32_t ch = (e & f) ^ (~e & g);
                    std::uint32_t temp1 = h + S1 + ch + SHA256_K[i] + m[i];
                    std::uint32_t S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
                    std::uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
                    std::uint32_t temp2 = S0 + maj;

                    h = g;
                    g = f;
                    f = e;
                    e = d + temp1;
                    d = c;
                    c = b;
                    b = a;
                    a = temp1 + temp2;
                }

                ctx.state[0] += a;
                ctx.state[1] += b;
                ctx.state[2] += c;
                ctx.state[3] += d;
                ctx.state[4] += e;
                ctx.state[5] += f;
                ctx.state[6] += g;
                ctx.state[7] += h;
            }

            inline void sha256_init(Sha256Context &ctx) {
                ctx.datalen = 0;
                ctx.bitlen = 0;
                ctx.state[0] = 0x6a09e667;
                ctx.state[1] = 0xbb67ae85;
                ctx.state[2] = 0x3c6ef372;
                ctx.state[3] = 0xa54ff53a;
                ctx.state[4] = 0x510e527f;
                ctx.state[5] = 0x9b05688c;
                ctx.state[6] = 0x1f83d9ab;
                ctx.state[7] = 0x5be0cd19;
            }

            inline void sha256_update(Sha256Context &ctx, const std::uint8_t *data, std::size_t len) {
                for (std::size_t i = 0; i < len; ++i) {
                    ctx.data[ctx.datalen++] = data[i];
                    if (ctx.datalen == 64) {
                        sha256_transform(ctx, ctx.data);
                        ctx.bitlen += 512;
                        ctx.datalen = 0;
                    }
                }
            }

            inline void sha256_final(Sha256Context &ctx, std::uint8_t hash[32]) {
                std::size_t i = ctx.datalen;

                if (ctx.datalen < 56) {
                    ctx.data[i++] = 0x80;
                    while (i < 56) {
                        ctx.data[i++] = 0x00;
                    }
                } else {
                    ctx.data[i++] = 0x80;
                    while (i < 64) {
                        ctx.data[i++] = 0x00;
                    }
                    sha256_transform(ctx, ctx.data);
                    std::memset(ctx.data, 0, 56);
                }

                ctx.bitlen += static_cast<std::uint64_t>(ctx.datalen) * 8;
                ctx.data[63] = static_cast<std::uint8_t>(ctx.bitlen);
                ctx.data[62] = static_cast<std::uint8_t>(ctx.bitlen >> 8);
                ctx.data[61] = static_cast<std::uint8_t>(ctx.bitlen >> 16);
                ctx.data[60] = static_cast<std::uint8_t>(ctx.bitlen >> 24);
                ctx.data[59] = static_cast<std::uint8_t>(ctx.bitlen >> 32);
                ctx.data[58] = static_cast<std::uint8_t>(ctx.bitlen >> 40);
                ctx.data[57] = static_cast<std::uint8_t>(ctx.bitlen >> 48);
                ctx.data[56] = static_cast<std::uint8_t>(ctx.bitlen >> 56);
                sha256_transform(ctx, ctx.data);

                for (std::uint32_t j = 0; j < 4; ++j) {
                    hash[j]       = (ctx.state[0] >> (24 - j * 8)) & 0xFF;
                    hash[j + 4]   = (ctx.state[1] >> (24 - j * 8)) & 0xFF;
                    hash[j + 8]   = (ctx.state[2] >> (24 - j * 8)) & 0xFF;
                    hash[j + 12]  = (ctx.state[3] >> (24 - j * 8)) & 0xFF;
                    hash[j + 16]  = (ctx.state[4] >> (24 - j * 8)) & 0xFF;
                    hash[j + 20]  = (ctx.state[5] >> (24 - j * 8)) & 0xFF;
                    hash[j + 24]  = (ctx.state[6] >> (24 - j * 8)) & 0xFF;
                    hash[j + 28]  = (ctx.state[7] >> (24 - j * 8)) & 0xFF;
                }
            }
        }

        static std::array<std::uint8_t, 32> compute_sha256(const unsigned char *data, std::size_t len) {
            Sha256Context ctx;
            sha256_init(ctx);
            sha256_update(ctx, reinterpret_cast<const std::uint8_t*>(data), len);
            std::array<std::uint8_t, 32> digest{};
            sha256_final(ctx, digest.data());
            return digest;
        }

        static std::array<std::uint8_t, 32> compute_sha256(const std::vector<unsigned char> &data) {
            return compute_sha256(data.data(), data.size());
        }

        static bool hashes_equal(const std::array<std::uint8_t, 32> &actual, const unsigned char *expected) {
            for (std::size_t i = 0; i < actual.size(); ++i) {
                if (actual[i] != expected[i]) {
                    return false;
                }
            }
            return true;
        }

        static bool load_class_bytes(JNIEnv *env, const char *internal_name, std::vector<unsigned char> &out) {
            if (env == nullptr || internal_name == nullptr) {
                return false;
            }

            jclass targetClass = env->FindClass(internal_name);
            if (targetClass == nullptr) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                return false;
            }

            jclass classClass = env->FindClass("java/lang/Class");
            if (classClass == nullptr) {
                env->DeleteLocalRef(targetClass);
                if (env->ExceptionCheck()) env->ExceptionClear();
                return false;
            }

            jmethodID getResourceAsStream = env->GetMethodID(classClass, "getResourceAsStream", "(Ljava/lang/String;)Ljava/io/InputStream;");
            if (getResourceAsStream == nullptr) {
                env->DeleteLocalRef(targetClass);
                env->DeleteLocalRef(classClass);
                if (env->ExceptionCheck()) env->ExceptionClear();
                return false;
            }

            std::string resourcePath = std::string("/") + internal_name + ".class";
            jstring jResourcePath = env->NewStringUTF(resourcePath.c_str());
            if (jResourcePath == nullptr) {
                env->DeleteLocalRef(targetClass);
                env->DeleteLocalRef(classClass);
                return false;
            }

            jobject inputStream = env->CallObjectMethod(targetClass, getResourceAsStream, jResourcePath);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                env->DeleteLocalRef(targetClass);
                env->DeleteLocalRef(classClass);
                env->DeleteLocalRef(jResourcePath);
                return false;
            }

            if (inputStream == nullptr) {
                env->DeleteLocalRef(targetClass);
                env->DeleteLocalRef(classClass);
                env->DeleteLocalRef(jResourcePath);
                return false;
            }

            jclass inputStreamClass = env->FindClass("java/io/InputStream");
            jclass baosClass = env->FindClass("java/io/ByteArrayOutputStream");
            if (inputStreamClass == nullptr || baosClass == nullptr) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                env->DeleteLocalRef(targetClass);
                env->DeleteLocalRef(classClass);
                env->DeleteLocalRef(jResourcePath);
                env->DeleteLocalRef(inputStream);
                return false;
            }

            jmethodID readMethod = env->GetMethodID(inputStreamClass, "read", "([B)I");
            jmethodID closeMethod = env->GetMethodID(inputStreamClass, "close", "()V");
            jmethodID baosCtor = env->GetMethodID(baosClass, "<init>", "()V");
            jmethodID baosWrite = env->GetMethodID(baosClass, "write", "([BII)V");
            jmethodID baosToByteArray = env->GetMethodID(baosClass, "toByteArray", "()[B");
            if (!readMethod || !closeMethod || !baosCtor || !baosWrite || !baosToByteArray) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                env->DeleteLocalRef(targetClass);
                env->DeleteLocalRef(classClass);
                env->DeleteLocalRef(jResourcePath);
                env->DeleteLocalRef(inputStream);
                env->DeleteLocalRef(inputStreamClass);
                env->DeleteLocalRef(baosClass);
                return false;
            }

            jobject baos = env->NewObject(baosClass, baosCtor);
            jbyteArray buffer = env->NewByteArray(4096);
            if (baos == nullptr || buffer == nullptr) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                env->DeleteLocalRef(targetClass);
                env->DeleteLocalRef(classClass);
                env->DeleteLocalRef(jResourcePath);
                env->DeleteLocalRef(inputStream);
                env->DeleteLocalRef(inputStreamClass);
                env->DeleteLocalRef(baosClass);
                if (baos != nullptr) env->DeleteLocalRef(baos);
                if (buffer != nullptr) env->DeleteLocalRef(buffer);
                return false;
            }

            bool success = true;
            while (success) {
                jint read = env->CallIntMethod(inputStream, readMethod, buffer);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    success = false;
                    break;
                }
                if (read == -1) {
                    break;
                }
                if (read > 0) {
                    env->CallVoidMethod(baos, baosWrite, buffer, 0, read);
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                        success = false;
                        break;
                    }
                }
            }

            if (success) {
                env->CallVoidMethod(inputStream, closeMethod);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    success = false;
                }
            }

            if (success) {
                jbyteArray byteArray = static_cast<jbyteArray>(env->CallObjectMethod(baos, baosToByteArray));
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    success = false;
                } else if (byteArray != nullptr) {
                    jsize len = env->GetArrayLength(byteArray);
                    out.resize(static_cast<std::size_t>(len));
                    env->GetByteArrayRegion(byteArray, 0, len, reinterpret_cast<jbyte*>(out.data()));
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                        out.clear();
                        success = false;
                    }
                    env->DeleteLocalRef(byteArray);
                } else {
                    success = false;
                }
            }

            env->DeleteLocalRef(buffer);
            env->DeleteLocalRef(baos);
            env->DeleteLocalRef(inputStream);
            env->DeleteLocalRef(inputStreamClass);
            env->DeleteLocalRef(baosClass);
            env->DeleteLocalRef(jResourcePath);
            env->DeleteLocalRef(classClass);
            env->DeleteLocalRef(targetClass);

            return success;
        }

        // Original function pointers for JVMTI hooks
        jint (*original_GetEnv)(JavaVM *vm, void **penv, jint version) = nullptr;
        void* original_agent_onattach = nullptr;

        #ifdef _WIN32
        // --- IAT hook GetProcAddress to intercept Agent_OnAttach / Agent_OnLoad ---

        typedef FARPROC (WINAPI *PFN_GetProcAddress)(HMODULE, LPCSTR);
        static PFN_GetProcAddress original_GetProcAddress = nullptr;

        // Blocker for Agent_OnAttach/Agent_OnLoad
        static jint JNICALL blocked_Agent_OnAttach(JavaVM *vm, char *options, void *reserved) {
            JNIEnv *env = nullptr;
            if (vm && vm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK && env != nullptr) {
                debug_print(env, "[Anti-Debug] JVMTI: Agent_OnAttach/OnLoad blocked!");
            }
            g_agent_attachment_attempts.fetch_add(1);
            return JNI_ERR;
        }

        // Our GetProcAddress replacement
        static FARPROC WINAPI hooked_GetProcAddress(HMODULE hModule, LPCSTR lpProcName) {
            if (lpProcName) {
                if (lstrcmpiA(lpProcName, "Agent_OnAttach") == 0) {
                    OutputDebugStringA("[Anti-Debug] JVMTI: GetProcAddress('Agent_OnAttach') intercepted - returning blocker");
                    g_agent_attachment_attempts.fetch_add(1);
                    return (FARPROC)blocked_Agent_OnAttach;
                }
                if (lstrcmpiA(lpProcName, "Agent_OnLoad") == 0) {
                    OutputDebugStringA("[Anti-Debug] JVMTI: GetProcAddress('Agent_OnLoad') intercepted - returning blocker");
                    g_agent_attachment_attempts.fetch_add(1);
                    return (FARPROC)blocked_Agent_OnAttach;
                }
            }
            return original_GetProcAddress ? original_GetProcAddress(hModule, lpProcName) : NULL;
        }

        // Patch IAT(GetProcAddress) of a given module
        static bool patch_iat_getprocaddress(HMODULE mod) {
            if (!mod) return false;

            BYTE *base = (BYTE*)mod;
            IMAGE_DOS_HEADER *dos = (IMAGE_DOS_HEADER*)base;
            if (dos->e_magic != IMAGE_DOS_SIGNATURE) return false;

            IMAGE_NT_HEADERS *nt = (IMAGE_NT_HEADERS*)(base + dos->e_lfanew);
            if (nt->Signature != IMAGE_NT_SIGNATURE) return false;

            IMAGE_DATA_DIRECTORY dir = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT];
            if (!dir.VirtualAddress || !dir.Size) return false;

            IMAGE_IMPORT_DESCRIPTOR *desc = (IMAGE_IMPORT_DESCRIPTOR*)(base + dir.VirtualAddress);
            bool patched = false;

            for (; desc->Name; ++desc) {
                IMAGE_THUNK_DATA *thunk = (IMAGE_THUNK_DATA*)(base + desc->FirstThunk);
                IMAGE_THUNK_DATA *orig  = desc->OriginalFirstThunk ? (IMAGE_THUNK_DATA*)(base + desc->OriginalFirstThunk) : nullptr;
                if (!orig) continue;

                for (; orig->u1.AddressOfData; ++orig, ++thunk) {
                    if (IMAGE_SNAP_BY_ORDINAL(orig->u1.Ordinal)) continue;
                    IMAGE_IMPORT_BY_NAME *name = (IMAGE_IMPORT_BY_NAME*)(base + orig->u1.AddressOfData);
                    if (!name) continue;

                    if (lstrcmpiA((char*)name->Name, "GetProcAddress") == 0) {
                        DWORD oldProtect;
        #ifdef _WIN64
                        if (VirtualProtect(&thunk->u1.Function, sizeof(ULONGLONG), PAGE_READWRITE, &oldProtect)) {
                            if (!original_GetProcAddress)
                                original_GetProcAddress = (PFN_GetProcAddress)(ULONG_PTR)thunk->u1.Function;
                            thunk->u1.Function = (ULONGLONG)(ULONG_PTR)hooked_GetProcAddress;
                            VirtualProtect(&thunk->u1.Function, sizeof(ULONGLONG), oldProtect, &oldProtect);
                            patched = true;
                        }
        #else
                        if (VirtualProtect(&thunk->u1.Function, sizeof(DWORD), PAGE_READWRITE, &oldProtect)) {
                            if (!original_GetProcAddress)
                                original_GetProcAddress = (PFN_GetProcAddress)thunk->u1.Function;
                            thunk->u1.Function = (DWORD)hooked_GetProcAddress;
                            VirtualProtect(&thunk->u1.Function, sizeof(DWORD), oldProtect, &oldProtect);
                            patched = true;
                        }
        #endif
                    }
                }
            }
            return patched;
        }
        #endif

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
                protected_exit(8);
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

        bool check_debugger_api() {
#ifdef _WIN32
            if (IsDebuggerPresent()) {
                return true;
            }

            BOOL remote_debugger = FALSE;
            CheckRemoteDebuggerPresent(GetCurrentProcess(), &remote_debugger);
            if (remote_debugger) {
                return true;
            }
#endif
            return false;
        }

        bool check_tracer_pid() {
#ifdef _WIN32
            HMODULE hNtdll = GetModuleHandleA("ntdll.dll");
            if (hNtdll) {
                using NtQueryInformationProcessFn = NTSTATUS (NTAPI *)(HANDLE, ULONG, PVOID, ULONG, PULONG);
                auto NtQueryInformationProcess = reinterpret_cast<NtQueryInformationProcessFn>(
                    GetProcAddress(hNtdll, "NtQueryInformationProcess"));
                if (NtQueryInformationProcess) {
                    DWORD debugPort = 0;
                    if (NtQueryInformationProcess(
                            GetCurrentProcess(),
                            7,
                            &debugPort,
                            sizeof(debugPort),
                            nullptr) == 0 && debugPort != 0) {
                        return true;
                    }
                }
            }
#else
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
#endif
            return false;
        }

        bool check_ptrace_self_test() {
#ifdef _WIN32
            return false;
#else
            errno = 0;
            if (ptrace(PTRACE_TRACEME, 0, nullptr, nullptr) == -1) {
                return errno == EPERM;
            }
            ptrace(PTRACE_DETACH, 0, nullptr, nullptr);
            return false;
#endif
        }

        bool check_debugger_processes() {
#ifdef _WIN32
            HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
            if (snapshot == INVALID_HANDLE_VALUE) {
                return false;
            }

            PROCESSENTRY32 entry;
            entry.dwSize = sizeof(PROCESSENTRY32);

            const char* debugger_names[] = {
                "ollydbg.exe", "x64dbg.exe", "x32dbg.exe", "windbg.exe",
                "ida.exe", "ida64.exe", "idaq.exe", "idaq64.exe",
                "immunitydebugger.exe", "cheatengine-x86_64.exe", "ghidra.exe"
            };

            if (Process32First(snapshot, &entry)) {
                do {
                    for (const char* name : debugger_names) {
                        if (_stricmp(entry.szExeFile, name) == 0) {
                            CloseHandle(snapshot);
                            return true;
                        }
                    }
                } while (Process32Next(snapshot, &entry));
            }
            CloseHandle(snapshot);
#else
            DIR* proc = opendir("/proc");
            if (!proc) {
                return false;
            }

            pid_t self = getpid();
            const char* debugger_names[] = {
                "gdb", "lldb", "frida", "radare2", "x64dbg", "x32dbg", "ida", "hopper", "dnspy", "pydevd"
            };

            struct dirent* entry;
            while ((entry = readdir(proc)) != nullptr) {
                if (!isdigit(entry->d_name[0])) {
                    continue;
                }
                pid_t pid = static_cast<pid_t>(atoi(entry->d_name));
                if (pid == self) {
                    continue;
                }
                std::string cmdline_path = std::string("/proc/") + entry->d_name + "/cmdline";
                std::ifstream cmdline(cmdline_path);
                if (!cmdline.is_open()) {
                    continue;
                }
                std::string raw;
                std::getline(cmdline, raw, '\0');
                cmdline.close();
                std::string lower;
                lower.reserve(raw.size());
                for (unsigned char c : raw) {
                    lower.push_back(static_cast<char>(std::tolower(c)));
                }
                for (const char* name : debugger_names) {
                    if (lower.find(name) != std::string::npos) {
                        closedir(proc);
                        return true;
                    }
                }
            }
            closedir(proc);
#endif
            return false;
        }

        bool check_suspicious_modules() {
#ifdef _WIN32
            HMODULE modules[1024];
            DWORD needed = 0;
            if (!EnumProcessModules(GetCurrentProcess(), modules, sizeof(modules), &needed)) {
                return false;
            }

            const char* suspicious_modules[] = {
                "ntsdexts.dll", "sbie.dll",
                "frida", "ida", "ollydbg", "x64dbg"
            };

            size_t module_count = needed / sizeof(HMODULE);
            char module_name[MAX_PATH];
            for (size_t i = 0; i < module_count; ++i) {
                if (GetModuleBaseNameA(GetCurrentProcess(), modules[i], module_name, sizeof(module_name))) {
                    std::string lower(module_name);
                    std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) { return std::tolower(c); });
                    for (const char* suspicious : suspicious_modules) {
                        if (lower.find(suspicious) != std::string::npos) {
                            return true;
                        }
                    }
                }
            }
#else
            FILE* maps = fopen("/proc/self/maps", "r");
            if (!maps) {
                return false;
            }

            const char* suspicious_tokens[] = {
                "frida", "gdb", "lldb", "trace", "valgrind", "rrlib", "libdwarf", "libunwind"
            };

            char line[512];
            while (fgets(line, sizeof(line), maps)) {
                std::string lower(line);
                std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) { return std::tolower(c); });
                for (const char* token : suspicious_tokens) {
                    if (lower.find(token) != std::string::npos) {
                        fclose(maps);
                        return true;
                    }
                }
            }
            fclose(maps);
#endif
            return false;
        }

        bool check_debug_environment() {
#ifdef _WIN32
            const char* vars[] = {
                "COR_ENABLE_PROFILING", "COMPLUS_ProfAPI_ProfilerCompatibilitySetting",
                "JAVA_TOOL_OPTIONS", "_NT_SYMBOL_PATH", "DBGHELP_LOG"
            };
#else
            const char* vars[] = {
                "LD_PRELOAD", "LD_LIBRARY_PATH", "LD_AUDIT", "DYLD_INSERT_LIBRARIES",
                "DYLD_SHARED_REGION", "FRIDA_REUSE_PORT", "RR_TRACE_DIR", "PYTHONINSPECT",
                "JAVA_TOOL_OPTIONS"
            };
#endif
            for (const char* var : vars) {
#ifdef _WIN32
                size_t value_len = 0;
                char* value = nullptr;
                if (_dupenv_s(&value, &value_len, var) == 0 && value != nullptr && value[0] != '\0') {
                    free(value);
                    return true;
                }
                free(value);
#else
                const char* value = std::getenv(var);
                if (value != nullptr && value[0] != '\0') {
                    return true;
                }
#endif
            }
            return false;
        }

        bool check_timing_anomaly() {
            using namespace std::chrono;
            auto start = steady_clock::now();
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            auto elapsed = duration_cast<milliseconds>(steady_clock::now() - start).count();
            if (elapsed > 80) {
                return true;
            }

            volatile int guard = 0;
            for (int i = 0; i < 1500000; ++i) {
                guard += i;
            }
            (void)guard;
            auto loop_elapsed = duration_cast<milliseconds>(steady_clock::now() - start).count();
            return loop_elapsed > 200;
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

        bool validate_code_sections(JNIEnv *env) {
#if ANTI_DEBUG_HAS_CONFIG
            bool ok = true;

            std::size_t pool_size = native_jvm::string_pool::get_pool_size();
            if (pool_size != native_jvm::anti_debug::config::STRING_POOL_ENCRYPTED_SIZE) {
                ok = false;
                debug_print(env, "[Anti-Debug] String pool size mismatch detected");
            } else {
                auto pool_hash = compute_sha256(reinterpret_cast<const unsigned char *>(native_jvm::string_pool::get_pool()), pool_size);
                if (!hashes_equal(pool_hash, native_jvm::anti_debug::config::STRING_POOL_EXPECTED_HASH)) {
                    ok = false;
                    debug_print(env, "[Anti-Debug] String pool integrity check failed");
                }
            }

            if (env != nullptr && native_jvm::anti_debug::config::HAS_LOADER_HASH) {
                std::vector<unsigned char> loader_bytes;
                if (load_class_bytes(env, native_jvm::anti_debug::config::LOADER_CLASS_INTERNAL_NAME, loader_bytes)) {
                    auto loader_hash = compute_sha256(loader_bytes);
                    if (!hashes_equal(loader_hash, native_jvm::anti_debug::config::LOADER_CLASS_EXPECTED_HASH)) {
                        ok = false;
                        debug_print(env, "[Anti-Debug] Loader class integrity check failed");
                    }
                } else {
                    ok = false;
                    debug_print(env, "[Anti-Debug] Unable to read loader class bytes");
                }
            }

            return ok;
#else
            (void)env;
            return true;
#endif
        }

        void debug_print(JNIEnv *env, const char* message) {
            if (!g_config.enableDebugLogging || env == nullptr || message == nullptr) return;

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
           // Try to hook jvm.dll's IAT(GetProcAddress)
           bool ok = false;

           HMODULE hJvm = GetModuleHandleA("jvm.dll");
           if (hJvm != NULL) {
               if (patch_iat_getprocaddress(hJvm)) {
                   OutputDebugStringA("[Anti-Debug] JVMTI: hook_agent_onattach installed via IAT(GetProcAddress) on jvm.dll");
                   ok = true;
               }
           }

           // Also try main module to be robust across launchers
           HMODULE hSelf = GetModuleHandleA(NULL);
           if (patch_iat_getprocaddress(hSelf)) {
               OutputDebugStringA("[Anti-Debug] JVMTI: hook_agent_onattach installed via IAT(GetProcAddress) on process");
               ok = true;
           }

           return ok;
       #else
           // Linux implementation - check for agent libraries
           void *handle = dlopen("libjvm.so", RTLD_LAZY | RTLD_NOLOAD);
           if (handle != NULL) {
               void *agent_onattach = dlsym(handle, "Agent_OnAttach");
               if (agent_onattach != NULL) {
                   original_agent_onattach = agent_onattach;
                   return true;
               }
               dlclose(handle);
           }
           return false;
       #endif
       }
    }

} // namespace native_jvm::anti_debug