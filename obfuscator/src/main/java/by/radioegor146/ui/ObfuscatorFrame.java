package by.radioegor146.ui;

import by.radioegor146.AntiDebugConfig;
import by.radioegor146.JavaFlowSettings;
import by.radioegor146.NativeObfuscator;
import by.radioegor146.ObfuscatorConfig;
import by.radioegor146.Platform;
import by.radioegor146.ProtectionConfig;
import by.radioegor146.javaobf.JavaObfuscationConfig;
import dev.skidfuscator.obfuscator.FlowExceptionMode;
import dev.skidfuscator.obfuscator.transform.impl.flow.FlowObfuscationMode;
import dev.skidfuscator.obfuscator.transform.impl.flow.FlowObfuscationProfile;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.HierarchyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;


public class ObfuscatorFrame extends JFrame {
    // Layout constants (tune to preference)
    private static final int LABEL_W = 180;
    private static final int FIELD_H = 30;
    private static final int BROWSE_W = 96;   // fixed column width for Browse
    private static final int HINT_MIN_W = 96;
    private static final int HINT_MAX_W = 360;
    private static final int HINT_PADDING = 12;
    private static final int ROW_H = 40;      // max row height for single-line rows

    // LEFT NAV SECTIONS
    private static final String CARD_IMPORT = "import";
    private static final String CARD_SETTINGS = "settings";
    private static final String CARD_JAVA_OBF = "java_obf";
    private static final String CARD_RUN = "run";

    // Shared controls (across cards)
    private final JTextField jarField = new JTextField();
    private final JTextField outDirField = new JTextField();
    private final JTextField libsDirField = new JTextField();
    private final JTextField blacklistField = new JTextField();
    private final JTextField whitelistField = new JTextField();
    private final JTextField plainLibNameField = new JTextField();
    private final JTextField customLibDirField = new JTextField();
    private final JComboBox<Platform> platformCombo = new JComboBox<>(Platform.values());
    private final JCheckBox useAnnotationsBox = new JCheckBox("Use annotations");
    private final JCheckBox debugJarBox = new JCheckBox("Generate debug jar");
    private final JCheckBox packageBox = new JCheckBox("Package native lib into JAR", true);

    // Protection feature checkboxes
    private final JCheckBox enableNativeObfuscationBox = new JCheckBox("Enable native obfuscation", true);
    private final JCheckBox enableVirtualizationBox = new JCheckBox("Enable VM virtualization");
    private final JCheckBox enableJitBox = new JCheckBox("Enable JIT compilation");
    private final JCheckBox flattenControlFlowBox = new JCheckBox("Enable control flow flattening");
    private final JCheckBox stringObfuscationBox = new JCheckBox("Encrypt string pool literals", true);
    private final JCheckBox constantObfuscationBox = new JCheckBox("Encrypt LDC primitive constants", true);

    // Anti-debug feature checkboxes
    private final JCheckBox enableAntiDebugBox = new JCheckBox("Enable anti-debug protection");
    private final JCheckBox gHotSpotVMStructsNullificationBox = new JCheckBox("gHotSpotVMStructs nullification");
    private final JCheckBox debuggerDetectionBox = new JCheckBox("Debugger detection");
    private final JCheckBox vmProtectionBox = new JCheckBox("VM protection");
    private final JCheckBox antiTamperBox = new JCheckBox("Anti-tamper checks");
    private final JCheckBox antiDebugApiChecksBox = new JCheckBox("API-based debugger checks", true);
    private final JCheckBox antiDebugTracerCheckBox = new JCheckBox("Tracer PID check", true);
    private final JCheckBox antiDebugPtraceCheckBox = new JCheckBox("ptrace self-test", true);
    private final JCheckBox antiDebugProcessScanBox = new JCheckBox("Debugger process scan", true);
    private final JCheckBox antiDebugModuleScanBox = new JCheckBox("Suspicious module scan", true);
    private final JCheckBox antiDebugEnvScanBox = new JCheckBox("Environment variable scan", true);
    private final JCheckBox antiDebugTimingCheckBox = new JCheckBox("Timing anomaly detection", true);
    private final JCheckBox antiDebugAgentBlockingBox = new JCheckBox("JVMTI agent blocking", true);
    private final JCheckBox antiDebugRegisterScrubBox = new JCheckBox("Hardware breakpoint scrubbing", true);
    private final JCheckBox antiDebugLoggingBox = new JCheckBox("Debug logging");

    // Java obfuscation controls
    private final JCheckBox enableJavaObfuscationBox = new JCheckBox("Enable Java-layer obfuscation");
    private final JComboBox<JavaObfuscationConfig.Strength> javaObfStrengthCombo = new JComboBox<>(JavaObfuscationConfig.Strength.values());
    private final JCheckBox javaStringObfBox = new JCheckBox("String encryption", true);
    private final JCheckBox javaNumberObfBox = new JCheckBox("Number encryption", true);
    private final JCheckBox javaFlowObfBox = new JCheckBox("Control-flow transforms", true);
    private final JCheckBox javaInvokeDynamicBox = new JCheckBox("InvokeDynamic references", false);
    private final JComboBox<FlowExceptionMode> javaFlowExceptionModeCombo = new JComboBox<>(FlowExceptionMode.values());
    private final JComboBox<FlowObfuscationMode> javaFlowModeCombo = new JComboBox<>(FlowObfuscationMode.values());
    private final JSpinner flowGuardDepthSpinner = new JSpinner(new SpinnerNumberModel(2, 0, 6, 1));
    private final JSpinner flowGuardMinSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 5, 1));
    private final JSpinner flowGuardMaxSpinner = new JSpinner(new SpinnerNumberModel(2, 1, 8, 1));
    private final JCheckBox flowOpXorCheck = new JCheckBox("XOR", true);
    private final JCheckBox flowOpAddCheck = new JCheckBox("ADD", true);
    private final JCheckBox javaSdkInjectionBox = new JCheckBox("Inject SDK runtime (experimental)", false);
    private final JCheckBox javaVmHashingBox = new JCheckBox("VM hashing (experimental)", false);
    private final JTextField javaBlacklistField = new JTextField();
    private final JTextField javaWhitelistField = new JTextField();
    private final JButton runButton = new JButton("▶ Run Obfuscation");
    private final JTextArea logArea = new JTextArea();
    private final JProgressBar progressBar = new JProgressBar();

    private final JList<String> leftNav;
    private final JPanel rightCards;

    private final Preferences prefs = Preferences.userNodeForPackage(ObfuscatorFrame.class);
    private static final String PREF_LAST_JAR_DIR = "lastJarDir";
    private static final String PREF_LAST_ANY_DIR = "lastAnyDir";

    public static void launch() {
        SwingUtilities.invokeLater(() -> {
            ObfuscatorFrame frame = new ObfuscatorFrame();
            frame.setVisible(true);
        });
    }

    public ObfuscatorFrame() {
        super("Native Obfuscator");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(980, 620));
        setLocationRelativeTo(null);

        // Set application icon
        try {
            setIconImage(createApplicationIcon());
        } catch (Exception e) {
            // Ignore if icon creation fails
        }

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(new EmptyBorder(12, 12, 12, 12));
        setContentPane(root);

        // ===== Left Sidebar =====
        leftNav = buildLeftNav();
        JScrollPane navScroll = new JScrollPane(leftNav);
        navScroll.setBorder(new EmptyBorder(0, 0, 0, 12));
        navScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        navScroll.setPreferredSize(new Dimension(160, 500));
        navScroll.setMinimumSize(new Dimension(160, 0)); // never collapse

        // ===== Right Cards =====
        rightCards = new JPanel(new CardLayout());
        rightCards.add(buildImportCard(), CARD_IMPORT);
        rightCards.add(buildSettingsCard(), CARD_SETTINGS);
        rightCards.add(buildJavaObfCard(), CARD_JAVA_OBF);
        rightCards.add(buildRunCard(), CARD_RUN);
        rightCards.setMinimumSize(new Dimension(520, 400));

        // Layout using JSplitPane for resize friendliness
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, navScroll, rightCards);
        split.setResizeWeight(0);
        split.setDividerSize(8);
        split.setContinuousLayout(true);
        split.setBorder(null);
        split.setDividerLocation(160);

        root.add(split, BorderLayout.CENTER);

        platformCombo.setSelectedItem(Platform.HOTSPOT);
        runButton.addActionListener(this::runObfuscation);

        // Feature interactions
        enableNativeObfuscationBox.addActionListener(e -> {
            boolean enabled = enableNativeObfuscationBox.isSelected();
            enableVirtualizationBox.setEnabled(enabled);
            enableJitBox.setEnabled(enabled && enableVirtualizationBox.isSelected());
            flattenControlFlowBox.setEnabled(enabled);
            stringObfuscationBox.setEnabled(enabled);
            constantObfuscationBox.setEnabled(enabled);
            packageBox.setEnabled(enabled);
            plainLibNameField.setEnabled(enabled);
            customLibDirField.setEnabled(enabled);
            if (!enabled) {
                enableVirtualizationBox.setSelected(false);
                enableJitBox.setSelected(false);
                flattenControlFlowBox.setSelected(false);
                stringObfuscationBox.setSelected(false);
                constantObfuscationBox.setSelected(false);
            } else {
                if (!stringObfuscationBox.isSelected()) stringObfuscationBox.setSelected(true);
                if (!constantObfuscationBox.isSelected()) constantObfuscationBox.setSelected(true);
            }
        });

        enableVirtualizationBox.addActionListener(e -> {
            enableJitBox.setEnabled(enableNativeObfuscationBox.isSelected() && enableVirtualizationBox.isSelected());
            if (!enableVirtualizationBox.isSelected()) enableJitBox.setSelected(false);
        });

        // Anti-debug feature interactions
        enableAntiDebugBox.addActionListener(e -> updateAntiDebugControls(true));
        debuggerDetectionBox.addActionListener(e -> updateAntiDebugControls(true));

        updateAntiDebugControls(true);

        // Placeholders (FlatLaf)
        jarField.putClientProperty("JTextComponent.placeholderText", "📦 Select input .jar");
        outDirField.putClientProperty("JTextComponent.placeholderText", "📁 Choose output directory");
        libsDirField.putClientProperty("JTextComponent.placeholderText", "📂 Optional libraries directory");
        whitelistField.putClientProperty("JTextComponent.placeholderText", "✅ Optional native whitelist.txt");
        blacklistField.putClientProperty("JTextComponent.placeholderText", "❌ Optional native blacklist.txt");
        plainLibNameField.putClientProperty("JTextComponent.placeholderText", "📝 Specify to skip packaging");
        customLibDirField.putClientProperty("JTextComponent.placeholderText", "📁 e.g. native/win64 — inside output JAR");
        javaBlacklistField.putClientProperty("JTextComponent.placeholderText", "❌ Optional Java blacklist.txt");
        javaWhitelistField.putClientProperty("JTextComponent.placeholderText", "✅ Optional Java whitelist.txt");
    }

    // ------------------------ ICON CREATION ------------------------

    /**
     * Creates a simple application icon programmatically
     */
    private Image createApplicationIcon() {
        int size = 32;
        BufferedImage icon = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = icon.createGraphics();

        // Enable anti-aliasing
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Background circle
        g2d.setColor(new Color(64, 128, 192));
        g2d.fillOval(2, 2, size-4, size-4);

        // Lock symbol
        g2d.setColor(Color.WHITE);
        g2d.setStroke(new BasicStroke(2f));

        // Lock body
        g2d.fillRoundRect(10, 16, 12, 10, 2, 2);

        // Lock shackle
        g2d.setColor(Color.WHITE);
        g2d.drawArc(12, 8, 8, 10, 0, 180);

        // Keyhole
        g2d.setColor(new Color(64, 128, 192));
        g2d.fillOval(14, 18, 4, 4);
        g2d.fillRect(15, 20, 2, 4);

        g2d.dispose();
        return icon;
    }

    // ------------------------ UI BUILDERS ------------------------

    private JList<String> buildLeftNav() {
        DefaultListModel<String> model = new DefaultListModel<>();
        model.addElement("📁 Import Files");
        model.addElement("⚙️ Native Settings");
        model.addElement("☕ Java Obfuscation");
        model.addElement("🚀 Run & Logs");

        final JList<String> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setSelectedIndex(0);
        list.setFixedCellHeight(36);
        list.setFixedCellWidth(160);
        list.setBorder(new EmptyBorder(4, 4, 4, 4));
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                switch (list.getSelectedIndex()) {
                    case 0: showCard(CARD_IMPORT); break;
                    case 1: showCard(CARD_SETTINGS); break;
                    case 2: showCard(CARD_JAVA_OBF); break;
                    case 3: showCard(CARD_RUN); break;
                    default: break;
                }
            }
        });

        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> jList, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(jList, value, index, isSelected, cellHasFocus);
                lbl.setHorizontalAlignment(SwingConstants.LEFT);
                lbl.setBorder(new EmptyBorder(6, 10, 6, 10));
                return lbl;
            }
        });
        return list;
    }

    private JPanel buildImportCard() {
        JPanel card = new JPanel(new BorderLayout());
        card.setBorder(new EmptyBorder(4, 0, 0, 0));

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        form.add(createPathRowPanel("📦 Input JAR", jarField, this::browseFileJar,
                "Select the Java archive to obfuscate"));
        form.add(Box.createRigidArea(new Dimension(0, 8)));

        form.add(createPathRowPanel("📁 Output Directory", outDirField, this::browseDir,
                "Destination folder for obfuscated files"));
        form.add(Box.createRigidArea(new Dimension(0, 8)));

        form.add(createPathRowPanel("📂 Libraries Directory", libsDirField, this::browseDirOptional,
                "Additional JAR/ZIP dependencies"));
        form.add(Box.createRigidArea(new Dimension(0, 8)));

        form.add(createPathRowPanel("❌ Blacklist File", blacklistField, () -> browseFileTxt(blacklistField),
                "Exclude classes/packages from obfuscation"));
        form.add(Box.createRigidArea(new Dimension(0, 8)));

        form.add(createPathRowPanel("✅ Whitelist File", whitelistField, () -> browseFileTxt(whitelistField),
                "Allow-only list; overrides blacklist"));



        form.add(Box.createVerticalGlue());

        card.add(form, BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton next = new JButton("Next → Native Settings ⚙");
        next.addActionListener(e -> { leftNav.setSelectedIndex(1); showCard(CARD_SETTINGS); });
        footer.add(next);
        card.add(footer, BorderLayout.SOUTH);

        return card;
    }


    private JPanel buildSettingsCard() {
        JPanel card = new JPanel(new BorderLayout());
        card.setBorder(new EmptyBorder(4, 0, 0, 0));

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        JPanel upperHintsScope = new JPanel();
        upperHintsScope.setLayout(new BoxLayout(upperHintsScope, BoxLayout.Y_AXIS));
        upperHintsScope.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Plain Library Name
        upperHintsScope.add(createFieldRowPanel("📝 Plain Library Name", plainLibNameField, "Specify to skip packaging into JAR"));
        upperHintsScope.add(Box.createRigidArea(new Dimension(0, 8)));

        // Custom Library Dir
        upperHintsScope.add(createFieldRowPanel("📁 Custom Library Dir (in jar)", customLibDirField, "e.g. native/win64 — inside output JAR"));
        upperHintsScope.add(Box.createRigidArea(new Dimension(0, 8)));

        form.add(upperHintsScope);

        // Platform only
        JPanel platformPanel = new JPanel();
        platformPanel.setLayout(new BoxLayout(platformPanel, BoxLayout.X_AXIS));
        platformPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        platformPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_H));

        JLabel platformLabel = new JLabel("💻 Platform");
        platformLabel.setPreferredSize(new Dimension(LABEL_W, FIELD_H));
        platformLabel.setAlignmentY(Component.CENTER_ALIGNMENT);
        platformLabel.setVerticalAlignment(SwingConstants.CENTER);
        platformPanel.add(platformLabel);

        platformCombo.setMaximumSize(new Dimension(200, FIELD_H));
        platformCombo.setAlignmentY(Component.CENTER_ALIGNMENT);
        platformPanel.add(platformCombo);
        platformPanel.add(Box.createHorizontalGlue());

        form.add(platformPanel);
        form.add(Box.createRigidArea(new Dimension(0, 10)));

        // Build Options
        JPanel buildOpts = new JPanel();
        buildOpts.setLayout(new BoxLayout(buildOpts, BoxLayout.Y_AXIS));
        buildOpts.setBorder(new TitledBorder("🔧 Build Options"));
        buildOpts.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel line1 = new JPanel();
        line1.setLayout(new BoxLayout(line1, BoxLayout.X_AXIS));
        line1.setAlignmentX(Component.LEFT_ALIGNMENT);
        useAnnotationsBox.setAlignmentY(Component.CENTER_ALIGNMENT);
        debugJarBox.setAlignmentY(Component.CENTER_ALIGNMENT);
        packageBox.setAlignmentY(Component.CENTER_ALIGNMENT);
        line1.add(useAnnotationsBox);
        line1.add(Box.createRigidArea(new Dimension(16, 0)));
        line1.add(debugJarBox);
        line1.add(Box.createRigidArea(new Dimension(16, 0)));
        line1.add(packageBox);
        line1.add(Box.createHorizontalGlue());
        buildOpts.add(line1);

        form.add(buildOpts);
        form.add(Box.createRigidArea(new Dimension(0, 12)));

        // Protection Features：单独 scope，单独计算 hint 宽度
        JPanel protectionPanel = new JPanel();
        protectionPanel.setLayout(new BoxLayout(protectionPanel, BoxLayout.Y_AXIS));
        protectionPanel.setBorder(new TitledBorder("🛡️ Protection Features"));
        protectionPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        protectionPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 220));

        protectionPanel.add(checkWithHint(enableNativeObfuscationBox,
                "Enable transpilation of Java methods to native C++ code"));
        protectionPanel.add(indent(checkWithHint(enableVirtualizationBox,
                "Translate selected methods to a custom VM; strongest protection"), 20));
        enableJitBox.setEnabled(false);
        protectionPanel.add(indent(checkWithHint(enableJitBox,
                "JIT for virtualized methods; improves runtime performance"), 40));
        protectionPanel.add(indent(checkWithHint(flattenControlFlowBox,
                "State-machine style CFG flattening for native methods"), 20));
        protectionPanel.add(indent(checkWithHint(stringObfuscationBox,
                "Encrypt and lazily decrypt UTF-8 literals stored in the native string pool"), 20));
        protectionPanel.add(indent(checkWithHint(constantObfuscationBox,
                "XOR-encode LDC primitives and decode them through JNI helpers"), 20));

        form.add(protectionPanel);
        form.add(Box.createRigidArea(new Dimension(0, 12)));

        // Anti-Debug Features: separate scope, separate calculations
        JPanel antiDebugPanel = new JPanel();
        antiDebugPanel.setLayout(new BoxLayout(antiDebugPanel, BoxLayout.Y_AXIS));
        antiDebugPanel.setBorder(new TitledBorder("🛡️ Anti-Debug Protection"));
        antiDebugPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        antiDebugPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 180));

        antiDebugPanel.add(checkWithHint(enableAntiDebugBox,
                "Enable comprehensive anti-debugging and reverse engineering protection"));
        antiDebugPanel.add(indent(checkWithHint(gHotSpotVMStructsNullificationBox,
                "Nullify gHotSpotVMStructs to prevent HotSpot internal access"), 20));
        antiDebugPanel.add(indent(checkWithHint(debuggerDetectionBox,
                "Detect debugger presence and trigger countermeasures"), 20));
        antiDebugPanel.add(indent(checkWithHint(antiDebugApiChecksBox,
                "Use platform APIs (IsDebuggerPresent, NtQuery) to spot attached debuggers"), 40));
        antiDebugPanel.add(indent(checkWithHint(antiDebugTracerCheckBox,
                "Check tracer PID / debug ports for debugger involvement"), 40));
        antiDebugPanel.add(indent(checkWithHint(antiDebugPtraceCheckBox,
                "Attempt self-ptrace on Unix systems to detect tracing"), 40));
        antiDebugPanel.add(indent(checkWithHint(antiDebugProcessScanBox,
                "Scan running processes for known debugger executables"), 40));
        antiDebugPanel.add(indent(checkWithHint(antiDebugModuleScanBox,
                "Inspect loaded modules for instrumentation libraries"), 40));
        antiDebugPanel.add(indent(checkWithHint(antiDebugEnvScanBox,
                "Flag debugger-related environment variables"), 40));
        antiDebugPanel.add(indent(checkWithHint(antiDebugTimingCheckBox,
                "Detect single-stepping via timing anomalies"), 40));
        antiDebugPanel.add(indent(checkWithHint(vmProtectionBox,
                "Protect VM function table from tampering"), 20));
        antiDebugPanel.add(indent(checkWithHint(antiDebugAgentBlockingBox,
                "Hook JVMTI entry points to block agent attachment"), 40));
        antiDebugPanel.add(indent(checkWithHint(antiTamperBox,
                "Detect code tampering and modification attempts"), 20));
        antiDebugPanel.add(indent(checkWithHint(antiDebugRegisterScrubBox,
                "Clear hardware breakpoints before executing sensitive code"), 20));
        antiDebugPanel.add(indent(checkWithHint(antiDebugLoggingBox,
                "Emit anti-debug status messages for troubleshooting"), 20));

        form.add(antiDebugPanel);
        form.add(Box.createVerticalGlue());

        JScrollPane sc = new JScrollPane(form);
        sc.setBorder(null);
        sc.getVerticalScrollBar().setUnitIncrement(16);
        sc.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        card.add(sc, BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton back = new JButton("← Back 📁");
        back.addActionListener(e -> { leftNav.setSelectedIndex(0); showCard(CARD_IMPORT); });
        JButton saveDefaults = new JButton("💾 Save as Defaults");
        saveDefaults.addActionListener(e -> savePreferences());
        JButton goJava = new JButton("Continue → Java Obfuscation ☕");
        goJava.addActionListener(e -> { leftNav.setSelectedIndex(2); showCard(CARD_JAVA_OBF); });
        footer.add(back);
        footer.add(saveDefaults);
        footer.add(goJava);
        card.add(footer, BorderLayout.SOUTH);

        return card;
    }

    private JPanel buildJavaObfCard() {
        JPanel card = new JPanel(new BorderLayout());
        card.setBorder(new EmptyBorder(4, 0, 0, 0));

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        // Java Obfuscation Enable/Disable
        JPanel enablePanel = new JPanel();
        enablePanel.setLayout(new BoxLayout(enablePanel, BoxLayout.Y_AXIS));
        enablePanel.setBorder(new TitledBorder("☕ Java-Layer Obfuscation"));
        enablePanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        enablePanel.add(checkWithHint(enableJavaObfuscationBox,
                "Enable control flow flattening for Java bytecode"));

        // Strength selection
        JPanel strengthPanel = new JPanel();
        strengthPanel.setLayout(new BoxLayout(strengthPanel, BoxLayout.X_AXIS));
        strengthPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        strengthPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_H));

        JLabel strengthLabel = new JLabel("Obfuscation Strength");
        strengthLabel.setPreferredSize(new Dimension(LABEL_W, FIELD_H));
        strengthLabel.setAlignmentY(Component.CENTER_ALIGNMENT);
        strengthLabel.setVerticalAlignment(SwingConstants.CENTER);
        strengthPanel.add(strengthLabel);

        javaObfStrengthCombo.setMaximumSize(new Dimension(200, FIELD_H));
        javaObfStrengthCombo.setAlignmentY(Component.CENTER_ALIGNMENT);
        javaObfStrengthCombo.setSelectedItem(JavaObfuscationConfig.Strength.MEDIUM);
        strengthPanel.add(javaObfStrengthCombo);

        // Tooltip for combo box
        javaObfStrengthCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof JavaObfuscationConfig.Strength) {
                    JavaObfuscationConfig.Strength strength = (JavaObfuscationConfig.Strength) value;
                    c.setToolTipText(strength.getDescription());
                }
                return c;
            }
        });

        strengthPanel.add(Box.createRigidArea(new Dimension(8, 0)));
        JLabel strengthHint = makeHint("Choose complexity level for state machine obfuscation");
        strengthPanel.add(strengthHint);
        strengthPanel.add(Box.createHorizontalGlue());

        enablePanel.add(Box.createRigidArea(new Dimension(0, 8)));
        enablePanel.add(strengthPanel);

        form.add(enablePanel);
        form.add(Box.createRigidArea(new Dimension(0, 12)));

        JPanel featurePanel = new JPanel();
        featurePanel.setLayout(new BoxLayout(featurePanel, BoxLayout.Y_AXIS));
        featurePanel.setBorder(new TitledBorder("Skidfuscator Stages"));
        featurePanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        featurePanel.add(checkWithHint(javaStringObfBox,
                "Encrypt literals and apply string equality hardening"));
        featurePanel.add(checkWithHint(javaNumberObfBox,
                "Scramble numeric constants and related instanceof checks"));
        featurePanel.add(checkWithHint(javaInvokeDynamicBox,
                "Hide method/field references behind encrypted invoke-dynamic stubs"));

        featurePanel.add(checkWithHint(javaFlowObfBox,
                "Apply switch flattening and bogus control-flow stages"));

        JPanel flowModePanel = new JPanel();
        flowModePanel.setLayout(new BoxLayout(flowModePanel, BoxLayout.X_AXIS));
        flowModePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        flowModePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_H));

        JLabel flowModeLabel = new JLabel("Flow exception mode");
        flowModeLabel.setPreferredSize(new Dimension(LABEL_W, FIELD_H));
        flowModeLabel.setAlignmentY(Component.CENTER_ALIGNMENT);
        flowModePanel.add(flowModeLabel);

        javaFlowExceptionModeCombo.setMaximumSize(new Dimension(200, FIELD_H));
        javaFlowExceptionModeCombo.setAlignmentY(Component.CENTER_ALIGNMENT);
        flowModePanel.add(javaFlowExceptionModeCombo);

        flowModePanel.add(Box.createRigidArea(new Dimension(8, 0)));
        flowModePanel.add(makeHint("Choose the dispatched exception used by flow transforms"));
        flowModePanel.add(Box.createHorizontalGlue());

        featurePanel.add(flowModePanel);
        featurePanel.add(buildFlowProfilePanel());
        featurePanel.add(Box.createRigidArea(new Dimension(0, 4)));
        featurePanel.add(checkWithHint(javaSdkInjectionBox,
                "Bundle Skidfuscator's runtime SDK into the output jar"));
        featurePanel.add(checkWithHint(javaVmHashingBox,
                "Use SSVM-based hashing predicates (requires extra deps)"));

        form.add(featurePanel);
        form.add(Box.createRigidArea(new Dimension(0, 12)));

        // Java Filter Files
        JPanel filterPanel = new JPanel();
        filterPanel.setLayout(new BoxLayout(filterPanel, BoxLayout.Y_AXIS));
        filterPanel.setBorder(new TitledBorder("🎯 Java Obfuscation Filtering"));
        filterPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        filterPanel.add(createPathRowPanel("❌ Java Blacklist File", javaBlacklistField, () -> browseFileTxt(javaBlacklistField),
                "Exclude classes/packages from Java obfuscation"));
        filterPanel.add(Box.createRigidArea(new Dimension(0, 8)));

        filterPanel.add(createPathRowPanel("✅ Java Whitelist File", javaWhitelistField, () -> browseFileTxt(javaWhitelistField),
                "Allow-only list for Java obfuscation; overrides blacklist"));

        form.add(filterPanel);
        form.add(Box.createVerticalGlue());

        // Enable/disable strength combo based on checkbox
        enableJavaObfuscationBox.addActionListener(e -> {
            boolean enabled = enableJavaObfuscationBox.isSelected();
            javaObfStrengthCombo.setEnabled(enabled);
            javaBlacklistField.setEnabled(enabled);
            javaWhitelistField.setEnabled(enabled);
            javaStringObfBox.setEnabled(enabled);
            javaNumberObfBox.setEnabled(enabled);
            javaFlowObfBox.setEnabled(enabled);
            javaInvokeDynamicBox.setEnabled(enabled);
            javaSdkInjectionBox.setEnabled(enabled);
            javaVmHashingBox.setEnabled(enabled);
            updateFlowControls();
        });

        javaFlowObfBox.addActionListener(e -> updateFlowControls());

        // Initially disable components
        javaObfStrengthCombo.setEnabled(false);
        javaBlacklistField.setEnabled(false);
        javaWhitelistField.setEnabled(false);
        javaStringObfBox.setEnabled(false);
        javaNumberObfBox.setEnabled(false);
        javaFlowObfBox.setEnabled(false);
        javaInvokeDynamicBox.setEnabled(false);
        javaFlowExceptionModeCombo.setEnabled(false);
        javaFlowModeCombo.setEnabled(false);
        flowGuardDepthSpinner.setEnabled(false);
        flowGuardMinSpinner.setEnabled(false);
        flowGuardMaxSpinner.setEnabled(false);
        flowOpXorCheck.setEnabled(false);
        flowOpAddCheck.setEnabled(false);
        javaSdkInjectionBox.setEnabled(false);
        javaVmHashingBox.setEnabled(false);
        updateFlowControls();

        JScrollPane sc = new JScrollPane(form);
        sc.setBorder(null);
        sc.getVerticalScrollBar().setUnitIncrement(16);
        sc.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        card.add(sc, BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton back = new JButton("← Back ⚙️");
        back.addActionListener(e -> { leftNav.setSelectedIndex(1); showCard(CARD_SETTINGS); });
        JButton goRun = new JButton("Continue → Run 🚀");
        goRun.addActionListener(e -> { leftNav.setSelectedIndex(3); showCard(CARD_RUN); });
        footer.add(back);
        footer.add(goRun);
        card.add(footer, BorderLayout.SOUTH);

        return card;
    }

    private JPanel buildRunCard() {
        JPanel card = new JPanel(new BorderLayout());
        card.setBorder(new EmptyBorder(4, 0, 0, 0));

        JPanel top = new JPanel(new BorderLayout());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(runButton);
        top.add(actions, BorderLayout.EAST);

        progressBar.setIndeterminate(true);
        progressBar.setString("⏳ Processing obfuscation...");
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);
        top.add(progressBar, BorderLayout.SOUTH);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(new TitledBorder("📜 Output Log"));

        card.add(top, BorderLayout.NORTH);
        card.add(logScroll, BorderLayout.CENTER);
        return card;
    }

    // ------------------------ Row Builders with alignment ------------------------

    private JPanel createPathRowPanel(String labelText, JTextField field, final Runnable browseAction, String hintText) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_H));

        // Label column
        JLabel label = new JLabel(labelText);
        label.setPreferredSize(new Dimension(LABEL_W, FIELD_H));
        label.setAlignmentY(Component.CENTER_ALIGNMENT);
        label.setVerticalAlignment(SwingConstants.CENTER);
        row.add(label);

        // Field column (fills)
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, FIELD_H));
        field.setPreferredSize(new Dimension(10, FIELD_H));
        field.setAlignmentY(Component.CENTER_ALIGNMENT);
        row.add(field);

        // Gap + Browse column (fixed width)
        row.add(Box.createRigidArea(new Dimension(8, 0)));
        JButton browseBtn = new JButton("📂 Browse");
        browseBtn.setPreferredSize(new Dimension(BROWSE_W, FIELD_H));
        browseBtn.setMaximumSize(new Dimension(BROWSE_W, FIELD_H));
        browseBtn.setAlignmentY(Component.CENTER_ALIGNMENT);
        browseBtn.addActionListener(e -> browseAction.run());
        row.add(browseBtn);

        // Hint column (fixed width so fields stay equal)
        row.add(Box.createRigidArea(new Dimension(8, 0)));
        JLabel hint = makeHint(hintText == null ? "" : hintText);
        row.add(hint);

        return row;
    }

    private JPanel createFieldRowPanel(String labelText, JTextField field, String hintText) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_H));

        JLabel label = new JLabel(labelText);
        label.setPreferredSize(new Dimension(LABEL_W, FIELD_H));
        label.setAlignmentY(Component.CENTER_ALIGNMENT);
        label.setVerticalAlignment(SwingConstants.CENTER);
        row.add(label);

        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, FIELD_H));
        field.setPreferredSize(new Dimension(10, FIELD_H));
        field.setAlignmentY(Component.CENTER_ALIGNMENT);
        row.add(field);

        row.add(Box.createRigidArea(new Dimension(8, 0)));

        JLabel hint = makeHint(hintText == null ? "" : hintText);
        row.add(hint);

        return row;
    }

    private JPanel createFlowRow(String labelText, JComponent field, String hintText) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_H));

        JLabel label = new JLabel(labelText);
        label.setPreferredSize(new Dimension(LABEL_W, FIELD_H));
        label.setAlignmentY(Component.CENTER_ALIGNMENT);
        row.add(label);

        field.setMaximumSize(new Dimension(220, FIELD_H));
        field.setAlignmentY(Component.CENTER_ALIGNMENT);
        row.add(field);

        row.add(Box.createRigidArea(new Dimension(8, 0)));
        row.add(makeHint(hintText));
        row.add(Box.createHorizontalGlue());

        return row;
    }

    private JPanel buildFlowProfilePanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new TitledBorder("Flow guard profile"));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        if (javaFlowModeCombo.getSelectedItem() == null) {
            javaFlowModeCombo.setSelectedItem(FlowObfuscationMode.BALANCED);
        }

        panel.add(createFlowRow("Guard mode", javaFlowModeCombo,
                "Performance=1 guard, Aggressive=deeper chains"));
        panel.add(Box.createRigidArea(new Dimension(0, 6)));
        panel.add(createFlowRow("Guard depth", flowGuardDepthSpinner,
                "How many chained guard blocks precede jumps"));

        JPanel opCountRow = new JPanel();
        opCountRow.setLayout(new BoxLayout(opCountRow, BoxLayout.X_AXIS));
        opCountRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        opCountRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_H));

        JLabel opCountLabel = new JLabel("Mutation rounds");
        opCountLabel.setPreferredSize(new Dimension(LABEL_W, FIELD_H));
        opCountLabel.setAlignmentY(Component.CENTER_ALIGNMENT);
        opCountRow.add(opCountLabel);

        JPanel spinnerGroup = new JPanel();
        spinnerGroup.setLayout(new BoxLayout(spinnerGroup, BoxLayout.X_AXIS));
        spinnerGroup.setAlignmentY(Component.CENTER_ALIGNMENT);
        spinnerGroup.add(new JLabel("Min"));
        spinnerGroup.add(Box.createRigidArea(new Dimension(4, 0)));
        flowGuardMinSpinner.setMaximumSize(new Dimension(60, FIELD_H));
        spinnerGroup.add(flowGuardMinSpinner);
        spinnerGroup.add(Box.createRigidArea(new Dimension(12, 0)));
        spinnerGroup.add(new JLabel("Max"));
        spinnerGroup.add(Box.createRigidArea(new Dimension(4, 0)));
        flowGuardMaxSpinner.setMaximumSize(new Dimension(60, FIELD_H));
        spinnerGroup.add(flowGuardMaxSpinner);
        opCountRow.add(spinnerGroup);

        opCountRow.add(Box.createRigidArea(new Dimension(8, 0)));
        opCountRow.add(makeHint("Bounds for XOR/ADD mix"));
        opCountRow.add(Box.createHorizontalGlue());

        panel.add(opCountRow);
        panel.add(Box.createRigidArea(new Dimension(0, 6)));

        JPanel opsRow = new JPanel();
        opsRow.setLayout(new BoxLayout(opsRow, BoxLayout.X_AXIS));
        opsRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        opsRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_H));
        JLabel opsLabel = new JLabel("Allowed ops");
        opsLabel.setPreferredSize(new Dimension(LABEL_W, FIELD_H));
        opsLabel.setAlignmentY(Component.CENTER_ALIGNMENT);
        opsRow.add(opsLabel);

        JPanel checkGroup = new JPanel();
        checkGroup.setLayout(new BoxLayout(checkGroup, BoxLayout.X_AXIS));
        checkGroup.setAlignmentY(Component.CENTER_ALIGNMENT);
        checkGroup.add(flowOpXorCheck);
        checkGroup.add(Box.createRigidArea(new Dimension(8, 0)));
        checkGroup.add(flowOpAddCheck);
        opsRow.add(checkGroup);

        opsRow.add(Box.createRigidArea(new Dimension(8, 0)));
        opsRow.add(makeHint("Choose arithmetic mutations for guards"));
        opsRow.add(Box.createHorizontalGlue());

        panel.add(opsRow);
        panel.add(Box.createRigidArea(new Dimension(0, 6)));
        panel.add(makeHint("Only applies when control-flow transforms are enabled."));

        ChangeListener boundsListener = e -> clampFlowOpBounds();
        flowGuardMinSpinner.addChangeListener(boundsListener);
        flowGuardMaxSpinner.addChangeListener(boundsListener);

        return panel;
    }

    /**
     * Compact, baseline-friendly hint label.
     */
    private JLabel makeHint(String text) {
        JLabel hint = new JLabel(text == null ? "" : text);
        hint.setForeground(new Color(154, 160, 166));
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 10f));
        hint.setAlignmentY(Component.CENTER_ALIGNMENT);
        hint.putClientProperty("isHint", Boolean.TRUE);

        initPerHintAutoSize(hint);
        return hint;
    }

    private void clampFlowOpBounds() {
        int min = (Integer) flowGuardMinSpinner.getValue();
        int max = (Integer) flowGuardMaxSpinner.getValue();
        if (max < min) {
            flowGuardMaxSpinner.setValue(min);
        }
    }

    private String describeFlowOps() {
        List<String> ops = new ArrayList<>();
        if (flowOpXorCheck.isSelected()) ops.add("XOR");
        if (flowOpAddCheck.isSelected()) ops.add("ADD");
        if (ops.isEmpty()) {
            ops.add("XOR");
        }
        return String.join("/", ops);
    }

    private JavaFlowSettings collectJavaFlowSettings() {
        if (!enableJavaObfuscationBox.isSelected() || !javaFlowObfBox.isSelected()) {
            return JavaFlowSettings.createDefault();
        }

        EnumSet<FlowObfuscationProfile.MutationType> operations = EnumSet.noneOf(FlowObfuscationProfile.MutationType.class);
        if (flowOpXorCheck.isSelected()) {
            operations.add(FlowObfuscationProfile.MutationType.XOR);
        }
        if (flowOpAddCheck.isSelected()) {
            operations.add(FlowObfuscationProfile.MutationType.ADD);
        }
        if (operations.isEmpty()) {
            operations.add(FlowObfuscationProfile.MutationType.XOR);
        }

        return JavaFlowSettings.builder()
                .setMode((FlowObfuscationMode) javaFlowModeCombo.getSelectedItem())
                .setGuardDepth((Integer) flowGuardDepthSpinner.getValue())
                .setGuardOpsMin((Integer) flowGuardMinSpinner.getValue())
                .setGuardOpsMax((Integer) flowGuardMaxSpinner.getValue())
                .setOperations(operations)
                .build();
    }



    private int measureHintWidth(JLabel lbl) {
        FontMetrics fm = lbl.getFontMetrics(lbl.getFont());
        int w = fm.stringWidth(lbl.getText());
        Insets in = lbl.getInsets();
        if (in != null) w += in.left + in.right;
        w += HINT_PADDING;
        return Math.max(HINT_MIN_W, Math.min(HINT_MAX_W, w));
    }

    private void sizeAndLockHint(JLabel hint) {
        int w = measureHintWidth(hint);
        Dimension d = new Dimension(w, 18);
        hint.setPreferredSize(d);
        hint.setMaximumSize(d);
    }

    private void initPerHintAutoSize(final JLabel hint) {
        sizeAndLockHint(hint);

        hint.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && hint.isShowing()) {
                SwingUtilities.invokeLater(() -> sizeAndLockHint(hint));
            }
        });

        hint.addPropertyChangeListener(new java.beans.PropertyChangeListener() {
            @Override public void propertyChange(java.beans.PropertyChangeEvent evt) {
                String n = evt.getPropertyName();
                if ("text".equals(n) || "font".equals(n)) {
                    sizeAndLockHint(hint);
                }
            }
        });
    }
    private JPanel checkWithHint(JCheckBox box, String hintText) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

        box.setAlignmentY(Component.CENTER_ALIGNMENT);
        row.add(box);
        row.add(Box.createRigidArea(new Dimension(8, 0)));

        JLabel hintLabel = makeHint(hintText);
        hintLabel.setAlignmentY(Component.CENTER_ALIGNMENT);
        row.add(hintLabel);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private JPanel indent(JComponent comp, int px) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(Box.createRigidArea(new Dimension(px, 0)));
        p.add(comp);
        return p;
    }

    private void showCard(String name) {
        ((CardLayout) rightCards.getLayout()).show(rightCards, name);
    }

    // ------------------------ Pickers ------------------------

    private File getExistingDir(String path) {
        if (path == null || path.trim().isEmpty()) return null;
        File f = new File(path.trim());
        if (f.isFile()) f = f.getParentFile();
        return (f != null && f.isDirectory()) ? f : null;
    }

    private File guessStartDirForJarChooser() {
        File byJar = getExistingDir(jarField.getText());
        if (byJar != null) return byJar;
        File byOut = getExistingDir(outDirField.getText());
        if (byOut != null) return byOut;
        File byLibs = getExistingDir(libsDirField.getText());
        if (byLibs != null) return byLibs;
        String lastJarDir = prefs.get(PREF_LAST_JAR_DIR, null);
        File byPrefJar = getExistingDir(lastJarDir);
        if (byPrefJar != null) return byPrefJar;
        String lastAnyDir = prefs.get(PREF_LAST_ANY_DIR, null);
        File byPrefAny = getExistingDir(lastAnyDir);
        if (byPrefAny != null) return byPrefAny;
        if (isWindows()) {
            String[] candidates = {"D:\\", "E:\\", "F:\\", "C:\\"};
            for (String candidate : candidates) {
                File root = new File(candidate);
                if (root.exists() && root.isDirectory()) return root;
            }
        }
        return new File(System.getProperty("user.home"));
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private void browseFileJar() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select input JAR");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        File startDir = guessStartDirForJarChooser();
        if (startDir.isDirectory()) chooser.setCurrentDirectory(startDir);
        chooser.setAcceptAllFileFilterUsed(true);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Java Archives (*.jar)", "jar"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            jarField.setText(f.getAbsolutePath());
            File parent = f.getParentFile();
            if (parent != null) {
                prefs.put(PREF_LAST_JAR_DIR, parent.getAbsolutePath());
                prefs.put(PREF_LAST_ANY_DIR, parent.getAbsolutePath());
            }
            if (outDirField.getText().trim().isEmpty() && parent != null) {
                outDirField.setText(new File(parent, "native-output").getAbsolutePath());
            }
        }
    }

    private void browseDir() {
        File dir = openDirectoryDialogSwing();
        if (dir != null) outDirField.setText(dir.getAbsolutePath());
    }

    private void browseDirOptional() {
        File dir = openDirectoryDialogSwing();
        if (dir != null) libsDirField.setText(dir.getAbsolutePath());
    }

    private void browseFileTxt(JTextField targetField) {
        File f = openNativeFileDialog(this, new String[]{".txt"});
        if (f != null) targetField.setText(f.getAbsolutePath());
    }

    private static File openNativeFileDialog(Frame parent, String[] extensions) {
        FileDialog fd = new FileDialog(parent, "Select text file", FileDialog.LOAD);
        if (extensions != null && extensions.length > 0) {
            fd.setFilenameFilter((dir, name) -> {
                String lower = name.toLowerCase();
                for (String ext : extensions) {
                    if (lower.endsWith(ext)) return true;
                }
                return false;
            });
        }
        fd.setVisible(true);
        if (fd.getFile() == null) return null;
        return new File(fd.getDirectory(), fd.getFile());
    }

    private File openDirectoryDialogSwing() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select directory");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        int ret = chooser.showOpenDialog(this);
        if (ret == JFileChooser.APPROVE_OPTION) return chooser.getSelectedFile();
        return null;
    }

    // ------------------------ Actions ------------------------

    private void runObfuscation(ActionEvent e) {
        String jarPath = jarField.getText().trim();
        String outDir = outDirField.getText().trim();
        if (jarPath.isEmpty() || outDir.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select input JAR and output directory.",
                    "Missing inputs", JOptionPane.WARNING_MESSAGE);
            leftNav.setSelectedIndex(0);
            showCard(CARD_IMPORT);
            return;
        }
        File jarFile = new File(jarPath);
        if (!jarFile.isFile()) {
            JOptionPane.showMessageDialog(this, "Input JAR does not exist.",
                    "Invalid input", JOptionPane.ERROR_MESSAGE);
            leftNav.setSelectedIndex(0);
            showCard(CARD_IMPORT);
            return;
        }

        leftNav.setSelectedIndex(2);
        showCard(CARD_RUN);
        setFormEnabled(false);
        progressBar.setVisible(true);
        appendLog("🚀 Starting obfuscation...\n");

        SwingWorker<Integer, String> worker = new SwingWorker<Integer, String>() {
            @Override protected Integer doInBackground() throws Exception {
                List<Path> libs = new ArrayList<Path>();
                String libsDir = libsDirField.getText().trim();
                if (!libsDir.isEmpty()) {
                    Path start = Paths.get(libsDir);
                    if (!Files.isDirectory(start)) throw new IOException("Libraries directory not found: " + libsDir);
                    Files.walk(start, FileVisitOption.FOLLOW_LINKS)
                            .filter(p -> {
                                String s = p.toString().toLowerCase();
                                return s.endsWith(".jar") || s.endsWith(".zip");
                            })
                            .forEach(libs::add);
                }
                List<String> blackList = new ArrayList<>();
                String blk = blacklistField.getText().trim();
                if (!blk.isEmpty())
                    blackList = Files.readAllLines(Paths.get(blk), StandardCharsets.UTF_8).stream()
                            .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
                List<String> whiteList = null;
                String wht = whitelistField.getText().trim();
                if (!wht.isEmpty())
                    whiteList = Files.readAllLines(Paths.get(wht), StandardCharsets.UTF_8).stream()
                            .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());

                String plainName = emptyToNull(plainLibNameField.getText());
                String customDir = emptyToNull(customLibDirField.getText());
                Platform platform = (Platform) platformCombo.getSelectedItem();
                boolean useAnnotations = useAnnotationsBox.isSelected();
                boolean debug = debugJarBox.isSelected();
                boolean enableNativeObfuscation = enableNativeObfuscationBox.isSelected();
                boolean enableVirtualization = enableNativeObfuscation && enableVirtualizationBox.isSelected();
                boolean enableJit = enableNativeObfuscation && enableVirtualization && enableJitBox.isSelected();
                boolean flattenControlFlow = enableNativeObfuscation && flattenControlFlowBox.isSelected();
                boolean stringObfuscation = enableNativeObfuscation && stringObfuscationBox.isSelected();
                boolean constantObfuscation = enableNativeObfuscation && constantObfuscationBox.isSelected();

                // Anti-debug settings
                boolean enableAntiDebug = enableAntiDebugBox.isSelected();
                boolean enableGHotSpotVMStructsNullification = enableAntiDebug && gHotSpotVMStructsNullificationBox.isSelected();
                boolean enableDebuggerDetection = enableAntiDebug && debuggerDetectionBox.isSelected();
                boolean enableVmProtection = enableAntiDebug && vmProtectionBox.isSelected();
                boolean enableAntiTamper = enableAntiDebug && antiTamperBox.isSelected();

                boolean enableAntiDebugApiChecks = enableAntiDebug && enableDebuggerDetection && antiDebugApiChecksBox.isSelected();
                boolean enableAntiDebugTracerCheck = enableAntiDebug && enableDebuggerDetection && antiDebugTracerCheckBox.isSelected();
                boolean enableAntiDebugPtraceCheck = enableAntiDebug && enableDebuggerDetection && antiDebugPtraceCheckBox.isSelected();
                boolean enableAntiDebugProcessScan = enableAntiDebug && enableDebuggerDetection && antiDebugProcessScanBox.isSelected();
                boolean enableAntiDebugModuleScan = enableAntiDebug && enableDebuggerDetection && antiDebugModuleScanBox.isSelected();
                boolean enableAntiDebugEnvScan = enableAntiDebug && enableDebuggerDetection && antiDebugEnvScanBox.isSelected();
                boolean enableAntiDebugTimingCheck = enableAntiDebug && enableDebuggerDetection && antiDebugTimingCheckBox.isSelected();
                boolean enableAntiDebugAgentBlocking = enableAntiDebug && antiDebugAgentBlockingBox.isSelected();
                boolean enableAntiDebugRegisterScrub = enableAntiDebug && antiDebugRegisterScrubBox.isSelected();
                boolean enableAntiDebugLogging = enableAntiDebug && antiDebugLoggingBox.isSelected();

                // Java obfuscation settings
                boolean enableJavaObfuscation = enableJavaObfuscationBox.isSelected();
                String javaObfStrength = javaObfStrengthCombo.getSelectedItem().toString();
                List<String> javaBlackList = new ArrayList<>();
                List<String> javaWhiteList = new ArrayList<>();

                String javaBlackListPath = emptyToNull(javaBlacklistField.getText());
                if (javaBlackListPath != null) {
                    javaBlackList = Files.readAllLines(Paths.get(javaBlackListPath), StandardCharsets.UTF_8).stream()
                            .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
                }

                String javaWhiteListPath = emptyToNull(javaWhitelistField.getText());
                if (javaWhiteListPath != null) {
                    javaWhiteList = Files.readAllLines(Paths.get(javaWhiteListPath), StandardCharsets.UTF_8).stream()
                            .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
                }

                publish("🛡️ Protection settings:");
                publish("  🔧 Native Obfuscation: " + (enableNativeObfuscation ? "✅ Enabled" : "❌ Disabled"));
                if (enableNativeObfuscation) {
                    publish("  🖥️ VM Virtualization: " + (enableVirtualization ? "✅ Enabled" : "❌ Disabled"));
                    if (enableVirtualization)
                        publish("  ⚡ JIT Compilation: " + (enableJit ? "✅ Enabled" : "❌ Disabled"));
                    publish("  🌀 Native Control Flow Flattening: " + (flattenControlFlow ? "✅ Enabled" : "❌ Disabled"));
                    publish("  🔐 String Encryption: " + (stringObfuscation ? "✅ Enabled" : "❌ Disabled"));
                    publish("  🔢 Constant Encryption: " + (constantObfuscation ? "✅ Enabled" : "❌ Disabled"));
                }
                publish("  ☕ Java Obfuscation: " + (enableJavaObfuscation ? "✅ Enabled (" + javaObfStrength + ")" : "❌ Disabled"));
                if (enableJavaObfuscation) {
                    publish("    • String encryption: " + (javaStringObfBox.isSelected() ? "✅ On" : "❌ Off"));
                    publish("    • Number encryption: " + (javaNumberObfBox.isSelected() ? "✅ On" : "❌ Off"));
                    publish("    • Control-flow transforms: " + (javaFlowObfBox.isSelected() ? "✅ On" : "❌ Off"));
                    if (javaFlowObfBox.isSelected()) {
                        publish("      ↳ Exception mode: " + javaFlowExceptionModeCombo.getSelectedItem());
                        publish(String.format("      ↳ Guard mode: %s, depth=%s",
                                javaFlowModeCombo.getSelectedItem(), flowGuardDepthSpinner.getValue()));
                        publish(String.format("      ↳ Mutations: %s–%s × %s",
                                flowGuardMinSpinner.getValue(),
                                flowGuardMaxSpinner.getValue(),
                                describeFlowOps()));
                    }
                    publish("    • InvokeDynamic references: " + (javaInvokeDynamicBox.isSelected() ? "✅ On" : "❌ Off"));
                    publish("    • SDK injection: " + (javaSdkInjectionBox.isSelected() ? "✅ On" : "❌ Off"));
                    publish("    • VM hashing: " + (javaVmHashingBox.isSelected() ? "✅ On" : "❌ Off"));
                }
                publish("  🛡️ Anti-Debug Protection: " + (enableAntiDebug ? "✅ Enabled" : "❌ Disabled"));
                if (enableAntiDebug) {
                    publish("    🔒 gHotSpotVMStructs Nullification: " + (enableGHotSpotVMStructsNullification ? "✅ Enabled" : "❌ Disabled"));
                    publish("    🔍 Debugger Detection: " + (enableDebuggerDetection ? "✅ Enabled" : "❌ Disabled"));
                    if (enableDebuggerDetection) {
                        publish("      • API checks: " + (enableAntiDebugApiChecks ? "✅ On" : "❌ Off"));
                        publish("      • Tracer PID: " + (enableAntiDebugTracerCheck ? "✅ On" : "❌ Off"));
                        publish("      • ptrace self-test: " + (enableAntiDebugPtraceCheck ? "✅ On" : "❌ Off"));
                        publish("      • Process scan: " + (enableAntiDebugProcessScan ? "✅ On" : "❌ Off"));
                        publish("      • Module scan: " + (enableAntiDebugModuleScan ? "✅ On" : "❌ Off"));
                        publish("      • Env scan: " + (enableAntiDebugEnvScan ? "✅ On" : "❌ Off"));
                        publish("      • Timing checks: " + (enableAntiDebugTimingCheck ? "✅ On" : "❌ Off"));
                    }
                    publish("    🛡️ VM Protection: " + (enableVmProtection ? "✅ Enabled" : "❌ Disabled"));
                    publish("      • JVMTI agent blocking: " + (enableAntiDebugAgentBlocking ? "✅ On" : "❌ Off"));
                    publish("    🔐 Anti-Tamper: " + (enableAntiTamper ? "✅ Enabled" : "❌ Disabled"));
                    publish("    🧹 Hardware breakpoint scrub: " + (enableAntiDebugRegisterScrub ? "✅ Enabled" : "❌ Disabled"));
                    publish("    📝 Debug logging: " + (enableAntiDebugLogging ? "✅ Enabled" : "❌ Disabled"));
                }
                publish("");

                // Create protection configuration
                ProtectionConfig protectionConfig = new ProtectionConfig(enableVirtualization, enableJit, flattenControlFlow,
                        stringObfuscation, constantObfuscation);

                // Create anti-debug configuration
                AntiDebugConfig.Builder antiDebugBuilder = new AntiDebugConfig.Builder()
                        .setGHotSpotVMStructsNullification(enableGHotSpotVMStructsNullification)
                        .setDebuggerDetection(enableDebuggerDetection)
                        .setDebuggerApiChecks(enableAntiDebugApiChecks)
                        .setDebuggerTracerCheck(enableAntiDebugTracerCheck)
                        .setDebuggerPtraceCheck(enableAntiDebugPtraceCheck)
                        .setDebuggerProcessScan(enableAntiDebugProcessScan)
                        .setDebuggerModuleScan(enableAntiDebugModuleScan)
                        .setDebuggerEnvironmentScan(enableAntiDebugEnvScan)
                        .setDebuggerTimingCheck(enableAntiDebugTimingCheck)
                        .setVmProtectionEnabled(enableVmProtection)
                        .setJvmtiAgentBlocking(enableAntiDebugAgentBlocking)
                        .setAntiTamperEnabled(enableAntiTamper)
                        .setDebugRegisterScrubbing(enableAntiDebugRegisterScrub)
                        .setDebugLoggingEnabled(enableAntiDebugLogging);

                AntiDebugConfig antiDebugConfig = antiDebugBuilder.build();

                // Create comprehensive configuration
                ObfuscatorConfig config = new ObfuscatorConfig.Builder()
                        .setInputJarPath(jarFile.toPath())
                        .setOutputDir(Paths.get(outDir))
                        .setInputLibs(libs)
                        .setBlackList(blackList)
                        .setWhiteList(whiteList)
                        .setPlainLibName(plainName)
                        .setCustomLibraryDirectory(customDir)
                        .setPlatform(platform)
                        .setUseAnnotations(useAnnotations)
                        .setGenerateDebugJar(debug)
                        .setProtectionConfig(protectionConfig)
                        .setAntiDebugConfig(antiDebugConfig)
                        .setEnableJavaObfuscation(enableJavaObfuscation)
                        .setJavaObfuscationStrength(javaObfStrength)
                        .setJavaBlackList(javaBlackList)
                        .setJavaWhiteList(javaWhiteList)
                        .setEnableNativeObfuscation(enableNativeObfuscation)
                    .setSkidStringObfuscation(javaStringObfBox.isSelected())
                    .setSkidNumberObfuscation(javaNumberObfBox.isSelected())
                    .setSkidFlowObfuscation(javaFlowObfBox.isSelected())
                    .setSkidInvokeDynamicObfuscation(javaInvokeDynamicBox.isSelected())
                    .setSkidFlowExceptionMode((FlowExceptionMode) javaFlowExceptionModeCombo.getSelectedItem())
                    .setSkidSdkInjection(javaSdkInjectionBox.isSelected())
                    .setSkidVmHashing(javaVmHashingBox.isSelected())
                    .setJavaFlowSettings(collectJavaFlowSettings())
                        .build();

                // Validate configuration
                config.validateAndWarn();

                NativeObfuscator obfuscator = new NativeObfuscator();
                obfuscator.process(config);

                if (enableNativeObfuscation && plainName == null && packageBox.isSelected()) {
                    Path cppDir = Paths.get(outDir, "cpp");
                    runCmakeAndPackage(cppDir, new File(outDir, jarFile.getName()).toPath(), obfuscator.getNativeDir());
                } else if (enableNativeObfuscation && plainName != null) {
                    appendLog("📝 Plain library mode selected; skipping jar packaging.\n");
                } else if (!enableNativeObfuscation) {
                    appendLog("🔧 Native obfuscation disabled; no C++ compilation needed.\n");
                } else {
                    appendLog("❌ Packaging disabled by user.\n");
                }
                return 0;
            }

            @Override protected void process(java.util.List<String> chunks) {
                for (String chunk : chunks) appendLog(chunk + "\n");
            }

            @Override protected void done() {
                try {
                    get();
                    appendLog("✅ Done. Output at: " + outDir + "\n");
                    JOptionPane.showMessageDialog(ObfuscatorFrame.this, "✅ Obfuscation completed successfully!", "✨ Success", JOptionPane.INFORMATION_MESSAGE);
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    appendLogError(cause);
                    JOptionPane.showMessageDialog(ObfuscatorFrame.this, "❌ " + (cause.getMessage() == null ? cause.toString() : cause.getMessage()), "⚠️ Error", JOptionPane.ERROR_MESSAGE);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    setFormEnabled(true);
                    progressBar.setVisible(false);
                }
            }
        };
        worker.execute();
    }

    // ------------------------ Utils ------------------------

    private static String emptyToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private void setFormEnabled(boolean enabled) {
        leftNav.setEnabled(enabled);
        jarField.setEnabled(enabled);
        outDirField.setEnabled(enabled);
        libsDirField.setEnabled(enabled);
        blacklistField.setEnabled(enabled);
        whitelistField.setEnabled(enabled);
        plainLibNameField.setEnabled(enabled);
        customLibDirField.setEnabled(enabled);
        platformCombo.setEnabled(enabled);
        useAnnotationsBox.setEnabled(enabled);
        debugJarBox.setEnabled(enabled);
        packageBox.setEnabled(enabled && enableNativeObfuscationBox.isSelected());
        enableNativeObfuscationBox.setEnabled(enabled);
        enableVirtualizationBox.setEnabled(enabled && enableNativeObfuscationBox.isSelected());
        enableJitBox.setEnabled(enabled && enableNativeObfuscationBox.isSelected() && enableVirtualizationBox.isSelected());
        flattenControlFlowBox.setEnabled(enabled && enableNativeObfuscationBox.isSelected());
        stringObfuscationBox.setEnabled(enabled && enableNativeObfuscationBox.isSelected());
        constantObfuscationBox.setEnabled(enabled && enableNativeObfuscationBox.isSelected());

        // Java obfuscation controls
        enableJavaObfuscationBox.setEnabled(enabled);
        javaObfStrengthCombo.setEnabled(enabled && enableJavaObfuscationBox.isSelected());
        javaBlacklistField.setEnabled(enabled && enableJavaObfuscationBox.isSelected());
        javaWhitelistField.setEnabled(enabled && enableJavaObfuscationBox.isSelected());
        javaStringObfBox.setEnabled(enabled && enableJavaObfuscationBox.isSelected());
        javaNumberObfBox.setEnabled(enabled && enableJavaObfuscationBox.isSelected());
        javaFlowObfBox.setEnabled(enabled && enableJavaObfuscationBox.isSelected());
        javaInvokeDynamicBox.setEnabled(enabled && enableJavaObfuscationBox.isSelected());
        javaSdkInjectionBox.setEnabled(enabled && enableJavaObfuscationBox.isSelected());
        javaVmHashingBox.setEnabled(enabled && enableJavaObfuscationBox.isSelected());
        updateFlowControls();

        // Anti-debug controls
        enableAntiDebugBox.setEnabled(enabled);
        updateAntiDebugControls(enabled);

        runButton.setEnabled(enabled);
    }

    private void updateFlowControls() {
        boolean flowControlsEnabled = enableJavaObfuscationBox.isEnabled()
                && enableJavaObfuscationBox.isSelected()
                && javaFlowObfBox.isSelected();

        javaFlowExceptionModeCombo.setEnabled(flowControlsEnabled);
        javaFlowModeCombo.setEnabled(flowControlsEnabled);
        flowGuardDepthSpinner.setEnabled(flowControlsEnabled);
        flowGuardMinSpinner.setEnabled(flowControlsEnabled);
        flowGuardMaxSpinner.setEnabled(flowControlsEnabled);
        flowOpXorCheck.setEnabled(flowControlsEnabled);
        flowOpAddCheck.setEnabled(flowControlsEnabled);
    }
    private void updateAntiDebugControls(boolean formEnabled) {
        boolean antiDebugSelected = enableAntiDebugBox.isSelected();
        boolean antiDebugEnabled = formEnabled && antiDebugSelected;

        gHotSpotVMStructsNullificationBox.setEnabled(antiDebugEnabled);
        debuggerDetectionBox.setEnabled(antiDebugEnabled);
        vmProtectionBox.setEnabled(antiDebugEnabled);
        antiTamperBox.setEnabled(antiDebugEnabled);

        if (!antiDebugSelected) {
            gHotSpotVMStructsNullificationBox.setSelected(false);
            debuggerDetectionBox.setSelected(false);
            vmProtectionBox.setSelected(false);
            antiTamperBox.setSelected(false);
            antiDebugApiChecksBox.setSelected(true);
            antiDebugTracerCheckBox.setSelected(true);
            antiDebugPtraceCheckBox.setSelected(true);
            antiDebugProcessScanBox.setSelected(true);
            antiDebugModuleScanBox.setSelected(true);
            antiDebugEnvScanBox.setSelected(true);
            antiDebugTimingCheckBox.setSelected(true);
            antiDebugAgentBlockingBox.setSelected(true);
            antiDebugRegisterScrubBox.setSelected(true);
            antiDebugLoggingBox.setSelected(false);
        }

        boolean detectionEnabled = antiDebugEnabled && debuggerDetectionBox.isSelected();
        JCheckBox[] detectionBoxes = {
                antiDebugApiChecksBox,
                antiDebugTracerCheckBox,
                antiDebugPtraceCheckBox,
                antiDebugProcessScanBox,
                antiDebugModuleScanBox,
                antiDebugEnvScanBox,
                antiDebugTimingCheckBox
        };
        for (JCheckBox box : detectionBoxes) {
            box.setEnabled(detectionEnabled);
        }

        antiDebugAgentBlockingBox.setEnabled(antiDebugEnabled);
        antiDebugRegisterScrubBox.setEnabled(antiDebugEnabled);
        antiDebugLoggingBox.setEnabled(antiDebugEnabled);
    }

    private void updateAntiDebugControls() {
        updateAntiDebugControls(true);
    }


    private void appendLog(String text) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(text);
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void appendLogError(Throwable t) {
        String msg = (t.getMessage() == null) ? t.toString() : t.getMessage();
        StringBuilder sb = new StringBuilder();
        sb.append("❌ ERROR: ").append(msg).append('\n');
        for (StackTraceElement el : t.getStackTrace()) sb.append("    at ").append(el).append('\n');
        appendLog(sb.toString());
    }

    private void savePreferences() {
        prefs.put(PREF_LAST_JAR_DIR, new File(jarField.getText().trim()).getParent());
        prefs.put(PREF_LAST_ANY_DIR, new File(outDirField.getText().trim()).getParent());
        JOptionPane.showMessageDialog(this, "💾 Defaults saved successfully!");
    }

    private void runCmakeAndPackage(Path cppDir, Path outJar, String nativeDir) throws IOException, InterruptedException {
        if (!Files.isDirectory(cppDir)) throw new IOException("C++ output directory not found: " + cppDir);
        appendLog("\n🔧 Configuring CMake...\n");
        runProcess(new String[]{"cmake", "."}, cppDir.toFile());
        appendLog("\n🔨 Building native library (Release)...\n");
        runProcess(new String[]{"cmake", "--build", ".", "--config", "Release"}, cppDir.toFile());
        Path libDir = cppDir.resolve("build").resolve("lib");
        if (!Files.isDirectory(libDir)) throw new IOException("Native lib dir not found: " + libDir);
        File libFile = Files.list(libDir).filter(p -> {
            String n = p.getFileName().toString().toLowerCase();
            return n.endsWith(".dll") || n.endsWith(".so") || n.endsWith(".dylib");
        }).map(Path::toFile).findFirst().orElse(null);
        if (libFile == null) throw new IOException("No native library (.dll/.so/.dylib) found in " + libDir);
        if (!Files.exists(outJar)) throw new IOException("Output JAR not found: " + outJar);
        String arch = System.getProperty("os.arch").toLowerCase();
        String os = System.getProperty("os.name").toLowerCase();
        String entryPath = getEntryPath(nativeDir, arch, os);
        appendLog("\n📦 Packaging native lib into jar at /" + entryPath + "...\n");
        packageIntoJar(outJar, libFile.toPath(), entryPath);
        appendLog("✅ Packaging completed.\n");
    }

    private static String getEntryPath(String nativeDir, String arch, String os) {
        String platformTypeName;
        if ("x86_64".equals(arch) || "amd64".equals(arch)) platformTypeName = "x64";
        else if ("aarch64".equals(arch)) platformTypeName = "arm64";
        else if ("x86".equals(arch)) platformTypeName = "x86";
        else platformTypeName = arch.startsWith("arm") ? "arm32" : ("raw" + arch);
        String osTypeName = os.contains("win") ? "windows.dll" : (os.contains("mac") ? "macos.dylib" : "linux.so");
        return nativeDir + "/" + platformTypeName + "-" + osTypeName;
    }

    private void runProcess(String[] cmd, File workDir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        final Process p = pb.start();
        Thread t = new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) appendLog(line + "\n");
            } catch (IOException ignored) { }
        });
        t.setDaemon(true); t.start();
        int code = p.waitFor();
        if (code != 0) throw new IOException("Command failed (" + String.join(" ", cmd) + ") with exit code " + code);
    }

    private void packageIntoJar(Path jarPath, Path fileToAdd, String entryPath) throws IOException {
        java.net.URI uri = java.net.URI.create("jar:" + jarPath.toUri());
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put("create", "false");
        try (java.nio.file.FileSystem fs = java.nio.file.FileSystems.newFileSystem(uri, env)) {
            Path inside = fs.getPath("/" + entryPath);
            if (inside.getParent() != null) Files.createDirectories(inside.getParent());
            Files.copy(fileToAdd, inside, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
