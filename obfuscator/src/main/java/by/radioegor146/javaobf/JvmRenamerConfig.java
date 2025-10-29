package by.radioegor146.javaobf;

import java.util.Objects;

/**
 * Aggregated JVM renamer configuration for UI/CLI flows.
 */
public final class JvmRenamerConfig {
    private final boolean enabled;
    private final JvmRenamerEntityConfig classConfig;
    private final JvmRenamerEntityConfig methodConfig;
    private final JvmRenamerEntityConfig fieldConfig;

    private JvmRenamerConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.classConfig = builder.classConfig;
        this.methodConfig = builder.methodConfig;
        this.fieldConfig = builder.fieldConfig;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static JvmRenamerConfig disabled() {
        return builder().enabled(false).build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public JvmRenamerEntityConfig getClassConfig() {
        return classConfig;
    }

    public JvmRenamerEntityConfig getMethodConfig() {
        return methodConfig;
    }

    public JvmRenamerEntityConfig getFieldConfig() {
        return fieldConfig;
    }

    public boolean isAnyEntityEnabled() {
        return (classConfig != null && classConfig.isEnabled())
                || (methodConfig != null && methodConfig.isEnabled())
                || (fieldConfig != null && fieldConfig.isEnabled());
    }

    public static final class Builder {
        private boolean enabled = false;
        private JvmRenamerEntityConfig classConfig = JvmRenamerEntityConfig.disabled();
        private JvmRenamerEntityConfig methodConfig = JvmRenamerEntityConfig.disabled();
        private JvmRenamerEntityConfig fieldConfig = JvmRenamerEntityConfig.disabled();

        private Builder() {
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder classConfig(JvmRenamerEntityConfig classConfig) {
            this.classConfig = Objects.requireNonNull(classConfig, "classConfig");
            return this;
        }

        public Builder methodConfig(JvmRenamerEntityConfig methodConfig) {
            this.methodConfig = Objects.requireNonNull(methodConfig, "methodConfig");
            return this;
        }

        public Builder fieldConfig(JvmRenamerEntityConfig fieldConfig) {
            this.fieldConfig = Objects.requireNonNull(fieldConfig, "fieldConfig");
            return this;
        }

        public JvmRenamerConfig build() {
            return new JvmRenamerConfig(this);
        }
    }
}
