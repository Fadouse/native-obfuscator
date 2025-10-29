package dev.skidfuscator.obfuscator.renamer.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class EntityRenamerSettings {
    private final boolean enabled;
    private final RenamerMode mode;
    private final List<String> alphabet;
    private final int minLength;
    private final String prefix;
    private final int packageDepth;
    private final int segmentLength;
    private final String fallbackToken;

    private EntityRenamerSettings(Builder builder) {
        this.enabled = builder.enabled;
        this.mode = builder.mode;
        this.alphabet = Collections.unmodifiableList(new ArrayList<>(builder.alphabet));
        this.minLength = builder.minLength;
        this.prefix = builder.prefix;
        this.packageDepth = builder.packageDepth;
        this.segmentLength = builder.segmentLength;
        this.fallbackToken = builder.fallbackToken;
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

    public int getMinLength() {
        return minLength;
    }

    public String getPrefix() {
        return prefix;
    }

    public int getPackageDepth() {
        return packageDepth;
    }

    public int getSegmentLength() {
        return segmentLength;
    }

    public String getFallbackToken() {
        return fallbackToken;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean enabled;
        private RenamerMode mode = RenamerMode.ALPHABETICAL;
        private List<String> alphabet = new ArrayList<>();
        private int minLength = 3;
        private String prefix = "";
        private int packageDepth = 1;
        private int segmentLength = 1;
        private String fallbackToken = "a";

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
            this.alphabet = new ArrayList<>(Objects.requireNonNull(alphabet, "alphabet"));
            return this;
        }

        public Builder minLength(int minLength) {
            this.minLength = Math.max(1, minLength);
            return this;
        }

        public Builder prefix(String prefix) {
            this.prefix = prefix == null ? "" : prefix;
            return this;
        }

        public Builder packageDepth(int packageDepth) {
            this.packageDepth = Math.max(1, packageDepth);
            return this;
        }

        public Builder segmentLength(int segmentLength) {
            this.segmentLength = Math.max(1, segmentLength);
            return this;
        }

        public Builder fallbackToken(String fallbackToken) {
            this.fallbackToken = fallbackToken == null || fallbackToken.isEmpty() ? "a" : fallbackToken;
            return this;
        }

        public EntityRenamerSettings build() {
            if (alphabet.isEmpty()) {
                throw new IllegalStateException("Alphabet must contain at least one entry");
            }
            return new EntityRenamerSettings(this);
        }
    }
}
