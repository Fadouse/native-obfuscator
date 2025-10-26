package by.radioegor146;

import dev.skidfuscator.obfuscator.transform.impl.flow.FlowObfuscationMode;
import dev.skidfuscator.obfuscator.transform.impl.flow.FlowObfuscationProfile;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public class JavaFlowSettings {
    private final FlowObfuscationMode mode;
    private final int guardDepth;
    private final int guardOpsMin;
    private final int guardOpsMax;
    private final EnumSet<FlowObfuscationProfile.MutationType> operations;

    private JavaFlowSettings(Builder builder) {
        this.mode = builder.mode;
        this.guardDepth = builder.guardDepth;
        this.guardOpsMin = builder.guardOpsMin;
        this.guardOpsMax = Math.max(builder.guardOpsMin, builder.guardOpsMax);
        this.operations = builder.operations.isEmpty()
                ? EnumSet.of(FlowObfuscationProfile.MutationType.XOR)
                : EnumSet.copyOf(builder.operations);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static JavaFlowSettings createDefault() {
        return builder().build();
    }

    public FlowObfuscationMode getMode() {
        return mode;
    }

    public int getGuardDepth() {
        return guardDepth;
    }

    public int getGuardOpsMin() {
        return guardOpsMin;
    }

    public int getGuardOpsMax() {
        return guardOpsMax;
    }

    public Set<FlowObfuscationProfile.MutationType> getOperations() {
        return Collections.unmodifiableSet(operations);
    }

    public boolean isDefault() {
        return mode == FlowObfuscationMode.BALANCED
                && guardDepth == 2
                && guardOpsMin == 1
                && guardOpsMax == 2
                && operations.equals(defaultOperations());
    }

    private static EnumSet<FlowObfuscationProfile.MutationType> defaultOperations() {
        return EnumSet.of(FlowObfuscationProfile.MutationType.XOR, FlowObfuscationProfile.MutationType.ADD);
    }

    public String formatMode() {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "JavaFlowSettings{" +
                "mode=" + mode +
                ", guardDepth=" + guardDepth +
                ", guardOpsMin=" + guardOpsMin +
                ", guardOpsMax=" + guardOpsMax +
                ", operations=" + operations +
                '}';
    }

    public static final class Builder {
        private FlowObfuscationMode mode = FlowObfuscationMode.BALANCED;
        private int guardDepth = 2;
        private int guardOpsMin = 1;
        private int guardOpsMax = 2;
        private EnumSet<FlowObfuscationProfile.MutationType> operations = defaultOperations();

        public Builder setMode(FlowObfuscationMode mode) {
            if (mode != null) {
                this.mode = mode;
            }
            return this;
        }

        public Builder setGuardDepth(int guardDepth) {
            this.guardDepth = Math.max(0, guardDepth);
            return this;
        }

        public Builder setGuardOpsMin(int guardOpsMin) {
            this.guardOpsMin = Math.max(1, guardOpsMin);
            return this;
        }

        public Builder setGuardOpsMax(int guardOpsMax) {
            this.guardOpsMax = Math.max(guardOpsMin, guardOpsMax);
            return this;
        }

        public Builder setOperations(Set<FlowObfuscationProfile.MutationType> operations) {
            if (operations != null && !operations.isEmpty()) {
                this.operations = EnumSet.copyOf(operations);
            }
            return this;
        }

        public JavaFlowSettings build() {
            return new JavaFlowSettings(this);
        }
    }
}
