package by.radioegor146;

import org.objectweb.asm.Label;

import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class LabelPool {

    private final WeakHashMap<Label, Integer> labels = new WeakHashMap<>();
    private final Set<Integer> usedStates = new HashSet<>();

    private int generateKey() {
        int key;
        do {
            key = ThreadLocalRandom.current().nextInt();
        } while (usedStates.contains(key));
        usedStates.add(key);
        return key;
    }

    public void setState(Label label, int state) {
        labels.put(label, state);
        usedStates.add(state);
    }

    public int generateStandaloneState() {
        return generateKey();
    }

    public String getName(Label label) {
        return String.valueOf(labels.computeIfAbsent(label, l -> generateKey()));
    }
}
