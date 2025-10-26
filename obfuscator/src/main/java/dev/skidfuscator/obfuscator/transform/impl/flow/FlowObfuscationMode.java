package dev.skidfuscator.obfuscator.transform.impl.flow;

public enum FlowObfuscationMode {
    PERFORMANCE,
    BALANCED,
    AGGRESSIVE;

    public static FlowObfuscationMode fromConfigValue(String raw) {
        if (raw == null || raw.isEmpty()) {
            return BALANCED;
        }

        try {
            return FlowObfuscationMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return BALANCED;
        }
    }
}
