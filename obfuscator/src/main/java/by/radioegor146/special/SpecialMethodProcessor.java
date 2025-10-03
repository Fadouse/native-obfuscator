package by.radioegor146.special;

import by.radioegor146.MethodContext;

public interface SpecialMethodProcessor {
    /**
     * Compute the native stub name that will be emitted for the supplied method without
     * mutating the {@link MethodContext}. Implementations should avoid performing any
     * structural rewrites or marking the method as native here so the preview step can run
     * safely before the full transpilation pass.
     *
     * @return the base native name (without the {@code __ngen_} prefix) or {@code null} when the
     * method should be kept in Java bytecode form.
     */
    String previewName(MethodContext context);

    String preProcess(MethodContext context);

    void postProcess(MethodContext context);
}
