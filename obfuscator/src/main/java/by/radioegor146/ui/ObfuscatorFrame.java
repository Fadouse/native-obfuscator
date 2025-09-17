package by.radioegor146.ui;

import by.radioegor146.NativeObfuscator;
import by.radioegor146.Platform;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
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
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;


public class ObfuscatorFrame extends JFrame {
    // Layout constants (tune to preference)
    private static final int LABEL_W = 180;
    private static final int FIELD_H = 30;
    private static final int BROWSE_W = 96;   // fixed column width for Browse
    private static final int ROW_H = 40;      // max row height for single-line rows

    // LEFT NAV SECTIONS
    private static final String CARD_IMPORT = "import";
    private static final String CARD_SETTINGS = "settings";
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
    private final JCheckBox packageBox = new JCheckBox("Package native library into JAR", true);

    // Protection feature checkboxes
    private final JCheckBox enableVirtualizationBox = new JCheckBox("Enable VM virtualization");
    private final JCheckBox enableJitBox = new JCheckBox("Enable JIT compilation");
    private final JCheckBox flattenControlFlowBox = new JCheckBox("Enable control flow flattening");
    private final JButton runButton = new JButton("Run obfuscation");
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
        enableVirtualizationBox.addActionListener(e -> {
            enableJitBox.setEnabled(enableVirtualizationBox.isSelected());
            if (!enableVirtualizationBox.isSelected()) enableJitBox.setSelected(false);
        });

        // Placeholders (FlatLaf)
        jarField.putClientProperty("JTextComponent.placeholderText", "Select input .jar");
        outDirField.putClientProperty("JTextComponent.placeholderText", "Choose output directory");
        libsDirField.putClientProperty("JTextComponent.placeholderText", "Optional libraries directory");
        whitelistField.putClientProperty("JTextComponent.placeholderText", "Optional whitelist.txt");
        blacklistField.putClientProperty("JTextComponent.placeholderText", "Optional blacklist.txt");
        plainLibNameField.putClientProperty("JTextComponent.placeholderText", "Specify to skip packaging");
        customLibDirField.putClientProperty("JTextComponent.placeholderText", "Example: native/win64 inside output JAR");
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
        model.addElement("Import files");
        model.addElement("Native settings");
        model.addElement("Run & logs");

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
                    case 2: showCard(CARD_RUN); break;
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

        form.add(createPathRowPanel("Input JAR", jarField, this::browseFileJar,
                "Select the Java archive to obfuscate"));
        form.add(Box.createRigidArea(new Dimension(0, 8)));

        form.add(createPathRowPanel("Output directory", outDirField, this::browseDir,
                "Destination folder for obfuscated files"));
        form.add(Box.createRigidArea(new Dimension(0, 8)));

        form.add(createPathRowPanel("Libraries directory", libsDirField, this::browseDirOptional,
                "Additional JAR/ZIP dependencies"));
        form.add(Box.createRigidArea(new Dimension(0, 8)));

        form.add(createPathRowPanel("Blacklist file", blacklistField, () -> browseFileTxt(blacklistField),
                "Exclude classes/packages from obfuscation"));
        form.add(Box.createRigidArea(new Dimension(0, 8)));

        form.add(createPathRowPanel("Whitelist file", whitelistField, () -> browseFileTxt(whitelistField),
                "Allow-only list; overrides blacklist"));



        form.add(Box.createVerticalGlue());

        card.add(form, BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton next = new JButton("Next → Settings");
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
        upperHintsScope.add(createFieldRowPanel("Plain library name", plainLibNameField, "Specify to skip packaging into JAR"));
        upperHintsScope.add(Box.createRigidArea(new Dimension(0, 8)));

        // Custom Library Dir
        upperHintsScope.add(createFieldRowPanel("Custom library directory (in jar)", customLibDirField, "e.g. native/win64 — inside output JAR"));
        upperHintsScope.add(Box.createRigidArea(new Dimension(0, 8)));

        form.add(upperHintsScope);

        // Platform only
        JPanel platformPanel = new JPanel();
        platformPanel.setLayout(new BoxLayout(platformPanel, BoxLayout.X_AXIS));
        platformPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        platformPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_H));

        JLabel platformLabel = new JLabel("Platform");
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
        buildOpts.setBorder(new TitledBorder("Build options"));
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
        protectionPanel.setBorder(new TitledBorder("Protection features"));
        protectionPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        protectionPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));

        protectionPanel.add(checkWithHint(enableVirtualizationBox,
                "Translate selected methods to a custom VM; strongest protection"));
        enableJitBox.setEnabled(false);
        protectionPanel.add(indent(checkWithHint(enableJitBox,
                "JIT for virtualized methods; improves runtime performance"), 20));
        protectionPanel.add(checkWithHint(flattenControlFlowBox,
                "State-machine style CFG flattening for native methods"));

        form.add(protectionPanel);
        form.add(Box.createVerticalGlue());

        JScrollPane sc = new JScrollPane(form);
        sc.setBorder(null);
        sc.getVerticalScrollBar().setUnitIncrement(16);
        sc.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        card.add(sc, BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton back = new JButton("← Back to import");
        back.addActionListener(e -> { leftNav.setSelectedIndex(0); showCard(CARD_IMPORT); });
        JButton saveDefaults = new JButton("Save as defaults");
        saveDefaults.addActionListener(e -> savePreferences());
        JButton goRun = new JButton("Continue → Run");
        goRun.addActionListener(e -> { leftNav.setSelectedIndex(2); showCard(CARD_RUN); });
        footer.add(back);
        footer.add(saveDefaults);
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
        progressBar.setString("Processing obfuscation...");
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);
        top.add(progressBar, BorderLayout.SOUTH);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(new TitledBorder("Output log"));

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
        JButton browseBtn = new JButton("Browse");
        browseBtn.setPreferredSize(new Dimension(BROWSE_W, FIELD_H));
        browseBtn.setMaximumSize(new Dimension(BROWSE_W, FIELD_H));
        browseBtn.setAlignmentY(Component.CENTER_ALIGNMENT);
        browseBtn.addActionListener(e -> browseAction.run());
        row.add(browseBtn);

        // Hint column (auto-sized per content)
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



    private void updateHintSize(JLabel hint) {
        Dimension preferred = hint.getPreferredSize();
        hint.setPreferredSize(preferred);
        hint.setMaximumSize(new Dimension(preferred.width, preferred.height));
    }

    private void initPerHintAutoSize(final JLabel hint) {
        updateHintSize(hint);

        hint.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && hint.isShowing()) {
                SwingUtilities.invokeLater(() -> updateHintSize(hint));
            }
        });

        hint.addPropertyChangeListener(evt -> {
            String name = evt.getPropertyName();
            if ("text".equals(name) || "font".equals(name)) {
                updateHintSize(hint);
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
        appendLog("Starting obfuscation...\n");

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
                boolean enableVirtualization = enableVirtualizationBox.isSelected();
                boolean enableJit = enableJitBox.isSelected();
                boolean flattenControlFlow = flattenControlFlowBox.isSelected();

                publish("Protection settings:");
                publish("  VM virtualization: " + (enableVirtualization ? "enabled" : "disabled"));
                if (enableVirtualization)
                    publish("  JIT compilation: " + (enableJit ? "enabled" : "disabled"));
                publish("  Control flow flattening: " + (flattenControlFlow ? "enabled" : "disabled"));
                publish("");

                NativeObfuscator obfuscator = new NativeObfuscator();
                Path dir = Paths.get(outDir);
                obfuscator.process(
                        jarFile.toPath(), dir, libs, blackList, whiteList,
                        plainName, customDir, platform, useAnnotations, debug,
                        enableVirtualization, enableJit, flattenControlFlow);

                if (plainName == null && packageBox.isSelected()) {
                    Path cppDir = Paths.get(outDir, "cpp");
                    runCmakeAndPackage(cppDir, new File(outDir, jarFile.getName()).toPath(), obfuscator.getNativeDir());
                } else if (plainName != null) {
                    appendLog("Plain library mode selected; skipping jar packaging.\n");
                } else {
                    appendLog("Packaging disabled by user.\n");
                }
                return 0;
            }

            @Override protected void process(java.util.List<String> chunks) {
                for (String chunk : chunks) appendLog(chunk + "\n");
            }

            @Override protected void done() {
                try {
                    get();
                    appendLog("Done. Output at: " + outDir + "\n");
                    JOptionPane.showMessageDialog(ObfuscatorFrame.this, "Obfuscation completed successfully.", "Success", JOptionPane.INFORMATION_MESSAGE);
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    appendLogError(cause);
                    JOptionPane.showMessageDialog(ObfuscatorFrame.this, (cause.getMessage() == null ? cause.toString() : cause.getMessage()), "Error", JOptionPane.ERROR_MESSAGE);
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
        packageBox.setEnabled(enabled);
        enableVirtualizationBox.setEnabled(enabled);
        enableJitBox.setEnabled(enabled && enableVirtualizationBox.isSelected());
        flattenControlFlowBox.setEnabled(enabled);
        runButton.setEnabled(enabled);
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
        sb.append("ERROR: ").append(msg).append('\n');
        for (StackTraceElement el : t.getStackTrace()) sb.append("    at ").append(el).append('\n');
        appendLog(sb.toString());
    }

    private void savePreferences() {
        prefs.put(PREF_LAST_JAR_DIR, new File(jarField.getText().trim()).getParent());
        prefs.put(PREF_LAST_ANY_DIR, new File(outDirField.getText().trim()).getParent());
        JOptionPane.showMessageDialog(this, "Defaults saved successfully.");
    }

    private void runCmakeAndPackage(Path cppDir, Path outJar, String nativeDir) throws IOException, InterruptedException {
        if (!Files.isDirectory(cppDir)) throw new IOException("C++ output directory not found: " + cppDir);
        appendLog("\nConfiguring CMake...\n");
        runProcess(new String[]{"cmake", "."}, cppDir.toFile());
        appendLog("\nBuilding native library (Release)...\n");
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
        appendLog("\nPackaging native library into jar at /" + entryPath + "...\n");
        packageIntoJar(outJar, libFile.toPath(), entryPath);
        appendLog("Packaging completed.\n");
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
