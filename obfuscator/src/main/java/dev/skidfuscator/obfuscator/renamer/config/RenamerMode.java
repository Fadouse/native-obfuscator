package dev.skidfuscator.obfuscator.renamer.config;

public enum RenamerMode {
    ALPHABETICAL,
    NUMERIC,
    CUSTOM;

    public static RenamerMode fromString(String value) {
        if (value == null) {
            return ALPHABETICAL;
        }
        for (RenamerMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return ALPHABETICAL;
    }
}
