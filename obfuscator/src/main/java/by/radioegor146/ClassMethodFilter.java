package by.radioegor146;

import by.radioegor146.nativeobfuscator.Native;
import by.radioegor146.nativeobfuscator.NotNative;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public class ClassMethodFilter {

    private static final String NATIVE_ANNOTATION_DESC = Type.getDescriptor(Native.class);
    private static final String NOT_NATIVE_ANNOTATION_DESC = Type.getDescriptor(NotNative.class);

    private final ClassMethodList blackList;
    private final ClassMethodList whiteList;
    private final boolean useAnnotations;

    public ClassMethodFilter(ClassMethodList blackList, ClassMethodList whiteList, boolean useAnnotations) {
        this.blackList = blackList;
        this.whiteList = whiteList;
        this.useAnnotations = useAnnotations;
    }

    private boolean hasInList(ClassMethodList list, String name) {
        if (list == null) {
            return false;
        }
        return list.contains(name);
    }

    private static boolean hasAnnotation(Iterable<AnnotationNode> annotations, String desc) {
        if (annotations == null) {
            return false;
        }
        for (AnnotationNode annotationNode : annotations) {
            if (annotationNode.desc.equals(desc)) {
                return true;
            }
        }
        return false;
    }

    public boolean shouldProcess(ClassNode classNode) {
        if (hasInList(blackList, classNode.name)) {
            return false;
        }
        if (whiteList != null && !hasInList(whiteList, classNode.name)) {
            return false;
        }
        if (!useAnnotations) {
            return true;
        }
        if (hasAnnotation(classNode.invisibleAnnotations, NOT_NATIVE_ANNOTATION_DESC)) {
            return false;
        }
        if (hasAnnotation(classNode.invisibleAnnotations, NATIVE_ANNOTATION_DESC)) {
            return true;
        }
        return classNode.methods.stream()
                .filter(MethodProcessor::shouldProcess)
                .anyMatch(methodNode -> this.shouldProcess(classNode, methodNode));
    }

    public boolean shouldProcess(ClassNode classNode, MethodNode methodNode) {
        if (hasInList(blackList, MethodProcessor.nameFromNode(methodNode, classNode))) {
            return false;
        }
        if (whiteList != null && !hasInList(whiteList, MethodProcessor.nameFromNode(methodNode, classNode))) {
            return false;
        }
        if (!useAnnotations) {
            return true;
        }
        if (hasAnnotation(classNode.invisibleAnnotations, NOT_NATIVE_ANNOTATION_DESC)) {
            return false;
        }
        if (hasAnnotation(methodNode.invisibleAnnotations, NOT_NATIVE_ANNOTATION_DESC)) {
            return false;
        }
        if (hasAnnotation(methodNode.invisibleAnnotations, NATIVE_ANNOTATION_DESC)) {
            return true;
        }
        return hasAnnotation(classNode.invisibleAnnotations, NATIVE_ANNOTATION_DESC);
    }

    public boolean hasPartialMethodObfuscation(ClassNode classNode) {
        if (!shouldProcess(classNode)) {
            return false;
        }
        boolean hasNativeMethods = classNode.methods.stream()
                .filter(MethodProcessor::shouldProcess)
                .anyMatch(methodNode -> shouldProcess(classNode, methodNode));
        if (!hasNativeMethods) {
            return false;
        }
        boolean hasJavaMethods = classNode.methods.stream()
                .filter(MethodProcessor::shouldProcess)
                .anyMatch(methodNode -> !shouldProcess(classNode, methodNode));
        return hasJavaMethods;
    }

    public static void cleanAnnotations(ClassNode classNode) {
        if (classNode.invisibleAnnotations != null) {
            classNode.invisibleAnnotations.removeIf(annotationNode -> annotationNode.desc.equals(NATIVE_ANNOTATION_DESC));
        }
        classNode.methods.stream()
                .filter(methodNode -> methodNode.invisibleAnnotations != null)
                .forEach(methodNode -> methodNode.invisibleAnnotations.removeIf(annotationNode ->
                    annotationNode.desc.equals(NATIVE_ANNOTATION_DESC) || annotationNode.desc.equals(NOT_NATIVE_ANNOTATION_DESC)));
    }
}
