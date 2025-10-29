package dev.skidfuscator.obfuscator.renamer.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import dev.skidfuscator.obfuscator.SkidfuscatorSession;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public final class RenamerSettings {
    private static final String CLASS_PATH = "classRenamer";
    private static final String METHOD_PATH = "methodRenamer";
    private static final String FIELD_PATH = "fieldRenamer";

    private final EntityRenamerSettings classSettings;
    private final EntityRenamerSettings methodSettings;
    private final EntityRenamerSettings fieldSettings;

    private RenamerSettings(EntityRenamerSettings classSettings,
                            EntityRenamerSettings methodSettings,
                            EntityRenamerSettings fieldSettings) {
        this.classSettings = classSettings;
        this.methodSettings = methodSettings;
        this.fieldSettings = fieldSettings;
    }

    public EntityRenamerSettings getClassSettings() {
        return classSettings;
    }

    public EntityRenamerSettings getMethodSettings() {
        return methodSettings;
    }

    public EntityRenamerSettings getFieldSettings() {
        return fieldSettings;
    }

    public boolean isAnyEnabled() {
        return classSettings.isEnabled() || methodSettings.isEnabled() || fieldSettings.isEnabled();
    }

    public static RenamerSettings from(Config root, SkidfuscatorSession session) {
        Config config = root == null ? ConfigFactory.empty() : root;
        boolean sessionDefault = session != null && session.isRenamer();

        EntityRenamerSettings classSettings = buildClassSettings(config, sessionDefault);
        EntityRenamerSettings methodSettings = buildMemberSettings(config, METHOD_PATH, sessionDefault, 2);
        EntityRenamerSettings fieldSettings = buildMemberSettings(config, FIELD_PATH, sessionDefault, 2);

        return new RenamerSettings(classSettings, methodSettings, fieldSettings);
    }

    private static EntityRenamerSettings buildClassSettings(Config config, boolean sessionDefault) {
        String path = CLASS_PATH;
        Config scoped = config.hasPath(path) ? config.getConfig(path) : ConfigFactory.empty();

        boolean enabled = scoped.hasPath("enabled") ? scoped.getBoolean("enabled") : sessionDefault;
        RenamerMode mode = RenamerMode.fromString(scoped.hasPath("type") ? scoped.getString("type") : null);
        List<String> alphabet = resolveAlphabet(scoped, mode, defaultAlphabet());

        int minLength = scoped.hasPath("depth") ? Math.max(1, scoped.getInt("depth")) : 3;
        int packageDepth = scoped.hasPath("directoryDepth") ? Math.max(1, scoped.getInt("directoryDepth")) : 1;
        int segmentLength = scoped.hasPath("segmentLength") ? Math.max(1, scoped.getInt("segmentLength")) : Math.max(1, minLength / packageDepth);
        int requiredLength = segmentLength * Math.max(0, packageDepth - 1) + 1;
        if (minLength < requiredLength) {
            minLength = requiredLength;
        }

        String prefix = scoped.hasPath("prefix") ? normalizePrefix(scoped.getString("prefix")) : "";

        String fallback = alphabet.get(0);

        return EntityRenamerSettings.builder()
                .enabled(enabled)
                .mode(mode)
                .alphabet(alphabet)
                .minLength(minLength)
                .prefix(prefix)
                .packageDepth(packageDepth)
                .segmentLength(segmentLength)
                .fallbackToken(fallback)
                .build();
    }

    private static EntityRenamerSettings buildMemberSettings(Config config, String path, boolean sessionDefault, int defaultDepth) {
        Config scoped = config.hasPath(path) ? config.getConfig(path) : ConfigFactory.empty();

        boolean enabled = scoped.hasPath("enabled") ? scoped.getBoolean("enabled") : sessionDefault;
        RenamerMode mode = RenamerMode.fromString(scoped.hasPath("type") ? scoped.getString("type") : null);
        List<String> alphabet = resolveAlphabet(scoped, mode, defaultAlphabet());
        int minLength = scoped.hasPath("depth") ? Math.max(1, scoped.getInt("depth")) : defaultDepth;
        String fallback = alphabet.get(0);

        return EntityRenamerSettings.builder()
                .enabled(enabled)
                .mode(mode)
                .alphabet(alphabet)
                .minLength(minLength)
                .prefix("")
                .packageDepth(1)
                .segmentLength(minLength)
                .fallbackToken(fallback)
                .build();
    }

    private static List<String> resolveAlphabet(Config scoped, RenamerMode mode, List<String> fallback) {
        if (mode == RenamerMode.NUMERIC) {
            return digitsAlphabet();
        }

        if (mode == RenamerMode.ALPHABETICAL && !scoped.hasPath("chars")) {
            return fallback;
        }

        if (scoped.hasPath("chars")) {
            List<String> supplied = scoped.getStringList("chars");
            Set<String> tokens = new LinkedHashSet<>();
            for (String entry : supplied) {
                if (entry == null) {
                    continue;
                }
                for (int i = 0; i < entry.length(); i++) {
                    tokens.add(String.valueOf(entry.charAt(i)));
                }
            }
            if (!tokens.isEmpty()) {
                return new ArrayList<>(tokens);
            }
        }

        if (mode == RenamerMode.CUSTOM) {
            return fallback;
        }

        return fallback;
    }

    private static List<String> defaultAlphabet() {
        return IntStream.rangeClosed('a', 'z')
                .mapToObj(c -> String.valueOf((char) c))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static List<String> digitsAlphabet() {
        return IntStream.rangeClosed('0', '9')
                .mapToObj(c -> String.valueOf((char) c))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null) {
            return "";
        }
        String trimmed = prefix.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String normalized = trimmed.replace('.', '/');
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }
}
