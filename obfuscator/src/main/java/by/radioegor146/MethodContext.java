package by.radioegor146;

import by.radioegor146.source.StringPool;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.util.*;

public class MethodContext {

    public NativeObfuscator obfuscator;

    public final MethodNode method;
    public final ClassNode clazz;
    public final int methodIndex;
    public final int classIndex;

    public final StringBuilder output;
    public final StringBuilder nativeMethods;

    public Type ret;
    public ArrayList<Type> argTypes;

    public int line;
    public List<Integer> stack;
    public List<Integer> locals;
    public Set<TryCatchBlockNode> tryCatches;
    public Map<CatchesBlock, String> catches;

    public HiddenMethodsPool.HiddenMethod proxyMethod;
    public MethodNode nativeMethod;

    public int stackPointer;

    private final LabelPool labelPool = new LabelPool();

    public String cppNativeMethodName;

    public boolean dispatcherMode;

    // If true, MethodProcessor should leave the Java method body unchanged
    // and skip generating/registering any native implementation for it.
    public boolean skipNative;

    // Protection configuration settings
    public ProtectionConfig protectionConfig;

    // Heuristics flags used to safely rewrite enum-switch bytecode patterns
    // that utilize the synthetic "$SwitchMap$..." int[] array. When the pattern
    // "GETSTATIC $SwitchMap...; ALOAD <enum>; INVOKEVIRTUAL ordinal()I; IALOAD; TABLESWITCH" is
    // detected, we replace the IALOAD with a direct computation (ordinal + 1), avoiding
    // reliance on the fragile synthetic mapping array.
    public boolean enumSwitchMapOnStack;
    public boolean lastWasEnumOrdinal;

    public MethodContext(NativeObfuscator obfuscator, MethodNode method, int methodIndex, ClassNode clazz,
                         int classIndex, ProtectionConfig protectionConfig) {
        this.obfuscator = obfuscator;
        this.method = method;
        this.methodIndex = methodIndex;
        this.clazz = clazz;
        this.classIndex = classIndex;
        this.protectionConfig = protectionConfig;

        this.output = new StringBuilder();
        this.nativeMethods = new StringBuilder();

        this.line = -1;
        this.stack = new ArrayList<>();
        this.locals = new ArrayList<>();
        this.tryCatches = new HashSet<>();
        this.catches = new HashMap<>();
    }

    public NodeCache<String> getCachedStrings() {
        return obfuscator.getCachedStrings();
    }

    public NodeCache<String> getCachedClasses() {
        return obfuscator.getCachedClasses();
    }

    public NodeCache<CachedMethodInfo> getCachedMethods() {
        return obfuscator.getCachedMethods();
    }

    public NodeCache<CachedFieldInfo> getCachedFields() {
        return obfuscator.getCachedFields();
    }

    public Snippets getSnippets() {
        return obfuscator.getSnippets();
    }

    public StringPool getStringPool() {
        return obfuscator.getStringPool();
    }

    public LabelPool getLabelPool() {
        return labelPool;
    }
}
