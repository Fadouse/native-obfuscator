package by.radioegor146.ui;

import by.radioegor146.NativeObfuscator;
import by.radioegor146.Platform;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class ObfuscatorFrame extends JFrame {
    // UI Colors
    private static final Color SIDEBAR_BG = new Color(45, 45, 48);
    private static final Color MAIN_BG = new Color(30, 30, 30);
    private static final Color CARD_BG = new Color(37, 37, 38);
    private static final Color ACCENT_COLOR = new Color(0, 122, 204);
    private static final Color TEXT_PRIMARY = new Color(220, 220, 220);
    private static final Color TEXT_SECONDARY = new Color(160, 160, 160);
    private static final Color BORDER_COLOR = new Color(60, 60, 60);
    private static final Color SUCCESS_COLOR = new Color(87, 166, 74);
    private static final Color ERROR_COLOR = new Color(232, 17, 35);

    // Form fields
    private final JTextField jarField = createStyledTextField();
    private final JTextField outDirField = createStyledTextField();
    private final JTextField libsDirField = createStyledTextField();
    private final JTextField blacklistField = createStyledTextField();
    private final JTextField whitelistField = createStyledTextField();
    private final JTextField plainLibNameField = createStyledTextField();
    private final JTextField customLibDirField = createStyledTextField();
    private final JComboBox<Platform> platformCombo = createStyledComboBox();
    private final JCheckBox useAnnotationsBox = createStyledCheckBox("Use annotations");
    private final JCheckBox debugJarBox = createStyledCheckBox("Generate debug jar");
    private final JCheckBox packageBox = createStyledCheckBox("Package native lib into JAR");
    private final JButton runButton = createPrimaryButton("Run Obfuscation");
    private final JTextArea logArea = createStyledTextArea();
    private final JProgressBar progressBar = createStyledProgressBar();
    private final JLabel statusLabel = new JLabel("Ready");

    // Sidebar components
    private JPanel sidebarPanel;
    private JButton nativeTabButton;
    private JButton javaTabButton;
    private JPanel cardPanel;
    private CardLayout cardLayout;

    // File chooser cache
    private File lastSelectedDirectory = null;

    public static void launch() {
        // Set system look and feel or FlatLaf if available
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            // If using FlatLaf, uncomment:
            // FlatDarkLaf.setup();
        } catch (Exception e) {
            e.printStackTrace();
        }

        SwingUtilities.invokeLater(() -> {
            ObfuscatorFrame frame = new ObfuscatorFrame();
            frame.setVisible(true);
        });
    }

    public ObfuscatorFrame() {
        super("Native Obfuscator");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1200, 700));
        setPreferredSize(new Dimension(1200, 700));
        setLocationRelativeTo(null);

        // Set dark theme for frame
        setBackground(MAIN_BG);

        initializeUI();
        setupLayout();
        setupEventHandlers();

        // Set default values
        platformCombo.setSelectedItem(Platform.HOTSPOT);
        packageBox.setSelected(true);
        updateStatus("Ready", TEXT_SECONDARY);
    }

    private void initializeUI() {
        // Create main container
        JPanel mainContainer = new JPanel(new BorderLayout(0, 0));
        mainContainer.setBackground(MAIN_BG);
        setContentPane(mainContainer);

        // Create sidebar
        createSidebar();
        mainContainer.add(sidebarPanel, BorderLayout.WEST);

        // Create main content area with cards
        cardLayout = new CardLayout();
        cardPanel = new JPanel(cardLayout);
        cardPanel.setBackground(MAIN_BG);

        // Add Native Obfuscation panel
        JPanel nativePanel = createNativeObfuscationPanel();
        cardPanel.add(nativePanel, "native");

        // Add Java Obfuscation placeholder panel
        JPanel javaPanel = createJavaObfuscationPanel();
        cardPanel.add(javaPanel, "java");

        mainContainer.add(cardPanel, BorderLayout.CENTER);

        // Show native panel by default
        cardLayout.show(cardPanel, "native");
        nativeTabButton.setBackground(ACCENT_COLOR);
    }

    private void createSidebar() {
        sidebarPanel = new JPanel();
        sidebarPanel.setLayout(new BoxLayout(sidebarPanel, BoxLayout.Y_AXIS));
        sidebarPanel.setBackground(SIDEBAR_BG);
        sidebarPanel.setPreferredSize(new Dimension(250, 0));
        sidebarPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, BORDER_COLOR));

        // Logo/Title area
        JPanel logoPanel = new JPanel();
        logoPanel.setBackground(SIDEBAR_BG);
        logoPanel.setLayout(new FlowLayout(FlowLayout.CENTER, 0, 20));
        logoPanel.setMaximumSize(new Dimension(250, 80));

        JLabel logoLabel = new JLabel("OBFUSCATOR");
        logoLabel.setFont(new Font("Segoe UI", Font.BOLD, 20));
        logoLabel.setForeground(TEXT_PRIMARY);
        logoPanel.add(logoLabel);

        sidebarPanel.add(logoPanel);
        sidebarPanel.add(Box.createRigidArea(new Dimension(0, 20)));

        // Navigation buttons
        nativeTabButton = createSidebarButton("Native Obfuscation", "native");
        javaTabButton = createSidebarButton("Java Obfuscation", "java");

        sidebarPanel.add(nativeTabButton);
        sidebarPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        sidebarPanel.add(javaTabButton);

        // Add separator
        sidebarPanel.add(Box.createRigidArea(new Dimension(0, 30)));
        JSeparator separator = new JSeparator();
        separator.setMaximumSize(new Dimension(200, 1));
        separator.setForeground(BORDER_COLOR);
        sidebarPanel.add(separator);

        // Add info panel
        JPanel infoPanel = new JPanel();
        infoPanel.setBackground(SIDEBAR_BG);
        infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
        infoPanel.setBorder(new EmptyBorder(20, 20, 20, 20));
        infoPanel.setMaximumSize(new Dimension(250, 200));

        JLabel infoTitle = new JLabel("Quick Info");
        infoTitle.setFont(new Font("Segoe UI", Font.BOLD, 14));
        infoTitle.setForeground(TEXT_PRIMARY);
        infoTitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        JTextArea infoText = new JTextArea();
        infoText.setText("Transform your Java bytecode into native code for enhanced protection against reverse engineering.");
        infoText.setWrapStyleWord(true);
        infoText.setLineWrap(true);
        infoText.setOpaque(false);
        infoText.setEditable(false);
        infoText.setForeground(TEXT_SECONDARY);
        infoText.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        infoText.setAlignmentX(Component.LEFT_ALIGNMENT);

        infoPanel.add(infoTitle);
        infoPanel.add(Box.createRigidArea(new Dimension(0, 10)));
        infoPanel.add(infoText);

        sidebarPanel.add(infoPanel);
        sidebarPanel.add(Box.createVerticalGlue());

        // Add status at bottom
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 10));
        statusPanel.setBackground(SIDEBAR_BG);
        statusPanel.setMaximumSize(new Dimension(250, 40));
        statusLabel.setForeground(TEXT_SECONDARY);
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        statusPanel.add(statusLabel);
        sidebarPanel.add(statusPanel);
    }

    private JButton createSidebarButton(String text, String cardName) {
        JButton button = new JButton(text);
        button.setMaximumSize(new Dimension(220, 40));
        button.setAlignmentX(Component.CENTER_ALIGNMENT);
        button.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        button.setForeground(TEXT_PRIMARY);
        button.setBackground(SIDEBAR_BG);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));

        button.addActionListener(e -> {
            // Reset all buttons
            nativeTabButton.setBackground(SIDEBAR_BG);
            javaTabButton.setBackground(SIDEBAR_BG);

            // Highlight selected
            button.setBackground(ACCENT_COLOR);

            // Show corresponding panel
            cardLayout.show(cardPanel, cardName);
        });

        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (button.getBackground() != ACCENT_COLOR) {
                    button.setBackground(new Color(60, 60, 63));
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (button.getBackground() != ACCENT_COLOR) {
                    button.setBackground(SIDEBAR_BG);
                }
            }
        });

        return button;
    }

    private JPanel createNativeObfuscationPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 0));
        panel.setBackground(MAIN_BG);
        panel.setBorder(new EmptyBorder(20, 20, 20, 20));

        // Header
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(MAIN_BG);
        headerPanel.setBorder(new EmptyBorder(0, 0, 20, 0));

        JLabel titleLabel = new JLabel("Native Code Obfuscation");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 24));
        titleLabel.setForeground(TEXT_PRIMARY);
        headerPanel.add(titleLabel, BorderLayout.WEST);

        panel.add(headerPanel, BorderLayout.NORTH);

        // Create split pane for form and log
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setBackground(MAIN_BG);
        splitPane.setBorder(null);
        splitPane.setDividerSize(5);
        splitPane.setDividerLocation(380);

        // Top: Form panel
        JPanel formContainer = new JPanel(new BorderLayout());
        formContainer.setBackground(MAIN_BG);

        JScrollPane formScroll = new JScrollPane(createFormPanel());
        formScroll.setBorder(null);
        formScroll.getViewport().setBackground(MAIN_BG);
        formContainer.add(formScroll, BorderLayout.CENTER);

        splitPane.setTopComponent(formContainer);

        // Bottom: Log panel
        JPanel logPanel = createLogPanel();
        splitPane.setBottomComponent(logPanel);

        panel.add(splitPane, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createFormPanel() {
        JPanel formPanel = new JPanel();
        formPanel.setLayout(new BoxLayout(formPanel, BoxLayout.Y_AXIS));
        formPanel.setBackground(MAIN_BG);

        // Input/Output section
        formPanel.add(createSectionPanel("Input & Output", new Component[][]{
                {new JLabel("Input JAR:"), jarField, createBrowseButton(() -> browseFile(jarField, "Select Input JAR", "jar", "JAR Files"))},
                {new JLabel("Output Directory:"), outDirField, createBrowseButton(() -> browseDirectory(outDirField, "Select Output Directory"))}
        }));

        formPanel.add(Box.createRigidArea(new Dimension(0, 15)));

        // Configuration section
        formPanel.add(createSectionPanel("Configuration", new Component[][]{
                {new JLabel("Libraries Directory:"), libsDirField, createBrowseButton(() -> browseDirectory(libsDirField, "Select Libraries Directory"))},
                {new JLabel("Whitelist File:"), whitelistField, createBrowseButton(() -> browseFile(whitelistField, "Select Whitelist", "txt", "Text Files"))},
                {new JLabel("Blacklist File:"), blacklistField, createBrowseButton(() -> browseFile(blacklistField, "Select Blacklist", "txt", "Text Files"))}
        }));

        formPanel.add(Box.createRigidArea(new Dimension(0, 15)));

        // Advanced section
        JPanel advancedContent = new JPanel(new GridBagLayout());
        advancedContent.setBackground(CARD_BG);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 10, 5, 10);

        // Platform row
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        JLabel platformLabel = new JLabel("Platform:");
        platformLabel.setForeground(TEXT_PRIMARY);
        advancedContent.add(platformLabel, gbc);

        gbc.gridx = 1; gbc.weightx = 1;
        advancedContent.add(platformCombo, gbc);

        // Library name row
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        JLabel libNameLabel = new JLabel("Plain Library Name:");
        libNameLabel.setForeground(TEXT_PRIMARY);
        advancedContent.add(libNameLabel, gbc);

        gbc.gridx = 1; gbc.weightx = 1;
        advancedContent.add(plainLibNameField, gbc);

        // Custom dir row
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        JLabel customDirLabel = new JLabel("Custom Lib Directory:");
        customDirLabel.setForeground(TEXT_PRIMARY);
        advancedContent.add(customDirLabel, gbc);

        gbc.gridx = 1; gbc.weightx = 1;
        advancedContent.add(customLibDirField, gbc);

        // Options row
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2;
        JPanel optionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 5));
        optionsPanel.setBackground(CARD_BG);
        optionsPanel.add(useAnnotationsBox);
        optionsPanel.add(debugJarBox);
        optionsPanel.add(packageBox);
        advancedContent.add(optionsPanel, gbc);

        formPanel.add(createCollapsibleSection("Advanced Settings", advancedContent));

        formPanel.add(Box.createRigidArea(new Dimension(0, 20)));

        // Action buttons
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        actionPanel.setBackground(MAIN_BG);
        actionPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));

        JButton clearButton = createSecondaryButton("Clear All");
        clearButton.addActionListener(e -> clearAllFields());

        actionPanel.add(clearButton);
        actionPanel.add(runButton);

        formPanel.add(actionPanel);

        return formPanel;
    }

    private JPanel createSectionPanel(String title, Component[][] rows) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(CARD_BG);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1),
                new EmptyBorder(15, 15, 15, 15)
        ));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
        titleLabel.setForeground(TEXT_PRIMARY);
        titleLabel.setBorder(new EmptyBorder(0, 0, 10, 0));
        panel.add(titleLabel, BorderLayout.NORTH);

        JPanel content = new JPanel(new GridBagLayout());
        content.setBackground(CARD_BG);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        for (int i = 0; i < rows.length; i++) {
            gbc.gridy = i;
            gbc.gridx = 0;
            gbc.weightx = 0;
            JLabel label = (JLabel) rows[i][0];
            label.setForeground(TEXT_PRIMARY);
            content.add(label, gbc);

            gbc.gridx = 1;
            gbc.weightx = 1;
            content.add(rows[i][1], gbc);

            if (rows[i].length > 2) {
                gbc.gridx = 2;
                gbc.weightx = 0;
                content.add(rows[i][2], gbc);
            }
        }

        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createCollapsibleSection(String title, JComponent content) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(CARD_BG);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1),
                new EmptyBorder(10, 10, 10, 10)
        ));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 300));

        JButton toggleButton = new JButton("▼ " + title);
        toggleButton.setFont(new Font("Segoe UI", Font.BOLD, 14));
        toggleButton.setForeground(TEXT_PRIMARY);
        toggleButton.setBackground(CARD_BG);
        toggleButton.setBorderPainted(false);
        toggleButton.setFocusPainted(false);
        toggleButton.setHorizontalAlignment(SwingConstants.LEFT);
        toggleButton.setCursor(new Cursor(Cursor.HAND_CURSOR));

        panel.add(toggleButton, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);

        toggleButton.addActionListener(e -> {
            boolean visible = content.isVisible();
            content.setVisible(!visible);
            toggleButton.setText((visible ? "▶ " : "▼ ") + title);
        });

        return panel;
    }

    private JPanel createLogPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(CARD_BG);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1),
                new EmptyBorder(10, 10, 10, 10)
        ));

        JLabel logTitle = new JLabel("Output Log");
        logTitle.setFont(new Font("Segoe UI", Font.BOLD, 14));
        logTitle.setForeground(TEXT_PRIMARY);
        logTitle.setBorder(new EmptyBorder(0, 0, 10, 0));
        panel.add(logTitle, BorderLayout.NORTH);

        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
        scrollPane.getViewport().setBackground(new Color(25, 25, 26));
        panel.add(scrollPane, BorderLayout.CENTER);

        panel.add(progressBar, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createJavaObfuscationPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(MAIN_BG);
        panel.setBorder(new EmptyBorder(20, 20, 20, 20));

        JLabel label = new JLabel("Java Obfuscation Settings");
        label.setFont(new Font("Segoe UI", Font.BOLD, 24));
        label.setForeground(TEXT_PRIMARY);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        panel.add(label, BorderLayout.NORTH);

        JPanel centerPanel = new JPanel();
        centerPanel.setBackground(CARD_BG);
        centerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1),
                new EmptyBorder(40, 40, 40, 40)
        ));

        JLabel comingSoon = new JLabel("Coming Soon...");
        comingSoon.setFont(new Font("Segoe UI", Font.PLAIN, 18));
        comingSoon.setForeground(TEXT_SECONDARY);
        centerPanel.add(comingSoon);

        panel.add(centerPanel, BorderLayout.CENTER);

        return panel;
    }

    private void setupLayout() {
        // Set placeholders
        jarField.putClientProperty("JTextField.placeholderText", "Select input JAR file...");
        outDirField.putClientProperty("JTextField.placeholderText", "Choose output directory...");
        libsDirField.putClientProperty("JTextField.placeholderText", "Optional: Select libraries directory...");
        whitelistField.putClientProperty("JTextField.placeholderText", "Optional: Select whitelist.txt...");
        blacklistField.putClientProperty("JTextField.placeholderText", "Optional: Select blacklist.txt...");
        plainLibNameField.putClientProperty("JTextField.placeholderText", "Optional: Enter plain library name...");
        customLibDirField.putClientProperty("JTextField.placeholderText", "Optional: Custom directory in JAR...");
    }

    private void setupEventHandlers() {
        runButton.addActionListener(this::runObfuscation);

        // Auto-fill output directory when JAR is selected
        jarField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateOutputDir(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateOutputDir(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateOutputDir(); }

            private void updateOutputDir() {
                if (outDirField.getText().trim().isEmpty()) {
                    String jarPath = jarField.getText().trim();
                    if (!jarPath.isEmpty()) {
                        File jarFile = new File(jarPath);
                        if (jarFile.exists()) {
                            File parent = jarFile.getParentFile();
                            if (parent != null) {
                                outDirField.setText(new File(parent, "native-output").getAbsolutePath());
                            }
                        }
                    }
                }
            }
        });
    }

    // UI Component factory methods
    private JTextField createStyledTextField() {
        JTextField field = new JTextField();
        field.setBackground(new Color(25, 25, 26));
        field.setForeground(TEXT_PRIMARY);
        field.setCaretColor(TEXT_PRIMARY);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR),
                new EmptyBorder(5, 10, 5, 10)
        ));
        field.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        return field;
    }

    private JComboBox<Platform> createStyledComboBox() {
        JComboBox<Platform> combo = new JComboBox<>(Platform.values());
        combo.setBackground(new Color(25, 25, 26));
        combo.setForeground(TEXT_PRIMARY);
        combo.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
        combo.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        return combo;
    }

    private JCheckBox createStyledCheckBox(String text) {
        JCheckBox checkBox = new JCheckBox(text);
        checkBox.setBackground(CARD_BG);
        checkBox.setForeground(TEXT_PRIMARY);
        checkBox.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        checkBox.setFocusPainted(false);
        return checkBox;
    }

    private JButton createPrimaryButton(String text) {
        JButton button = new JButton(text);
        button.setBackground(ACCENT_COLOR);
        button.setForeground(Color.WHITE);
        button.setFont(new Font("Segoe UI", Font.BOLD, 14));
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.setPreferredSize(new Dimension(180, 40));

        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                button.setBackground(ACCENT_COLOR.brighter());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                button.setBackground(ACCENT_COLOR);
            }
        });

        return button;
    }

    private JButton createSecondaryButton(String text) {
        JButton button = new JButton(text);
        button.setBackground(CARD_BG);
        button.setForeground(TEXT_PRIMARY);
        button.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        button.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
        button.setFocusPainted(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.setPreferredSize(new Dimension(120, 40));

        return button;
    }

    private JButton createBrowseButton(Runnable action) {
        JButton button = new JButton("Browse");
        button.setBackground(new Color(60, 60, 63));
        button.setForeground(TEXT_PRIMARY);
        button.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.setPreferredSize(new Dimension(80, 28));

        button.addActionListener(e -> action.run());

        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                button.setBackground(new Color(70, 70, 73));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                button.setBackground(new Color(60, 60, 63));
            }
        });

        return button;
    }

    private JTextArea createStyledTextArea() {
        JTextArea area = new JTextArea();
        area.setBackground(new Color(25, 25, 26));
        area.setForeground(new Color(200, 200, 200));
        area.setFont(new Font("Consolas", Font.PLAIN, 12));
        area.setEditable(false);
        area.setBorder(new EmptyBorder(10, 10, 10, 10));
        return area;
    }

    private JProgressBar createStyledProgressBar() {
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        bar.setString("Processing...");
        bar.setStringPainted(true);
        bar.setBackground(CARD_BG);
        bar.setForeground(ACCENT_COLOR);
        bar.setBorder(new EmptyBorder(10, 0, 0, 0));
        bar.setVisible(false);
        return bar;
    }

    // File/Directory browsing methods using system file chooser
    private void browseFile(JTextField targetField, String title, String extension, String description) {
        JFileChooser chooser = new JFileChooser(lastSelectedDirectory);
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);

        if (extension != null && description != null) {
            FileNameExtensionFilter filter = new FileNameExtensionFilter(description, extension);
            chooser.setFileFilter(filter);
        }

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File selected = chooser.getSelectedFile();
            targetField.setText(selected.getAbsolutePath());
            lastSelectedDirectory = selected.getParentFile();
        }
    }

    private void browseDirectory(JTextField targetField, String title) {
        JFileChooser chooser = new JFileChooser(lastSelectedDirectory);
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File selected = chooser.getSelectedFile();
            targetField.setText(selected.getAbsolutePath());
            lastSelectedDirectory = selected;
        }
    }

    private void clearAllFields() {
        jarField.setText("");
        outDirField.setText("");
        libsDirField.setText("");
        blacklistField.setText("");
        whitelistField.setText("");
        plainLibNameField.setText("");
        customLibDirField.setText("");
        platformCombo.setSelectedItem(Platform.HOTSPOT);
        useAnnotationsBox.setSelected(false);
        debugJarBox.setSelected(false);
        packageBox.setSelected(true);
        logArea.setText("");
        updateStatus("Ready", TEXT_SECONDARY);
    }

    private void updateStatus(String message, Color color) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(message);
            statusLabel.setForeground(color);
        });
    }

    private void runObfuscation(ActionEvent e) {
        String jarPath = jarField.getText().trim();
        String outDir = outDirField.getText().trim();

        if (jarPath.isEmpty() || outDir.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Please select both input JAR and output directory.",
                    "Missing Required Fields",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        File jarFile = new File(jarPath);
        if (!jarFile.isFile()) {
            JOptionPane.showMessageDialog(this,
                    "The selected input JAR file does not exist.",
                    "Invalid Input",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        setFormEnabled(false);
        progressBar.setVisible(true);
        logArea.setText("");
        appendLog("=== Starting Native Obfuscation Process ===\n");
        appendLog("Input: " + jarPath + "\n");
        appendLog("Output: " + outDir + "\n\n");
        updateStatus("Processing...", ACCENT_COLOR);

        SwingWorker<Integer, String> worker = new SwingWorker<Integer, String>() {
            @Override
            protected Integer doInBackground() throws Exception {
                try {
                    // Collect libraries
                    List<Path> libs = new ArrayList<>();
                    String libsDir = libsDirField.getText().trim();
                    if (!libsDir.isEmpty()) {
                        appendLog("Scanning libraries directory: " + libsDir + "\n");
                        Files.walk(Paths.get(libsDir), FileVisitOption.FOLLOW_LINKS)
                                .filter(p -> p.toString().endsWith(".jar") || p.toString().endsWith(".zip"))
                                .forEach(libs::add);
                        appendLog("Found " + libs.size() + " library files\n");
                    }

                    // Load blacklist
                    List<String> blackList = new ArrayList<>();
                    String blk = blacklistField.getText().trim();
                    if (!blk.isEmpty()) {
                        appendLog("Loading blacklist from: " + blk + "\n");
                        blackList = Files.readAllLines(Paths.get(blk), StandardCharsets.UTF_8)
                                .stream()
                                .filter(s -> !s.trim().isEmpty())
                                .collect(Collectors.toList());
                        appendLog("Loaded " + blackList.size() + " blacklist entries\n");
                    }

                    // Load whitelist
                    List<String> whiteList = null;
                    String wht = whitelistField.getText().trim();
                    if (!wht.isEmpty()) {
                        appendLog("Loading whitelist from: " + wht + "\n");
                        whiteList = Files.readAllLines(Paths.get(wht), StandardCharsets.UTF_8)
                                .stream()
                                .filter(s -> !s.trim().isEmpty())
                                .collect(Collectors.toList());
                        appendLog("Loaded " + whiteList.size() + " whitelist entries\n");
                    }

                    String plainName = emptyToNull(plainLibNameField.getText());
                    String customDir = emptyToNull(customLibDirField.getText());
                    Platform platform = (Platform) platformCombo.getSelectedItem();
                    boolean useAnnotations = useAnnotationsBox.isSelected();
                    boolean debug = debugJarBox.isSelected();

                    appendLog("\n=== Configuration ===\n");
                    appendLog("Platform: " + platform + "\n");
                    appendLog("Use Annotations: " + useAnnotations + "\n");
                    appendLog("Debug JAR: " + debug + "\n");
                    appendLog("Package into JAR: " + packageBox.isSelected() + "\n");
                    if (plainName != null) appendLog("Plain Library Name: " + plainName + "\n");
                    if (customDir != null) appendLog("Custom Directory: " + customDir + "\n");

                    appendLog("\n=== Processing ===\n");

                    // Run obfuscation
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

                    // Package if needed
                    if (plainName == null && packageBox.isSelected()) {
                        appendLog("\n=== Building Native Library ===\n");
                        Path cppDir = Paths.get(outDir, "cpp");
                        runCmakeAndPackage(cppDir, Paths.get(outDir),
                                new File(outDir, jarFile.getName()).toPath(),
                                obfuscator.getNativeDir());
                    } else if (plainName != null) {
                        appendLog("\nSkipping JAR packaging (plain library mode)\n");
                    } else if (!packageBox.isSelected()) {
                        appendLog("\nSkipping JAR packaging (disabled by user)\n");
                    }

                    return 0;
                } catch (Exception ex) {
                    appendLog("\n!!! ERROR !!!\n");
                    appendLog(ex.getMessage() + "\n");
                    throw ex;
                }
            }

            @Override
            protected void done() {
                try {
                    get();
                    appendLog("\n=== OBFUSCATION COMPLETED SUCCESSFULLY ===\n");
                    appendLog("Output location: " + outDir + "\n");
                    updateStatus("Completed", SUCCESS_COLOR);

                    // Show success dialog with option to open output folder
                    int result = JOptionPane.showOptionDialog(ObfuscatorFrame.this,
                            "Obfuscation completed successfully!\nOutput saved to: " + outDir,
                            "Success",
                            JOptionPane.OK_CANCEL_OPTION,
                            JOptionPane.INFORMATION_MESSAGE,
                            null,
                            new String[]{"Open Output Folder", "OK"},
                            "OK");

                    if (result == 0) {
                        try {
                            Desktop.getDesktop().open(new File(outDir));
                        } catch (IOException ex) {
                            // Ignore if can't open
                        }
                    }
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    appendLogError(cause);
                    updateStatus("Failed", ERROR_COLOR);

                    JOptionPane.showMessageDialog(ObfuscatorFrame.this,
                            "Obfuscation failed:\n" +
                                    (cause.getMessage() == null ? cause.toString() : cause.getMessage()),
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    updateStatus("Interrupted", ERROR_COLOR);
                } finally {
                    setFormEnabled(true);
                    progressBar.setVisible(false);
                }
            }
        };

        worker.execute();
    }

    private static String emptyToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private void setFormEnabled(boolean enabled) {
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

        // Update button text
        runButton.setText(enabled ? "Run Obfuscation" : "Processing...");
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

    private void runCmakeAndPackage(Path cppDir, Path outDir, Path outJar, String nativeDir)
            throws IOException, InterruptedException {
        if (!Files.isDirectory(cppDir)) {
            throw new IOException("C++ output directory not found: " + cppDir);
        }

        appendLog("\nConfiguring CMake...\n");
        runProcess(new String[]{"cmake", "."}, cppDir.toFile());

        appendLog("\nBuilding native library (Release)...\n");
        runProcess(new String[]{"cmake", "--build", ".", "--config", "Release"}, cppDir.toFile());

        Path libDir = cppDir.resolve("build").resolve("lib");
        if (!Files.isDirectory(libDir)) {
            throw new IOException("Native lib directory not found: " + libDir);
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

        // Determine platform-specific path
        String arch = System.getProperty("os.arch").toLowerCase();
        String os = System.getProperty("os.name").toLowerCase();
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

        String osTypeName = (os.contains("win")) ? "windows.dll" :
                (os.contains("mac") ? "macos.dylib" : "linux.so");
        String entryPath = nativeDir + "/" + platformTypeName + "-" + osTypeName;

        appendLog("\nPackaging native library into JAR...\n");
        appendLog("Library location in JAR: /" + entryPath + "\n");
        packageIntoJar(outJar, libFile.toPath(), entryPath);
        appendLog("Packaging completed successfully!\n");
    }

    private void runProcess(String[] cmd, File workDir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        Thread outputThread = new Thread(() -> {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLog("  > " + line + "\n");
                }
            } catch (IOException ignored) {
            }
        });

        outputThread.setDaemon(true);
        outputThread.start();

        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new IOException("Command failed: " + String.join(" ", cmd) +
                    " (exit code: " + exitCode + ")");
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