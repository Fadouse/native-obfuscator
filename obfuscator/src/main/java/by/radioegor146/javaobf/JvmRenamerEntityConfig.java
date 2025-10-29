package by.radioegor146.javaobf;

import dev.skidfuscator.obfuscator.renamer.config.RenamerMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * UI-facing configuration describing how a single JVM entity type should be renamed.
 */
public final class JvmRenamerEntityConfig {
    private final boolean enabled;
    private final RenamerMode mode;
    private final List<String> alphabet;
    private final int depth;
    private final String prefix;
    private final Integer directoryDepth;
    private final Integer segmentLength;

    private JvmRenamerEntityConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.mode = builder.mode;
        this.alphabet = Collections.unmodifiableList(new ArrayList<>(builder.alphabet));
        this.depth = builder.depth;
        this.prefix = builder.prefix;
        this.directoryDepth = builder.directoryDepth;
        this.segmentLength = builder.segmentLength;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static JvmRenamerEntityConfig disabled() {
        return builder().enabled(false).build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public RenamerMode getMode() {
        return mode;
    }

    public List<String> getAlphabet() {
        return alphabet;
    }

    public int getDepth() {
        return depth;
    }

    public String getPrefix() {
        return prefix;
    }

    public Integer getDirectoryDepth() {
        return directoryDepth;
    }

    public Integer getSegmentLength() {
        return segmentLength;
    }

    public static final class Builder {
        private boolean enabled = false;
        private RenamerMode mode = RenamerMode.ALPHABETICAL;
        private List<String> alphabet = new ArrayList<>();
        private int depth = 3;
        private String prefix = null;
        private Integer directoryDepth = null;
        private Integer segmentLength = null;

        private Builder() {
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder mode(RenamerMode mode) {
            this.mode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        public Builder alphabet(List<String> alphabet) {
            this.alphabet = new ArrayList<>(alphabet == null ? List.of() : alphabet);
            return this;
        }

        public Builder depth(int depth) {
            this.depth = Math.max(1, depth);
            return this;
        }

        public Builder prefix(String prefix) {
            String trimmed = prefix == null ? null : prefix.trim();
            this.prefix = (trimmed == null || trimmed.isEmpty()) ? null : trimmed;
            return this;
        }

        public Builder directoryDepth(Integer directoryDepth) {
            if (directoryDepth == null) {
                this.directoryDepth = null;
            } else {
                this.directoryDepth = Math.max(1, directoryDepth);
            }
            return this;
        }

        public Builder segmentLength(Integer segmentLength) {
            if (segmentLength == null) {
                this.segmentLength = null;
            } else {
                this.segmentLength = Math.max(1, segmentLength);
            }
            return this;
        }

        public JvmRenamerEntityConfig build() {
            return new JvmRenamerEntityConfig(this);
        }
    }
}
