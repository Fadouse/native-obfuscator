package by.radioegor146.ui;

import by.radioegor146.NativeObfuscator;
import by.radioegor146.Platform;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.FilenameFilter;
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
    private final JCheckBox packageBox = new JCheckBox("Package native lib into JAR", true);
    private final JButton runButton = new JButton("Run");
    private final JTextArea logArea = new JTextArea();
    private final JProgressBar progressBar = new JProgressBar();

    private final JList<String> leftNav;
    private final JPanel rightCards;

    private final Preferences prefs = Preferences.userNodeForPackage(ObfuscatorFrame.class);
    private static final String PREF_LAST_JAR_DIR  = "lastJarDir";
    private static final String PREF_LAST_ANY_DIR  = "lastAnyDir";

    public static void launch() {
        SwingUtilities.invokeLater(() -> {
            ObfuscatorFrame frame = new ObfuscatorFrame();
            frame.setVisible(true);
        });
    }

    public ObfuscatorFrame() {
        super("Native Obfuscator – GUI");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(980, 620));
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(new EmptyBorder(12, 12, 12, 12));
        setContentPane(root);

        // ===== Left Sidebar (reserved for future expansion) =====
        leftNav = buildLeftNav();
        JScrollPane navScroll = new JScrollPane(leftNav);
        navScroll.setBorder(new EmptyBorder(0, 0, 0, 12));
        navScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        navScroll.setPreferredSize(new Dimension(180, 500));

        // ===== Right Cards =====
        rightCards = new JPanel(new CardLayout());
        rightCards.add(buildImportCard(), CARD_IMPORT);
        rightCards.add(buildSettingsCard(), CARD_SETTINGS);
        rightCards.add(buildRunCard(), CARD_RUN);

        // Layout using JSplitPane for resize friendliness
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, navScroll, rightCards);
        split.setResizeWeight(0);
        split.setDividerSize(8);
        split.setContinuousLayout(true);
        split.setBorder(null);

        root.add(split, BorderLayout.CENTER);

        platformCombo.setSelectedItem(Platform.HOTSPOT);
        runButton.addActionListener(this::runObfuscation);

        // Placeholders (FlatLaf text fields support)
        jarField.putClientProperty("JTextComponent.placeholderText", "Select input .jar");
        outDirField.putClientProperty("JTextComponent.placeholderText", "Choose output directory");
        libsDirField.putClientProperty("JTextComponent.placeholderText", "Optional libraries directory");
        whitelistField.putClientProperty("JTextComponent.placeholderText", "Optional whitelist.txt");
        blacklistField.putClientProperty("JTextComponent.placeholderText", "Optional blacklist.txt");
        plainLibNameField.putClientProperty("JTextComponent.placeholderText", "Plain lib name (optional)");
        customLibDirField.putClientProperty("JTextComponent.placeholderText", "Custom lib dir inside jar (optional)");
    }

    // ------------------------ UI BUILDERS ------------------------

    private JList<String> buildLeftNav() {
        DefaultListModel<String> model = new DefaultListModel<>();
        model.addElement("Import Files");
        model.addElement("Native Settings");
        model.addElement("Run & Logs");

        JList<String> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setSelectedIndex(0);
        list.setFixedCellHeight(36);
        list.setBorder(new EmptyBorder(4, 4, 4, 4));
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                switch (list.getSelectedIndex()) {
                    case 0:
                        showCard(CARD_IMPORT);
                        break;
                    case 1:
                        showCard(CARD_SETTINGS);
                        break;
                    case 2:
                        showCard(CARD_RUN);
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
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 6, 6, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 0;
        int row = 0;

        row = addPathRow(form, c, row, "Input JAR", jarField, this::browseFileJar);
        row = addPathRow(form, c, row, "Output Directory", outDirField, this::browseDir);
        row = addPathRow(form, c, row, "Libraries Directory", libsDirField, this::browseDirOptional);
        row = addPathRow(form, c, row, "Whitelist File", whitelistField, () -> browseFileTxt(whitelistField));
        row = addPathRow(form, c, row, "Blacklist File", blacklistField, () -> browseFileTxt(blacklistField));

        card.add(form, BorderLayout.NORTH);
        card.add(buildHintPanel("Select your input/output and optional allow/deny lists. " +
                "Native file dialogs are used for better OS-level search and navigation."), BorderLayout.SOUTH);
        return card;
    }

    private JPanel buildSettingsCard() {
        JPanel card = new JPanel(new BorderLayout());
        card.setBorder(new EmptyBorder(4, 0, 0, 0));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 6, 6, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 0;
        int row = 0;

        row = addFieldRow(form, c, row, "Plain Library Name", plainLibNameField);
        row = addFieldRow(form, c, row, "Custom Library Dir (in jar)", customLibDirField);

        // Platform + flags
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        c.gridwidth = 1;
        form.add(new JLabel("Platform"), c);
        c.gridx = 1;
        c.gridy = row;
        c.weightx = 1;
        form.add(platformCombo, c);
        JPanel flags = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        flags.add(useAnnotationsBox);
        flags.add(debugJarBox);
        flags.add(packageBox);
        c.gridx = 2;
        c.gridy = row;
        c.weightx = 0;
        form.add(flags, c);
        row++;

        card.add(form, BorderLayout.NORTH);
        card.add(buildHintPanel("Configure native obfuscation options and platform. " +
                "Use annotations and debug jar are optional."), BorderLayout.SOUTH);
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
        progressBar.setString("Running...");
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);
        top.add(progressBar, BorderLayout.SOUTH);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);

        card.add(top, BorderLayout.NORTH);
        card.add(logScroll, BorderLayout.CENTER);
        card.add(buildHintPanel("Start obfuscation here. Build logs will appear below. " +
                "Native library packaging can be enabled in Settings."), BorderLayout.SOUTH);
        return card;
    }

    private JPanel buildHintPanel(String text) {
        JPanel pnl = new JPanel(new BorderLayout());
        JLabel lbl = new JLabel(text);
        lbl.setBorder(new EmptyBorder(8, 6, 6, 6));
        pnl.add(lbl, BorderLayout.CENTER);
        return pnl;
    }

    private void showCard(String name) {
        CardLayout cl = (CardLayout) rightCards.getLayout();
        cl.show(rightCards, name);
    }

    // ------------------------ Row Helpers ------------------------

    private int addPathRow(JPanel form, GridBagConstraints c, int row, String label, JTextField field, Runnable browse) {
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        c.gridwidth = 1;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.gridy = row;
        c.weightx = 1;
        form.add(field, c);
        JButton btn = new JButton("Browse");
        btn.addActionListener(e -> browse.run());
        c.gridx = 2;
        c.gridy = row;
        c.weightx = 0;
        form.add(btn, c);
        return row + 1;
    }

    private int addFieldRow(JPanel form, GridBagConstraints c, int row, String label, JTextField field) {
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        c.gridwidth = 1;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.gridy = row;
        c.weightx = 1;
        c.gridwidth = 2;
        form.add(field, c);
        c.gridwidth = 1;
        return row + 1;
    }

    // ------------------------ Native/System Pickers ------------------------

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
            for (String p : candidates) {
                File root = new File(p);
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
        if (startDir.isDirectory()) {
            chooser.setCurrentDirectory(startDir);
        }

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
        if (dir != null) {
            outDirField.setText(dir.getAbsolutePath());
        }
    }

    private void browseDirOptional() {
        File dir = openDirectoryDialogSwing();
        if (dir != null) {
            libsDirField.setText(dir.getAbsolutePath());
        }
    }

    private void browseFileTxt(JTextField targetField) {
        File f = openNativeFileDialog(this, "Select text file", new String[]{".txt"});
        if (f != null) {
            targetField.setText(f.getAbsolutePath());
        }
    }

    /**
     * Native file dialog (AWT FileDialog) for files to leverage OS search / look & feel.
     *
     * @param parent     parent frame
     * @param title      dialog title
     * @param extensions allowed extensions (lowercase, with dot), or null for all
     */
    private static File openNativeFileDialog(Frame parent, String title, String[] extensions) {
        FileDialog fd = new FileDialog(parent, title, FileDialog.LOAD);
        if (extensions != null && extensions.length > 0) {
            fd.setFilenameFilter(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    String lower = name.toLowerCase();
                    for (String ext : extensions) {
                        if (lower.endsWith(ext)) return true;
                    }
                    return false;
                }
            });
        }
        fd.setVisible(true);
        if (fd.getFile() == null) return null;
        return new File(fd.getDirectory(), fd.getFile());
    }

    /**
     * Directory chooser (JFileChooser with DIRECTORIES_ONLY).
     * JFileChooser is used for folders as AWT FileDialog has limited directory-only support.
     */
    private File openDirectoryDialogSwing() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select directory");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        int ret = chooser.showOpenDialog(this);
        if (ret == JFileChooser.APPROVE_OPTION) {
            return chooser.getSelectedFile();
        }
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

        showCard(CARD_RUN);
        leftNav.setSelectedIndex(2);
        setFormEnabled(false);
        progressBar.setVisible(true);
        appendLog("Starting obfuscation...\n");

        SwingWorker<Integer, String> worker = new SwingWorker<Integer, String>() {
            @Override
            protected Integer doInBackground() throws Exception {
                List<Path> libs = new ArrayList<>();
                String libsDir = libsDirField.getText().trim();
                if (!libsDir.isEmpty()) {
                    Path start = Paths.get(libsDir);
                    if (!Files.isDirectory(start)) {
                        throw new IOException("Libraries directory not found: " + libsDir);
                    }

                    Files.walk(start, FileVisitOption.FOLLOW_LINKS)
                            .filter(p -> {
                                String s = p.toString().toLowerCase();
                                return s.endsWith(".jar") || s.endsWith(".zip");
                            })
                            .forEach(libs::add);
                }

                List<String> blackList = new ArrayList<>();
                String blk = blacklistField.getText().trim();
                if (!blk.isEmpty()) {
                    blackList = Files.readAllLines(Paths.get(blk), StandardCharsets.UTF_8)
                            .stream().map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
                }

                List<String> whiteList = null;
                String wht = whitelistField.getText().trim();
                if (!wht.isEmpty()) {
                    whiteList = Files.readAllLines(Paths.get(wht), StandardCharsets.UTF_8)
                            .stream().map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
                }

                String plainName = emptyToNull(plainLibNameField.getText());
                String customDir = emptyToNull(customLibDirField.getText());
                Platform platform = (Platform) platformCombo.getSelectedItem();
                boolean useAnnotations = useAnnotationsBox.isSelected();
                boolean debug = debugJarBox.isSelected();

                NativeObfuscator obfuscator = new NativeObfuscator();
                obfuscator.process(
                        jarFile.toPath(),
                        Paths.get(outDir),
                        libs,
                        blackList,
                        whiteList,
                        plainName,
                        customDir,
                        platform,
                        useAnnotations,
                        debug
                );

                // After obfuscation, automatically run cmake and package the built library into the jar
                if (plainName == null && packageBox.isSelected()) {
                    Path cppDir = Paths.get(outDir, "cpp");
                    runCmakeAndPackage(cppDir, Paths.get(outDir),
                            new File(outDir, jarFile.getName()).toPath(), obfuscator.getNativeDir());
                } else if (plainName != null) {
                    appendLog("Plain library mode selected; skipping jar packaging.\n");
                } else {
                    appendLog("Packaging disabled by user.\n");
                }
                return 0;
            }

            @Override
            protected void done() {
                try {
                    get();
                    appendLog("Done. Output at: " + outDir + "\n");
                    JOptionPane.showMessageDialog(ObfuscatorFrame.this,
                            "Obfuscation completed.", "Success", JOptionPane.INFORMATION_MESSAGE);
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    appendLogError(cause);
                    JOptionPane.showMessageDialog(ObfuscatorFrame.this,
                            cause.getMessage() == null ? cause.toString() : cause.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
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
        for (StackTraceElement el : t.getStackTrace()) {
            sb.append("    at ").append(el).append('\n');
        }
        appendLog(sb.toString());
    }

    private void runCmakeAndPackage(Path cppDir, Path outDir, Path outJar, String nativeDir) throws IOException, InterruptedException {
        if (!Files.isDirectory(cppDir)) {
            throw new IOException("C++ output directory not found: " + cppDir);
        }

        appendLog("\nConfiguring CMake...\n");
        runProcess(new String[]{"cmake", "."}, cppDir.toFile());

        appendLog("\nBuilding native library (Release)...\n");
        runProcess(new String[]{"cmake", "--build", ".", "--config", "Release"}, cppDir.toFile());

        Path libDir = cppDir.resolve("build").resolve("lib");
        if (!Files.isDirectory(libDir)) {
            throw new IOException("Native lib dir not found: " + libDir);
        }

        File libFile = Files.list(libDir)
                .filter(p -> {
                    String n = p.getFileName().toString().toLowerCase();
                    return n.endsWith(".dll") || n.endsWith(".so") || n.endsWith(".dylib");
                })
                .map(Path::toFile)
                .findFirst()
                .orElse(null);
        if (libFile == null) {
            throw new IOException("No native library (.dll/.so/.dylib) found in " + libDir);
        }

        if (!Files.exists(outJar)) {
            throw new IOException("Output JAR not found: " + outJar);
        }

        String arch = System.getProperty("os.arch").toLowerCase();
        String os = System.getProperty("os.name").toLowerCase();
        String entryPath = getEntryPath(nativeDir, arch, os);

        appendLog("\nPackaging native lib into jar at /" + entryPath + "...\n");
        packageIntoJar(outJar, libFile.toPath(), entryPath);
        appendLog("Packaging completed.\n");
    }

    private static String getEntryPath(String nativeDir, String arch, String os) {
        String platformTypeName;
        switch (arch) {
            case "x86_64":
            case "amd64":
                platformTypeName = "x64";
                break;
            case "aarch64":
                platformTypeName = "arm64";
                break;
            case "x86":
                platformTypeName = "x86";
                break;
            default:
                platformTypeName = arch.startsWith("arm") ? "arm32" : ("raw" + arch);
        }
        String osTypeName = (os.contains("win")) ? "windows.dll" : (os.contains("mac") ? "macos.dylib" : "linux.so");
        return nativeDir + "/" + platformTypeName + "-" + osTypeName;
    }

    private void runProcess(String[] cmd, File workDir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        Thread t = new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    appendLog(line + "\n");
                }
            } catch (IOException ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("Command failed (" + String.join(" ", cmd) + ") with exit code " + code);
        }
    }

    private void packageIntoJar(Path jarPath, Path fileToAdd, String entryPath) throws IOException {
        java.net.URI uri = java.net.URI.create("jar:" + jarPath.toUri().toString());
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put("create", "false");
        try (java.nio.file.FileSystem fs = java.nio.file.FileSystems.newFileSystem(uri, env)) {
            Path inside = fs.getPath("/" + entryPath);
            if (inside.getParent() != null) {
                Files.createDirectories(inside.getParent());
            }
            Files.copy(fileToAdd, inside, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
