package dev.skidfuscator.obfuscator.renamer.generator;

import java.util.ArrayList;
import java.util.List;

public final class RadixNameGenerator implements NameGenerator {
    private final List<String> alphabet;
    private final List<Integer> state;

    public RadixNameGenerator(List<String> alphabet, int minLength) {
        if (alphabet == null || alphabet.isEmpty()) {
            throw new IllegalArgumentException("Alphabet must not be empty");
        }
        this.alphabet = new ArrayList<>(alphabet);
        int length = Math.max(1, minLength);
        this.state = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            this.state.add(0);
        }
    }

    @Override
    public synchronized String next() {
        StringBuilder builder = new StringBuilder();
        for (int index : state) {
            builder.append(alphabet.get(index));
        }
        String result = builder.toString();
        increment();
        return result;
    }

    private void increment() {
        for (int position = state.size() - 1; position >= 0; position--) {
            int value = state.get(position) + 1;
            if (value < alphabet.size()) {
                state.set(position, value);
                for (int i = position + 1; i < state.size(); i++) {
                    state.set(i, 0);
                }
                return;
            }
        }
        state.add(0, 0);
        for (int i = 1; i < state.size(); i++) {
            state.set(i, 0);
        }
    }
}
