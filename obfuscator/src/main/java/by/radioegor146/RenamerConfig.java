package by.radioegor146;

import java.util.Collections;
import java.util.List;

/**
 * Configuration class for JVM renamer obfuscation.
 * Provides detailed control over how classes, methods, and fields are renamed.
 */
public class RenamerConfig {
    
    // Global renamer settings
    private final boolean enabled;
    private final boolean renameClasses;
    private final boolean renameMethods;
    private final boolean renameFields;
    
    // Class renaming settings
    private final String classPrefix;
    private final String classCharset;
    private final boolean classKeepPackageStructure;
    private final String classPackagePrefix;
    
    // Method renaming settings
    private final String methodPrefix;
    private final String methodCharset;
    
    // Field renaming settings
    private final String fieldPrefix;
    private final String fieldCharset;
    
    // Exclusion lists
    private final List<String> excludeClasses;
    private final List<String> excludeMethods;
    private final List<String> excludeFields;
    
    // Compatibility settings
    private final boolean reflectionCompatible;
    private final boolean invokeDynamicCompatible;
    
    private RenamerConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.renameClasses = builder.renameClasses;
        this.renameMethods = builder.renameMethods;
        this.renameFields = builder.renameFields;
        
        this.classPrefix = builder.classPrefix;
        this.classCharset = builder.classCharset;
        this.classKeepPackageStructure = builder.classKeepPackageStructure;
        this.classPackagePrefix = builder.classPackagePrefix;
        
        this.methodPrefix = builder.methodPrefix;
        this.methodCharset = builder.methodCharset;
        
        this.fieldPrefix = builder.fieldPrefix;
        this.fieldCharset = builder.fieldCharset;
        
        this.excludeClasses = builder.excludeClasses != null ? 
                List.copyOf(builder.excludeClasses) : Collections.emptyList();
        this.excludeMethods = builder.excludeMethods != null ? 
                List.copyOf(builder.excludeMethods) : Collections.emptyList();
        this.excludeFields = builder.excludeFields != null ? 
                List.copyOf(builder.excludeFields) : Collections.emptyList();
        
        this.reflectionCompatible = builder.reflectionCompatible;
        this.invokeDynamicCompatible = builder.invokeDynamicCompatible;
    }
    
    // Getters
    public boolean isEnabled() { return enabled; }
    public boolean isRenameClasses() { return renameClasses; }
    public boolean isRenameMethods() { return renameMethods; }
    public boolean isRenameFields() { return renameFields; }
    
    public String getClassPrefix() { return classPrefix; }
    public String getClassCharset() { return classCharset; }
    public boolean isClassKeepPackageStructure() { return classKeepPackageStructure; }
    public String getClassPackagePrefix() { return classPackagePrefix; }
    
    public String getMethodPrefix() { return methodPrefix; }
    public String getMethodCharset() { return methodCharset; }
    
    public String getFieldPrefix() { return fieldPrefix; }
    public String getFieldCharset() { return fieldCharset; }
    
    public List<String> getExcludeClasses() { return excludeClasses; }
    public List<String> getExcludeMethods() { return excludeMethods; }
    public List<String> getExcludeFields() { return excludeFields; }
    
    public boolean isReflectionCompatible() { return reflectionCompatible; }
    public boolean isInvokeDynamicCompatible() { return invokeDynamicCompatible; }
    
    /**
     * Creates a default disabled renamer configuration.
     */
    public static RenamerConfig createDisabled() {
        return new Builder().setEnabled(false).build();
    }
    
    /**
     * Creates a default enabled renamer configuration with standard settings.
     */
    public static RenamerConfig createDefault() {
        return new Builder()
                .setEnabled(true)
                .setRenameClasses(true)
                .setRenameMethods(true)
                .setRenameFields(true)
                .setClassPrefix("")
                .setClassCharset("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
                .setClassKeepPackageStructure(false)
                .setClassPackagePrefix("")
                .setMethodPrefix("")
                .setMethodCharset("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
                .setFieldPrefix("")
                .setFieldCharset("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
                .setReflectionCompatible(true)
                .setInvokeDynamicCompatible(true)
                .build();
    }
    
    @Override
    public String toString() {
        return String.format("RenamerConfig{\n" +
                "  enabled=%s,\n" +
                "  renameClasses=%s, renameMethods=%s, renameFields=%s,\n" +
                "  classPrefix='%s', classCharset='%s',\n" +
                "  classKeepPackageStructure=%s, classPackagePrefix='%s',\n" +
                "  methodPrefix='%s', methodCharset='%s',\n" +
                "  fieldPrefix='%s', fieldCharset='%s',\n" +
                "  reflectionCompatible=%s, invokeDynamicCompatible=%s\n" +
                "}",
                enabled, renameClasses, renameMethods, renameFields,
                classPrefix, classCharset, classKeepPackageStructure, classPackagePrefix,
                methodPrefix, methodCharset, fieldPrefix, fieldCharset,
                reflectionCompatible, invokeDynamicCompatible);
    }
    
    /**
     * Builder class for constructing RenamerConfig instances.
     */
    public static class Builder {
        private boolean enabled = false;
        private boolean renameClasses = true;
        private boolean renameMethods = true;
        private boolean renameFields = true;
        
        private String classPrefix = "";
        private String classCharset = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        private boolean classKeepPackageStructure = false;
        private String classPackagePrefix = "";
        
        private String methodPrefix = "";
        private String methodCharset = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        
        private String fieldPrefix = "";
        private String fieldCharset = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        
        private List<String> excludeClasses = Collections.emptyList();
        private List<String> excludeMethods = Collections.emptyList();
        private List<String> excludeFields = Collections.emptyList();
        
        private boolean reflectionCompatible = true;
        private boolean invokeDynamicCompatible = true;
        
        public Builder setEnabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }
        
        public Builder setRenameClasses(boolean renameClasses) {
            this.renameClasses = renameClasses;
            return this;
        }
        
        public Builder setRenameMethods(boolean renameMethods) {
            this.renameMethods = renameMethods;
            return this;
        }
        
        public Builder setRenameFields(boolean renameFields) {
            this.renameFields = renameFields;
            return this;
        }
        
        public Builder setClassPrefix(String classPrefix) {
            this.classPrefix = classPrefix != null ? classPrefix : "";
            return this;
        }
        
        public Builder setClassCharset(String classCharset) {
            this.classCharset = classCharset != null && !classCharset.isEmpty() ? 
                    classCharset : "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
            return this;
        }
        
        public Builder setClassKeepPackageStructure(boolean classKeepPackageStructure) {
            this.classKeepPackageStructure = classKeepPackageStructure;
            return this;
        }
        
        public Builder setClassPackagePrefix(String classPackagePrefix) {
            this.classPackagePrefix = classPackagePrefix != null ? classPackagePrefix : "";
            return this;
        }
        
        public Builder setMethodPrefix(String methodPrefix) {
            this.methodPrefix = methodPrefix != null ? methodPrefix : "";
            return this;
        }
        
        public Builder setMethodCharset(String methodCharset) {
            this.methodCharset = methodCharset != null && !methodCharset.isEmpty() ? 
                    methodCharset : "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
            return this;
        }
        
        public Builder setFieldPrefix(String fieldPrefix) {
            this.fieldPrefix = fieldPrefix != null ? fieldPrefix : "";
            return this;
        }
        
        public Builder setFieldCharset(String fieldCharset) {
            this.fieldCharset = fieldCharset != null && !fieldCharset.isEmpty() ? 
                    fieldCharset : "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
            return this;
        }
        
        public Builder setExcludeClasses(List<String> excludeClasses) {
            this.excludeClasses = excludeClasses;
            return this;
        }
        
        public Builder setExcludeMethods(List<String> excludeMethods) {
            this.excludeMethods = excludeMethods;
            return this;
        }
        
        public Builder setExcludeFields(List<String> excludeFields) {
            this.excludeFields = excludeFields;
            return this;
        }
        
        public Builder setReflectionCompatible(boolean reflectionCompatible) {
            this.reflectionCompatible = reflectionCompatible;
            return this;
        }
        
        public Builder setInvokeDynamicCompatible(boolean invokeDynamicCompatible) {
            this.invokeDynamicCompatible = invokeDynamicCompatible;
            return this;
        }
        
        public RenamerConfig build() {
            return new RenamerConfig(this);
        }
    }
}
