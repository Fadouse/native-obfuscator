package by.radioegor146;

import java.util.Objects;

/**
 * Configuration class for anti-debugging protection mechanisms.
 * Provides fine-grained switches for individual detection and mitigation strategies.
 */
public class AntiDebugConfig {

    private final boolean gHotSpotVMStructsNullification;
    private final boolean debuggerDetection;
    private final boolean debuggerApiChecks;
    private final boolean debuggerTracerCheck;
    private final boolean debuggerPtraceCheck;
    private final boolean debuggerProcessScan;
    private final boolean debuggerModuleScan;
    private final boolean debuggerEnvironmentScan;
    private final boolean debuggerTimingCheck;
    private final boolean vmProtectionEnabled;
    private final boolean jvmtiAgentBlocking;
    private final boolean antiTamperEnabled;
    private final boolean debugRegisterScrubbing;
    private final boolean debugLoggingEnabled;

    private AntiDebugConfig(Builder builder) {
        this.gHotSpotVMStructsNullification = builder.gHotSpotVMStructsNullification;
        this.debuggerDetection = builder.debuggerDetection;
        this.debuggerApiChecks = builder.debuggerApiChecks;
        this.debuggerTracerCheck = builder.debuggerTracerCheck;
        this.debuggerPtraceCheck = builder.debuggerPtraceCheck;
        this.debuggerProcessScan = builder.debuggerProcessScan;
        this.debuggerModuleScan = builder.debuggerModuleScan;
        this.debuggerEnvironmentScan = builder.debuggerEnvironmentScan;
        this.debuggerTimingCheck = builder.debuggerTimingCheck;
        this.vmProtectionEnabled = builder.vmProtectionEnabled;
        this.jvmtiAgentBlocking = builder.jvmtiAgentBlocking;
        this.antiTamperEnabled = builder.antiTamperEnabled;
        this.debugRegisterScrubbing = builder.debugRegisterScrubbing;
        this.debugLoggingEnabled = builder.debugLoggingEnabled;
    }

    public AntiDebugConfig(boolean gHotSpotVMStructsNullification,
                           boolean debuggerDetection,
                           boolean vmProtectionEnabled,
                           boolean antiTamperEnabled) {
        this(new Builder()
                .setGHotSpotVMStructsNullification(gHotSpotVMStructsNullification)
                .setDebuggerDetection(debuggerDetection)
                .setVmProtectionEnabled(vmProtectionEnabled)
                .setAntiTamperEnabled(antiTamperEnabled)
                .finalizeDefaults());
    }

    public boolean isGHotSpotVMStructsNullificationEnabled() {
        return gHotSpotVMStructsNullification;
    }

    public boolean isDebuggerDetectionEnabled() {
        return debuggerDetection;
    }

    public boolean isDebuggerApiChecksEnabled() {
        return debuggerApiChecks;
    }

    public boolean isDebuggerTracerCheckEnabled() {
        return debuggerTracerCheck;
    }

    public boolean isDebuggerPtraceCheckEnabled() {
        return debuggerPtraceCheck;
    }

    public boolean isDebuggerProcessScanEnabled() {
        return debuggerProcessScan;
    }

    public boolean isDebuggerModuleScanEnabled() {
        return debuggerModuleScan;
    }

    public boolean isDebuggerEnvironmentScanEnabled() {
        return debuggerEnvironmentScan;
    }

    public boolean isDebuggerTimingCheckEnabled() {
        return debuggerTimingCheck;
    }

    public boolean isVmProtectionEnabled() {
        return vmProtectionEnabled;
    }

    public boolean isJvmtiAgentBlockingEnabled() {
        return jvmtiAgentBlocking;
    }

    public boolean isAntiTamperEnabled() {
        return antiTamperEnabled;
    }

    public boolean isDebugRegisterScrubbingEnabled() {
        return debugRegisterScrubbing;
    }

    public boolean isDebugLoggingEnabled() {
        return debugLoggingEnabled;
    }

    public boolean isAnyEnabled() {
        return gHotSpotVMStructsNullification || debuggerDetection || vmProtectionEnabled || antiTamperEnabled
                || jvmtiAgentBlocking || debugRegisterScrubbing || debugLoggingEnabled;
    }

    public void validateAndWarn() {
        if (gHotSpotVMStructsNullification && !vmProtectionEnabled) {
            System.out.println("Warning: gHotSpotVMStructs nullification enabled without VM protection.");
            System.out.println("Consider enabling VM protection for enhanced security.");
        }

        if (antiTamperEnabled && !debuggerDetection) {
            System.out.println("Warning: Anti-tamper enabled without debugger detection.");
            System.out.println("Debugger detection provides better protection against tampering.");
        }

        if (debuggerDetection
                && !debuggerApiChecks
                && !debuggerTracerCheck
                && !debuggerPtraceCheck
                && !debuggerProcessScan
                && !debuggerModuleScan
                && !debuggerEnvironmentScan
                && !debuggerTimingCheck) {
            System.out.println("Warning: Debugger detection is enabled but all detection checks are disabled.");
            System.out.println("Enable at least one detection routine or disable debugger detection.");
        }

        if (debugLoggingEnabled
                && !gHotSpotVMStructsNullification
                && !debuggerDetection
                && !vmProtectionEnabled
                && !antiTamperEnabled
                && !jvmtiAgentBlocking
                && !debugRegisterScrubbing) {
            System.out.println("Info: Debug logging is enabled without other anti-debug features.");
            System.out.println("Logs will be produced but no mitigations are active.");
        }
    }

    public static AntiDebugConfig createDefault() {
        return new Builder().build();
    }

    public static AntiDebugConfig createMaxProtection() {
        return new Builder()
                .setGHotSpotVMStructsNullification(true)
                .setDebuggerDetection(true)
                .setVmProtectionEnabled(true)
                .setAntiTamperEnabled(true)
                .setDebugLoggingEnabled(false)
                .build();
    }

    public static AntiDebugConfig createBasicProtection() {
        return new Builder()
                .setGHotSpotVMStructsNullification(true)
                .setDebuggerDetection(true)
                .setDebuggerTimingCheck(false)
                .setVmProtectionEnabled(false)
                .setAntiTamperEnabled(false)
                .build();
    }

    @Override
    public String toString() {
        return "AntiDebugConfig{" +
                "gHotSpotVMStructsNullification=" + gHotSpotVMStructsNullification +
                ", debuggerDetection=" + debuggerDetection +
                ", debuggerApiChecks=" + debuggerApiChecks +
                ", debuggerTracerCheck=" + debuggerTracerCheck +
                ", debuggerPtraceCheck=" + debuggerPtraceCheck +
                ", debuggerProcessScan=" + debuggerProcessScan +
                ", debuggerModuleScan=" + debuggerModuleScan +
                ", debuggerEnvironmentScan=" + debuggerEnvironmentScan +
                ", debuggerTimingCheck=" + debuggerTimingCheck +
                ", vmProtectionEnabled=" + vmProtectionEnabled +
                ", jvmtiAgentBlocking=" + jvmtiAgentBlocking +
                ", antiTamperEnabled=" + antiTamperEnabled +
                ", debugRegisterScrubbing=" + debugRegisterScrubbing +
                ", debugLoggingEnabled=" + debugLoggingEnabled +
                '}';
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        AntiDebugConfig that = (AntiDebugConfig) obj;
        return gHotSpotVMStructsNullification == that.gHotSpotVMStructsNullification
                && debuggerDetection == that.debuggerDetection
                && debuggerApiChecks == that.debuggerApiChecks
                && debuggerTracerCheck == that.debuggerTracerCheck
                && debuggerPtraceCheck == that.debuggerPtraceCheck
                && debuggerProcessScan == that.debuggerProcessScan
                && debuggerModuleScan == that.debuggerModuleScan
                && debuggerEnvironmentScan == that.debuggerEnvironmentScan
                && debuggerTimingCheck == that.debuggerTimingCheck
                && vmProtectionEnabled == that.vmProtectionEnabled
                && jvmtiAgentBlocking == that.jvmtiAgentBlocking
                && antiTamperEnabled == that.antiTamperEnabled
                && debugRegisterScrubbing == that.debugRegisterScrubbing
                && debugLoggingEnabled == that.debugLoggingEnabled;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                gHotSpotVMStructsNullification,
                debuggerDetection,
                debuggerApiChecks,
                debuggerTracerCheck,
                debuggerPtraceCheck,
                debuggerProcessScan,
                debuggerModuleScan,
                debuggerEnvironmentScan,
                debuggerTimingCheck,
                vmProtectionEnabled,
                jvmtiAgentBlocking,
                antiTamperEnabled,
                debugRegisterScrubbing,
                debugLoggingEnabled
        );
    }

    public Builder toBuilder() {
        return new Builder()
                .setGHotSpotVMStructsNullification(gHotSpotVMStructsNullification)
                .setDebuggerDetection(debuggerDetection)
                .setDebuggerApiChecks(debuggerApiChecks)
                .setDebuggerTracerCheck(debuggerTracerCheck)
                .setDebuggerPtraceCheck(debuggerPtraceCheck)
                .setDebuggerProcessScan(debuggerProcessScan)
                .setDebuggerModuleScan(debuggerModuleScan)
                .setDebuggerEnvironmentScan(debuggerEnvironmentScan)
                .setDebuggerTimingCheck(debuggerTimingCheck)
                .setVmProtectionEnabled(vmProtectionEnabled)
                .setJvmtiAgentBlocking(jvmtiAgentBlocking)
                .setAntiTamperEnabled(antiTamperEnabled)
                .setDebugRegisterScrubbing(debugRegisterScrubbing)
                .setDebugLoggingEnabled(debugLoggingEnabled);
    }

    public static class Builder {
        private boolean gHotSpotVMStructsNullification;
        private boolean debuggerDetection;
        private boolean debuggerApiChecks;
        private boolean debuggerTracerCheck;
        private boolean debuggerPtraceCheck;
        private boolean debuggerProcessScan;
        private boolean debuggerModuleScan;
        private boolean debuggerEnvironmentScan;
        private boolean debuggerTimingCheck;
        private boolean vmProtectionEnabled;
        private boolean jvmtiAgentBlocking;
        private boolean antiTamperEnabled;
        private boolean debugRegisterScrubbing;
        private boolean debugLoggingEnabled;

        private boolean debuggerApiChecksConfigured;
        private boolean debuggerTracerCheckConfigured;
        private boolean debuggerPtraceCheckConfigured;
        private boolean debuggerProcessScanConfigured;
        private boolean debuggerModuleScanConfigured;
        private boolean debuggerEnvironmentScanConfigured;
        private boolean debuggerTimingCheckConfigured;
        private boolean jvmtiAgentBlockingConfigured;
        private boolean debugRegisterScrubbingConfigured;

        public Builder setGHotSpotVMStructsNullification(boolean value) {
            this.gHotSpotVMStructsNullification = value;
            return this;
        }

        public Builder setDebuggerDetection(boolean value) {
            this.debuggerDetection = value;
            return this;
        }

        public Builder setDebuggerApiChecks(boolean value) {
            this.debuggerApiChecks = value;
            this.debuggerApiChecksConfigured = true;
            return this;
        }

        public Builder setDebuggerTracerCheck(boolean value) {
            this.debuggerTracerCheck = value;
            this.debuggerTracerCheckConfigured = true;
            return this;
        }

        public Builder setDebuggerPtraceCheck(boolean value) {
            this.debuggerPtraceCheck = value;
            this.debuggerPtraceCheckConfigured = true;
            return this;
        }

        public Builder setDebuggerProcessScan(boolean value) {
            this.debuggerProcessScan = value;
            this.debuggerProcessScanConfigured = true;
            return this;
        }

        public Builder setDebuggerModuleScan(boolean value) {
            this.debuggerModuleScan = value;
            this.debuggerModuleScanConfigured = true;
            return this;
        }

        public Builder setDebuggerEnvironmentScan(boolean value) {
            this.debuggerEnvironmentScan = value;
            this.debuggerEnvironmentScanConfigured = true;
            return this;
        }

        public Builder setDebuggerTimingCheck(boolean value) {
            this.debuggerTimingCheck = value;
            this.debuggerTimingCheckConfigured = true;
            return this;
        }

        public Builder setVmProtectionEnabled(boolean value) {
            this.vmProtectionEnabled = value;
            return this;
        }

        public Builder setJvmtiAgentBlocking(boolean value) {
            this.jvmtiAgentBlocking = value;
            this.jvmtiAgentBlockingConfigured = true;
            return this;
        }

        public Builder setAntiTamperEnabled(boolean value) {
            this.antiTamperEnabled = value;
            return this;
        }

        public Builder setDebugRegisterScrubbing(boolean value) {
            this.debugRegisterScrubbing = value;
            this.debugRegisterScrubbingConfigured = true;
            return this;
        }

        public Builder setDebugLoggingEnabled(boolean value) {
            this.debugLoggingEnabled = value;
            return this;
        }

        private Builder finalizeDefaults() {
            if (debuggerDetection) {
                if (!debuggerApiChecksConfigured) debuggerApiChecks = true;
                if (!debuggerTracerCheckConfigured) debuggerTracerCheck = true;
                if (!debuggerPtraceCheckConfigured) debuggerPtraceCheck = true;
                if (!debuggerProcessScanConfigured) debuggerProcessScan = true;
                if (!debuggerModuleScanConfigured) debuggerModuleScan = true;
                if (!debuggerEnvironmentScanConfigured) debuggerEnvironmentScan = true;
                if (!debuggerTimingCheckConfigured) debuggerTimingCheck = true;
                if (!debugRegisterScrubbingConfigured) debugRegisterScrubbing = true;
            }

            if (!jvmtiAgentBlockingConfigured) {
                jvmtiAgentBlocking = vmProtectionEnabled;
            }

            boolean detectionActive = debuggerDetection
                    || debuggerApiChecks
                    || debuggerTracerCheck
                    || debuggerPtraceCheck
                    || debuggerProcessScan
                    || debuggerModuleScan
                    || debuggerEnvironmentScan
                    || debuggerTimingCheck;
            debuggerDetection = detectionActive;

            return this;
        }

        public AntiDebugConfig build() {
            return new AntiDebugConfig(finalizeDefaults());
        }
    }
}
