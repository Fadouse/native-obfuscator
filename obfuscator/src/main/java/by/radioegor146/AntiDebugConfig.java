package by.radioegor146;

/**
 * Configuration class for anti-debugging protection mechanisms.
 * Contains settings for various techniques to detect and counter debugging attempts.
 */
public class AntiDebugConfig {

    private final boolean gHotSpotVMStructsNullification;
    private final boolean debuggerDetection;
    private final boolean vmProtectionEnabled;
    private final boolean antiTamperEnabled;

    public AntiDebugConfig(boolean gHotSpotVMStructsNullification,
                          boolean debuggerDetection,
                          boolean vmProtectionEnabled,
                          boolean antiTamperEnabled) {
        this.gHotSpotVMStructsNullification = gHotSpotVMStructsNullification;
        this.debuggerDetection = debuggerDetection;
        this.vmProtectionEnabled = vmProtectionEnabled;
        this.antiTamperEnabled = antiTamperEnabled;
    }

    /**
     * @return true if gHotSpotVMStructs nullification should be enabled
     */
    public boolean isGHotSpotVMStructsNullificationEnabled() {
        return gHotSpotVMStructsNullification;
    }

    /**
     * @return true if debugger detection routines should be enabled
     */
    public boolean isDebuggerDetectionEnabled() {
        return debuggerDetection;
    }

    /**
     * @return true if VM-level protection should be enabled
     */
    public boolean isVmProtectionEnabled() {
        return vmProtectionEnabled;
    }

    /**
     * @return true if anti-tampering checks should be enabled
     */
    public boolean isAntiTamperEnabled() {
        return antiTamperEnabled;
    }

    /**
     * Creates a default configuration with all anti-debug features disabled.
     */
    public static AntiDebugConfig createDefault() {
        return new AntiDebugConfig(false, false, false, false);
    }

    /**
     * Creates a configuration with maximum anti-debug protection.
     */
    public static AntiDebugConfig createMaxProtection() {
        return new AntiDebugConfig(true, true, true, true);
    }

    /**
     * Creates a configuration with basic anti-debug protection.
     */
    public static AntiDebugConfig createBasicProtection() {
        return new AntiDebugConfig(true, true, false, false);
    }

    /**
     * Validates the configuration and prints warnings for potentially problematic combinations.
     */
    public void validateAndWarn() {
        if (gHotSpotVMStructsNullification && !vmProtectionEnabled) {
            System.out.println("Warning: gHotSpotVMStructs nullification enabled without VM protection.");
            System.out.println("Consider enabling VM protection for enhanced security.");
        }

        if (antiTamperEnabled && !debuggerDetection) {
            System.out.println("Warning: Anti-tamper enabled without debugger detection.");
            System.out.println("Debugger detection provides better protection against tampering.");
        }
    }

    /**
     * Checks if any anti-debug feature is enabled.
     * @return true if at least one anti-debug feature is enabled
     */
    public boolean isAnyEnabled() {
        return gHotSpotVMStructsNullification || debuggerDetection || vmProtectionEnabled || antiTamperEnabled;
    }

    @Override
    public String toString() {
        return String.format("AntiDebugConfig{gHotSpotVMStructsNullification=%s, debuggerDetection=%s, vmProtection=%s, antiTamper=%s}",
                gHotSpotVMStructsNullification, debuggerDetection, vmProtectionEnabled, antiTamperEnabled);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        AntiDebugConfig that = (AntiDebugConfig) obj;
        return gHotSpotVMStructsNullification == that.gHotSpotVMStructsNullification &&
               debuggerDetection == that.debuggerDetection &&
               vmProtectionEnabled == that.vmProtectionEnabled &&
               antiTamperEnabled == that.antiTamperEnabled;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(gHotSpotVMStructsNullification, debuggerDetection, vmProtectionEnabled, antiTamperEnabled);
    }
}