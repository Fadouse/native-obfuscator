package by.radioegor146;

/**
 * Configuration class for various protection mechanisms in the native obfuscator.
 * This class holds settings for virtualization, control flow flattening, and other obfuscation techniques.
 */
public class ProtectionConfig {

    private final boolean virtualizationEnabled;
    private final boolean jitEnabled;
    private final boolean controlFlowFlatteningEnabled;
    private final boolean stringObfuscationEnabled;
    private final boolean constantObfuscationEnabled;

    public ProtectionConfig(boolean virtualizationEnabled, boolean jitEnabled, boolean controlFlowFlatteningEnabled,
                            boolean stringObfuscationEnabled, boolean constantObfuscationEnabled) {
        this.virtualizationEnabled = virtualizationEnabled;
        this.jitEnabled = jitEnabled;
        this.controlFlowFlatteningEnabled = controlFlowFlatteningEnabled;
        this.stringObfuscationEnabled = stringObfuscationEnabled;
        this.constantObfuscationEnabled = constantObfuscationEnabled;
    }

    /**
     * @return true if VM-based code virtualization should be enabled
     */
    public boolean isVirtualizationEnabled() {
        return virtualizationEnabled;
    }

    /**
     * @return true if JIT compilation should be enabled for virtualized methods
     */
    public boolean isJitEnabled() {
        return jitEnabled;
    }

    /**
     * @return true if control flow flattening should be applied to native methods
     */
    public boolean isControlFlowFlatteningEnabled() {
        return controlFlowFlatteningEnabled;
    }

    /**
     * @return true if strings stored in the native string pool should be encrypted
     */
    public boolean isStringObfuscationEnabled() {
        return stringObfuscationEnabled;
    }

    /**
     * @return true if LDC primitive constants should be encoded via runtime decoding helpers
     */
    public boolean isConstantObfuscationEnabled() {
        return constantObfuscationEnabled;
    }

    /**
     * Creates a default configuration with all protection mechanisms disabled.
     */
    public static ProtectionConfig createDefault() {
        return new ProtectionConfig(false, false, false, true, true);
    }

    /**
     * Creates a configuration for maximum protection.
     */
    public static ProtectionConfig createMaxProtection() {
        return new ProtectionConfig(true, false, true, true, true); // JIT disabled by default for security
    }

    /**
     * Validates the configuration and prints warnings for potentially problematic combinations.
     */
    public void validateAndWarn() {
        if (controlFlowFlatteningEnabled && !virtualizationEnabled) {
            System.out.println("Warning: Control flow flattening is enabled without VM virtualization.");
            System.out.println("This configuration should work but may provide less protection.");
        }

        if (jitEnabled && !virtualizationEnabled) {
            System.out.println("Warning: JIT is enabled without VM virtualization. JIT has no effect in this configuration.");
        }

        if (!stringObfuscationEnabled) {
            System.out.println("Info: Native string obfuscation is disabled. String literals will be stored in plain text inside the native library.");
        }

        if (!constantObfuscationEnabled) {
            System.out.println("Info: Numeric constant obfuscation is disabled. LDC primitives will be emitted without runtime decoding.");
        }
    }

    @Override
    public String toString() {
        return String.format("ProtectionConfig{virtualization=%s, jit=%s, controlFlowFlattening=%s, stringObf=%s, constObf=%s}",
                virtualizationEnabled, jitEnabled, controlFlowFlatteningEnabled, stringObfuscationEnabled, constantObfuscationEnabled);
    }
}
