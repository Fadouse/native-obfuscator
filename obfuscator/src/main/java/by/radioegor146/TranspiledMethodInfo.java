package by.radioegor146;

/**
 * Holds information about a transpiled method for cross-class direct call optimization
 */
public class TranspiledMethodInfo {
    private final String owner;          // Internal class name (e.g., "com/example/MyClass")
    private final String name;           // Method name
    private final String descriptor;     // Method descriptor
    private final String cppMethodName;  // C++ function name (e.g., "__ngen_MyClass_method")
    private final String namespace;      // C++ namespace (e.g., "native_jvm::classes::__ngen_MyClass_0")
    private final boolean isStatic;      // Whether the method is static

    public TranspiledMethodInfo(String owner, String name, String descriptor,
                                String cppMethodName, String namespace, boolean isStatic) {
        this.owner = owner;
        this.name = name;
        this.descriptor = descriptor;
        this.cppMethodName = cppMethodName;
        this.namespace = namespace;
        this.isStatic = isStatic;
    }

    public String getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    public String getDescriptor() {
        return descriptor;
    }

    public String getCppMethodName() {
        return cppMethodName;
    }

    public String getNamespace() {
        return namespace;
    }

    public boolean isStatic() {
        return isStatic;
    }

    public String getFullyQualifiedName() {
        return namespace + "::" + cppMethodName;
    }

    /**
     * Creates a unique key for this method: "owner#name!descriptor"
     */
    public String getKey() {
        return owner + "#" + name + "!" + descriptor;
    }
}
