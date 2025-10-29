package dev.skidfuscator.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ReflectionSupport {
    private static final Map<String, String> CLASS_FORWARD = new ConcurrentHashMap<>();
    private static final Map<String, String> CLASS_REVERSE = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> METHOD_FORWARD = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> METHOD_REVERSE = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> FIELD_FORWARD = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> FIELD_REVERSE = new ConcurrentHashMap<>();
    private static final String METADATA_CLASS = "dev.skidfuscator.runtime.ReflectionMappings";

    static {
        try {
            Class.forName(METADATA_CLASS, true, ReflectionSupport.class.getClassLoader());
        } catch (ClassNotFoundException ignored) {
        }
    }

    private ReflectionSupport() {
    }

    public static void registerClassMapping(String originalCanonical, String obfuscatedCanonical) {
        CLASS_FORWARD.put(originalCanonical, obfuscatedCanonical);
        CLASS_REVERSE.put(obfuscatedCanonical, originalCanonical);
    }

    static void registerClassInternal(String originalCanonical, String obfuscatedCanonical) {
        registerClassMapping(originalCanonical, obfuscatedCanonical);
    }

    static void registerMethodInternal(String ownerCanonical, String descriptor, String originalName, String obfuscatedName) {
        String forwardKey = methodKey(originalName, descriptor);
        METHOD_FORWARD.computeIfAbsent(ownerCanonical, key -> new ConcurrentHashMap<>())
                .put(forwardKey, obfuscatedName);
        METHOD_REVERSE.computeIfAbsent(ownerCanonical, key -> new ConcurrentHashMap<>())
                .put(methodKey(obfuscatedName, descriptor), originalName);
    }

    static void registerFieldInternal(String ownerCanonical, String descriptor, String originalName, String obfuscatedName) {
        String forwardKey = fieldKey(originalName, descriptor);
        FIELD_FORWARD.computeIfAbsent(ownerCanonical, key -> new ConcurrentHashMap<>())
                .put(forwardKey, obfuscatedName);
        FIELD_REVERSE.computeIfAbsent(ownerCanonical, key -> new ConcurrentHashMap<>())
                .put(fieldKey(obfuscatedName, descriptor), originalName);
    }

    public static String obfuscateClassName(String canonicalName) {
        return CLASS_FORWARD.getOrDefault(canonicalName, canonicalName);
    }

    public static String deobfuscateClassName(String canonicalName) {
        return CLASS_REVERSE.getOrDefault(canonicalName, canonicalName);
    }

    public static Class<?> forName(String canonicalName) throws ClassNotFoundException {
        return Class.forName(obfuscateClassName(canonicalName));
    }

    public static String obfuscateMethodName(String ownerCanonical, String methodName, String descriptor) {
        Map<String, String> map = METHOD_FORWARD.get(ownerCanonical);
        if (map == null) {
            return methodName;
        }
        return map.getOrDefault(methodKey(methodName, descriptor), methodName);
    }

    public static String deobfuscateMethodName(String ownerCanonical, String methodName, String descriptor) {
        Map<String, String> map = METHOD_REVERSE.get(ownerCanonical);
        if (map == null) {
            return methodName;
        }
        return map.getOrDefault(methodKey(methodName, descriptor), methodName);
    }

    public static String obfuscateFieldName(String ownerCanonical, String fieldName, String descriptor) {
        Map<String, String> map = FIELD_FORWARD.get(ownerCanonical);
        if (map == null) {
            return fieldName;
        }
        return map.getOrDefault(fieldKey(fieldName, descriptor), fieldName);
    }

    public static String deobfuscateFieldName(String ownerCanonical, String fieldName, String descriptor) {
        Map<String, String> map = FIELD_REVERSE.get(ownerCanonical);
        if (map == null) {
            return fieldName;
        }
        return map.getOrDefault(fieldKey(fieldName, descriptor), fieldName);
    }

    private static String methodKey(String name, String descriptor) {
        return name + descriptor;
    }

    private static String fieldKey(String name, String descriptor) {
        return name + descriptor;
    }
}
