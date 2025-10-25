package dev.skidfuscator.obfuscator.transform.impl.reference;

import com.typesafe.config.Config;
import dev.skidfuscator.config.DefaultTransformerConfig;

public class ReferenceConfig extends DefaultTransformerConfig {
    public ReferenceConfig(Config config, String path) {
        super(config, path);
    }

    public String getRuntimeOwner() {
        return this.getString("runtimeOwner", "");
    }

    public int getMaxPerMethod() {
        return this.getInt("maxPerMethod", 24);
    }

    public int getRuntimeCopies() {
        return Math.max(1, this.getInt("runtimeCopies", 3));
    }

    public double getProbability() {
        double probability = this.getDouble("probability", 1.0d);
        if (probability < 0.0d) {
            return 0.0d;
        }
        if (probability > 1.0d) {
            return 1.0d;
        }
        return probability;
    }

    public boolean isEraseArgumentTypes() {
        return this.getBoolean("eraseArgumentTypes", false);
    }

    public boolean isEraseReturnTypes() {
        return this.getBoolean("eraseReturnTypes", false);
    }
}
