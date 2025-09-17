package by.radioegor146.javaobf;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class JavaObfuscationConfig {

    public enum Strength {
        LOW("Basic state machine with direct state transitions"),
        MEDIUM("Enhanced state machine with arithmetic-based state calculations"),
        HIGH("Advanced state machine with complex mathematical operations and dummy states");

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
    private final List<String> javaBlackList;
    private final List<String> javaWhiteList;

    public JavaObfuscationConfig(boolean enabled, Strength strength) {
        this(enabled, strength, new ArrayList<>(), new ArrayList<>());
    }

    public JavaObfuscationConfig(boolean enabled, Strength strength, List<String> javaBlackList, List<String> javaWhiteList) {
        this.enabled = enabled;
        this.strength = Objects.requireNonNull(strength, "strength");
        this.javaBlackList = new ArrayList<>(Objects.requireNonNull(javaBlackList, "javaBlackList"));
        this.javaWhiteList = new ArrayList<>(Objects.requireNonNull(javaWhiteList, "javaWhiteList"));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Strength getStrength() {
        return strength;
    }

    public List<String> getJavaBlackList() {
        return new ArrayList<>(javaBlackList);
    }

    public List<String> getJavaWhiteList() {
        return new ArrayList<>(javaWhiteList);
    }

    public static JavaObfuscationConfig defaultConfig() {
        return new JavaObfuscationConfig(false, Strength.MEDIUM);
    }

    public static JavaObfuscationConfig enabled(Strength strength) {
        return new JavaObfuscationConfig(true, strength);
    }
}

