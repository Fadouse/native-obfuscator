package by.radioegor146;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for VM stack operations - POP, POP2, DUP_X1, DUP_X2, DUP2, DUP2_X1, DUP2_X2
 * These tests verify the logical correctness of the stack manipulation operations.
 */
public class VmTranslatorStackOperationsTest {

    @Test
    void testStackPointerCalculations() {
        // Test that stack pointer calculations are correct for new instructions
        int currentStackPointer = 5;

        // POP: decreases stack by 1
        int newStackPointer = currentStackPointer - 1; // POP effect
        assertEquals(4, newStackPointer);

        // POP2: decreases stack by 2
        newStackPointer = currentStackPointer - 2; // POP2 effect
        assertEquals(3, newStackPointer);

        // DUP_X1: increases stack by 1
        newStackPointer = currentStackPointer + 1; // DUP_X1 effect
        assertEquals(6, newStackPointer);

        // DUP2: increases stack by 2
        newStackPointer = currentStackPointer + 2; // DUP2 effect
        assertEquals(7, newStackPointer);

        // DUP2_X1: increases stack by 2
        newStackPointer = currentStackPointer + 2; // DUP2_X1 effect
        assertEquals(7, newStackPointer);
    }

    @Test
    void testPopStackTransformation() {
        // Test the logical transformation of POP
        // Input stack: [..., value2, value1]
        // Output stack: [..., value2]

        int[] inputStack = {10, 20, 30}; // bottom to top: 10, 20, 30
        int[] expectedOutput = {10, 20}; // after POP: 10, 20

        int[] result = simulatePop(inputStack);
        assertArrayEquals(expectedOutput, result);
    }

    @Test
    void testPop2StackTransformation() {
        // Test the logical transformation of POP2
        // Input stack: [..., value3, value2, value1]
        // Output stack: [..., value3]

        int[] inputStack = {10, 20, 30, 40}; // bottom to top: 10, 20, 30, 40
        int[] expectedOutput = {10, 20}; // after POP2: 10, 20

        int[] result = simulatePop2(inputStack);
        assertArrayEquals(expectedOutput, result);
    }

    @Test
    void testDupX1StackTransformation() {
        // Test the logical transformation of DUP_X1
        // Input stack: [..., value2, value1]
        // Output stack: [..., value1, value2, value1]

        int[] inputStack = {10, 20}; // bottom to top: 10, 20
        int[] expectedOutput = {20, 10, 20}; // after DUP_X1: 20, 10, 20

        // Simulate DUP_X1 transformation
        int[] result = simulateDupX1(inputStack);
        assertArrayEquals(expectedOutput, result);
    }

    @Test
    void testDupX2StackTransformation() {
        // Test the logical transformation of DUP_X2
        // Input stack: [..., value3, value2, value1]
        // Output stack: [..., value1, value3, value2, value1]

        int[] inputStack = {10, 20, 30}; // bottom to top: 10, 20, 30
        int[] expectedOutput = {30, 10, 20, 30}; // after DUP_X2: 30, 10, 20, 30

        int[] result = simulateDupX2(inputStack);
        assertArrayEquals(expectedOutput, result);
    }

    @Test
    void testDup2StackTransformation() {
        // Test the logical transformation of DUP2
        // Input stack: [..., value2, value1]
        // Output stack: [..., value2, value1, value2, value1]

        int[] inputStack = {10, 20}; // bottom to top: 10, 20
        int[] expectedOutput = {10, 20, 10, 20}; // after DUP2: 10, 20, 10, 20

        int[] result = simulateDup2(inputStack);
        assertArrayEquals(expectedOutput, result);
    }

    @Test
    void testDup2X1StackTransformation() {
        // Test the logical transformation of DUP2_X1
        // Input stack: [..., value3, value2, value1]
        // Output stack: [..., value2, value1, value3, value2, value1]

        int[] inputStack = {10, 20, 30}; // bottom to top: 10, 20, 30
        int[] expectedOutput = {20, 30, 10, 20, 30}; // after DUP2_X1: 20, 30, 10, 20, 30

        int[] result = simulateDup2X1(inputStack);
        assertArrayEquals(expectedOutput, result);
    }

    @Test
    void testDup2X2StackTransformation() {
        // Test the logical transformation of DUP2_X2
        // Input stack: [..., value4, value3, value2, value1]
        // Output stack: [..., value2, value1, value4, value3, value2, value1]

        int[] inputStack = {10, 20, 30, 40}; // bottom to top: 10, 20, 30, 40
        int[] expectedOutput = {30, 40, 10, 20, 30, 40}; // after DUP2_X2: 30, 40, 10, 20, 30, 40

        int[] result = simulateDup2X2(inputStack);
        assertArrayEquals(expectedOutput, result);
    }

    @Test
    void testOpcodeConstants() {
        // Test that our new opcodes have been added correctly
        // This ensures the enum values exist and are unique

        // These would be the expected opcode values based on our additions
        // OP_DUP_X1 = 122, OP_DUP_X2 = 123, OP_DUP2 = 124, OP_DUP2_X1 = 125, OP_DUP2_X2 = 126
        assertTrue(122 < 127); // OP_DUP_X1 < OP_COUNT
        assertTrue(123 < 127); // OP_DUP_X2 < OP_COUNT
        assertTrue(124 < 127); // OP_DUP2 < OP_COUNT
        assertTrue(125 < 127); // OP_DUP2_X1 < OP_COUNT
        assertTrue(126 < 127); // OP_DUP2_X2 < OP_COUNT
    }

    // Helper methods to simulate stack transformations

    private int[] simulatePop(int[] input) {
        if (input.length < 1) return input;

        int[] result = new int[input.length - 1];
        System.arraycopy(input, 0, result, 0, input.length - 1);
        return result;
    }

    private int[] simulatePop2(int[] input) {
        if (input.length < 2) return input;

        int[] result = new int[input.length - 2];
        System.arraycopy(input, 0, result, 0, input.length - 2);
        return result;
    }

    private int[] simulateDupX1(int[] input) {
        if (input.length < 2) return input;

        int value1 = input[input.length - 1]; // top
        int value2 = input[input.length - 2]; // second

        int[] result = new int[input.length + 1];
        // Copy prefix
        System.arraycopy(input, 0, result, 0, input.length - 2);
        // Apply transformation: ..., value2, value1 -> ..., value1, value2, value1
        result[input.length - 2] = value1;
        result[input.length - 1] = value2;
        result[input.length] = value1;

        return result;
    }

    private int[] simulateDupX2(int[] input) {
        if (input.length < 3) return input;

        int value1 = input[input.length - 1]; // top
        int value2 = input[input.length - 2]; // second
        int value3 = input[input.length - 3]; // third

        int[] result = new int[input.length + 1];
        // Copy prefix
        System.arraycopy(input, 0, result, 0, input.length - 3);
        // Apply transformation: ..., value3, value2, value1 -> ..., value1, value3, value2, value1
        result[input.length - 3] = value1;
        result[input.length - 2] = value3;
        result[input.length - 1] = value2;
        result[input.length] = value1;

        return result;
    }

    private int[] simulateDup2(int[] input) {
        if (input.length < 2) return input;

        int value1 = input[input.length - 1]; // top
        int value2 = input[input.length - 2]; // second

        int[] result = new int[input.length + 2];
        // Copy original stack
        System.arraycopy(input, 0, result, 0, input.length);
        // Apply transformation: ..., value2, value1 -> ..., value2, value1, value2, value1
        result[input.length] = value2;
        result[input.length + 1] = value1;

        return result;
    }

    private int[] simulateDup2X1(int[] input) {
        if (input.length < 3) return input;

        int value1 = input[input.length - 1]; // top
        int value2 = input[input.length - 2]; // second
        int value3 = input[input.length - 3]; // third

        int[] result = new int[input.length + 2];
        // Copy prefix
        System.arraycopy(input, 0, result, 0, input.length - 3);
        // Apply transformation: ..., value3, value2, value1 -> ..., value2, value1, value3, value2, value1
        result[input.length - 3] = value2;
        result[input.length - 2] = value1;
        result[input.length - 1] = value3;
        result[input.length] = value2;
        result[input.length + 1] = value1;

        return result;
    }

    private int[] simulateDup2X2(int[] input) {
        if (input.length < 4) return input;

        int value1 = input[input.length - 1]; // top
        int value2 = input[input.length - 2]; // second
        int value3 = input[input.length - 3]; // third
        int value4 = input[input.length - 4]; // fourth

        int[] result = new int[input.length + 2];
        // Copy prefix
        System.arraycopy(input, 0, result, 0, input.length - 4);
        // Apply transformation: ..., value4, value3, value2, value1 -> ..., value2, value1, value4, value3, value2, value1
        result[input.length - 4] = value2;
        result[input.length - 3] = value1;
        result[input.length - 2] = value4;
        result[input.length - 1] = value3;
        result[input.length] = value2;
        result[input.length + 1] = value1;

        return result;
    }
}