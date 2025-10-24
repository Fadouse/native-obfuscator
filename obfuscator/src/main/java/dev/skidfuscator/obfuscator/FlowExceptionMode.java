package dev.skidfuscator.obfuscator;

/**
 * Strategy for dispatching the bogus control-flow exception.
 *
 * STANDARD    – use randomly selected JDK exceptions (current behaviour).
 * LIGHTWEIGHT – use an optimized runtime-generated exception with disabled stack traces.
 */
public enum FlowExceptionMode {
    STANDARD,
    LIGHTWEIGHT
}
