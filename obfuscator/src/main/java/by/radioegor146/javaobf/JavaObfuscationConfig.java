package by.radioegor146.javaobf;

import java.util.Collections;
import java.util.List;

/**
 * Minimal compatibility container for UI components referencing the legacy Java obfuscation settings.
 */
public class JavaObfuscationConfig {
    public enum Strength {
        LOW("Low (light transformations)"),
        MEDIUM("Medium (balanced protection)"),
        HIGH("High (aggressive transformations)");

        private final String description;

        Strength(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    private final boolean enabled;
    private final Strength strength;
    private final List<String> blackList;
    private final List<String> whiteList;

    public JavaObfuscationConfig(boolean enabled, Strength strength) {
        this(enabled, strength, Collections.emptyList(), Collections.emptyList());
    }

    public JavaObfuscationConfig(boolean enabled, Strength strength, List<String> blackList, List<String> whiteList) {
        this.enabled = enabled;
        this.strength = strength;
        this.blackList = blackList == null ? Collections.emptyList() : blackList;
        this.whiteList = whiteList == null ? Collections.emptyList() : whiteList;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Strength getStrength() {
        return strength;
    }

    public List<String> getBlackList() {
        return blackList;
    }

    public List<String> getWhiteList() {
        return whiteList;
    }
}
