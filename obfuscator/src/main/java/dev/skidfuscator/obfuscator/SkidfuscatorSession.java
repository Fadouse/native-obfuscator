package dev.skidfuscator.obfuscator;

import dev.skidfuscator.jvm.Jvm;
import lombok.Builder;

import java.io.File;

/**
 * The Skidfuscator session object to be able to configure a session
 * with the obfuscator.
 */
@Builder
public class SkidfuscatorSession {
    private File input;
    private File output;
    private File[] libs;
    private File mappings;
    private File exempt;
    private File config;
    private File runtime;
    @Builder.Default private boolean phantom = false;
    @Builder.Default private boolean jmod = false;
    @Builder.Default private boolean fuckit = false;
    @Builder.Default private boolean analytics = false;
    @Builder.Default private boolean renamer = false;
    @Builder.Default private boolean c2j = false;

    @Builder.Default private boolean lowCon = false;
    @Builder.Default private boolean dex = false;
    @Builder.Default private boolean debug = false;
    @Builder.Default private boolean skidStringObfuscation = true;
    @Builder.Default private boolean skidNumberObfuscation = true;
    @Builder.Default private boolean skidFlowObfuscation = true;
    @Builder.Default private boolean skidSdkInjection = false;
    @Builder.Default private boolean skidVmHashing = false;
    @Builder.Default private FlowExceptionMode flowExceptionMode = FlowExceptionMode.STANDARD;

    /**
     *
     * @return the input
     */
    public File getInput() {
        return input;
    }

    public void setInput(File input) {
        this.input = input;
    }

    /**
     * @return the output
     */
    public File getOutput() {
        return output;
    }

    /**
     * @return the libs
     */
    public File[] getLibs() {
        return libs;
    }

    /**
     * @return the mappings file
     */
    public File getMappings() {
        return mappings;
    }

    /**
     * @return the config file
     */
    public File getConfig() {
        return config;
    }

    /**
     * @return the exempt
     */
    public File getExempt() {
        return exempt;
    }

    /**
     * @return the runtime
     */
    public File getRuntime() {
        if (runtime == null) {
            final String home = System.getProperty("java.home");
            return new File(
                    home,
                    Jvm.getJavaVersion() > 8
                            ? "jmods"
                            : "lib/rt.jar"
            );
        }
        return runtime;
    }

    /**
     * @return the boolean whether the execution uses JPhantom
     */
    public boolean isPhantom() {
        return phantom;
    }

    /**
     * @return the boolean whether the runtime lib is in JMod format
     */
    public boolean isJmod() {
        if (runtime == null)
            return Jvm.isJmod();

        return jmod;
    }

    /**
     * @return  the bool of whether the person is mentally ill and
     *          is willing to skip the forced phantom generation
     */
    public boolean isFuckIt() {
        return fuckit;
    }

    public boolean isAnalytics() {
        return analytics;
    }

    public boolean isDex() {
        return dex;
    }

    public boolean isDebug() {
        return debug;
    }

    public boolean isSkidStringObfuscationEnabled() {
        return skidStringObfuscation;
    }

    public boolean isSkidNumberObfuscationEnabled() {
        return skidNumberObfuscation;
    }

    public boolean isSkidFlowObfuscationEnabled() {
        return skidFlowObfuscation;
    }

    public boolean isSkidSdkInjectionEnabled() {
        return skidSdkInjection;
    }

    public boolean isSkidVmHashingEnabled() {
        return skidVmHashing;
    }

    public FlowExceptionMode getFlowExceptionMode() {
        return flowExceptionMode;
    }
}
