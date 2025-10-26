package dev.skidfuscator.obfuscator.transform.impl.flow;

import com.typesafe.config.Config;
import dev.skidfuscator.obfuscator.util.RandomUtil;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public final class FlowObfuscationProfile {
    private final FlowObfuscationMode mode;
    private final int guardDepth;
    private final int minMutationOps;
    private final int maxMutationOps;
    private final EnumSet<MutationType> allowedOperations;

    private FlowObfuscationProfile(FlowObfuscationMode mode,
                                   int guardDepth,
                                   int minMutationOps,
                                   int maxMutationOps,
                                   EnumSet<MutationType> allowedOperations) {
        this.mode = mode;
        this.guardDepth = Math.max(0, guardDepth);
        this.minMutationOps = Math.max(1, minMutationOps);
        this.maxMutationOps = Math.max(this.minMutationOps, maxMutationOps);
        this.allowedOperations = allowedOperations;
    }

    public static FlowObfuscationProfile fromConfig(Config config) {
        FlowObfuscationMode mode = FlowObfuscationMode.BALANCED;
        if (config != null && config.hasPath("flow.mode")) {
            mode = FlowObfuscationMode.fromConfigValue(config.getString("flow.mode"));
        }

        int defaultDepth = switch (mode) {
            case PERFORMANCE -> 1;
            case BALANCED -> 2;
            case AGGRESSIVE -> 3;
        };

        int depth = defaultDepth;
        if (config != null && config.hasPath("flow.guardDepth")) {
            depth = Math.max(0, config.getInt("flow.guardDepth"));
        }

        int minOps = mode == FlowObfuscationMode.AGGRESSIVE ? 2 : 1;
        int maxOps = mode == FlowObfuscationMode.PERFORMANCE ? 1 : mode == FlowObfuscationMode.BALANCED ? 2 : 4;
        if (config != null && config.hasPath("flow.guardOps.min")) {
            minOps = Math.max(1, config.getInt("flow.guardOps.min"));
        }
        if (config != null && config.hasPath("flow.guardOps.max")) {
            maxOps = Math.max(minOps, config.getInt("flow.guardOps.max"));
        }

        EnumSet<MutationType> ops;
        switch (mode) {
            case PERFORMANCE -> ops = EnumSet.of(MutationType.XOR);
            case BALANCED -> ops = EnumSet.of(MutationType.XOR, MutationType.ADD);
            case AGGRESSIVE -> ops = EnumSet.allOf(MutationType.class);
            default -> ops = EnumSet.of(MutationType.XOR);
        }

        if (config != null && config.hasPath("flow.guardOps.types")) {
            List<String> tokens = config.getStringList("flow.guardOps.types");
            EnumSet<MutationType> configured = EnumSet.noneOf(MutationType.class);
            for (String token : tokens) {
                try {
                    configured.add(MutationType.valueOf(token.trim().toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                    // skip invalid entries
                }
            }
            if (!configured.isEmpty()) {
                ops = configured;
            }
        }

        return new FlowObfuscationProfile(mode, depth, minOps, maxOps, ops);
    }

    public FlowObfuscationMode mode() {
        return mode;
    }

    public int guardDepth() {
        return guardDepth;
    }

    public int randomMutationCount() {
        if (maxMutationOps == minMutationOps) {
            return minMutationOps;
        }
        return minMutationOps + RandomUtil.nextInt((maxMutationOps - minMutationOps) + 1);
    }

    public MutationType pickMutationType() {
        List<MutationType> available = new ArrayList<>(allowedOperations);
        if (available.isEmpty()) {
            return MutationType.XOR;
        }
        return available.get(RandomUtil.nextInt(available.size()));
    }

    public enum MutationType {
        XOR,
        ADD
    }
}
