package dev.skidfuscator.runtime;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Bootstrap runtime that lazily resolves encrypted field and method references.
 */
public final class InvokeDynamicRuntime {
    private static final long MIX = 0x9E3779B97F4A7C15L;

    private static final int OPC_GETSTATIC = 178;
    private static final int OPC_PUTSTATIC = 179;
    private static final int OPC_GETFIELD = 180;
    private static final int OPC_PUTFIELD = 181;
    private static final int OPC_INVOKEVIRTUAL = 182;
    private static final int OPC_INVOKESPECIAL = 183;
    private static final int OPC_INVOKESTATIC = 184;
    private static final int OPC_INVOKEINTERFACE = 185;

    private InvokeDynamicRuntime() {
    }

    public static CallSite bootstrap(MethodHandles.Lookup lookup, String invokedName, MethodType invokedType,
                                     int opcode,
                                     String ownerPayload, long ownerKey,
                                     String namePayload, long nameKey,
                                     String descPayload, long descKey,
                                     int interfaceFlag) throws Throwable {
        final String owner = decode(ownerPayload, ownerKey);
        final String member = decode(namePayload, nameKey);
        final String desc = decode(descPayload, descKey);
        final ClassLoader callerLoader = lookup.lookupClass().getClassLoader();
        final Class<?> ownerClass = resolve(owner, callerLoader);

        MethodHandle target;
        switch (opcode) {
            case OPC_INVOKESTATIC -> {
                MethodType methodType = MethodType.fromMethodDescriptorString(desc, callerLoader);
                target = lookup.findStatic(ownerClass, member, methodType);
            }
            case OPC_INVOKEVIRTUAL, OPC_INVOKEINTERFACE -> {
                MethodType methodType = MethodType.fromMethodDescriptorString(desc, callerLoader);
                target = lookup.findVirtual(ownerClass, member, methodType);
            }
            case OPC_INVOKESPECIAL -> {
                MethodType methodType = MethodType.fromMethodDescriptorString(desc, callerLoader);
                target = lookup.findSpecial(ownerClass, member, methodType, lookup.lookupClass());
            }
            case OPC_GETSTATIC -> {
                Class<?> fieldType = fieldType(desc, callerLoader);
                target = lookup.findStaticGetter(ownerClass, member, fieldType);
            }
            case OPC_PUTSTATIC -> {
                Class<?> fieldType = fieldType(desc, callerLoader);
                target = lookup.findStaticSetter(ownerClass, member, fieldType);
            }
            case OPC_GETFIELD -> {
                Class<?> fieldType = fieldType(desc, callerLoader);
                target = lookup.findGetter(ownerClass, member, fieldType);
            }
            case OPC_PUTFIELD -> {
                Class<?> fieldType = fieldType(desc, callerLoader);
                target = lookup.findSetter(ownerClass, member, fieldType);
            }
            default -> throw new BootstrapMethodError("Unsupported opcode: " + opcode);
        }

        return new ConstantCallSite(target.asType(invokedType));
    }

    private static Class<?> resolve(String internalName, ClassLoader loader) throws ClassNotFoundException {
        String binaryName = internalName.replace('/', '.');
        if (loader == null) {
            return Class.forName(binaryName);
        }
        return Class.forName(binaryName, false, loader);
    }

    private static Class<?> fieldType(String descriptor, ClassLoader loader) throws ClassNotFoundException {
        MethodType mt = MethodType.fromMethodDescriptorString("()" + descriptor, loader);
        return mt.returnType();
    }

    private static String decode(String payload, long key) {
        byte[] data = Base64.getDecoder().decode(payload);
        long state = key ^ MIX;
        for (int i = 0; i < data.length; i++) {
            data[i] ^= (byte) state;
            state = Long.rotateLeft(state + MIX + i, 3);
        }
        return new String(data, StandardCharsets.UTF_8);
    }
}
