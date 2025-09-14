package by.radioegor146.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class UiLauncher {
    public static void main(String[] args) {
        // Auto-detect system theme; fall back to light
        boolean dark = detectSystemDarkMode();
        try {
            if (dark) FlatDarkLaf.setup(); else FlatLightLaf.setup();
        } catch (Throwable t) {
            // Fallback to Nimbus if FlatLaf not available
            try {
                for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                    if ("Nimbus".equals(info.getName())) {
                        UIManager.setLookAndFeel(info.getClassName());
                        break;
                    }
                }
            } catch (Exception ignored) { }
        }
        SwingUtilities.invokeLater(ObfuscatorFrame::launch);
    }

    // Best-effort dark mode detection for Windows/macOS; others default to light
    private static boolean detectSystemDarkMode() {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("mac")) {
                Process p = new ProcessBuilder("/usr/bin/defaults", "read", "-g", "AppleInterfaceStyle").start();
                int code = p.waitFor();
                if (code == 0) {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                        String v = r.readLine();
                        return v != null && v.toLowerCase().contains("dark");
                    }
                }
            } else if (os.contains("win")) {
                Process p = new ProcessBuilder("reg", "query",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme").start();
                int code = p.waitFor();
                if (code == 0) {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            if (line.contains("AppsUseLightTheme")) {
                                // DWORD 0 => dark; 1 => light
                                boolean isLight = line.contains("0x1");
                                return !isLight;
                            }
                        }
                    }
                }
                return false; // default to light
            }
        } catch (IOException | InterruptedException ignored) { }
        return false;
    }
}
