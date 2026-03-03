package mc;

import com.google.gson.*;
import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.zip.*;
import javax.net.ssl.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Gemini Universal Launcher — with Instance Manager
 *
 * Instances are stored under BASE_DIR/instances/<instanceId>/
 * Each instance has an instance.json describing its name, MC version, mod loader, etc.
 * Existing instance folders without instance.json are auto-imported as Vanilla instances.
 */
public class launcher extends JFrame {

    private final String BASE_DIR = System.getProperty("user.home") + "/Desktop/MyCustomLauncher/";
    private final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    // Mirror manifest URLs tried in order when Mojang is unreachable
    private final String[] MANIFEST_MIRRORS = {
        "https://raw.githubusercontent.com/minecraft-linux/mcpelauncher-manifest/master/versions/version_manifest_v2.json",
        "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json",
        "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json",
    };
    private final String FABRIC_INSTALLER_URL = "https://maven.fabricmc.net/net/fabricmc/fabric-installer/0.11.0/fabric-installer-0.11.0.jar";
    // Forge installer is downloaded from Forge's Maven per-version at runtime
    private final String FORGE_MAVEN_META = "https://files.minecraftforge.net/net/minecraftforge/forge/maven-metadata.json";
    private final String FORGE_INSTALLER_BASE = "https://maven.minecraftforge.net/net/minecraftforge/forge/";
    // Modrinth API
    private final String MODRINTH_API = "https://api.modrinth.com/v2";
    private final String MOJANG_JRE_ALL_URL =
        "https://piston-meta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

    private static final String COMPONENT_JAVA21  = "java-runtime-delta";
    private static final String COMPONENT_JAVA17  = "java-runtime-gamma";
    private static final String COMPONENT_JAVA17B = "java-runtime-beta";
    private static final String COMPONENT_JAVA16  = "java-runtime-alpha";
    private static final String COMPONENT_JAVA8   = "jre-legacy";

    // ===================== MICROSOFT AUTH CONSTANTS =====================
    // We use the "XAL" client ID that Minecraft launchers traditionally use.
    // This is a public client registered by Minecraft/Mojang for launcher use.
    // OAuth flow: Microsoft → Xbox Live → XSTS → Minecraft
    private static final String MS_CLIENT_ID    = "00000000402b5328";
    private static final String MS_REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf";
    private static final String MS_AUTH_URL =
        "https://login.live.com/oauth20_authorize.srf"
        + "?client_id=" + MS_CLIENT_ID
        + "&response_type=code"
        + "&redirect_uri=" + MS_REDIRECT_URI
        + "&scope=XboxLive.signin%20offline_access";
    private static final String MS_TOKEN_URL    = "https://login.live.com/oauth20_token.srf";
    private static final String XBL_AUTH_URL    = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_AUTH_URL   = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL    = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL  = "https://api.minecraftservices.com/minecraft/profile";

    /**
     * Holds a logged-in Microsoft/Minecraft session.
     * Persisted to BASE_DIR/auth.json so login survives launcher restarts.
     *
     * Token refresh: Microsoft access tokens expire in ~3600s.
     * We store the refresh token and re-exchange it silently on next launch.
     */
    private static class AuthSession {
        String minecraftToken;   // Bearer token for MC services
        String minecraftUuid;    // Player UUID (no dashes)
        String minecraftName;    // In-game username
        String msRefreshToken;   // For silent re-login
        long   tokenExpiresAt;   // epoch seconds when minecraftToken expires

        boolean isExpired() { return System.currentTimeMillis() / 1000 > tokenExpiresAt - 60; }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("minecraftToken",   minecraftToken);
            o.addProperty("minecraftUuid",    minecraftUuid);
            o.addProperty("minecraftName",    minecraftName);
            o.addProperty("msRefreshToken",   msRefreshToken);
            o.addProperty("tokenExpiresAt",   tokenExpiresAt);
            return o;
        }

        static AuthSession fromJson(JsonObject o) {
            AuthSession s = new AuthSession();
            s.minecraftToken  = o.has("minecraftToken")  ? o.get("minecraftToken").getAsString()  : null;
            s.minecraftUuid   = o.has("minecraftUuid")   ? o.get("minecraftUuid").getAsString()   : null;
            s.minecraftName   = o.has("minecraftName")   ? o.get("minecraftName").getAsString()   : null;
            s.msRefreshToken  = o.has("msRefreshToken")  ? o.get("msRefreshToken").getAsString()  : null;
            s.tokenExpiresAt  = o.has("tokenExpiresAt")  ? o.get("tokenExpiresAt").getAsLong()    : 0;
            return s;
        }
    }

    // ===================== INSTANCE DATA MODEL =====================

    /** Represents a single launcher instance (one MC install / profile). */
    private static class InstanceConfig {
        String id, name, mcVersion, modLoader, ram, created, lastPlayed, notes;
        String group = "Default";        // instance group / folder label
        long   totalPlaytimeSeconds = 0; // cumulative playtime in seconds
        String jvmArgs = "";             // extra JVM flags appended at launch
        List<String> servers = new ArrayList<>();

        InstanceConfig(String id, String name, String mcVersion, String modLoader, String ram) {
            this.id = id; this.name = name; this.mcVersion = mcVersion;
            this.modLoader = modLoader;
            this.ram = (ram != null && !ram.isEmpty()) ? ram : "2G";
            this.created = Instant.now().toString();
            this.lastPlayed = ""; this.notes = "";
        }

        @Override public String toString() {
            String loader = modLoader != null ? modLoader : "VANILLA";
            String badge = "VANILLA".equals(loader) ? "" : " [" + loader + "]";
            return name + badge + " (" + mcVersion + ")";
        }

        String formattedPlaytime() {
            long h = totalPlaytimeSeconds / 3600, m = (totalPlaytimeSeconds % 3600) / 60;
            return h > 0 ? h + "h " + m + "m" : m > 0 ? m + "m" : totalPlaytimeSeconds + "s";
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id); o.addProperty("name", name);
            o.addProperty("mcVersion", mcVersion); o.addProperty("modLoader", modLoader);
            o.addProperty("ram", ram); o.addProperty("created", created);
            o.addProperty("lastPlayed", lastPlayed);
            o.addProperty("notes", notes != null ? notes : "");
            o.addProperty("group", group != null ? group : "Default");
            o.addProperty("totalPlaytimeSeconds", totalPlaytimeSeconds);
            o.addProperty("jvmArgs", jvmArgs != null ? jvmArgs : "");
            com.google.gson.JsonArray srv = new com.google.gson.JsonArray();
            if (servers != null) for (String s : servers) srv.add(s);
            o.add("servers", srv);
            return o;
        }

        static InstanceConfig fromJson(String id, JsonObject o) {
            InstanceConfig c = new InstanceConfig(
                id,
                o.has("name")      ? o.get("name").getAsString()     : id,
                o.has("mcVersion") ? o.get("mcVersion").getAsString() : id,
                o.has("modLoader") ? o.get("modLoader").getAsString() : "VANILLA",
                o.has("ram")       ? o.get("ram").getAsString()       : "2G"
            );
            if (o.has("created"))              c.created              = o.get("created").getAsString();
            if (o.has("lastPlayed"))           c.lastPlayed           = o.get("lastPlayed").getAsString();
            if (o.has("notes"))                c.notes                = o.get("notes").getAsString();
            if (o.has("group"))                c.group                = o.get("group").getAsString();
            if (o.has("totalPlaytimeSeconds")) c.totalPlaytimeSeconds = o.get("totalPlaytimeSeconds").getAsLong();
            if (o.has("jvmArgs"))              c.jvmArgs              = o.get("jvmArgs").getAsString();
            if (o.has("servers")) for (JsonElement e : o.getAsJsonArray("servers")) c.servers.add(e.getAsString());
            return c;
        }
    }

    // ===================== UI FIELDS =====================

    private DefaultListModel<InstanceConfig> instanceListModel;
    private JList<InstanceConfig> instanceList;
    private JButton newInstanceBtn, deleteInstanceBtn, editInstanceBtn, launchInstanceBtn;
    private JButton duplicateInstanceBtn;

    private JLabel detailName, detailVersion, detailLoader, detailLastPlayed, detailPlaytime, detailDiskSize;
    private JTextField instanceRamField;
    private JButton installFabricForInstanceBtn, installForgeForInstanceBtn, installModForInstanceBtn;
    private JTextArea notesArea;
    private JLabel ramUsageLabel;
    private JComboBox<String> groupFilterBox; // filter list by group
    private JTextField jvmArgsField;

    private JTextArea logArea;
    private JProgressBar progressBar;
    private JTextField logSearchField;  // Ctrl+F log search

    private JTextField nameField;
    private JCheckBox offlineToggle;
    private JTextField instanceSearchField;

    private JButton installJavaButton, installFabricButton, installModrinthButton;
    private JButton installJava25Button, installJava21Button, installJava17Button, installJava8Button;
    private JButton mojangJre21Button, mojangJre17Button, mojangJre8Button, mojangJreAutoButton;

    // ---- Mods browser fields ----
    private JTextField modSearchField;
    private JComboBox<String> modCategoryBox, modLoaderFilterBox;
    private JPanel modResultsPanel;
    private JScrollPane modResultsScroll;
    private JLabel modStatusLabel;

    private List<String> allMcVersions = new ArrayList<>();

    // ---- Active game process (for RAM monitoring) ----
    private volatile Process activeGameProcess = null;
    private volatile long launchEpochSeconds   = 0; // when current game session started

    // ===================== AUTH STATE =====================
    private AuthSession currentSession = null; // null = offline / not logged in
    private JLabel accountLabel;
    private JButton loginButton, logoutButton;

    // ===================== HELPERS =====================

    /** Canonical instance directory path for a given instance id. */
    private String instanceDir(String id) { return BASE_DIR + "instances/" + id + "/"; }
    private String instanceDir(InstanceConfig ic) { return instanceDir(ic.id); }

    /**
     * Run a background task with automatic progress bar and optional button disabling.
     * Shows indeterminate progress while running, hides it when done.
     * @param task       work to do off the EDT
     * @param onSuccess  called on EDT if task succeeds (may be null)
     * @param onError    called on EDT with exception message if task fails (may be null)
     * @param toDisable  buttons to disable while running (re-enabled in finally)
     */
    private void runTask(ThrowingRunnable task, Runnable onSuccess, java.util.function.Consumer<String> onError, JButton... toDisable) {
        SwingUtilities.invokeLater(() -> {
            for (JButton b : toDisable) b.setEnabled(false);
            progressBar.setVisible(true); progressBar.setIndeterminate(true);
        });
        new Thread(() -> {
            try {
                task.run();
                if (onSuccess != null) SwingUtilities.invokeLater(onSuccess);
            } catch (Exception ex) {
                log("Error: " + ex.getMessage()); debugException(ex);
                if (onError != null) SwingUtilities.invokeLater(() -> onError.accept(ex.getMessage()));
            } finally {
                SwingUtilities.invokeLater(() -> {
                    for (JButton b : toDisable) b.setEnabled(true);
                    progressBar.setVisible(false); progressBar.setIndeterminate(false);
                });
            }
        }).start();
    }

    @FunctionalInterface interface ThrowingRunnable { void run() throws Exception; }

    public launcher() {
        applyTheme();
        setTitle("Gemini Launcher");
        setSize(1280, 820);
        setMinimumSize(new Dimension(960, 640));
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        // Dark window background before UI builds
        getRootPane().putClientProperty("apple.awt.fullWindowContent", true);
        getContentPane().setBackground(C_BG);
        // Apply SSL bypass at startup — handles school/corporate proxy SSL inspection.
        // Silently no-ops on normal networks.
        try { disableSSLVerification(); } catch (Exception ignored) {}
        setIconImage(buildAppIcon());
        buildUI();
        new FetchVersionsWorker().execute();
        loadInstances();
        loadSession();
        setVisible(true);
        applyDarkTitleBar(this);
        registerKeyboardShortcuts();
        setupTrayIcon();
        // Background update check — silently no-ops if GitHub is unreachable
        checkForLauncherUpdate();
    }

    /** Draws a 32x32 abstract geometric icon — hexagonal facets with a violet/blue gradient core. */
    private static Image buildAppIcon() {
        int sz = 32;
        BufferedImage img = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,        RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,           RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,      RenderingHints.VALUE_STROKE_PURE);

        // ── Background: deep rounded square ──────────────────────────────────
        g.setColor(new Color(0x0B0D16));
        g.fillRoundRect(0, 0, sz, sz, 7, 7);

        int cx = sz / 2, cy = sz / 2;

        // ── Three stacked diamond facets (perspective gem) ────────────────────
        // Each facet is a quadrilateral painted as a filled polygon.

        // Facet 1 — top-left face (cool blue-violet)
        int[] xL = { cx, cx - 9, cx - 5, cx + 1 };
        int[] yL = { cy - 9, cy - 1, cy + 5, cy - 1 };
        g.setPaint(new LinearGradientPaint(
            new java.awt.geom.Point2D.Float(cx - 9, cy - 1),
            new java.awt.geom.Point2D.Float(cx,     cy - 9),
            new float[]{0f, 1f},
            new Color[]{new Color(0x5B5FDE), new Color(0x9B8FF5)}
        ));
        g.fillPolygon(xL, yL, 4);

        // Facet 2 — top-right face (lighter periwinkle highlight)
        int[] xR = { cx, cx + 1, cx + 7, cx + 9 };
        int[] yR = { cy - 9, cy - 1, cy + 3, cy - 3 };
        g.setPaint(new LinearGradientPaint(
            new java.awt.geom.Point2D.Float(cx,     cy - 9),
            new java.awt.geom.Point2D.Float(cx + 9, cy - 3),
            new float[]{0f, 1f},
            new Color[]{new Color(0xC4BBFF), new Color(0x7C6EFA)}
        ));
        g.fillPolygon(xR, yR, 4);

        // Facet 3 — bottom face (dark base, creates depth)
        int[] xB = { cx - 5, cx + 1, cx + 7, cx - 1 };
        int[] yB = { cy + 5, cy - 1, cy + 3, cy + 10 };
        g.setPaint(new LinearGradientPaint(
            new java.awt.geom.Point2D.Float(cx - 5, cy + 5),
            new java.awt.geom.Point2D.Float(cx - 1, cy + 10),
            new float[]{0f, 1f},
            new Color[]{new Color(0x3D3A8C), new Color(0x1A1830)}
        ));
        g.fillPolygon(xB, yB, 4);

        // ── Shared edges — thin bright lines to define facet boundaries ───────
        g.setStroke(new BasicStroke(0.7f));

        // Top ridge (apex → left shoulder → right shoulder)
        g.setColor(new Color(0xD0CCFF, true));
        g.drawLine(cx, cy - 9, cx - 9, cy - 1);
        g.drawLine(cx, cy - 9, cx + 9, cy - 3);

        // Central vertical crease
        g.setColor(new Color(0xFFFFFF, true));
        g.drawLine(cx, cy - 9, cx - 1, cy + 10);

        // Lower-left edge
        g.setColor(new Color(0x6060CC, true));
        g.drawLine(cx - 9, cy - 1, cx - 1, cy + 10);

        // Lower-right edge
        g.setColor(new Color(0x8888EE, true));
        g.drawLine(cx + 9, cy - 3, cx - 1, cy + 10);

        // Horizontal belt
        g.setColor(new Color(0xAAAAAA, true));
        g.drawLine(cx - 9, cy - 1, cx + 1, cy - 1);
        g.drawLine(cx + 1, cy - 1, cx + 7, cy + 3);
        g.drawLine(cx - 5, cy + 5, cx + 7, cy + 3);

        // ── Specular highlight — tiny bright dot near apex ────────────────────
        g.setPaint(new RadialGradientPaint(
            new java.awt.geom.Point2D.Float(cx - 2, cy - 6), 3f,
            new float[]{0f, 1f},
            new Color[]{new Color(255, 255, 255, 180), new Color(255, 255, 255, 0)}
        ));
        g.fillOval(cx - 4, cy - 8, 5, 4);

        // ── Outer border ──────────────────────────────────────────────────────
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(0x2A2D45));
        g.drawRoundRect(0, 0, sz - 1, sz - 1, 7, 7);

        g.dispose();
        return img;
    }

    /**
     * Sets the Windows title bar to dark mode by calling DwmSetWindowAttribute via PowerShell.
     * Finds the window by title — no JNA or HWND reflection needed.
     * No-ops silently on non-Windows or older Windows builds.
     */
    private static void applyDarkTitleBar(java.awt.Window window) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        // Small delay to ensure the window title is set before PS searches for it
        new Thread(() -> {
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            try {
                String title = (window instanceof java.awt.Frame) ? ((java.awt.Frame) window).getTitle() : "Gemini Launcher";
                String ps =
                    "Add-Type -TypeDefinition @\"\n" +
                    "using System; using System.Runtime.InteropServices;\n" +
                    "public class DWM {\n" +
                    "  [DllImport(\"user32.dll\")] public static extern IntPtr FindWindow(string c, string t);\n" +
                    "  [DllImport(\"dwmapi.dll\")] public static extern int DwmSetWindowAttribute(IntPtr h, int a, ref int v, int s);\n" +
                    "}\n" +
                    "\"@\n" +
                    "$hwnd = [DWM]::FindWindow($null, '" + title.replace("'", "") + "')\n" +
                    "$v = 1\n" +
                    "[DWM]::DwmSetWindowAttribute($hwnd, 20, [ref]$v, 4)\n" +
                    "[DWM]::DwmSetWindowAttribute($hwnd, 19, [ref]$v, 4)\n";
                new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", ps)
                    .redirectErrorStream(true).start();
            } catch (Exception ignored) {}
        }).start();
    }

    // ===================== COLOUR PALETTE — "Stellar Night" =====================
    // Rich deep navy-blacks with periwinkle violet and warm text
    static Color C_BG        = new Color(0x07080D);   // near-void
    static Color C_SURFACE   = new Color(0x0D0F17);   // card surface
    static Color C_SURFACE2  = new Color(0x141720);   // elevated surface
    static Color C_SURFACE3  = new Color(0x1A1E2B);   // input fields
    static Color C_BORDER    = new Color(0x1D2235);   // hairline
    static Color C_BORDER2   = new Color(0x252D42);   // visible border
    static Color C_ACCENT    = new Color(0x7C6EFA);   // periwinkle violet
    static Color C_ACCENT_HI = new Color(0x9D92FB);   // hover tint
    static Color C_ACCENT_DIM= new Color(0x221E4A);   // dim fill
    static Color C_ACCENT2   = new Color(0x4F9CF9);   // sky blue
    static Color C_GREEN     = new Color(0x34D399);   // emerald
    static Color C_AMBER     = new Color(0xFBBF24);   // amber
    static Color C_TEXT      = new Color(0xEEF0F8);   // warm white
    static Color C_TEXT_MED  = new Color(0x8892A8);   // mid grey-blue
    static Color C_TEXT_DIM  = new Color(0x353D52);   // very dim
    static Color C_DANGER    = new Color(0xF06A6A);   // coral red
    static Color C_CONSOLE   = new Color(0x7DD3C0);   // teal console text
    static Color C_SIDEBAR   = new Color(0x080A12);   // blue-tinted sidebar

    static Font FONT_UI;
    static Font FONT_MONO;
    static Font FONT_TITLE;

    private void applyTheme() {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}

        FONT_UI = new Font("Segoe UI Variable Display", Font.PLAIN, 13);
        if (!FONT_UI.getFamily().toLowerCase().contains("segoe"))
            FONT_UI = new Font("Segoe UI", Font.PLAIN, 13);
        if (!FONT_UI.getFamily().toLowerCase().contains("segoe"))
            FONT_UI = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        FONT_MONO = new Font("Cascadia Code", Font.PLAIN, 12);
        if (!FONT_MONO.getFamily().toLowerCase().contains("cascadia"))
            FONT_MONO = new Font("Consolas", Font.PLAIN, 12);
        if (!FONT_MONO.getFamily().toLowerCase().contains("consolas"))
            FONT_MONO = new Font(Font.MONOSPACED, Font.PLAIN, 12);
        FONT_TITLE = FONT_UI.deriveFont(Font.BOLD, 20f);

        String[] panelKeys = {"Panel.background","OptionPane.background","Dialog.background",
            "ColorChooser.background","TabbedPane.background","SplitPane.background",
            "ScrollPane.background","Viewport.background","Table.background","Tree.background"};
        for (String k : panelKeys) UIManager.put(k, C_BG);
        UIManager.put("Panel.foreground",              C_TEXT);
        UIManager.put("Label.foreground",              C_TEXT);
        UIManager.put("Label.font",                    FONT_UI);
        UIManager.put("Button.foreground",             C_TEXT);
        UIManager.put("Button.background",             C_SURFACE2);
        UIManager.put("Button.font",                   FONT_UI);
        UIManager.put("Button.focus",                  new Color(0,0,0,0));
        UIManager.put("TextField.background",          C_SURFACE3);
        UIManager.put("TextField.foreground",          C_TEXT);
        UIManager.put("TextField.caretForeground",     C_ACCENT);
        UIManager.put("TextField.border",              BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(C_BORDER2), new EmptyBorder(5,10,5,10)));
        UIManager.put("TextField.font",                FONT_UI);
        UIManager.put("TextArea.background",           new Color(0x060809));
        UIManager.put("TextArea.foreground",           C_CONSOLE);
        UIManager.put("TextArea.caretForeground",      C_ACCENT);
        UIManager.put("TextArea.font",                 FONT_MONO);
        UIManager.put("ComboBox.background",           C_SURFACE3);
        UIManager.put("ComboBox.foreground",           C_TEXT);
        UIManager.put("ComboBox.font",                 FONT_UI);
        UIManager.put("ComboBox.selectionBackground",  C_ACCENT_DIM);
        UIManager.put("ComboBox.selectionForeground",  C_ACCENT_HI);
        UIManager.put("List.background",               C_SURFACE);
        UIManager.put("List.foreground",               C_TEXT);
        UIManager.put("List.selectionBackground",      C_ACCENT_DIM);
        UIManager.put("List.selectionForeground",      C_ACCENT_HI);
        UIManager.put("List.font",                     FONT_UI);
        UIManager.put("ScrollBar.background",          C_BG);
        UIManager.put("ScrollBar.thumb",               C_BORDER2);
        UIManager.put("ScrollBar.track",               C_BG);
        UIManager.put("ScrollBar.thumbDarkShadow",     C_BG);
        UIManager.put("ScrollBar.thumbHighlight",      C_BORDER2);
        UIManager.put("ScrollBar.thumbShadow",         C_BG);
        UIManager.put("ScrollBar.width",               5);
        UIManager.put("ScrollPane.border",             BorderFactory.createEmptyBorder());
        UIManager.put("TabbedPane.foreground",         C_TEXT_MED);
        UIManager.put("TabbedPane.selected",           C_SURFACE2);
        UIManager.put("TabbedPane.contentBorderInsets", new Insets(0,0,0,0));
        UIManager.put("TabbedPane.tabInsets",          new Insets(8,16,8,16));
        UIManager.put("TabbedPane.font",               FONT_UI.deriveFont(Font.BOLD));
        UIManager.put("ProgressBar.background",        C_SURFACE2);
        UIManager.put("ProgressBar.foreground",        C_ACCENT);
        UIManager.put("ProgressBar.border",            BorderFactory.createEmptyBorder());
        UIManager.put("ProgressBar.font",              FONT_UI);
        UIManager.put("CheckBox.background",           C_BG);
        UIManager.put("CheckBox.foreground",           C_TEXT_MED);
        UIManager.put("CheckBox.font",                 FONT_UI);
        UIManager.put("Separator.foreground",          C_BORDER);
        UIManager.put("SplitPane.dividerSize",         1);
        UIManager.put("OptionPane.messageForeground",  C_TEXT);
        UIManager.put("OptionPane.messageFont",        FONT_UI);
        UIManager.put("OptionPane.buttonFont",         FONT_UI);
        UIManager.put("PopupMenu.background",          C_SURFACE2);
        UIManager.put("PopupMenu.border",              BorderFactory.createLineBorder(C_BORDER2));
        UIManager.put("MenuItem.background",           C_SURFACE2);
        UIManager.put("MenuItem.foreground",           C_TEXT_MED);
        UIManager.put("MenuItem.selectionBackground",  C_ACCENT_DIM);
        UIManager.put("MenuItem.selectionForeground",  C_ACCENT_HI);
        UIManager.put("MenuItem.font",                 FONT_UI);
    }

    // ===================== UI CONSTRUCTION =====================

    // Sidebar navigation state
    private JPanel[] contentPanels;
    private JButton[] navBtns;
    private int activeTab = 0;
    private JPanel cardStack; // holds the three content panels

    private void buildUI() {
        setLayout(new BorderLayout(0, 0));
        getContentPane().setBackground(C_BG);

        // ── TOP BAR ──────────────────────────────────────────────────────────
     // --- TOP BAR ----------------------------------------------------------
        JPanel topBar = new JPanel(new BorderLayout(0, 0)) {
            @Override 
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                // 1. Subtle top-to-bottom gradient (This one is fine as GradientPaint)
                Color bgEnd = new Color(C_BG.getRed(), C_BG.getGreen(), C_BG.getBlue());
                g2.setPaint(new GradientPaint(0f, 0f, C_SURFACE, 0f, (float)getHeight(), bgEnd));
                g2.fillRect(0, 0, getWidth(), getHeight());

                // 2. Bottom hairline (MUST be LinearGradientPaint for 3 colors)
                float[] fractions = {0.0f, 0.5f, 1.0f};
                Color[] colors = {
                    new Color(C_ACCENT.getRed(), C_ACCENT.getGreen(), C_ACCENT.getBlue(), 60),
                    C_BORDER,
                    new Color(C_ACCENT.getRed(), C_ACCENT.getGreen(), C_ACCENT.getBlue(), 20)
                };
                
                // We define the horizontal path from x=0 to x=getWidth()
                LinearGradientPaint hairlinePaint = new LinearGradientPaint(
                    new java.awt.geom.Point2D.Float(0, 0), 
                    new java.awt.geom.Point2D.Float(getWidth(), 0), 
                    fractions, 
                    colors
                );
                
                g2.setPaint(hairlinePaint);
                g2.fillRect(0, getHeight() - 1, getWidth(), 1);
                
                g2.dispose();
            }
        };
        topBar.setOpaque(false);
        topBar.setPreferredSize(new Dimension(0, 54));
        topBar.setBorder(new EmptyBorder(0, 18, 0, 18));

        // Wordmark + version badge
        JPanel brandPanel = new JPanel(null);
        brandPanel.setOpaque(false);
        brandPanel.setPreferredSize(new Dimension(130, 40));
        JLabel brand = new JLabel("GEMINI") {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                int tw = fm.stringWidth(getText());
                // Triple-stop gradient: accent → hi → accent2
                g2.setPaint(new LinearGradientPaint(
                    new java.awt.geom.Point2D.Float(0,0),
                    new java.awt.geom.Point2D.Float(tw, 0),
                    new float[]{0f, 0.55f, 1f},
                    new Color[]{C_ACCENT, C_ACCENT_HI, new Color(0xA8D4FF)}));
                g2.drawString(getText(), 0, fm.getAscent());
                g2.dispose();
            }
        };
        brand.setFont(FONT_UI.deriveFont(Font.BOLD, 18f));
        brand.setBounds(0, 8, 90, 28);
        // Tiny version badge
        JLabel verBadge = new JLabel("v1.0") {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(C_ACCENT_DIM); g2.fillRoundRect(0,0,getWidth(),getHeight(),4,4);
                g2.setColor(new Color(C_ACCENT.getRed(),C_ACCENT.getGreen(),C_ACCENT.getBlue(),100));
                g2.setStroke(new BasicStroke(0.8f)); g2.drawRoundRect(0,0,getWidth()-1,getHeight()-1,4,4);
                g2.setColor(C_ACCENT_HI); g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(),3,(getHeight()+fm.getAscent()-fm.getDescent())/2);
                g2.dispose();
            }
        };
        verBadge.setFont(FONT_UI.deriveFont(Font.BOLD, 9f));
        verBadge.setBounds(92, 16, 30, 13);
        brandPanel.add(brand); brandPanel.add(verBadge);

        // Center: player name + toggles
        JPanel centerBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        centerBar.setOpaque(false);

        nameField = new JTextField("DevPlayer", 12);
        styleInputField(nameField);

        offlineToggle = new JCheckBox("Offline");
        styleToggle(offlineToggle, C_TEXT_MED);

        JCheckBox sslBypassToggle = new JCheckBox("Bypass SSL");
        styleToggle(sslBypassToggle, C_AMBER);
        sslBypassToggle.setSelected(true);
        sslBypassToggle.setToolTipText("Disable SSL certificate verification — use on school/corporate networks");
        sslBypassToggle.addActionListener(e -> {
            if (sslBypassToggle.isSelected()) {
                try { disableSSLVerification(); log("SSL verification disabled."); }
                catch (Exception ex) { log("SSL bypass failed: " + ex.getMessage()); }
            } else {
                try {
                    SSLContext sc = SSLContext.getInstance("TLS"); sc.init(null, null, null);
                    HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
                    HttpsURLConnection.setDefaultHostnameVerifier(null);
                    log("SSL verification restored.");
                } catch (Exception ex) { log("SSL restore failed: " + ex.getMessage()); }
            }
        });

        JLabel playerLbl = new JLabel("Player");
        playerLbl.setFont(FONT_UI.deriveFont(11f)); playerLbl.setForeground(C_TEXT_DIM);

        // Vertical divider
        JSeparator vSep = new JSeparator(JSeparator.VERTICAL);
        vSep.setForeground(C_BORDER2);
        vSep.setPreferredSize(new Dimension(1, 22));

        centerBar.add(playerLbl); centerBar.add(nameField);
        centerBar.add(vSep);
        centerBar.add(offlineToggle); centerBar.add(sslBypassToggle);

        // Right: account + tools + help
        JPanel rightBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightBar.setOpaque(false);

        accountLabel = new JLabel("● Offline");
        accountLabel.setForeground(C_TEXT_DIM);
        accountLabel.setFont(FONT_UI.deriveFont(Font.ITALIC, 12f));

        loginButton  = new GlowButton("Sign In", C_ACCENT2);
        logoutButton = new GlowButton("Sign Out", C_DANGER);
        logoutButton.setVisible(false);
        loginButton.addActionListener(e -> onLoginClicked());
        logoutButton.addActionListener(e -> onLogout());

        // Tools menu
        JPopupMenu toolsMenu = new JPopupMenu();
        toolsMenu.setBackground(C_SURFACE2);
        toolsMenu.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(C_BORDER2), new EmptyBorder(3,0,3,0)));
        java.util.function.BiConsumer<String, Runnable> addMenuItem = (lbl, act) -> {
            JMenuItem item = new JMenuItem(lbl);
            item.setBackground(C_SURFACE2); item.setForeground(C_TEXT_MED); item.setFont(FONT_UI);
            item.setBorder(new EmptyBorder(6, 16, 6, 24));
            item.addActionListener(ev -> act.run());
            toolsMenu.add(item);
        };
        addMenuItem.accept("🎨  Theme Editor",        () -> showThemeEditor());
        addMenuItem.accept("📦  Install Modpack",      () -> showModpackInstaller());
        addMenuItem.accept("⚖  Compare MC Versions",  () -> showVersionComparison());
        toolsMenu.addSeparator();
        addMenuItem.accept("🔄  Check for Updates",    () -> checkForLauncherUpdate());
        addMenuItem.accept("⌨  Keyboard Shortcuts",   () -> showKeyboardShortcuts());

        DarkButton toolsBtn = new DarkButton("⚙  Tools");
        toolsBtn.addActionListener(e -> toolsMenu.show(toolsBtn, 0, toolsBtn.getHeight() + 3));

        DarkButton tutorialBtn = new DarkButton("?");
        tutorialBtn.setToolTipText("Open tutorial");
        tutorialBtn.addActionListener(e -> showTutorial());

        rightBar.add(toolsBtn); rightBar.add(tutorialBtn);
        rightBar.add(accountLabel); rightBar.add(loginButton); rightBar.add(logoutButton);

        topBar.add(brandPanel, BorderLayout.WEST);
        topBar.add(centerBar, BorderLayout.CENTER);
        topBar.add(rightBar, BorderLayout.EAST);

        // ── SIDEBAR NAV ──────────────────────────────────────────────────────
        JPanel sidebar = new JPanel(null) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                // Very slight gradient from top (slightly lighter) to bottom
                g2.setPaint(new GradientPaint(0,0,C_SIDEBAR,0,getHeight(),new Color(0x06070E)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                // Right edge: gradient line (accent tinted at top, pure border at bottom)
                g2.setPaint(new GradientPaint(0,0,new Color(C_ACCENT.getRed(),C_ACCENT.getGreen(),C_ACCENT.getBlue(),60),
                    0, getHeight()/2, C_BORDER));
                g2.fillRect(getWidth()-1, 0, 1, getHeight());
                g2.dispose();
            }
        };
        sidebar.setOpaque(false);
        sidebar.setPreferredSize(new Dimension(68, 0));

        // Logo mark
        JPanel logoMark = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int cx = getWidth()/2, cy = getHeight()/2, r = 15;
                // Multi-ring glow
                for (int i = 4; i > 0; i--) {
                    int a = 10 * i;
                    g2.setColor(new Color(C_ACCENT.getRed(), C_ACCENT.getGreen(), C_ACCENT.getBlue(), a));
                    g2.fillOval(cx-r-i*3, cy-r-i*3, (r+i*3)*2, (r+i*3)*2);
                }
                // Inner fill
                g2.setColor(new Color(C_ACCENT.getRed(), C_ACCENT.getGreen(), C_ACCENT.getBlue(), 22));
                g2.fillOval(cx-r, cy-r, r*2, r*2);
                // Ring
                g2.setColor(new Color(C_ACCENT.getRed(), C_ACCENT.getGreen(), C_ACCENT.getBlue(), 180));
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawOval(cx-r, cy-r, r*2, r*2);
                // G letter
                g2.setColor(C_ACCENT_HI);
                g2.setFont(FONT_UI.deriveFont(Font.BOLD, 14f));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString("G", cx - fm.stringWidth("G")/2, cy + fm.getAscent()/2 - 1);
                g2.dispose();
            }
        };
        logoMark.setOpaque(false);
        logoMark.setBounds(0, 2, 68, 56);
        sidebar.add(logoMark);

        // Nav items
        String[][] navItems = {{"▣", "PLAY"}, {"⬡", "MODS"}, {"⚙", "JAVA"}};
        navBtns = new JButton[navItems.length];
        for (int i = 0; i < navItems.length; i++) {
            final int idx = i;
            navBtns[i] = buildNavButton(navItems[i][0], navItems[i][1], i == 0);
            navBtns[i].setBounds(6, 62 + i * 60, 56, 52);
            navBtns[i].addActionListener(e -> switchToTab(idx));
            sidebar.add(navBtns[i]);
        }

        // ── CONTENT (CardLayout) ──────────────────────────────────────────────
        contentPanels = new JPanel[3];
        contentPanels[0] = buildInstancesTab();
        contentPanels[1] = buildModsTab();
        contentPanels[2] = buildJavaTab();

        cardStack = new JPanel(new CardLayout());
        cardStack.setBackground(C_BG);
        for (int i = 0; i < contentPanels.length; i++) {
            cardStack.add(contentPanels[i], "tab" + i);
        }

        JPanel mainArea = new JPanel(new BorderLayout(0, 0));
        mainArea.setBackground(C_BG);
        mainArea.add(sidebar, BorderLayout.WEST);
        mainArea.add(cardStack, BorderLayout.CENTER);

        // ── CONSOLE + PROGRESS ───────────────────────────────────────────────
        Color consoleBg = new Color(0x05060B);
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setBackground(consoleBg);
        logArea.setForeground(C_CONSOLE);
        logArea.setFont(FONT_MONO.deriveFont(11.5f));
        logArea.setCaretColor(C_ACCENT);
        logArea.setBorder(new EmptyBorder(8, 16, 8, 16));

        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(null);
        logScroll.setPreferredSize(new Dimension(0, 148));
        logScroll.getViewport().setBackground(consoleBg);
        logScroll.getVerticalScrollBar().setPreferredSize(new Dimension(4, 0));

     // Console header bar
        JPanel consoleHeader = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                
                // Background
                g2.setColor(new Color(0x09090F));
                g2.fillRect(0, 0, getWidth(), getHeight());

                // Top separator line with accent tint (3-color gradient)
                float[] fractions = {0.0f, 0.33f, 1.0f}; // Matches your getWidth()/3 logic
                Color[] colors = {
                    new Color(C_ACCENT.getRed(), C_ACCENT.getGreen(), C_ACCENT.getBlue(), 50),
                    C_BORDER,
                    new Color(0, 0, 0, 0) // Transparent
                };

                LinearGradientPaint headerLinePaint = new LinearGradientPaint(
                    new java.awt.geom.Point2D.Float(0, 0),
                    new java.awt.geom.Point2D.Float(getWidth(), 0),
                    fractions,
                    colors
                );

                g2.setPaint(headerLinePaint);
                g2.fillRect(0, 0, getWidth(), 1);
                
                g2.dispose();
            }
        };
        consoleHeader.setOpaque(false);
        consoleHeader.setBorder(new EmptyBorder(5, 16, 5, 16));
        JLabel consoleLbl = new JLabel("CONSOLE") {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                // Small colored dot before label
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(C_GREEN.getRed(),C_GREEN.getGreen(),C_GREEN.getBlue(),160));
                g2.fillOval(0, (getHeight()-6)/2, 6, 6);
                g2.setFont(getFont()); g2.setColor(getForeground());
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(), 11, (getHeight()+fm.getAscent()-fm.getDescent())/2);
                g2.dispose();
            }
            @Override public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize(); return new Dimension(d.width+11, d.height);
            }
        };
        consoleLbl.setFont(FONT_UI.deriveFont(Font.BOLD, 9.5f));
        consoleLbl.setForeground(C_TEXT_MED);
        JLabel consoleHint = new JLabel("Ctrl+F  search");
        consoleHint.setFont(FONT_MONO.deriveFont(9.5f));
        consoleHint.setForeground(C_TEXT_DIM);
        consoleHeader.add(consoleLbl, BorderLayout.WEST);
        consoleHeader.add(consoleHint, BorderLayout.EAST);

        // Log search bar
        logSearchField = new JTextField();
        logSearchField.setBackground(new Color(0x0B0E15));
        logSearchField.setForeground(C_ACCENT_HI);
        logSearchField.setFont(FONT_MONO.deriveFont(11f));
        logSearchField.setCaretColor(C_ACCENT);
        logSearchField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 1, 0, C_BORDER2),
            new EmptyBorder(4, 14, 4, 14)));
        logSearchField.putClientProperty("JTextField.placeholderText", "Search log output  (Enter = next match)");
        logSearchField.setVisible(false);
        logSearchField.addActionListener(e -> searchLog(logSearchField.getText(), true));
        JLabel logSearchClose = new JLabel("✕");
        logSearchClose.setForeground(C_TEXT_DIM); logSearchClose.setFont(FONT_UI);
        logSearchClose.setBorder(new EmptyBorder(0, 10, 0, 14));
        logSearchClose.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        logSearchClose.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) {
                logSearchField.setVisible(false);
                if (logSearchField.getParent() != null) logSearchField.getParent().revalidate();
            }
        });
        JPanel logSearchBar = new JPanel(new BorderLayout());
        logSearchBar.setBackground(new Color(0x0B0E15));
        logSearchBar.add(logSearchField, BorderLayout.CENTER);
        logSearchBar.add(logSearchClose, BorderLayout.EAST);
        logSearchBar.setVisible(false);
        logSearchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { searchLog(logSearchField.getText(), false); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { searchLog(logSearchField.getText(), false); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) {}
        });

        // Slim progress bar — 2px vivid accent line
        progressBar = new JProgressBar() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(C_SURFACE2);
                g2.fillRect(0, 0, getWidth(), getHeight());
                if (isVisible() && getValue() > 0) {
                    int filled = (int)(getWidth() * (getValue() / (double)getMaximum()));
                    g2.setPaint(new GradientPaint(0,0,C_ACCENT2,filled,0,C_ACCENT));
                    g2.fillRect(0, 0, filled, getHeight());
                    // Bright tip
                    if (filled > 4) {
                        g2.setColor(new Color(255,255,255,120));
                        g2.fillRect(filled-2, 0, 2, getHeight());
                    }
                }
                g2.dispose();
            }
        };
        progressBar.setVisible(false);
        progressBar.setStringPainted(false);
        progressBar.setBorderPainted(false);
        progressBar.setBackground(C_SURFACE);
        progressBar.setForeground(C_ACCENT);
        progressBar.setPreferredSize(new Dimension(0, 2));

        JPanel bottomPanel = new JPanel(new BorderLayout(0, 0));
        bottomPanel.setBackground(consoleBg);
        bottomPanel.add(consoleHeader, BorderLayout.NORTH);
        bottomPanel.add(logScroll, BorderLayout.CENTER);
        bottomPanel.add(logSearchBar, BorderLayout.SOUTH);

        JPanel bottomWrapper = new JPanel(new BorderLayout());
        bottomWrapper.setBackground(consoleBg);
        bottomWrapper.add(bottomPanel, BorderLayout.CENTER);
        bottomWrapper.add(progressBar, BorderLayout.SOUTH);

        add(topBar, BorderLayout.NORTH);
        add(mainArea, BorderLayout.CENTER);
        add(bottomWrapper, BorderLayout.SOUTH);
    }

    /** Switch to tab by index, updating sidebar state */
    private void switchToTab(int idx) {
        activeTab = idx;
        ((CardLayout) cardStack.getLayout()).show(cardStack, "tab" + idx);
        for (int i = 0; i < navBtns.length; i++) {
            navBtns[i].putClientProperty("active", i == idx);
            navBtns[i].repaint();
        }
    }

    /** Build a sidebar nav icon+label button with smooth hover animation */
    private JButton buildNavButton(String icon, String label, boolean active) {
        JButton btn = new JButton() {
            float hoverAlpha = 0f;
            javax.swing.Timer hoverTimer;
            {
                putClientProperty("active", active);
                setOpaque(false); setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false);
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                addMouseListener(new java.awt.event.MouseAdapter() {
                    public void mouseEntered(java.awt.event.MouseEvent e) { animHover(true); }
                    public void mouseExited (java.awt.event.MouseEvent e) { animHover(false); }
                    void animHover(boolean in) {
                        if (hoverTimer != null) hoverTimer.stop();
                        hoverTimer = new javax.swing.Timer(16, null);
                        hoverTimer.addActionListener(ev -> {
                            hoverAlpha += in ? 0.12f : -0.12f;
                            if (hoverAlpha<=0f) { hoverAlpha=0f; hoverTimer.stop(); }
                            if (hoverAlpha>=1f) { hoverAlpha=1f; hoverTimer.stop(); }
                            repaint();
                        });
                        hoverTimer.start();
                    }
                });
            }
            @Override protected void paintComponent(Graphics g) {
                boolean sel = Boolean.TRUE.equals(getClientProperty("active"));
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
                int w = getWidth(), h = getHeight();

                if (sel) {
                    // Glassy violet selection pill
                    g2.setColor(new Color(C_ACCENT.getRed(), C_ACCENT.getGreen(), C_ACCENT.getBlue(), 28));
                    g2.fillRoundRect(0,0,w,h,12,12);
                    // Bright left bar
                    g2.setPaint(new GradientPaint(0,6,C_ACCENT,0,h-6,new Color(C_ACCENT.getRed(),C_ACCENT.getGreen(),C_ACCENT.getBlue(),120)));
                    g2.fillRoundRect(0,6,3,h-12,3,3);
                    // Subtle border
                    g2.setColor(new Color(C_ACCENT.getRed(), C_ACCENT.getGreen(), C_ACCENT.getBlue(), 55));
                    g2.setStroke(new BasicStroke(1f));
                    g2.drawRoundRect(0,0,w-1,h-1,12,12);
                } else if (hoverAlpha > 0.01f) {
                    g2.setColor(new Color(255,255,255,(int)(10*hoverAlpha)));
                    g2.fillRoundRect(2,2,w-4,h-4,10,10);
                }

                // Icon
                float iconSize = sel ? 17f : 15f + hoverAlpha;
                g2.setFont(FONT_UI.deriveFont(iconSize));
                Color iconCol = sel ? C_ACCENT_HI
                    : new Color(C_TEXT_MED.getRed(), C_TEXT_MED.getGreen(), C_TEXT_MED.getBlue(),
                        (int)(90 + 80*hoverAlpha));
                g2.setColor(iconCol);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(icon, (w-fm.stringWidth(icon))/2, 25);

                // Label
                g2.setFont(FONT_UI.deriveFont(Font.BOLD, 8.5f));
                fm = g2.getFontMetrics();
                Color lblCol = sel ? new Color(C_ACCENT.getRed(),C_ACCENT.getGreen(),C_ACCENT.getBlue(),210)
                    : new Color(C_TEXT_DIM.getRed(),C_TEXT_DIM.getGreen(),C_TEXT_DIM.getBlue(),(int)(180+75*hoverAlpha));
                g2.setColor(lblCol);
                g2.drawString(label, (w-fm.stringWidth(label))/2, 42);

                g2.dispose();
            }
            @Override public Dimension getPreferredSize() { return new Dimension(56, 52); }
        };
        return btn;
    }

    private void styleInputField(JTextField f) {
        f.setBackground(C_SURFACE3);
        f.setForeground(C_TEXT);
        f.setFont(FONT_UI);
        f.setCaretColor(C_ACCENT);
        f.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(C_BORDER2, 1),
            new EmptyBorder(5, 10, 5, 10)));
    }

    private void styleToggle(JCheckBox cb, Color fg) {
        cb.setBackground(null); cb.setOpaque(false);
        cb.setForeground(fg); cb.setFont(FONT_UI.deriveFont(12f));
        cb.setFocusPainted(false);
    }

    // ── COMPONENT CLASSES ────────────────────────────────────────────────────

    /** Styled rounded tab pane — kept for backward compat if used anywhere. */
    private static class StyledTabbedPane extends JTabbedPane {
        StyledTabbedPane() {
            setBackground(C_BG); setForeground(C_TEXT);
            setFont(FONT_UI != null ? FONT_UI.deriveFont(Font.BOLD, 11f) : new Font(Font.SANS_SERIF, Font.BOLD, 11));
            setBorder(null);
            setUI(new javax.swing.plaf.basic.BasicTabbedPaneUI() {
                @Override protected void installDefaults() { super.installDefaults(); highlight = C_SURFACE2; lightHighlight = C_SURFACE2; shadow = C_BG; darkShadow = C_BG; focus = C_ACCENT; }
                @Override protected void paintTabBackground(Graphics g, int tp, int ti, int x, int y, int w, int h, boolean sel) {
                    ((Graphics2D)g).setColor(sel ? C_SURFACE2 : C_BG); ((Graphics2D)g).fillRect(x,y,w,h);
                    if (sel) { ((Graphics2D)g).setColor(C_ACCENT); ((Graphics2D)g).fillRect(x,y+h-2,w,2); }
                }
                @Override protected void paintTabBorder(Graphics g, int tp, int ti, int x, int y, int w, int h, boolean sel) {}
                @Override protected void paintFocusIndicator(Graphics g, int tp, Rectangle[] r, int ti, Rectangle ir, Rectangle tr, boolean sel) {}
                @Override protected void paintContentBorder(Graphics g, int tp, int si) {}
                @Override protected int getTabLabelShiftY(int tp, int ti, boolean sel) { return 0; }
                @Override protected int getTabLabelShiftX(int tp, int ti, boolean sel) { return 0; }
            });
        }
    }

    /** Filled pill button – primary actions — with glow shadow */
    static class GlowButton extends JButton {
        private final Color accent;
        private float hoverAlpha = 0f;
        private javax.swing.Timer hoverTimer;
        GlowButton(String text, Color accent) {
            super(text); this.accent = accent;
            setOpaque(false); setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false);
            setForeground(Color.WHITE);
            setFont(FONT_UI != null ? FONT_UI.deriveFont(Font.BOLD, 12f) : new Font(Font.SANS_SERIF, Font.BOLD, 12));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new java.awt.event.MouseAdapter() {
                public void mouseEntered(java.awt.event.MouseEvent e) { animateHover(true); }
                public void mouseExited (java.awt.event.MouseEvent e) { animateHover(false); }
            });
        }
        private void animateHover(boolean in) {
            if (hoverTimer != null) hoverTimer.stop();
            hoverTimer = new javax.swing.Timer(16, null);
            hoverTimer.addActionListener(e -> {
                hoverAlpha += in ? 0.12f : -0.12f;
                if (hoverAlpha <= 0f) { hoverAlpha = 0f; hoverTimer.stop(); }
                if (hoverAlpha >= 1f) { hoverAlpha = 1f; hoverTimer.stop(); }
                repaint();
            });
            hoverTimer.start();
        }
        @Override public Dimension getPreferredSize() { Dimension d=super.getPreferredSize(); return new Dimension(d.width+28, 34); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D)g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            // Outer glow (hovered)
            if (hoverAlpha > 0.01f) {
                int ga = (int)(40 * hoverAlpha);
                g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), ga));
                g2.fillRoundRect(-3, -3, w+6, h+6, 16, 16);
            }
            // Main fill — gradient top-to-bottom
            Color top = hoverAlpha > 0.01f ? accent.brighter() : accent;
            Color bot = new Color(Math.max(0,accent.getRed()-30), Math.max(0,accent.getGreen()-30), Math.max(0,accent.getBlue()-30));
            g2.setPaint(new GradientPaint(0,0,top,0,h,bot));
            g2.fillRoundRect(0,0,w,h,10,10);
            // Inner top highlight
            g2.setPaint(new GradientPaint(0,0,new Color(255,255,255,35),0,h/2,new Color(255,255,255,0)));
            g2.fillRoundRect(1,1,w-2,h/2,10,10);
            g2.setColor(isEnabled() ? getForeground() : new Color(255,255,255,120));
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            g2.drawString(getText(),(w-fm.stringWidth(getText()))/2,(h+fm.getAscent()-fm.getDescent())/2);
            g2.dispose();
        }
    }

    /** Ghost/outline button – secondary actions — animated hover */
    static class DarkButton extends JButton {
        private final Color bgCol;
        private float hoverAlpha = 0f;
        private javax.swing.Timer hoverTimer;
        DarkButton(String text) { this(text, C_SURFACE2); }
        DarkButton(String text, Color bg) {
            super(text); this.bgCol = bg;
            setOpaque(false); setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false);
            setForeground(C_TEXT_MED);
            setFont(FONT_UI != null ? FONT_UI.deriveFont(12f) : new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new java.awt.event.MouseAdapter() {
                public void mouseEntered(java.awt.event.MouseEvent e) { animateHover(true); }
                public void mouseExited (java.awt.event.MouseEvent e) { animateHover(false); }
            });
        }
        private void animateHover(boolean in) {
            if (hoverTimer != null) hoverTimer.stop();
            hoverTimer = new javax.swing.Timer(16, null);
            hoverTimer.addActionListener(e -> {
                hoverAlpha += in ? 0.15f : -0.15f;
                if (hoverAlpha <= 0f) { hoverAlpha = 0f; hoverTimer.stop(); }
                if (hoverAlpha >= 1f) { hoverAlpha = 1f; hoverTimer.stop(); }
                repaint();
            });
            hoverTimer.start();
        }
        @Override public Dimension getPreferredSize() { Dimension d=super.getPreferredSize(); return new Dimension(d.width+20, 30); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D)g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            // Base fill
            g2.setColor(bgCol);
            g2.fillRoundRect(0,0,w,h,8,8);
            // Hover brightening layer
            if (hoverAlpha > 0.01f) {
                g2.setColor(new Color(255,255,255,(int)(18*hoverAlpha)));
                g2.fillRoundRect(0,0,w,h,8,8);
            }
            // Border — brightens on hover
            float ba = 0.5f + 0.5f * hoverAlpha;
            int br = (int)(C_BORDER.getRed()*ba + C_BORDER2.getRed()*(1-ba));
            int bg = (int)(C_BORDER.getGreen()*ba + C_BORDER2.getGreen()*(1-ba));
            int bb = (int)(C_BORDER.getBlue()*ba + C_BORDER2.getBlue()*(1-ba));
            g2.setColor(new Color(Math.min(255,br+(int)(80*hoverAlpha)), Math.min(255,bg+(int)(80*hoverAlpha)), Math.min(255,bb+(int)(80*hoverAlpha))));
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(0,0,w-1,h-1,8,8);
            // Text
            g2.setColor(isEnabled() ? (hoverAlpha>0.5f ? C_TEXT : getForeground()) : C_TEXT_DIM);
            g2.setFont(getFont());
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(getText(),(w-fm.stringWidth(getText()))/2,(h+fm.getAscent()-fm.getDescent())/2);
            g2.dispose();
        }
    }

    private JPanel buildInstancesTab() {
        instanceListModel = new DefaultListModel<>();
        instanceList = new JList<>(instanceListModel);
        instanceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        instanceList.setBackground(C_SIDEBAR);
        instanceList.setForeground(C_TEXT);
        instanceList.setFont(FONT_UI);
        instanceList.setFixedCellHeight(72);
        instanceList.setCellRenderer(new InstanceCellRenderer());
        instanceList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) onInstanceSelected(); });

        // ---- Group filter ----
        groupFilterBox = new JComboBox<>(new String[]{"All Groups"});
        groupFilterBox.setBackground(C_SIDEBAR);
        groupFilterBox.setForeground(C_TEXT_MED);
        groupFilterBox.setFont(FONT_UI.deriveFont(Font.BOLD, 10f));
        groupFilterBox.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER));
        styleCombo(groupFilterBox);  // already present — ensure this line is AFTER the above
        groupFilterBox.addActionListener(e -> filterInstances());

        // ---- Search field ----
        instanceSearchField = new JTextField();
        instanceSearchField.putClientProperty("JTextField.placeholderText", "Search instances...");
        instanceSearchField.setBackground(C_SIDEBAR);
        instanceSearchField.setForeground(C_TEXT);
        instanceSearchField.setFont(FONT_UI.deriveFont(12.5f));
        instanceSearchField.setCaretColor(C_ACCENT);
        instanceSearchField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER), new EmptyBorder(8, 14, 8, 14)));
        instanceSearchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { filterInstances(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { filterInstances(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filterInstances(); }
        });

        JScrollPane listScroll = new JScrollPane(instanceList);
        listScroll.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, C_BORDER));
        listScroll.getViewport().setBackground(C_SIDEBAR);
        listScroll.getVerticalScrollBar().setPreferredSize(new Dimension(4, 0));

        newInstanceBtn    = new GlowButton("+ New", C_ACCENT);
        editInstanceBtn      = new DarkButton("Edit");
        duplicateInstanceBtn = new DarkButton("Copy");
        deleteInstanceBtn    = new DarkButton("Delete", new Color(0x1A0808));
        deleteInstanceBtn.setForeground(C_DANGER);
        deleteInstanceBtn.setEnabled(false); editInstanceBtn.setEnabled(false); duplicateInstanceBtn.setEnabled(false);
        newInstanceBtn.addActionListener(e -> onNewInstance());
        deleteInstanceBtn.addActionListener(e -> onDeleteInstance());
        editInstanceBtn.addActionListener(e -> onEditInstance());
        duplicateInstanceBtn.addActionListener(e -> onDuplicateInstance());

        JPanel listBtns = new JPanel(new GridLayout(2, 3, 4, 4));
        listBtns.setBackground(C_SIDEBAR);
        listBtns.setBorder(new EmptyBorder(7, 8, 9, 8));
        JButton groupAssignBtn = new DarkButton("Group");
        groupAssignBtn.setEnabled(false);
        groupAssignBtn.addActionListener(e -> onManageGroups());
        groupAssignBtn.setName("groupAssignBtn");
        listBtns.add(newInstanceBtn); listBtns.add(duplicateInstanceBtn); listBtns.add(groupAssignBtn);
        listBtns.add(editInstanceBtn); listBtns.add(deleteInstanceBtn); listBtns.add(new JLabel());

        JPanel searchStack = new JPanel(new BorderLayout(0, 0));
        searchStack.setBackground(C_SIDEBAR);
        searchStack.add(groupFilterBox, BorderLayout.NORTH);
        searchStack.add(instanceSearchField, BorderLayout.SOUTH);

        JPanel leftPanel = new JPanel(new BorderLayout(0, 0)) {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(C_SIDEBAR); g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        leftPanel.setOpaque(false);
        leftPanel.setPreferredSize(new Dimension(242, 0));
        leftPanel.add(searchStack, BorderLayout.NORTH);
        leftPanel.add(listScroll, BorderLayout.CENTER);
        leftPanel.add(listBtns, BorderLayout.SOUTH);

        JPanel rightPanel = buildInstanceDetailPanel();

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        split.setDividerLocation(242);
        split.setResizeWeight(0.2);
        split.setBorder(null);
        split.setDividerSize(0);
        split.setBackground(C_BG);

        JPanel tab = new JPanel(new BorderLayout());
        tab.setBackground(C_BG);
        tab.add(split, BorderLayout.CENTER);
        return tab;
    }

    // All loaded instances (unfiltered) for search
    private final List<InstanceConfig> allInstances = new ArrayList<>();

    private void filterInstances() {
        String q = instanceSearchField != null ? instanceSearchField.getText().trim().toLowerCase() : "";
        String selGroup = groupFilterBox != null ? (String) groupFilterBox.getSelectedItem() : "All Groups";
        instanceListModel.clear();
        for (InstanceConfig ic : allInstances) {
            boolean matchSearch = q.isEmpty() || ic.name.toLowerCase().contains(q)
                || ic.mcVersion.toLowerCase().contains(q)
                || (ic.modLoader != null && ic.modLoader.toLowerCase().contains(q));
            boolean matchGroup = "All Groups".equals(selGroup) || selGroup == null
                || selGroup.equals(ic.group != null ? ic.group : "Default");
            if (matchSearch && matchGroup) instanceListModel.addElement(ic);
        }
    }

    /** Rebuild the group filter combobox options from loaded instances. */
    private void refreshGroupFilter() {
        if (groupFilterBox == null) return;
        String current = (String) groupFilterBox.getSelectedItem();
        java.util.Set<String> groups = new java.util.LinkedHashSet<>();
        groups.add("All Groups");
        for (InstanceConfig ic : allInstances) groups.add(ic.group != null ? ic.group : "Default");
        groupFilterBox.setModel(new javax.swing.DefaultComboBoxModel<>(groups.toArray(new String[0])));
        if (current != null && groups.contains(current)) groupFilterBox.setSelectedItem(current);
    }

    private JPanel buildInstanceDetailPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 0));
        panel.setBackground(C_BG);

        // ---- Header card ----
        detailName       = new JLabel("Select an instance");
        detailVersion    = new JLabel("—");
        detailLoader     = new JLabel("—");
        detailLastPlayed = new JLabel("—");
        detailName.setFont(FONT_UI.deriveFont(Font.BOLD, 18f));
        detailName.setForeground(C_TEXT);
        detailVersion.setFont(FONT_UI); detailVersion.setForeground(C_TEXT_MED);
        detailLoader.setFont(FONT_UI);  detailLoader.setForeground(C_TEXT_MED);
        detailLastPlayed.setFont(FONT_MONO.deriveFont(11f)); detailLastPlayed.setForeground(C_TEXT_MED);

        JPanel headerCard = new JPanel(new BorderLayout(0, 6)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(C_SURFACE);
                g2.fillRect(0, 0, getWidth(), getHeight());
                // Radial glow at top-left corner
                for (int i = 6; i > 0; i--) {
                    int a = 4 * i;
                    g2.setColor(new Color(C_ACCENT.getRed(), C_ACCENT.getGreen(), C_ACCENT.getBlue(), a));
                    g2.fillOval(-60+(6-i)*8, -60+(6-i)*8, 120+i*16, 120+i*16);
                }
                // Subtle dot texture
                g2.setColor(new Color(255,255,255,3));
                for (int x = 0; x < getWidth(); x += 4)
                    for (int y = 0; y < getHeight(); y += 4)
                        if ((x+y)%8==0) g2.fillRect(x, y, 1, 1);
                g2.setColor(C_BORDER2); g2.fillRect(0,getHeight()-1,getWidth(),1);
                g2.dispose();
            }
        };
        headerCard.setOpaque(false);
        headerCard.setBorder(new EmptyBorder(16, 20, 16, 20));
        JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        nameRow.setOpaque(false);
        nameRow.add(detailName);

        detailPlaytime  = new JLabel("—");
        detailDiskSize  = new JLabel("—");
        detailPlaytime.setFont(FONT_MONO.deriveFont(11f)); detailPlaytime.setForeground(C_ACCENT_HI);
        detailDiskSize.setFont(FONT_MONO.deriveFont(11f)); detailDiskSize.setForeground(C_TEXT_MED);

        JPanel metaRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 0));
        metaRow.setOpaque(false);
        metaRow.add(dimLabel("MC:", C_TEXT_DIM));       metaRow.add(detailVersion);
        metaRow.add(dimLabel("Loader:", C_TEXT_DIM));   metaRow.add(detailLoader);
        metaRow.add(dimLabel("Last played:", C_TEXT_DIM)); metaRow.add(detailLastPlayed);
        JPanel metaRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 0));
        metaRow2.setOpaque(false);
        metaRow2.add(dimLabel("Playtime:", C_TEXT_DIM)); metaRow2.add(detailPlaytime);
        metaRow2.add(dimLabel("Disk:", C_TEXT_DIM));    metaRow2.add(detailDiskSize);

        JPanel metaStack = new JPanel(); metaStack.setOpaque(false);
        metaStack.setLayout(new BoxLayout(metaStack, BoxLayout.Y_AXIS));
        metaStack.add(metaRow); metaStack.add(metaRow2);

        headerCard.add(nameRow, BorderLayout.NORTH);
        headerCard.add(metaStack, BorderLayout.CENTER);

        // ---- Notes area ----
        notesArea = new JTextArea(3, 20);
        notesArea.setBackground(C_SURFACE3);
        notesArea.setForeground(C_TEXT_MED);
        notesArea.setFont(FONT_UI.deriveFont(11.5f));
        notesArea.setCaretColor(C_ACCENT);
        notesArea.setLineWrap(true); notesArea.setWrapStyleWord(true);
        notesArea.setBorder(new EmptyBorder(6, 8, 6, 8));
        notesArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { saveNotesDebounced(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { saveNotesDebounced(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) {}
        });
        JScrollPane notesScroll = new JScrollPane(notesArea);
        notesScroll.setBorder(BorderFactory.createLineBorder(C_BORDER2));
        notesScroll.setPreferredSize(new Dimension(0, 64));

        // ---- Server list ----
        JPanel serverPanel = new JPanel(new BorderLayout(4, 4));
        serverPanel.setOpaque(false);
        DefaultListModel<String> serverModel = new DefaultListModel<>();
        JList<String> serverList = new JList<>(serverModel);
        serverList.setBackground(C_SURFACE3); serverList.setForeground(C_TEXT);
        serverList.setFont(FONT_MONO.deriveFont(11f));
        serverList.setFixedCellHeight(22);
        serverList.setSelectionBackground(C_ACCENT_DIM);
        serverList.setSelectionForeground(C_ACCENT_HI);
        activeServerModel = serverModel; // wire up so onInstanceSelected can refresh it
        JScrollPane serverScroll = new JScrollPane(serverList);
        serverScroll.setBorder(BorderFactory.createLineBorder(C_BORDER2));
        serverScroll.setPreferredSize(new Dimension(0, 66));
        JTextField serverInput = new JTextField();
        styleField(serverInput);
        serverInput.putClientProperty("JTextField.placeholderText", "Add server IP...");
        JButton addServerBtn = new DarkButton("+");
        JButton removeServerBtn = new DarkButton("-");
        JButton joinServerBtn = new GlowButton("Join", C_ACCENT);
        joinServerBtn.setForeground(C_BG);
        Runnable addServer = () -> {
            String ip = serverInput.getText().trim();
            if (ip.isEmpty()) return;
            InstanceConfig cur = instanceList.getSelectedValue();
            if (cur != null) { cur.servers.add(ip); saveInstanceConfig(cur); serverModel.addElement(ip); serverInput.setText(""); }
        };
        serverInput.addActionListener(e -> addServer.run());
        addServerBtn.addActionListener(e -> addServer.run());
        removeServerBtn.addActionListener(e -> {
            int idx = serverList.getSelectedIndex();
            if (idx < 0) return;
            InstanceConfig cur = instanceList.getSelectedValue();
            if (cur != null) { cur.servers.remove(idx); saveInstanceConfig(cur); serverModel.remove(idx); }
        });
        joinServerBtn.addActionListener(e -> {
            String ip = serverList.getSelectedValue();
            if (ip == null) ip = serverInput.getText().trim();
            if (ip.isEmpty()) { JOptionPane.showMessageDialog(this, "Select or type a server IP first.", "No Server", JOptionPane.WARNING_MESSAGE); return; }
            final String finalIp = ip;
            InstanceConfig cur = instanceList.getSelectedValue();
            if (cur == null) { JOptionPane.showMessageDialog(this, "Select an instance first.", "No Instance", JOptionPane.WARNING_MESSAGE); return; }
            launchInstanceBtn.setEnabled(false);
            final InstanceConfig ic = cur;
            new LaunchWorker() {
                @Override protected Void doInBackground() {
                    try { startProcessWithServer(ic, finalIp); } catch (Exception ex) { log("Launch error: " + ex.getMessage()); }
                    return null;
                }
            }.execute();
        });
        JPanel serverBtnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        serverBtnRow.setOpaque(false);
        serverBtnRow.add(serverInput); serverBtnRow.add(addServerBtn); serverBtnRow.add(removeServerBtn); serverBtnRow.add(joinServerBtn);
        serverPanel.add(serverScroll, BorderLayout.CENTER);
        serverPanel.add(serverBtnRow, BorderLayout.SOUTH);

        // ---- RAM usage label (updated while game runs) ----
        ramUsageLabel = new JLabel("RAM: —");
        ramUsageLabel.setFont(FONT_MONO.deriveFont(10f));
        ramUsageLabel.setForeground(C_TEXT_DIM);

        // ---- Screenshots button ----
        JButton screenshotsBtn = new DarkButton("[S] Screenshots");
        screenshotsBtn.setEnabled(false);
        screenshotsBtn.addActionListener(e -> {
            InstanceConfig ic = instanceList.getSelectedValue();
            if (ic == null) return;
            showScreenshotViewer(ic);
        });
        // store reference so we can enable/disable with the others
        screenshotsBtn.setName("screenshotsBtn");

        // ---- Actions card ----
        JPanel actCard = new JPanel();
        actCard.setLayout(new BoxLayout(actCard, BoxLayout.Y_AXIS));
        actCard.setBackground(C_SURFACE);
        actCard.setBorder(new EmptyBorder(14, 16, 14, 16));

        Dimension maxDim = new Dimension(Integer.MAX_VALUE, 34);

        JLabel actTitle = new JLabel("INSTANCE SETTINGS");
        actTitle.setFont(FONT_UI.deriveFont(Font.BOLD, 10f)); actTitle.setForeground(C_TEXT_DIM);
        actTitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        // RAM row
        instanceRamField = new JTextField("2G", 5);
        instanceRamField.setBackground(C_SURFACE2); instanceRamField.setForeground(C_TEXT);
        instanceRamField.setFont(FONT_MONO); instanceRamField.setCaretColor(C_ACCENT);
        instanceRamField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(C_BORDER), new EmptyBorder(3, 8, 3, 8)));
        JButton saveRamBtn = new DarkButton("Save RAM");
        saveRamBtn.addActionListener(e -> onSaveInstanceRam());
        JPanel ramRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        ramRow.setOpaque(false);
        ramRow.add(dimLabel("RAM:", C_TEXT_DIM)); ramRow.add(instanceRamField); ramRow.add(saveRamBtn); ramRow.add(ramUsageLabel);
        ramRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        // JVM args row
        jvmArgsField = new JTextField("", 22);
        styleField(jvmArgsField);
        jvmArgsField.putClientProperty("JTextField.placeholderText", "Extra JVM flags (e.g. -Xss8m)");
        JButton saveJvmBtn = new DarkButton("Save");
        saveJvmBtn.addActionListener(e -> {
            InstanceConfig ic = instanceList.getSelectedValue();
            if (ic != null) { ic.jvmArgs = jvmArgsField.getText().trim(); saveInstanceConfig(ic); log("JVM args saved for " + ic.name); }
        });
        // JVM preset buttons
        JButton presetPerf    = new DarkButton("⚡ Performance");
        JButton presetStream  = new DarkButton("📹 Streaming");
        JButton presetLowEnd  = new DarkButton("💾 Low-end");
        presetPerf.addActionListener(e -> jvmArgsField.setText("-XX:+AlwaysPreTouch -XX:+DisableExplicitGC -Dfml.ignoreInvalidMinecraftCertificates=true"));
        presetStream.addActionListener(e -> jvmArgsField.setText("-XX:+AggressiveHeap -Xss4m -Dlog4j2.formatMsgNoLookups=true"));
        presetLowEnd.addActionListener(e -> jvmArgsField.setText("-client -Xss1m -XX:+UseSerialGC -XX:MaxPermSize=128m"));
        JPanel jvmRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        jvmRow.setOpaque(false);
        jvmRow.add(dimLabel("JVM:", C_TEXT_DIM)); jvmRow.add(jvmArgsField); jvmRow.add(saveJvmBtn);
        jvmRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel jvmPresets = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        jvmPresets.setOpaque(false);
        jvmPresets.add(presetPerf); jvmPresets.add(presetStream); jvmPresets.add(presetLowEnd);
        jvmPresets.setAlignmentX(Component.LEFT_ALIGNMENT);

        installFabricForInstanceBtn = new DarkButton("[F] Install Fabric");
        installFabricForInstanceBtn.setEnabled(false);
        installFabricForInstanceBtn.addActionListener(e -> onInstallFabricForSelected());

        JButton installForgeForInstanceBtn = new DarkButton("[G] Install Forge");
        installForgeForInstanceBtn.setEnabled(false);
        installForgeForInstanceBtn.addActionListener(e -> {
            InstanceConfig ic2 = instanceList.getSelectedValue();
            if (ic2 != null) installForgeForInstance(ic2, instanceDir(ic2));
        });
        this.installForgeForInstanceBtn = installForgeForInstanceBtn;

        installModForInstanceBtn = new DarkButton("[+] Browse & Install Mods");
        installModForInstanceBtn.setEnabled(false);
        installModForInstanceBtn.addActionListener(e -> onInstallModForSelected());

        // Snapshot buttons
        JButton saveSnapshotBtn    = new DarkButton("[⬆] Save Snapshot");
        JButton restoreSnapshotBtn = new DarkButton("[⬇] Restore Snapshot");
        saveSnapshotBtn.setEnabled(false); restoreSnapshotBtn.setEnabled(false);
        saveSnapshotBtn.setName("saveSnapshotBtn"); restoreSnapshotBtn.setName("restoreSnapshotBtn");
        saveSnapshotBtn.addActionListener(e -> { InstanceConfig ic = instanceList.getSelectedValue(); if (ic != null) saveSnapshot(ic); });
        restoreSnapshotBtn.addActionListener(e -> { InstanceConfig ic = instanceList.getSelectedValue(); if (ic != null) restoreSnapshot(ic); });

        // Disk usage button
        JButton diskBtn = new DarkButton("[💾] Disk Usage");
        diskBtn.setEnabled(false); diskBtn.setName("diskBtn");
        diskBtn.addActionListener(e -> showDiskUsage());

        // Benchmark button
        JButton benchmarkBtn = new DarkButton("[⚙] Benchmark Mode");
        benchmarkBtn.setEnabled(false); benchmarkBtn.setName("benchmarkBtn");
        benchmarkBtn.addActionListener(e -> { InstanceConfig ic = instanceList.getSelectedValue(); if (ic != null) launchBenchmark(ic); });

        actCard.add(actTitle); actCard.add(Box.createVerticalStrut(8));
        actCard.add(ramRow); actCard.add(Box.createVerticalStrut(4));
        actCard.add(jvmRow); actCard.add(Box.createVerticalStrut(2));
        actCard.add(jvmPresets); actCard.add(Box.createVerticalStrut(8));
        for (JButton b : new JButton[]{installFabricForInstanceBtn, installForgeForInstanceBtn, installModForInstanceBtn, screenshotsBtn}) {
            b.setAlignmentX(Component.LEFT_ALIGNMENT); b.setMaximumSize(maxDim);
            actCard.add(b); actCard.add(Box.createVerticalStrut(4));
        }
        actCard.add(Box.createVerticalStrut(8));
        actCard.add(sectionLabel("SNAPSHOTS"));
        for (JButton b : new JButton[]{saveSnapshotBtn, restoreSnapshotBtn}) {
            b.setAlignmentX(Component.LEFT_ALIGNMENT); b.setMaximumSize(maxDim);
            actCard.add(b); actCard.add(Box.createVerticalStrut(4));
        }
        actCard.add(Box.createVerticalStrut(8));
        actCard.add(sectionLabel("TOOLS"));
        JButton exportBtn = new DarkButton("Export Instance (ZIP)");
        JButton importBtn = new DarkButton("Import Instance (ZIP)");
        JButton checkUpdatesBtn = new DarkButton("[U] Check Mod Updates");
        JButton crashBtn = new DarkButton("[!] View Crash Report");
        exportBtn.addActionListener(e -> onExportInstance());
        importBtn.addActionListener(e -> onImportInstance());
        checkUpdatesBtn.addActionListener(e -> onCheckModUpdates());
        crashBtn.addActionListener(e -> { InstanceConfig ic3 = instanceList.getSelectedValue(); if (ic3 != null) showCrashViewer(ic3); });
        for (JButton b : new JButton[]{exportBtn, checkUpdatesBtn, crashBtn, diskBtn, benchmarkBtn}) {
            b.setAlignmentX(Component.LEFT_ALIGNMENT); b.setMaximumSize(maxDim);
            actCard.add(b); actCard.add(Box.createVerticalStrut(4));
        }
        importBtn.setAlignmentX(Component.LEFT_ALIGNMENT); importBtn.setMaximumSize(maxDim);
        actCard.add(importBtn);
        actCard.add(Box.createVerticalStrut(8));
        actCard.add(sectionLabel("NOTES"));
        notesScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        notesScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
        actCard.add(notesScroll); actCard.add(Box.createVerticalStrut(8));
        actCard.add(sectionLabel("QUICK-JOIN SERVERS"));
        serverPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        serverPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
        actCard.add(serverPanel);

        // ---- Launch button ----
        launchInstanceBtn = new JButton("▶  LAUNCH") {
            private boolean hovered = false;
            { addMouseListener(new java.awt.event.MouseAdapter() {
                public void mouseEntered(java.awt.event.MouseEvent e) { hovered = true; repaint(); }
                public void mouseExited(java.awt.event.MouseEvent e)  { hovered = false; repaint(); }
            }); }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
                if (isEnabled()) {
                    Color top = hovered ? new Color(0xA78BFA) : new Color(0x8B5CF6);
                    Color bot = hovered ? new Color(0x7C3AED) : new Color(0x6D28D9);
                    g2.setPaint(new GradientPaint(0, 0, top, 0, getHeight(), bot));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                    // Glow border on hover
                    if (hovered) {
                        g2.setColor(new Color(0xA78BFA, true));
                        g2.setStroke(new BasicStroke(1.5f));
                        g2.drawRoundRect(1,1,getWidth()-2,getHeight()-2,10,10);
                    }
                } else {
                    g2.setColor(C_SURFACE2);
                    g2.fillRoundRect(0,0,getWidth(),getHeight(),10,10);
                }
                g2.setColor(isEnabled() ? Color.WHITE : C_TEXT_DIM);
                g2.setFont(FONT_UI.deriveFont(Font.BOLD, 15f));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(),(getWidth()-fm.stringWidth(getText()))/2,(getHeight()+fm.getAscent()-fm.getDescent())/2);
                g2.dispose();
            }
        };
        launchInstanceBtn.setEnabled(false);
        launchInstanceBtn.setOpaque(false); launchInstanceBtn.setContentAreaFilled(false);
        launchInstanceBtn.setBorderPainted(false); launchInstanceBtn.setFocusPainted(false);
        launchInstanceBtn.setPreferredSize(new Dimension(0, 56));
        launchInstanceBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        launchInstanceBtn.addActionListener(e -> { launchInstanceBtn.setEnabled(false); new LaunchWorker().execute(); });

        // ---- Assemble right panel ----
        JScrollPane actScroll = new JScrollPane(actCard);
        actScroll.setBorder(null);
        actScroll.setBackground(C_BG);
        actScroll.getViewport().setBackground(C_SURFACE);

        JPanel centerContent = new JPanel(new BorderLayout(0, 1));
        centerContent.setBackground(C_BG);
        centerContent.add(headerCard, BorderLayout.NORTH);
        centerContent.add(actScroll, BorderLayout.CENTER);

        panel.add(centerContent, BorderLayout.CENTER);
        JPanel launchWrap = new JPanel(new BorderLayout());
        launchWrap.setBackground(C_BG);
        launchWrap.setBorder(new EmptyBorder(8, 14, 14, 14));
        launchWrap.add(launchInstanceBtn);
        panel.add(launchWrap, BorderLayout.SOUTH);
        return panel;
    }

    private JLabel dimLabel(String text, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(FONT_UI != null ? FONT_UI.deriveFont(11f) : new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        l.setForeground(color != null ? color : C_TEXT_MED);
        return l;
    }

    // ===================== MODS BROWSER TAB =====================

    private JPanel buildModsTab() {
        JPanel tab = new JPanel(new BorderLayout(0, 0));
        tab.setBackground(C_BG);

        // ---- Search bar ----
        JPanel searchBar = new JPanel(new BorderLayout(8, 0)) {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(C_SURFACE); g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(C_BORDER);  g.fillRect(0, getHeight()-1, getWidth(), 1);
            }
        };
        searchBar.setOpaque(false);
        searchBar.setBorder(new EmptyBorder(10, 14, 10, 14));

        modSearchField = new JTextField(26);
        modSearchField.setBackground(C_SURFACE2);
        modSearchField.setForeground(C_TEXT);
        modSearchField.setFont(FONT_UI);
        modSearchField.setCaretColor(C_ACCENT);
        modSearchField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(C_BORDER), new EmptyBorder(5, 10, 5, 10)));

        modCategoryBox = new JComboBox<>(new String[]{
            "All Categories", "Adventure", "Decoration", "Equipment", "Food", "Library",
            "Magic", "Management", "Mobs", "Optimization", "Technology", "Transportation",
            "Utility", "Worldgen"
        });
        modLoaderFilterBox = new JComboBox<>(new String[]{"Any Loader", "fabric", "forge", "quilt", "neoforge"});
        styleCombo(modCategoryBox); styleCombo(modLoaderFilterBox);

        JButton searchBtn = new GlowButton("Search", C_ACCENT);
        searchBtn.setForeground(C_BG);
        JButton clearBtn  = new DarkButton("Clear");
        JButton matchInstanceBtn = new DarkButton("== Match Instance");
        matchInstanceBtn.setToolTipText("Set loader/version to match the selected instance");

        JPanel leftSearch = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftSearch.setOpaque(false);
        leftSearch.add(modSearchField);
        leftSearch.add(dimLabel("Category", C_TEXT_DIM)); leftSearch.add(modCategoryBox);
        leftSearch.add(dimLabel("Loader", C_TEXT_DIM));   leftSearch.add(modLoaderFilterBox);

        JPanel rightSearch = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightSearch.setOpaque(false);
        rightSearch.add(matchInstanceBtn); rightSearch.add(clearBtn); rightSearch.add(searchBtn);

        searchBar.add(leftSearch, BorderLayout.CENTER);
        searchBar.add(rightSearch, BorderLayout.EAST);

        // ---- Status bar ----
        modStatusLabel = new JLabel("  Search for mods above, or use == Match Instance to filter for your selected instance.");
        modStatusLabel.setFont(FONT_UI.deriveFont(Font.ITALIC, 11f));
        modStatusLabel.setForeground(C_TEXT_DIM);
        modStatusLabel.setBorder(new EmptyBorder(4, 14, 4, 14));
        modStatusLabel.setOpaque(true);
        modStatusLabel.setBackground(new Color(0x0D1117));

        // ---- Results grid ----
        modResultsPanel = new JPanel();
        modResultsPanel.setLayout(new WrapLayout(FlowLayout.LEFT, 10, 10));
        modResultsPanel.setBackground(C_BG);
        modResultsPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        modResultsScroll = new JScrollPane(modResultsPanel);
        modResultsScroll.setBorder(null);
        modResultsScroll.setBackground(C_BG);
        modResultsScroll.getViewport().setBackground(C_BG);
        modResultsScroll.getVerticalScrollBar().setUnitIncrement(20);

        tab.add(searchBar, BorderLayout.NORTH);
        tab.add(modStatusLabel, BorderLayout.CENTER);
        JPanel mainArea = new JPanel(new BorderLayout());
        mainArea.setBackground(C_BG);
        mainArea.add(modStatusLabel, BorderLayout.NORTH);
        mainArea.add(modResultsScroll, BorderLayout.CENTER);
        tab.add(mainArea, BorderLayout.CENTER);

        // Wire up
        ActionListener doSearch = e -> triggerModSearch();
        searchBtn.addActionListener(doSearch);
        modSearchField.addActionListener(doSearch);
        clearBtn.addActionListener(e -> {
            modSearchField.setText("");
            modResultsPanel.removeAll();
            modResultsPanel.revalidate(); modResultsPanel.repaint();
            modStatusLabel.setText("  Cleared.");
        });
        matchInstanceBtn.addActionListener(e -> {
            InstanceConfig ic = instanceList.getSelectedValue();
            if (ic == null) { JOptionPane.showMessageDialog(this, "Select an instance first.", "No Instance", JOptionPane.WARNING_MESSAGE); return; }
            String loader = ic.modLoader != null ? ic.modLoader.toLowerCase() : "fabric";
            for (int i = 0; i < modLoaderFilterBox.getItemCount(); i++) {
                if (modLoaderFilterBox.getItemAt(i).equalsIgnoreCase(loader)) { modLoaderFilterBox.setSelectedIndex(i); break; }
            }
            modStatusLabel.setText("  Matched " + loader + " / MC " + ic.mcVersion + " — hit Search.");
        });

        return tab;
    }

    private void styleCombo(JComboBox<?> cb) {
        cb.setBackground(C_SURFACE2);
        cb.setForeground(C_TEXT);
        cb.setFont(FONT_UI);
        cb.setBorder(BorderFactory.createLineBorder(C_BORDER));
        cb.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, sel, focus);
                l.setBackground(sel ? C_ACCENT.darker() : C_SURFACE2);
                l.setForeground(sel ? C_BG : C_TEXT);
                l.setFont(FONT_UI);
                l.setBorder(new EmptyBorder(4, 8, 4, 8));
                return l;
            }
        });
    }

    /**
     * FlowLayout subclass that wraps children properly — standard FlowLayout
     * doesn't re-wrap when the container is resized.
     */
    private static class WrapLayout extends FlowLayout {
        WrapLayout(int align, int hgap, int vgap) { super(align, hgap, vgap); }
        @Override public Dimension preferredLayoutSize(Container target) {
            return layoutSize(target, true);
        }
        @Override public Dimension minimumLayoutSize(Container target) {
            Dimension min = layoutSize(target, false); min.width -= (getHgap() + 1); return min;
        }
        private Dimension layoutSize(Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                int targetWidth = target.getSize().width;
                if (targetWidth == 0) targetWidth = Integer.MAX_VALUE;
                int hgap = getHgap(), vgap = getVgap();
                Insets insets = target.getInsets();
                int maxWidth = targetWidth - (insets.left + insets.right + hgap * 2);
                int rowWidth = 0, rowHeight = 0, totalHeight = insets.top + vgap;
                for (int i = 0; i < target.getComponentCount(); i++) {
                    Component c = target.getComponent(i); if (!c.isVisible()) continue;
                    Dimension d = preferred ? c.getPreferredSize() : c.getMinimumSize();
                    if (rowWidth + d.width > maxWidth) { totalHeight += rowHeight + vgap; rowWidth = 0; rowHeight = 0; }
                    rowWidth += d.width + hgap; rowHeight = Math.max(rowHeight, d.height);
                }
                totalHeight += rowHeight + insets.bottom + vgap;
                return new Dimension(targetWidth, totalHeight);
            }
        }
    }

    private void triggerModSearch() {
        String query    = modSearchField.getText().trim();
        String category = (String) modCategoryBox.getSelectedItem();
        String loader   = (String) modLoaderFilterBox.getSelectedItem();
        InstanceConfig ic = instanceList.getSelectedValue();
        String gameVersion = (ic != null) ? ic.mcVersion : null;

        modStatusLabel.setText("Searching...");
        modResultsPanel.removeAll(); modResultsPanel.revalidate(); modResultsPanel.repaint();

        new Thread(() -> {
            try {
                // Build Modrinth search URL
                // https://docs.modrinth.com/#tag/projects/operation/searchProjects
                StringBuilder url = new StringBuilder(MODRINTH_API + "/search?limit=20&index=relevance&project_type=mod");
                if (!query.isEmpty()) url.append("&query=").append(URLEncoder.encode(query, "UTF-8"));

                // facets: [["project_type:mod"], ["categories:fabric"], ["versions:1.20.1"]]
                List<String> facetGroups = new ArrayList<>();
                if (!"All Categories".equals(category))
                    facetGroups.add("[\"categories:" + category.toLowerCase() + "\"]");
                if (!"Any Loader".equals(loader))
                    facetGroups.add("[\"categories:" + loader.toLowerCase() + "\"]");
                if (gameVersion != null && !gameVersion.isEmpty())
                    facetGroups.add("[\"versions:" + gameVersion + "\"]");
                if (!facetGroups.isEmpty())
                    url.append("&facets=[").append(String.join(",", facetGroups)).append("]");

                String json = downloadString(url.toString());
                JsonObject resp = JsonParser.parseString(json).getAsJsonObject();
                JsonArray hits = resp.has("hits") ? resp.getAsJsonArray("hits") : new JsonArray();
                int total = resp.has("total_hits") ? resp.get("total_hits").getAsInt() : hits.size();

                List<JsonObject> mods = new ArrayList<>();
                for (JsonElement h : hits) mods.add(h.getAsJsonObject());

                SwingUtilities.invokeLater(() -> {
                    modResultsPanel.removeAll();
                    modStatusLabel.setText(total + " results" + (gameVersion != null ? " for MC " + gameVersion : "") + (mods.size() < total ? " (showing " + mods.size() + ")" : ""));
                    for (JsonObject mod : mods) modResultsPanel.add(buildModCard(mod, ic));
                    modResultsPanel.revalidate(); modResultsPanel.repaint();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> modStatusLabel.setText("Search failed: " + ex.getMessage()));
                debugException(ex);
            }
        }).start();
    }

    /**
     * Builds a single mod card panel for the results grid.
     * Shows: icon placeholder, name, author, short description, download count, install button.
     */
    private JPanel buildModCard(JsonObject mod, InstanceConfig targetInstance) {
        String slug        = mod.has("slug")        ? mod.get("slug").getAsString()        : "";
        String title       = mod.has("title")       ? mod.get("title").getAsString()       : slug;
        String author      = mod.has("author")      ? mod.get("author").getAsString()      : "Unknown";
        String description = mod.has("description") ? mod.get("description").getAsString() : "";
        long   downloads   = mod.has("downloads")   ? mod.get("downloads").getAsLong()     : 0;
        String iconUrl     = mod.has("icon_url") && !mod.get("icon_url").isJsonNull() ? mod.get("icon_url").getAsString() : null;
        if (description.length() > 90) description = description.substring(0, 87) + "…";

        JPanel card = new JPanel(new BorderLayout(6, 6)) {
            private boolean hovered = false;
            { addMouseListener(new java.awt.event.MouseAdapter() {
                public void mouseEntered(java.awt.event.MouseEvent e) { hovered = true; repaint(); }
                public void mouseExited(java.awt.event.MouseEvent e)  { hovered = false; repaint(); }
            }); }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(hovered ? C_SURFACE2 : C_SURFACE);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.setColor(hovered ? C_ACCENT.darker().darker() : C_BORDER);
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 8, 8);
                g2.dispose();
            }
        };
        card.setOpaque(false);
        card.setPreferredSize(new Dimension(290, 120));
        card.setBorder(new EmptyBorder(10, 12, 10, 12));

        // Icon
        JLabel iconLabel = new JLabel();
        iconLabel.setPreferredSize(new Dimension(44, 44));
        iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
        iconLabel.setIcon(placeholderIcon(44, 44));
        if (iconUrl != null) {
            final String fUrl = iconUrl;
            new Thread(() -> {
                try {
                    javax.swing.ImageIcon raw = new javax.swing.ImageIcon(new URL(fUrl));
                    Image scaled = raw.getImage().getScaledInstance(44, 44, Image.SCALE_SMOOTH);
                    SwingUtilities.invokeLater(() -> { iconLabel.setIcon(new javax.swing.ImageIcon(scaled)); card.revalidate(); });
                } catch (Exception ignored) {}
            }).start();
        }

        JLabel titleLabel = new JLabel("<html><b style='color:#E6EDF3'>" + escapeHtml(title) + "</b>"
            + "<br><span style='color:#7D8590; font-size:10px'>by " + escapeHtml(author) + "</span></html>");
        titleLabel.setFont(FONT_UI);
        JPanel headRow = new JPanel(new BorderLayout(8, 0));
        headRow.setOpaque(false);
        headRow.add(iconLabel, BorderLayout.WEST);
        headRow.add(titleLabel, BorderLayout.CENTER);

        JLabel descLabel = new JLabel("<html><span style='color:#7D8590'>" + escapeHtml(description) + "</span></html>");
        descLabel.setFont(FONT_UI.deriveFont(11f));

        String dlStr = downloads >= 1_000_000 ? (downloads/1_000_000) + "M ↓"
                     : downloads >= 1_000      ? (downloads/1_000)     + "K ↓"
                     : downloads + " ↓";
        JLabel dlLabel = new JLabel(dlStr);
        dlLabel.setFont(FONT_MONO.deriveFont(10f));
        dlLabel.setForeground(new Color(0x3FB950));

        JButton installBtn = new GlowButton("Install", C_ACCENT);
        installBtn.setForeground(C_BG);
        final String finalSlug = slug;
        installBtn.addActionListener(e -> onInstallModFromCard(finalSlug, title, targetInstance, installBtn));

        JPanel foot = new JPanel(new BorderLayout(4, 0));
        foot.setOpaque(false);
        foot.add(dlLabel, BorderLayout.WEST);
        foot.add(installBtn, BorderLayout.EAST);

        card.add(headRow, BorderLayout.NORTH);
        card.add(descLabel, BorderLayout.CENTER);
        card.add(foot, BorderLayout.SOUTH);
        return card;
    }

    private Icon placeholderIcon(int w, int h) {
        return new Icon() {
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(C_SURFACE2); g2.fillRoundRect(x, y, w, h, 8, 8);
                g2.setColor(C_BORDER); g2.drawRoundRect(x, y, w-1, h-1, 8, 8);
                g2.setColor(C_TEXT_DIM); g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, w/3));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString("?", x + (w - fm.stringWidth("?"))/2, y + (h + fm.getAscent() - fm.getDescent())/2);
                g2.dispose();
            }
            public int getIconWidth()  { return w; }
            public int getIconHeight() { return h; }
        };
    }

    private String escapeHtml(String s) {
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
    }

    private void onInstallModFromCard(String slug, String title, InstanceConfig ic, JButton btn) {
        // If no instance was selected when the search ran, ask now
        InstanceConfig target = ic;
        if (target == null) target = instanceList.getSelectedValue();
        if (target == null) {
            JOptionPane.showMessageDialog(this,
                "Select an instance in the Instances tab first, then come back to install mods.",
                "No Instance Selected", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final InstanceConfig finalTarget = target;

        int confirm = JOptionPane.showConfirmDialog(this,
            "Install \"" + title + "\" into instance \"" + finalTarget.name + "\"?",
            "Install Mod", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        btn.setEnabled(false); btn.setText("Installing...");
        final String instanceDir = instanceDir(finalTarget);
        new Thread(() -> {
            try {
                installModFromModrinth(slug, finalTarget.mcVersion, finalTarget.modLoader, instanceDir);
                SwingUtilities.invokeLater(() -> { btn.setText("Installed!"); btn.setBackground(new Color(0, 70, 0)); });
                log("Mod \"" + title + "\" installed into " + finalTarget.name);
            } catch (Exception ex) {
                log("Mod install failed: " + ex.getMessage()); debugException(ex);
                SwingUtilities.invokeLater(() -> { btn.setEnabled(true); btn.setText("Install"); JOptionPane.showMessageDialog(this, "Install failed:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE); });
            }
        }).start();
    }

    private JPanel buildJavaTab() {
        installJavaButton     = new DarkButton("Install Java (custom ZIP URL)");
        installFabricButton   = new DarkButton("[F] Install Fabric (selected instance)");
        JButton installForgeButton = new DarkButton("[G] Install Forge (selected instance)");
        installModrinthButton = new DarkButton("[+] Install Mod from Modrinth slug");
        installJava25Button   = new DarkButton("Java 25 — Adoptium / Corretto");
        installJava21Button   = new DarkButton("Java 21 — Adoptium / Corretto");
        installJava17Button   = new DarkButton("Java 17 — Adoptium / Corretto");
        installJava8Button    = new DarkButton("Java 8  — Adoptium / Corretto");
        mojangJre21Button     = new DarkButton("[*] Java 21 from Mojang");
        mojangJre17Button     = new DarkButton("[*] Java 17 from Mojang");
        mojangJre8Button      = new DarkButton("[*] Java 8  from Mojang");
        mojangJreAutoButton   = new DarkButton("[*] Auto-install Mojang JRE for instance");

        // Tint Mojang buttons green
        for (JButton b : new JButton[]{mojangJre21Button, mojangJre17Button, mojangJre8Button, mojangJreAutoButton}) {
            ((DarkButton)b).setForeground(new Color(0x56D364));
        }

        installJavaButton.addActionListener(this::onInstallJavaClicked);
        installFabricButton.addActionListener(this::onInstallFabricClicked);
        installForgeButton.addActionListener(e -> {
            InstanceConfig ic = instanceList.getSelectedValue();
            if (ic == null) { JOptionPane.showMessageDialog(this, "Select an instance first.", "No Instance", JOptionPane.WARNING_MESSAGE); return; }
            installForgeForInstance(ic, instanceDir(ic));
        });
        installModrinthButton.addActionListener(this::onInstallModrinthClicked);
        installJava25Button.addActionListener(e -> onInstallRecommendedJavaClicked(25));
        installJava21Button.addActionListener(e -> onInstallRecommendedJavaClicked(21));
        installJava17Button.addActionListener(e -> onInstallRecommendedJavaClicked(17));
        installJava8Button.addActionListener(e -> onInstallRecommendedJavaClicked(8));
        mojangJre21Button.addActionListener(e -> onMojangJreClicked(COMPONENT_JAVA21));
        mojangJre17Button.addActionListener(e -> onMojangJreClicked(COMPONENT_JAVA17));
        mojangJre8Button.addActionListener(e -> onMojangJreClicked(COMPONENT_JAVA8));
        mojangJreAutoButton.addActionListener(e -> onMojangJreAutoClicked());

        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBackground(C_BG);
        col.setBorder(new EmptyBorder(16, 16, 16, 16));
        Dimension maxDim = new Dimension(Integer.MAX_VALUE, 36);

        col.add(sectionLabel("MOJANG OFFICIAL JREs  [*] Recommended"));
        col.add(Box.createVerticalStrut(4));
        for (JButton b : new JButton[]{mojangJreAutoButton, mojangJre21Button, mojangJre17Button, mojangJre8Button}) {
            b.setMaximumSize(maxDim); b.setAlignmentX(Component.LEFT_ALIGNMENT);
            col.add(b); col.add(Box.createVerticalStrut(4));
        }
        col.add(Box.createVerticalStrut(14));
        col.add(sectionLabel("THIRD-PARTY VENDORS  (Fallback)"));
        col.add(Box.createVerticalStrut(4));
        for (JButton b : new JButton[]{installJava25Button, installJava21Button, installJava17Button, installJava8Button}) {
            b.setMaximumSize(maxDim); b.setAlignmentX(Component.LEFT_ALIGNMENT);
            col.add(b); col.add(Box.createVerticalStrut(4));
        }
        col.add(Box.createVerticalStrut(14));
        col.add(sectionLabel("MOD LOADERS & TOOLS"));
        col.add(Box.createVerticalStrut(4));
        for (JButton b : new JButton[]{installFabricButton, installForgeButton, installModrinthButton, installJavaButton}) {
            b.setMaximumSize(maxDim); b.setAlignmentX(Component.LEFT_ALIGNMENT);
            col.add(b); col.add(Box.createVerticalStrut(4));
        }

        JScrollPane scroll = new JScrollPane(col);
        scroll.setBorder(null);
        scroll.setBackground(C_BG);
        scroll.getViewport().setBackground(C_BG);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(C_BG);
        wrapper.add(scroll, BorderLayout.CENTER);
        return wrapper;
    }

    private JLabel sectionLabel(String title) {
        JLabel l = new JLabel(title.toUpperCase()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
                g2.setFont(getFont()); g2.setColor(getForeground());
                FontMetrics fm = g2.getFontMetrics();
                int tw = fm.stringWidth(getText());
                g2.drawString(getText(), 0, fm.getAscent() + 8);
                // Ruled line after text
                int lineY = fm.getAscent() + 8 - fm.getAscent()/2;
                int lineX = tw + 8;
                if (lineX < getWidth() - 4) {
                    g2.setPaint(new GradientPaint(lineX,0,C_BORDER2,Math.min(lineX+80, getWidth()),0,new Color(0,0,0,0)));
                    g2.setStroke(new BasicStroke(0.8f));
                    g2.drawLine(lineX, lineY, getWidth()-4, lineY);
                }
                g2.dispose();
            }
            @Override public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize(); return new Dimension(Integer.MAX_VALUE, d.height+16);
            }
        };
        l.setFont(FONT_UI != null ? FONT_UI.deriveFont(Font.BOLD, 9f) : new Font(Font.SANS_SERIF, Font.BOLD, 9));
        l.setForeground(new Color(C_ACCENT.getRed(), C_ACCENT.getGreen(), C_ACCENT.getBlue(), 180));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        return l;
    }

    // ===================== INSTANCE CELL RENDERER =====================

    private static class InstanceCellRenderer extends JPanel implements ListCellRenderer<InstanceConfig> {
        private InstanceConfig ic;
        private boolean selected;

        InstanceCellRenderer() { setOpaque(false); setPreferredSize(new Dimension(0, 72)); }

        @Override
        public Component getListCellRendererComponent(JList<? extends InstanceConfig> list, InstanceConfig value,
                int index, boolean isSelected, boolean cellHasFocus) {
            this.ic = value; this.selected = isSelected; return this;
        }

        /** Deterministic color from name string */
        private static Color avatarColor(String name) {
            int h = (name != null ? name.hashCode() : 0) & 0x7FFFFFFF;
            float hue = (h % 360) / 360f;
            return Color.getHSBColor(hue, 0.55f, 0.72f);
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (ic == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

            int w = getWidth(), h = getHeight();

            if (selected) {
                // Selected: glass card with accent fill
                g2.setColor(C_ACCENT_DIM);
                g2.fillRect(0, 0, w, h);
                // Gradient fade-in from left
                GradientPaint shine = new GradientPaint(0,0,new Color(C_ACCENT.getRed(),C_ACCENT.getGreen(),C_ACCENT.getBlue(),18),w,0,new Color(0,0,0,0));
                g2.setPaint(shine); g2.fillRect(0,0,w,h);
                // Bright left bar
                g2.setPaint(new GradientPaint(0,4,C_ACCENT,0,h-4,new Color(C_ACCENT.getRed(),C_ACCENT.getGreen(),C_ACCENT.getBlue(),100)));
                g2.fillRoundRect(0,4,3,h-8,3,3);
            } else {
                g2.setColor(C_SIDEBAR);
                g2.fillRect(0, 0, w, h);
            }

            // Bottom separator
            g2.setColor(C_BORDER); g2.fillRect(12, h-1, w-24, 1);

            // Avatar block (left)
            Color av = avatarColor(ic.name);
            int avX = 12, avY = (h-38)/2, avW = 38, avH = 38;
            g2.setColor(new Color(av.getRed(), av.getGreen(), av.getBlue(), selected ? 80 : 50));
            g2.fillRoundRect(avX, avY, avW, avH, 10, 10);
            g2.setColor(new Color(av.getRed(), av.getGreen(), av.getBlue(), selected ? 200 : 140));
            g2.setStroke(new BasicStroke(1.2f));
            g2.drawRoundRect(avX, avY, avW-1, avH-1, 10, 10);
            // Avatar letter
            g2.setFont(FONT_UI != null ? FONT_UI.deriveFont(Font.BOLD, 16f) : new Font(Font.SANS_SERIF, Font.BOLD, 16));
            String letter = ic.name != null && ic.name.length() > 0 ? String.valueOf(ic.name.charAt(0)).toUpperCase() : "?";
            FontMetrics lfm = g2.getFontMetrics();
            g2.setColor(selected ? av.brighter() : av);
            g2.drawString(letter, avX + (avW - lfm.stringWidth(letter))/2, avY + (avH + lfm.getAscent() - lfm.getDescent())/2);

            int tx = avX + avW + 10;

            // Loader badge pill
            String loader = ic.modLoader != null ? ic.modLoader : "VANILLA";
            Color loaderCol = "FABRIC".equals(loader) ? new Color(0xC084FC)
                            : "FORGE".equals(loader)  ? C_AMBER
                            : C_GREEN;
            g2.setFont(FONT_MONO != null ? FONT_MONO.deriveFont(Font.BOLD, 8f) : new Font(Font.MONOSPACED, Font.BOLD, 8));
            String badge = loader.length() > 3 ? loader.substring(0,3) : loader;
            FontMetrics bfm = g2.getFontMetrics();
            int bw = bfm.stringWidth(badge)+10, bh=13, bx=w-bw-10, by=10;
            g2.setColor(new Color(loaderCol.getRed(),loaderCol.getGreen(),loaderCol.getBlue(),20));
            g2.fillRoundRect(bx,by,bw,bh,bh,bh);
            g2.setColor(new Color(loaderCol.getRed(),loaderCol.getGreen(),loaderCol.getBlue(),85));
            g2.setStroke(new BasicStroke(0.8f));
            g2.drawRoundRect(bx,by,bw-1,bh-1,bh,bh);
            g2.setColor(loaderCol);
            g2.drawString(badge, bx+5, by+bfm.getAscent()+(bh-bfm.getHeight())/2+1);

            // Instance name
            g2.setFont(FONT_UI != null ? FONT_UI.deriveFont(Font.BOLD, 13f) : new Font(Font.SANS_SERIF, Font.BOLD, 13));
            g2.setColor(selected ? C_ACCENT_HI : C_TEXT);
            g2.drawString(ic.name, tx, 28);

            // Meta line
            g2.setFont(FONT_MONO != null ? FONT_MONO.deriveFont(10.5f) : new Font(Font.MONOSPACED, Font.PLAIN, 10));
            g2.setColor(selected ? new Color(C_ACCENT.getRed(),C_ACCENT.getGreen(),C_ACCENT.getBlue(),120) : C_TEXT_MED);
            String meta = "MC " + ic.mcVersion + "  ·  " + ic.ram;
            g2.drawString(meta, tx, 46);

            // Playtime micro-bar (if any)
            if (ic.totalPlaytimeSeconds > 0) {
                int barW = Math.min(80, w - tx - 14);
                int barX = tx, barY = 57, barH = 3;
                g2.setColor(C_BORDER);
                g2.fillRoundRect(barX, barY, barW, barH, 2, 2);
                // Scale: cap at 100h for visual
                float fill = Math.min(1f, ic.totalPlaytimeSeconds / (float)(100*3600));
                g2.setColor(new Color(loaderCol.getRed(),loaderCol.getGreen(),loaderCol.getBlue(), selected?200:130));
                g2.fillRoundRect(barX, barY, Math.max(4,(int)(barW*fill)), barH, 2, 2);
                // Playtime text
                g2.setFont(FONT_MONO != null ? FONT_MONO.deriveFont(9f) : new Font(Font.MONOSPACED, Font.PLAIN, 9));
                g2.setColor(selected ? C_ACCENT_HI : C_TEXT_MED);
                g2.drawString(ic.formattedPlaytime(), barX + barW + 5, barY + 4);
            }

            g2.dispose();
        }
    }

    // ===================== INSTANCE MANAGER LOGIC =====================

    /**
     * Scan BASE_DIR/instances/ and load all instance.json files.
     * Folders without instance.json are auto-imported as Vanilla instances
     * (backward-compatible with old launcher that used MC version strings as folder names).
     */
    private void loadInstances() {
        instanceListModel.clear();
        allInstances.clear();
        File instancesRoot = new File(BASE_DIR, "instances");
        if (!instancesRoot.exists()) { log("No instances folder yet. Click '+ New Instance' to create one."); return; }
        File[] folders = instancesRoot.listFiles(File::isDirectory);
        if (folders == null) return;
        Arrays.sort(folders, Comparator.comparing(File::getName));
        for (File folder : folders) {
            File configFile = new File(folder, "instance.json");
            InstanceConfig ic;
            if (configFile.exists()) {
                try {
                    JsonObject obj = JsonParser.parseString(new String(Files.readAllBytes(configFile.toPath()))).getAsJsonObject();
                    ic = InstanceConfig.fromJson(folder.getName(), obj);
                } catch (Exception ex) {
                    log("Warning: bad instance.json in " + folder.getName()); ic = autoImportInstance(folder);
                }
            } else {
                ic = autoImportInstance(folder);
                saveInstanceConfig(ic);
            }
            if (ic != null) { allInstances.add(ic); instanceListModel.addElement(ic); }
        }
        if (instanceListModel.getSize() > 0) instanceList.setSelectedIndex(0);
        log("Loaded " + instanceListModel.getSize() + " instance(s).");
        refreshGroupFilter();
    }

    private InstanceConfig autoImportInstance(File folder) {
        String id = folder.getName();
        String modLoader = "VANILLA";
        File versionsDir = new File(folder, "versions");
        if (versionsDir.exists()) {
            File[] vf = versionsDir.listFiles(f -> f.isDirectory() && f.getName().startsWith("fabric-loader"));
            if (vf != null && vf.length > 0) modLoader = "FABRIC";
            File[] ff = versionsDir.listFiles(f -> f.isDirectory() && f.getName().contains("forge"));
            if (ff != null && ff.length > 0) modLoader = "FORGE";
        }
        return new InstanceConfig(id, id, id, modLoader, "2G");
    }

    private void saveInstanceConfig(InstanceConfig ic) {
        try {
            File folder = new File(BASE_DIR, "instances/" + ic.id);
            folder.mkdirs();
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(ic.toJson());
            Files.write(new File(folder, "instance.json").toPath(), json.getBytes());
        } catch (IOException ex) {
            log("Warning: could not save instance.json for " + ic.id);
        }
    }

    private void onInstanceSelected() {
        InstanceConfig ic = instanceList.getSelectedValue();
        boolean has = ic != null;
        deleteInstanceBtn.setEnabled(has);
        editInstanceBtn.setEnabled(has);
        duplicateInstanceBtn.setEnabled(has);
        launchInstanceBtn.setEnabled(has);
        installFabricForInstanceBtn.setEnabled(has);
        installForgeForInstanceBtn.setEnabled(has);
        installModForInstanceBtn.setEnabled(has);
        // enable all named buttons that require a selection
        enableNamedBtn("screenshotsBtn",    has);
        enableNamedBtn("saveSnapshotBtn",   has);
        enableNamedBtn("restoreSnapshotBtn",has);
        enableNamedBtn("diskBtn",           has);
        enableNamedBtn("benchmarkBtn",      has);
        enableNamedBtn("groupAssignBtn",    has);

        if (!has) {
            detailName.setText("No instance selected"); detailVersion.setText("---");
            detailLoader.setText("---"); detailLastPlayed.setText("---");
            if (detailPlaytime != null) detailPlaytime.setText("—");
            if (detailDiskSize  != null) detailDiskSize.setText("—");
            instanceRamField.setText("2G");
            if (notesArea != null) notesArea.setText("");
            if (jvmArgsField != null) jvmArgsField.setText("");
            return;
        }
        detailName.setText(ic.name);
        detailVersion.setText(ic.mcVersion);
        detailLoader.setText(ic.modLoader != null ? ic.modLoader : "VANILLA");
        detailLastPlayed.setText(ic.lastPlayed != null && !ic.lastPlayed.isEmpty() ? ic.lastPlayed : "Never");
        if (detailPlaytime != null) detailPlaytime.setText(ic.totalPlaytimeSeconds > 0 ? ic.formattedPlaytime() : "None yet");
        instanceRamField.setText(ic.ram != null ? ic.ram : "2G");
        if (jvmArgsField != null) jvmArgsField.setText(ic.jvmArgs != null ? ic.jvmArgs : "");
        if (notesArea != null) notesArea.setText(ic.notes != null ? ic.notes : "");
        rebuildServerListUI(ic);
        // Async disk size calculation
        if (detailDiskSize != null) {
            detailDiskSize.setText("calculating...");
            new Thread(() -> {
                long sz = dirSize(new File(BASE_DIR, "instances/" + ic.id));
                SwingUtilities.invokeLater(() -> { if (instanceList.getSelectedValue() == ic) detailDiskSize.setText(formatBytes(sz)); });
            }).start();
        }
    }

    private void enableNamedBtn(String name, boolean enabled) {
        enableNamedBtn(getContentPane(), name, enabled);
    }
    private void enableNamedBtn(java.awt.Container container, String name, boolean enabled) {
        for (Component c : container.getComponents()) {
            if (c instanceof JButton && name.equals(((JButton)c).getName())) ((JButton)c).setEnabled(enabled);
            if (c instanceof java.awt.Container) enableNamedBtn((java.awt.Container)c, name, enabled);
        }
    }

    // Weak reference to server list model so we can refresh it on selection change
    private DefaultListModel<String> activeServerModel = null;

    private void rebuildServerListUI(InstanceConfig ic) {
        if (activeServerModel == null) return;
        activeServerModel.clear();
        if (ic.servers != null) for (String s : ic.servers) activeServerModel.addElement(s);
    }

    /** "New Instance" button — shows creation dialog. */
    private void onNewInstance() {
        JTextField nameInput = new JTextField("My Instance", 20);
        JComboBox<String> versionInput = new JComboBox<>(allMcVersions.isEmpty()
            ? new String[]{"(versions loading — type manually)"}
            : allMcVersions.toArray(new String[0]));
        versionInput.setEditable(true);
        JComboBox<String> loaderInput = new JComboBox<>(new String[]{"VANILLA", "FABRIC", "FORGE"});
        JTextField ramInput = new JTextField("2G", 6);
        styleCombo(versionInput); styleCombo(loaderInput);
        styleField(nameInput); styleField(ramInput);

        Object[] result = showDarkDialog("Create New Instance",
            new String[]{"Instance Name", "Minecraft Version", "Mod Loader", "RAM (e.g. 2G)"},
            new JComponent[]{nameInput, versionInput, loaderInput, ramInput});
        if (result == null) return;

        String instName  = nameInput.getText().trim();
        String mcVersion = Objects.toString(versionInput.getSelectedItem(), "").trim();
        String modLoader = (String) loaderInput.getSelectedItem();
        String ram       = ramInput.getText().trim();

        if (instName.isEmpty() || mcVersion.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Name and version are required.", "Error", JOptionPane.ERROR_MESSAGE); return;
        }
        if (!validateRam(ram)) {
            JOptionPane.showMessageDialog(this, "Invalid RAM value (e.g. 2G, 512M).", "Error", JOptionPane.ERROR_MESSAGE); return;
        }

        String baseId = sanitizeName(instName) + "-" + mcVersion;
        String id = baseId; int suffix = 2;
        while (new File(BASE_DIR, "instances/" + id).exists()) id = baseId + "-" + (suffix++);

        InstanceConfig ic = new InstanceConfig(id, instName, mcVersion, modLoader, ram);
        new File(BASE_DIR, "instances/" + id).mkdirs();
        saveInstanceConfig(ic);
        allInstances.add(ic);
        instanceListModel.addElement(ic);
        instanceList.setSelectedValue(ic, true);
        log("Created instance: " + instName + " (" + mcVersion + ", " + modLoader + ")");

        int download = JOptionPane.showConfirmDialog(this,
            "Instance created! Download game files for " + mcVersion + " now?\n(You can also do this later by pressing LAUNCH.)",
            "Download Files?", JOptionPane.YES_NO_OPTION);
        if (download == JOptionPane.YES_OPTION) {
            progressBar.setVisible(true);
            final String finalId = id;
            new Thread(() -> {
                try {
                    File bin  = new File(BASE_DIR, "instances/" + finalId + "/bin");
                    File lib  = new File(BASE_DIR, "instances/" + finalId + "/libraries");
                    File jar  = new File(bin, "client.jar");
                    syncVersion(mcVersion, bin, lib, new File(BASE_DIR, "assets"), jar);
                    log("Game files downloaded for: " + instName);
                    if ("FABRIC".equals(modLoader)) {
                        log("Auto-installing Fabric for " + instName + "...");
                        installFabric(mcVersion, instanceDir(finalId));
                        ic.modLoader = "FABRIC"; saveInstanceConfig(ic);
                        SwingUtilities.invokeLater(() -> { int idx2 = instanceListModel.indexOf(ic); if (idx2 >= 0) instanceListModel.set(idx2, ic); });
                    } else if ("FORGE".equals(modLoader)) {
                        log("Auto-installing Forge for " + instName + "...");
                        installForge(mcVersion, instanceDir(finalId));
                        ic.modLoader = "FORGE"; saveInstanceConfig(ic);
                        SwingUtilities.invokeLater(() -> { int idx2 = instanceListModel.indexOf(ic); if (idx2 >= 0) instanceListModel.set(idx2, ic); });
                    }
                    SwingUtilities.invokeLater(() -> log("Instance ready: " + instName));
                } catch (Exception ex) {
                    log("Error downloading files: " + ex.getMessage()); debugException(ex);
                } finally {
                    SwingUtilities.invokeLater(() -> progressBar.setVisible(false));
                }
            }).start();
        }
    }

    /** "Delete Instance" button. */
    private void onDeleteInstance() {
        InstanceConfig ic = instanceList.getSelectedValue();
        if (ic == null) return;
        if (JOptionPane.showConfirmDialog(this,
            "Delete instance \"" + ic.name + "\"?\nThis permanently deletes:\n" + instanceDir(ic),
            "Confirm Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;

        progressBar.setVisible(true); progressBar.setIndeterminate(true);
        new Thread(() -> {
            try {
                deleteRecursively(new File(BASE_DIR, "instances/" + ic.id).toPath());
                SwingUtilities.invokeLater(() -> { allInstances.remove(ic); instanceListModel.removeElement(ic); if (instanceListModel.getSize() > 0) instanceList.setSelectedIndex(0); else onInstanceSelected(); log("Deleted: " + ic.name); });
            } catch (Exception ex) { log("Delete error: " + ex.getMessage()); debugException(ex); }
            finally { SwingUtilities.invokeLater(() -> { progressBar.setVisible(false); progressBar.setIndeterminate(false); }); }
        }).start();
    }

    /** "Edit Instance" button — rename, change version/loader/RAM. */
    private void onEditInstance() {
        InstanceConfig ic = instanceList.getSelectedValue();
        if (ic == null) return;

        JTextField nameInput = new JTextField(ic.name, 20);
        JComboBox<String> versionInput = new JComboBox<>(allMcVersions.toArray(new String[0]));
        versionInput.setEditable(true);
        int idx = allMcVersions.indexOf(ic.mcVersion);
        if (idx >= 0) versionInput.setSelectedIndex(idx); else versionInput.setSelectedItem(ic.mcVersion);
        JComboBox<String> loaderInput = new JComboBox<>(new String[]{"VANILLA", "FABRIC", "FORGE"});
        loaderInput.setSelectedItem(ic.modLoader != null ? ic.modLoader : "VANILLA");
        JTextField ramInput = new JTextField(ic.ram, 6);
        styleCombo(versionInput); styleCombo(loaderInput);
        styleField(nameInput); styleField(ramInput);

        Object[] result = showDarkDialog("Edit Instance: " + ic.name,
            new String[]{"Instance Name", "Minecraft Version", "Mod Loader", "RAM"},
            new JComponent[]{nameInput, versionInput, loaderInput, ramInput});
        if (result == null) return;

        String newName    = nameInput.getText().trim();
        String newVersion = Objects.toString(versionInput.getSelectedItem(), "").trim();
        String newLoader  = (String) loaderInput.getSelectedItem();
        String newRam     = ramInput.getText().trim();
        if (newName.isEmpty()) return;
        if (!validateRam(newRam)) { JOptionPane.showMessageDialog(this, "Invalid RAM value.", "Error", JOptionPane.ERROR_MESSAGE); return; }

        ic.name = newName; ic.mcVersion = newVersion; ic.modLoader = newLoader; ic.ram = newRam;
        saveInstanceConfig(ic);
        int selIdx = instanceList.getSelectedIndex();
        instanceListModel.set(selIdx, ic);
        instanceList.setSelectedIndex(selIdx);
        onInstanceSelected();
        log("Updated instance: " + ic.name);
    }

    /**
     * Shows a fully dark-themed modal dialog with labeled form fields.
     * Returns non-null if user clicked OK, null if cancelled.
     */
    private Object[] showDarkDialog(String title, String[] labels, JComponent[] fields) {
        JDialog dialog = new JDialog(this, title, true);
        dialog.getContentPane().setBackground(C_BG);
        dialog.setLayout(new BorderLayout(0, 0));

        // Form area
        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(C_SURFACE);
        form.setBorder(new EmptyBorder(20, 24, 16, 24));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 0, 6, 12); gbc.anchor = GridBagConstraints.WEST;
        for (int i = 0; i < labels.length; i++) {
            JLabel lbl = new JLabel(labels[i]);
            lbl.setFont(FONT_UI.deriveFont(12f));
            lbl.setForeground(C_TEXT_DIM);
            gbc.gridx = 0; gbc.gridy = i; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            form.add(lbl, gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            form.add(fields[i], gbc);
        }

        // Button row
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnRow.setBackground(C_BG);
        btnRow.setBorder(new EmptyBorder(10, 16, 14, 16));
        JButton cancelBtn = new DarkButton("Cancel");
        JButton okBtn     = new DarkButton("OK", new Color(0x1A3D1A)) {
            { setForeground(C_ACCENT); }
        };
        okBtn.setFont(FONT_UI != null ? FONT_UI.deriveFont(Font.BOLD, 12f) : new Font(Font.SANS_SERIF, Font.BOLD, 12));

        final boolean[] confirmed = {false};
        okBtn.addActionListener(e -> { confirmed[0] = true; dialog.dispose(); });
        cancelBtn.addActionListener(e -> dialog.dispose());
        dialog.getRootPane().setDefaultButton(okBtn);

        btnRow.add(cancelBtn); btnRow.add(okBtn);

        // Title bar stripe
        JPanel titleBar = new JPanel(new BorderLayout());
        titleBar.setBackground(C_SURFACE2);
        titleBar.setBorder(new EmptyBorder(12, 20, 12, 20));
        JLabel titleLbl = new JLabel(title);
        titleLbl.setFont(FONT_UI.deriveFont(Font.BOLD, 14f));
        titleLbl.setForeground(C_TEXT);
        titleBar.add(titleLbl);

        dialog.add(titleBar, BorderLayout.NORTH);
        dialog.add(form, BorderLayout.CENTER);
        dialog.add(btnRow, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(380, dialog.getHeight()));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);

        return confirmed[0] ? fields : null;
    }

    /** Apply dark styling to a JTextField for use inside dark dialogs. */
    private void styleField(JTextField f) {
        f.setBackground(C_SURFACE2);
        f.setForeground(C_TEXT);
        f.setFont(FONT_UI);
        f.setCaretColor(C_ACCENT);
        f.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(C_BORDER), new EmptyBorder(5, 8, 5, 8)));
    }

    private void onSaveInstanceRam() {
        InstanceConfig ic = instanceList.getSelectedValue();
        if (ic == null) return;
        String ram = instanceRamField.getText().trim();
        if (!validateRam(ram)) { JOptionPane.showMessageDialog(this, "Invalid RAM value.", "Error", JOptionPane.ERROR_MESSAGE); return; }
        ic.ram = ram; saveInstanceConfig(ic);
        log("RAM for \"" + ic.name + "\" set to " + ram);
    }

    private void onInstallFabricForSelected() {
        InstanceConfig ic = instanceList.getSelectedValue();
        if (ic == null) return;
        installFabricForInstance(ic, instanceDir(ic));
    }

    private void onInstallModForSelected() {
        InstanceConfig ic = instanceList.getSelectedValue();
        if (ic == null) return;
        String slug = JOptionPane.showInputDialog(this, "Enter Modrinth project slug (e.g. 'fabric-api'):", "Install Mod", JOptionPane.PLAIN_MESSAGE);
        if (slug == null || slug.trim().isEmpty()) return;
        final String slugFinal = slug.trim();
        runTask(
            () -> installModFromModrinth(slugFinal, ic.mcVersion, ic.modLoader, instanceDir(ic)),
            () -> JOptionPane.showMessageDialog(this, "Mod installed.", "Success", JOptionPane.INFORMATION_MESSAGE),
            err -> JOptionPane.showMessageDialog(this, "Failed: " + err, "Error", JOptionPane.ERROR_MESSAGE),
            installModForInstanceBtn);
    }

    // ===================== TUTORIAL =====================

    private void showTutorial() {
        String[][] pages = {
            {
                "Welcome to Gemini Launcher",
                "<html><div style='width:460px;font-family:Segoe UI,sans-serif;'>"
                + "<h2 style='color:#39D353;margin:0 0 10px 0;'>👋 Welcome!</h2>"
                + "<p>Gemini is a standalone Minecraft launcher that runs entirely from one Java file — "
                + "no installer needed. Here's a quick tour of everything it can do.</p>"
                + "<br><p>Use the <b>← →</b> arrows below to navigate, or click a section in the list on the left.</p>"
                + "<br><p style='color:#7D8590;font-size:11px;'>Tip: You can reopen this tutorial anytime via the <b>?</b> button in the top-right corner.</p>"
                + "</div></html>"
            },
            {
                "Creating Your First Instance",
                "<html><div style='width:460px;font-family:Segoe UI,sans-serif;'>"
                + "<h2 style='color:#39D353;margin:0 0 10px 0;'>🟢 Creating an Instance</h2>"
                + "<p>An <b>instance</b> is an isolated Minecraft installation. You can have as many as you want — "
                + "one for vanilla, one for mods, one for each version.</p>"
                + "<br><ol style='margin-left:20px;'>"
                + "<li>Click <b>+ New</b> in the Instances tab.</li>"
                + "<li>Enter a name, pick a Minecraft version, choose a mod loader (Vanilla / Fabric / Forge).</li>"
                + "<li>Set your RAM (e.g. <b>2G</b> or <b>4G</b>).</li>"
                + "<li>Click <b>OK</b>. Optionally download game files immediately.</li>"
                + "</ol>"
                + "<br><p style='color:#7D8590;'>You can also <b>duplicate</b> an existing instance with the Copy button.</p>"
                + "</div></html>"
            },
            {
                "Launching the Game",
                "<html><div style='width:460px;font-family:Segoe UI,sans-serif;'>"
                + "<h2 style='color:#39D353;margin:0 0 10px 0;'>🚀 Launching</h2>"
                + "<p>Select an instance from the list on the left, then click the big green <b>LAUNCH</b> button.</p>"
                + "<br><p>The launcher will:</p>"
                + "<ol style='margin-left:20px;'>"
                + "<li>Download any missing game files (client JAR, libraries, assets).</li>"
                + "<li>Extract native libraries (LWJGL, GLFW).</li>"
                + "<li>Start the game with optimised JVM flags (G1GC, etc.).</li>"
                + "</ol>"
                + "<br><p>Enable <b>Offline mode</b> in the top bar to skip all network requests — "
                + "useful on school networks where Mojang is blocked.</p>"
                + "<br><p style='color:#7D8590;'>The <b>RAM</b> field controls max heap (<code>-Xmx</code>). 2G is fine for vanilla; use 4G+ for heavy modpacks.</p>"
                + "</div></html>"
            },
            {
                "Microsoft Login",
                "<html><div style='width:460px;font-family:Segoe UI,sans-serif;'>"
                + "<h2 style='color:#39D353;margin:0 0 10px 0;'>🔑 Microsoft Login</h2>"
                + "<p>To play on online servers you need to sign in with the Microsoft account that owns Minecraft.</p>"
                + "<br><ol style='margin-left:20px;'>"
                + "<li>Click <b>Sign in with Microsoft</b> in the top-right.</li>"
                + "<li>Your browser opens Microsoft's login page — sign in normally.</li>"
                + "<li>After login the browser shows a blank/error page. <b>Copy the full URL</b> from the address bar.</li>"
                + "<li>Paste it into the dialog in the launcher and click OK.</li>"
                + "</ol>"
                + "<br><p style='color:#7D8590;'>Your session is saved to <code>auth.json</code> and refreshed automatically before each launch.</p>"
                + "</div></html>"
            },
            {
                "Installing Fabric & Forge",
                "<html><div style='width:460px;font-family:Segoe UI,sans-serif;'>"
                + "<h2 style='color:#39D353;margin:0 0 10px 0;'>🧵 Mod Loaders</h2>"
                + "<p><b>Fabric</b> (recommended for modern mods):</p>"
                + "<ul style='margin-left:20px;'><li>Select an instance → click <b>[F] Install Fabric</b>.</li>"
                + "<li>Or choose <b>FABRIC</b> when creating the instance — it auto-installs.</li></ul>"
                + "<br><p><b>Forge</b>:</p>"
                + "<ul style='margin-left:20px;'><li>Select an instance → click <b>[G] Install Forge</b>.</li>"
                + "<li>Requires Java 17+. Make sure game files are downloaded first.</li></ul>"
                + "<br><p style='color:#7D8590;'>After installing a loader, the instance detail shows the updated loader name and launches with the correct main class automatically.</p>"
                + "</div></html>"
            },
            {
                "Installing Mods",
                "<html><div style='width:460px;font-family:Segoe UI,sans-serif;'>"
                + "<h2 style='color:#39D353;margin:0 0 10px 0;'>🧩 Installing Mods</h2>"
                + "<p><b>1. Mods Browser tab</b> — search Modrinth directly:</p>"
                + "<ul style='margin-left:20px;'>"
                + "<li>Go to the <b>MODS</b> tab.</li>"
                + "<li>Search by name, filter by category and loader.</li>"
                + "<li>Click <b>Install →</b> on any result.</li>"
                + "</ul>"
                + "<br><p><b>2. Slug install</b> — if you know the Modrinth project slug:</p>"
                + "<ul style='margin-left:20px;'>"
                + "<li>Click <b>[+] Browse &amp; Install Mods</b> in the instance panel.</li>"
                + "<li>Enter the slug (e.g. <code>fabric-api</code>) and confirm.</li>"
                + "</ul>"
                + "<br><p style='color:#7D8590;'>Use <b>[U] Check Mod Updates</b> to see if newer versions are available on Modrinth for your MC version.</p>"
                + "</div></html>"
            },
            {
                "Quick-Join Servers",
                "<html><div style='width:460px;font-family:Segoe UI,sans-serif;'>"
                + "<h2 style='color:#39D353;margin:0 0 10px 0;'>🌐 Quick-Join Servers</h2>"
                + "<p>Save server IPs per instance and launch directly into a server.</p>"
                + "<br><ol style='margin-left:20px;'>"
                + "<li>Select an instance.</li>"
                + "<li>In the <b>Quick-Join Servers</b> section, type an IP (e.g. <code>mc.hypixel.net</code>) and click <b>+</b>.</li>"
                + "<li>Select it from the list and click <b>Join</b>.</li>"
                + "</ol>"
                + "<br><p>The game launches and auto-connects using <code>--server</code> / <code>--port</code> args.</p>"
                + "<br><p style='color:#7D8590;'>Custom ports: use <code>ip:port</code> format, e.g. <code>play.example.com:25566</code>.</p>"
                + "</div></html>"
            },
            {
                "School / Blocked Networks",
                "<html><div style='width:460px;font-family:Segoe UI,sans-serif;'>"
                + "<h2 style='color:#F0A500;margin:0 0 10px 0;'>🏫 School Networks</h2>"
                + "<p>If your school blocks Mojang's servers, follow this workflow:</p>"
                + "<br><p><b>At home:</b></p>"
                + "<ul style='margin-left:20px;'>"
                + "<li>Create your instance and let it fully download.</li>"
                + "<li>The version JSON is cached in <code>version_cache/</code> automatically.</li>"
                + "</ul>"
                + "<br><p><b>At school:</b></p>"
                + "<ul style='margin-left:20px;'>"
                + "<li>Copy the entire <code>MyCustomLauncher/</code> folder to your laptop.</li>"
                + "<li>Enable <b>Offline mode</b> in the top bar.</li>"
                + "<li>Enable <b>Bypass SSL</b> if there's a proxy intercepting HTTPS.</li>"
                + "<li>Launch — no network requests are made.</li>"
                + "</ul>"
                + "</div></html>"
            },
            {
                "Export, Import & Screenshots",
                "<html><div style='width:460px;font-family:Segoe UI,sans-serif;'>"
                + "<h2 style='color:#39D353;margin:0 0 10px 0;'>📦 Export, Import &amp; Screenshots</h2>"
                + "<p><b>Export:</b> Click <b>Export Instance (ZIP)</b> to back up an instance including mods, configs, and saves.</p>"
                + "<br><p><b>Import:</b> Click <b>Import Instance (ZIP)</b> to restore from a backup or share with a friend.</p>"
                + "<br><p><b>Screenshots:</b> Click <b>[S] Screenshots</b> to browse all screenshots. "
                + "Click any thumbnail to view full-size. Press <b>F2</b> in-game to take one.</p>"
                + "<br><p style='color:#7D8590;'>Crash reports are shown automatically when the game exits with an error — or click <b>[!] View Crash Report</b>.</p>"
                + "</div></html>"
            },
            {
                "Java Setup",
                "<html><div style='width:460px;font-family:Segoe UI,sans-serif;'>"
                + "<h2 style='color:#39D353;margin:0 0 10px 0;'>☕ Java Setup</h2>"
                + "<p>Install Java from the <b>JAVA / TOOLS</b> tab. The recommended option is <b>Mojang Official JREs</b>:</p>"
                + "<ul style='margin-left:20px;'>"
                + "<li><b>Java 21</b> — Minecraft 1.21+</li>"
                + "<li><b>Java 17</b> — Minecraft 1.17–1.20</li>"
                + "<li><b>Java 8</b> — Minecraft 1.16 and older</li>"
                + "<li><b>Auto-detect</b> — picks the right version for the selected instance</li>"
                + "</ul>"
                + "<br><p>Installed JREs are stored in <code>MyCustomLauncher/mojang_jre/</code> — fully portable, no system install needed.</p>"
                + "<br><p style='color:#7D8590;'>Third-party vendors (Adoptium, Corretto) are also available as fallbacks.</p>"
                + "</div></html>"
            },
        };

        JDialog dlg = new JDialog(this, "Gemini Launcher — Tutorial", true);
        dlg.setSize(760, 520);
        dlg.setLocationRelativeTo(this);
        dlg.getContentPane().setBackground(C_BG);
        dlg.setLayout(new BorderLayout(0, 0));
        applyDarkTitleBar(dlg);

        // Left sidebar: page list
        DefaultListModel<String> sideModel = new DefaultListModel<>();
        for (String[] page : pages) sideModel.addElement(page[0]);
        JList<String> sideList = new JList<>(sideModel);
        sideList.setBackground(C_SURFACE);
        sideList.setForeground(C_TEXT_DIM);
        sideList.setFont(FONT_UI.deriveFont(12f));
        sideList.setFixedCellHeight(36);
        sideList.setSelectionBackground(new Color(0x1C3A2A));
        sideList.setSelectionForeground(C_ACCENT);
        sideList.setBorder(new EmptyBorder(4, 0, 4, 0));
        sideList.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel l = new JLabel("  " + (index + 1) + ".  " + value);
            l.setFont(FONT_UI.deriveFont(11f));
            l.setOpaque(true);
            l.setBackground(isSelected ? new Color(0x1C3A2A) : (index % 2 == 0 ? C_SURFACE : C_SURFACE2));
            l.setForeground(isSelected ? C_ACCENT : C_TEXT_DIM);
            l.setBorder(new EmptyBorder(0, 8, 0, 8));
            return l;
        });
        JScrollPane sideScroll = new JScrollPane(sideList);
        sideScroll.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, C_BORDER));
        sideScroll.setPreferredSize(new Dimension(200, 0));
        sideScroll.getViewport().setBackground(C_SURFACE);

        // Main content pane
        JEditorPane content = new JEditorPane("text/html", pages[0][1]);
        content.setEditable(false);
        content.setBackground(C_BG);
        content.setBorder(new EmptyBorder(24, 28, 24, 28));
        ((javax.swing.text.html.HTMLEditorKit) content.getEditorKit()).getStyleSheet()
            .addRule("body{color:#E6EDF3;background:#0D1117;font-family:Segoe UI,sans-serif;font-size:13px;}"
                   + "h2{color:#39D353;} code{color:#79C0FF;background:#1C2330;padding:1px 4px;border-radius:3px;}"
                   + "p,li{line-height:1.6;margin-bottom:6px;} ol,ul{margin-top:4px;}");
        JScrollPane contentScroll = new JScrollPane(content);
        contentScroll.setBorder(null);
        contentScroll.getViewport().setBackground(C_BG);

        // Bottom nav bar
        JPanel navBar = new JPanel(new BorderLayout(8, 0));
        navBar.setBackground(C_SURFACE2);
        navBar.setBorder(new EmptyBorder(8, 16, 8, 16));
        JLabel pageIndicator = new JLabel("1 / " + pages.length, SwingConstants.CENTER);
        pageIndicator.setFont(FONT_UI.deriveFont(11f));
        pageIndicator.setForeground(C_TEXT_DIM);
        DarkButton prevBtn = new DarkButton("← Back");
        DarkButton nextBtn = new DarkButton("Next →");
        DarkButton closeBtn = new DarkButton("Close", new Color(0x1A3D1A)) {{ setForeground(C_ACCENT); }};
        prevBtn.setEnabled(false);

        final int[] cur = {0};
        Runnable gotoPage = () -> {
            content.setText(pages[cur[0]][1]);
            content.setCaretPosition(0);
            pageIndicator.setText((cur[0] + 1) + " / " + pages.length);
            prevBtn.setEnabled(cur[0] > 0);
            nextBtn.setEnabled(cur[0] < pages.length - 1);
            sideList.setSelectedIndex(cur[0]);
            sideList.ensureIndexIsVisible(cur[0]);
        };
        prevBtn.addActionListener(e -> { if (cur[0] > 0) { cur[0]--; gotoPage.run(); } });
        nextBtn.addActionListener(e -> { if (cur[0] < pages.length - 1) { cur[0]++; gotoPage.run(); } });
        closeBtn.addActionListener(e -> dlg.dispose());
        sideList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && sideList.getSelectedIndex() >= 0 && sideList.getSelectedIndex() != cur[0]) {
                cur[0] = sideList.getSelectedIndex(); gotoPage.run();
            }
        });
        // Keyboard navigation
        dlg.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(javax.swing.KeyStroke.getKeyStroke("LEFT"), "prev");
        dlg.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(javax.swing.KeyStroke.getKeyStroke("RIGHT"), "next");
        dlg.getRootPane().getActionMap().put("prev", new javax.swing.AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { if (cur[0] > 0) { cur[0]--; gotoPage.run(); } }
        });
        dlg.getRootPane().getActionMap().put("next", new javax.swing.AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { if (cur[0] < pages.length - 1) { cur[0]++; gotoPage.run(); } }
        });

        JPanel navLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        navLeft.setOpaque(false);
        navLeft.add(prevBtn); navLeft.add(nextBtn);
        navBar.add(navLeft, BorderLayout.WEST);
        navBar.add(pageIndicator, BorderLayout.CENTER);
        navBar.add(closeBtn, BorderLayout.EAST);

        dlg.add(sideScroll, BorderLayout.WEST);
        dlg.add(contentScroll, BorderLayout.CENTER);
        dlg.add(navBar, BorderLayout.SOUTH);
        sideList.setSelectedIndex(0);
        dlg.setVisible(true);
    }

    // ===================== NEW FEATURES =====================

    /** Duplicate an instance — copies the folder and creates a new config. */
    private void onDuplicateInstance() {
        InstanceConfig src = instanceList.getSelectedValue();
        if (src == null) return;
        String newName = JOptionPane.showInputDialog(this, "Name for the copy:", src.name + " (Copy)");
        if (newName == null || newName.trim().isEmpty()) return;
        newName = newName.trim();
        String baseId = sanitizeName(newName) + "-" + src.mcVersion;
        String newId = baseId; int suffix = 2;
        while (new File(BASE_DIR, "instances/" + newId).exists()) newId = baseId + "-" + (suffix++);
        final String finalId = newId; final String finalName = newName;
        runTask(() -> {
            File srcDir = new File(BASE_DIR, "instances/" + src.id);
            File dstDir = new File(BASE_DIR, "instances/" + finalId);
            copyDirectory(srcDir.toPath(), dstDir.toPath());
            InstanceConfig copy = new InstanceConfig(finalId, finalName, src.mcVersion, src.modLoader, src.ram);
            copy.notes = src.notes;
            copy.servers = new ArrayList<>(src.servers);
            saveInstanceConfig(copy);
            SwingUtilities.invokeLater(() -> {
                allInstances.add(copy);
                instanceListModel.addElement(copy);
                instanceList.setSelectedValue(copy, true);
                log("Duplicated '" + src.name + "' → '" + finalName + "'");
            });
        }, null, err -> JOptionPane.showMessageDialog(this, "Copy failed: " + err, "Error", JOptionPane.ERROR_MESSAGE));
    }

    private void copyDirectory(Path src, Path dst) throws IOException {
        Files.walk(src).forEach(s -> {
            try { Files.copy(s, dst.resolve(src.relativize(s)), StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException e) { throw new RuntimeException(e); }
        });
    }

    /** Debounced notes save — waits 800ms after last keystroke before saving. */
    private javax.swing.Timer notesDebounceTimer = null;
    private void saveNotesDebounced() {
        if (notesDebounceTimer != null) notesDebounceTimer.stop();
        notesDebounceTimer = new javax.swing.Timer(800, e -> {
            InstanceConfig ic = instanceList.getSelectedValue();
            if (ic != null && notesArea != null) { ic.notes = notesArea.getText(); saveInstanceConfig(ic); }
        });
        notesDebounceTimer.setRepeats(false);
        notesDebounceTimer.start();
    }

    /** Screenshot viewer — browse PNG/JPG files in the instance's screenshots folder. */
    private void showScreenshotViewer(InstanceConfig ic) {
        File ssDir = new File(instanceDir(ic), "screenshots");
        if (!ssDir.exists() || ssDir.listFiles() == null) {
            JOptionPane.showMessageDialog(this, "No screenshots folder found.\nPlay the game first to take screenshots (F2).", "No Screenshots", JOptionPane.INFORMATION_MESSAGE); return;
        }
        File[] files = ssDir.listFiles(f -> f.getName().toLowerCase().matches(".*\\.(png|jpg|jpeg)"));
        if (files == null || files.length == 0) {
            JOptionPane.showMessageDialog(this, "No screenshots yet.\nPress F2 in-game to take one.", "No Screenshots", JOptionPane.INFORMATION_MESSAGE); return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());

        JDialog dlg = new JDialog(this, "Screenshots — " + ic.name, false);
        dlg.setSize(900, 620); dlg.setLocationRelativeTo(this);
        dlg.getContentPane().setBackground(C_BG);
        dlg.setLayout(new BorderLayout(0, 0));

        // Thumbnail strip
        JPanel strip = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 6));
        strip.setBackground(C_SURFACE);
        JScrollPane stripScroll = new JScrollPane(strip);
        stripScroll.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER));
        stripScroll.setPreferredSize(new Dimension(0, 110));
        stripScroll.getViewport().setBackground(C_SURFACE);

        // Main image display
        JLabel imgLabel = new JLabel("Select a screenshot", SwingConstants.CENTER);
        imgLabel.setForeground(C_TEXT_DIM); imgLabel.setFont(FONT_UI);
        imgLabel.setBackground(C_BG); imgLabel.setOpaque(true);
        JScrollPane imgScroll = new JScrollPane(imgLabel);
        imgScroll.setBorder(null);
        imgScroll.getViewport().setBackground(C_BG);

        JLabel filenameLabel = new JLabel(" "); filenameLabel.setForeground(C_TEXT_DIM); filenameLabel.setFont(FONT_MONO.deriveFont(11f));
        JButton openFolderBtn = new DarkButton("Open Folder");
        openFolderBtn.addActionListener(e -> { try { Desktop.getDesktop().open(ssDir); } catch (Exception ex) { log("Cannot open folder: " + ex.getMessage()); } });
        JPanel bottomBar = new JPanel(new BorderLayout(8, 0));
        bottomBar.setBackground(C_SURFACE2); bottomBar.setBorder(new EmptyBorder(6, 10, 6, 10));
        bottomBar.add(filenameLabel, BorderLayout.CENTER); bottomBar.add(openFolderBtn, BorderLayout.EAST);

        final File[] selected = {null};
        for (File f : files) {
            try {
                BufferedImage thumb = javax.imageio.ImageIO.read(f);
                if (thumb == null) continue;
                int tw = 140, th = 80;
                java.awt.image.BufferedImage scaled = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
                scaled.createGraphics().drawImage(thumb.getScaledInstance(tw, th, java.awt.Image.SCALE_SMOOTH), 0, 0, null);
                JLabel btn = new JLabel(new javax.swing.ImageIcon(scaled));
                btn.setBorder(BorderFactory.createLineBorder(C_BORDER, 1));
                btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                btn.addMouseListener(new java.awt.event.MouseAdapter() {
                    public void mouseClicked(java.awt.event.MouseEvent e) {
                        selected[0] = f;
                        btn.setBorder(BorderFactory.createLineBorder(C_ACCENT, 2));
                        // reload full image
                        try {
                            BufferedImage full = javax.imageio.ImageIO.read(f);
                            imgLabel.setIcon(new javax.swing.ImageIcon(full)); imgLabel.setText(null);
                            filenameLabel.setText(f.getName() + "  (" + full.getWidth() + "×" + full.getHeight() + ")");
                        } catch (Exception ex) { imgLabel.setText("Cannot load image"); }
                        strip.repaint();
                    }
                });
                strip.add(btn);
            } catch (Exception ignored) {}
        }

        dlg.add(stripScroll, BorderLayout.NORTH);
        dlg.add(imgScroll, BorderLayout.CENTER);
        dlg.add(bottomBar, BorderLayout.SOUTH);
        dlg.setVisible(true);
    }

    /** Launch the game and connect directly to a server IP on startup. */
    private void startProcessWithServer(InstanceConfig ic, String serverIp) throws Exception {
        // Delegate to startProcess but with --server flag injected — done via a thread-local
        serverJoinTarget = serverIp;
        try { startProcess(); } finally { serverJoinTarget = null; }
    }
    private volatile String serverJoinTarget = null;

    /** Export an instance to a ZIP file. */
    private void onExportInstance() {
        InstanceConfig ic = instanceList.getSelectedValue();
        if (ic == null) { JOptionPane.showMessageDialog(this, "Select an instance first.", "No Instance", JOptionPane.WARNING_MESSAGE); return; }
        javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
        fc.setSelectedFile(new File(ic.id + ".zip"));
        fc.setDialogTitle("Export Instance As ZIP");
        if (fc.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        File dest = fc.getSelectedFile();
        runTask(
            () -> zipDirectory(new File(BASE_DIR, "instances/" + ic.id).toPath(), dest.toPath()),
            () -> JOptionPane.showMessageDialog(this, "Exported to:\n" + dest, "Export Complete", JOptionPane.INFORMATION_MESSAGE),
            err -> JOptionPane.showMessageDialog(this, "Export failed:\n" + err, "Error", JOptionPane.ERROR_MESSAGE));
    }

    /** Import an instance from a ZIP file. */
    private void onImportInstance() {
        javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
        fc.setDialogTitle("Import Instance from ZIP");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("ZIP files", "zip"));
        if (fc.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        File zip = fc.getSelectedFile();
        String baseName = zip.getName().replaceFirst("\\.zip$", "");
        String newId = baseName; int suffix = 2;
        while (new File(BASE_DIR, "instances/" + newId).exists()) newId = baseName + "-" + (suffix++);
        final String finalId = newId;
        runTask(() -> {
            File dest = new File(BASE_DIR, "instances/" + finalId);
            dest.mkdirs();
            extractZip(zip, dest);
            // Load or auto-generate config
            File cfg = new File(dest, "instance.json");
            InstanceConfig ic = cfg.exists()
                ? InstanceConfig.fromJson(finalId, JsonParser.parseString(new String(Files.readAllBytes(cfg.toPath()))).getAsJsonObject())
                : new InstanceConfig(finalId, finalId, finalId, "VANILLA", "2G");
            ic.id = finalId;
            saveInstanceConfig(ic);
            SwingUtilities.invokeLater(() -> {
                allInstances.add(ic); instanceListModel.addElement(ic); instanceList.setSelectedValue(ic, true);
                log("Imported instance: " + finalId);
            });
        }, null, err -> JOptionPane.showMessageDialog(this, "Import failed:\n" + err, "Error", JOptionPane.ERROR_MESSAGE));
    }

    /** Check all mods in the selected instance against Modrinth for updates. */
    private void onCheckModUpdates() {
        InstanceConfig ic = instanceList.getSelectedValue();
        if (ic == null) { JOptionPane.showMessageDialog(this, "Select an instance first.", "No Instance", JOptionPane.WARNING_MESSAGE); return; }
        File modsDir = new File(instanceDir(ic), "mods");
        if (!modsDir.exists()) { JOptionPane.showMessageDialog(this, "No mods folder found for this instance.", "No Mods", JOptionPane.INFORMATION_MESSAGE); return; }
        File[] jars = modsDir.listFiles(f -> f.getName().endsWith(".jar"));
        if (jars == null || jars.length == 0) { JOptionPane.showMessageDialog(this, "No mods installed in this instance.", "No Mods", JOptionPane.INFORMATION_MESSAGE); return; }
        runTask(() -> {
            StringBuilder report = new StringBuilder("Mod update check for " + ic.name + ":\n\n");
            int checked = 0;
            for (File jar : jars) {
                String slug = jar.getName().replaceFirst("-\\d+.*\\.jar$", "").replaceFirst("\\.jar$", "").toLowerCase().replaceAll("[^a-z0-9-]", "-");
                try {
                    String json = downloadString("https://api.modrinth.com/v2/project/" + slug);
                    JsonObject proj = JsonParser.parseString(json).getAsJsonObject();
                    String latest = proj.has("versions") ? "" : "?";
                    // fetch latest version for this MC version
                    String versUrl = "https://api.modrinth.com/v2/project/" + slug + "/version?game_versions=[%22" + ic.mcVersion + "%22]&loaders=[%22" + ic.modLoader.toLowerCase() + "%22]";
                    try {
                        JsonElement vers = JsonParser.parseString(downloadString(versUrl));
                        if (vers.isJsonArray() && vers.getAsJsonArray().size() > 0) {
                            latest = vers.getAsJsonArray().get(0).getAsJsonObject().get("version_number").getAsString();
                        }
                    } catch (Exception ignored) {}
                    report.append("✓ ").append(jar.getName()).append(" → latest: ").append(latest).append("\n");
                    checked++;
                } catch (Exception e) {
                    report.append("? ").append(jar.getName()).append(" (not found on Modrinth)\n");
                }
            }
            report.append("\nChecked ").append(checked).append("/").append(jars.length).append(" mod(s) against Modrinth.");
            final String result = report.toString();
            SwingUtilities.invokeLater(() -> {
                JTextArea ta = new JTextArea(result, 18, 50); ta.setEditable(false);
                ta.setBackground(C_SURFACE); ta.setForeground(C_TEXT); ta.setFont(FONT_MONO.deriveFont(11f));
                JOptionPane.showMessageDialog(this, new JScrollPane(ta), "Mod Update Report", JOptionPane.PLAIN_MESSAGE);
            });
        }, null, err -> JOptionPane.showMessageDialog(this, "Check failed:\n" + err, "Error", JOptionPane.ERROR_MESSAGE));
    }

    /** Show the latest crash report for the selected instance. */
    private void showCrashViewer(InstanceConfig ic) {
        File crashDir = new File(instanceDir(ic), "crash-reports");
        if (!crashDir.exists()) { JOptionPane.showMessageDialog(this, "No crash reports found.", "No Crashes", JOptionPane.INFORMATION_MESSAGE); return; }
        File[] crashes = crashDir.listFiles(f -> f.getName().endsWith(".txt"));
        if (crashes == null || crashes.length == 0) { JOptionPane.showMessageDialog(this, "No crash reports found.", "No Crashes", JOptionPane.INFORMATION_MESSAGE); return; }
        Arrays.sort(crashes, Comparator.comparingLong(File::lastModified).reversed());
        try {
            String content = new String(Files.readAllBytes(crashes[0].toPath()));
            JTextArea ta = new JTextArea(content, 28, 80);
            ta.setEditable(false); ta.setCaretPosition(0);
            ta.setBackground(new Color(0x1A0A0A)); ta.setForeground(new Color(0xFF7B7B)); ta.setFont(FONT_MONO.deriveFont(11f));
            JDialog dlg = new JDialog(this, "Crash Report — " + crashes[0].getName(), false);
            dlg.setSize(900, 600); dlg.setLocationRelativeTo(this);
            dlg.getContentPane().setBackground(C_BG);
            dlg.add(new JScrollPane(ta));
            JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6)); bar.setBackground(C_SURFACE2);
            JButton openBtn = new DarkButton("Open in Editor");
            openBtn.addActionListener(e -> { try { Desktop.getDesktop().open(crashes[0]); } catch (Exception ex) { log("Cannot open: " + ex.getMessage()); }});
            JButton closeBtn = new DarkButton("Close");
            closeBtn.addActionListener(e -> dlg.dispose());
            bar.add(openBtn); bar.add(closeBtn);
            dlg.add(bar, BorderLayout.SOUTH);
            dlg.setVisible(true);
        } catch (IOException ex) { log("Cannot read crash report: " + ex.getMessage()); }
    }

    /** Start polling the game process's memory usage and display in ramUsageLabel. */
    private void startRamMonitor(Process proc) {
        activeGameProcess = proc;
        new Thread(() -> {
            while (proc.isAlive()) {
                try {
                    // Use jcmd/jps to get PID memory, or just read /proc on Linux; on Windows use tasklist
                    long memMb = estimateProcessMemoryMb(proc);
                    final String txt = memMb > 0 ? "RAM: " + memMb + " MB" : "RAM: running";
                    SwingUtilities.invokeLater(() -> { if (ramUsageLabel != null) ramUsageLabel.setText(txt); });
                    Thread.sleep(2000);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            activeGameProcess = null;
            SwingUtilities.invokeLater(() -> { if (ramUsageLabel != null) ramUsageLabel.setText("RAM: —"); });
        }).start();
    }

    private long estimateProcessMemoryMb(Process proc) {
        try {
            long pid = proc.pid();
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                Process p = new ProcessBuilder("tasklist", "/fi", "PID eq " + pid, "/fo", "csv", "/nh").start();
                String out = readStreamFully(p.getInputStream());
                for (String line : out.split("\n")) {
                    if (line.contains(String.valueOf(pid))) {
                        String[] parts = line.split(",");
                        if (parts.length >= 5) return Long.parseLong(parts[4].replaceAll("[^0-9]", "")) / 1024;
                    }
                }
            } else {
                File status = new File("/proc/" + pid + "/status");
                if (status.exists()) {
                    for (String line : new String(Files.readAllBytes(status.toPath())).split("\n"))
                        if (line.startsWith("VmRSS:")) return Long.parseLong(line.replaceAll("[^0-9]", "")) / 1024;
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /** Send a system tray notification (falls back to log if tray unavailable). */
    private void trayNotify(String title, String message) {
        if (!java.awt.SystemTray.isSupported()) { log("[Notify] " + title + ": " + message); return; }
        try {
            java.awt.SystemTray tray = java.awt.SystemTray.getSystemTray();
            java.awt.TrayIcon icon = new java.awt.TrayIcon(buildAppIcon(), "Gemini Launcher");
            icon.setImageAutoSize(true);
            tray.add(icon);
            icon.displayMessage(title, message, java.awt.TrayIcon.MessageType.INFO);
            // Auto-remove after 6 seconds
            new Thread(() -> { try { Thread.sleep(6000); SwingUtilities.invokeLater(() -> tray.remove(icon)); } catch (InterruptedException ignored) {} }).start();
        } catch (Exception e) { log("[Notify] " + title + ": " + message); }
    }

    // ===================== KEYBOARD SHORTCUTS =====================

    private void registerKeyboardShortcuts() {
        javax.swing.InputMap im = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        javax.swing.ActionMap am = getRootPane().getActionMap();

        // Ctrl+N → New Instance
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_N, java.awt.event.InputEvent.CTRL_DOWN_MASK), "newInstance");
        am.put("newInstance", new javax.swing.AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { onNewInstance(); } });

        // Ctrl+L → Launch
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_L, java.awt.event.InputEvent.CTRL_DOWN_MASK), "launch");
        am.put("launch", new javax.swing.AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { if (launchInstanceBtn.isEnabled()) { launchInstanceBtn.setEnabled(false); new LaunchWorker().execute(); } } });

        // Ctrl+F → Focus log search
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F, java.awt.event.InputEvent.CTRL_DOWN_MASK), "logSearch");
        am.put("logSearch", new javax.swing.AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { toggleLogSearch(); } });

        // Ctrl+D → Duplicate instance
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_D, java.awt.event.InputEvent.CTRL_DOWN_MASK), "duplicate");
        am.put("duplicate", new javax.swing.AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { if (duplicateInstanceBtn.isEnabled()) onDuplicateInstance(); } });

        // Ctrl+T → Theme editor
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_T, java.awt.event.InputEvent.CTRL_DOWN_MASK), "themeEditor");
        am.put("themeEditor", new javax.swing.AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { showThemeEditor(); } });

        // Escape → close log search
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "escape");
        am.put("escape", new javax.swing.AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { if (logSearchField != null && logSearchField.isVisible()) { logSearchField.setVisible(false); if (logSearchField.getParent() != null) logSearchField.getParent().revalidate(); } } });
    }

    private void showKeyboardShortcuts() {
        String[][] shortcuts = {
            {"Ctrl + N",   "New instance"},
            {"Ctrl + L",   "Launch selected instance"},
            {"Ctrl + D",   "Duplicate selected instance"},
            {"Ctrl + F",   "Search console log"},
            {"Ctrl + T",   "Open Theme Editor"},
            {"Escape",     "Close log search bar"},
            {"← / →",      "Navigate tutorial pages"},
        };

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(C_SURFACE);
        panel.setBorder(new EmptyBorder(18, 24, 18, 24));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 0, 4, 24); gbc.anchor = GridBagConstraints.WEST;

        JLabel heading = new JLabel("Keyboard Shortcuts");
        heading.setFont(FONT_UI.deriveFont(Font.BOLD, 14f)); heading.setForeground(C_TEXT);
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        panel.add(heading, gbc);
        gbc.gridwidth = 1; gbc.gridy = 1;
        panel.add(Box.createVerticalStrut(6), gbc);

        for (int i = 0; i < shortcuts.length; i++) {
            gbc.gridx = 0; gbc.gridy = i + 2;
            JLabel key = new JLabel(shortcuts[i][0]);
            key.setFont(FONT_MONO.deriveFont(Font.BOLD, 12f)); key.setForeground(C_ACCENT);
            key.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(C_BORDER, 1),
                new EmptyBorder(2, 8, 2, 8)));
            key.setOpaque(true); key.setBackground(C_SURFACE2);
            panel.add(key, gbc);
            gbc.gridx = 1;
            JLabel desc = new JLabel(shortcuts[i][1]);
            desc.setFont(FONT_UI.deriveFont(12f)); desc.setForeground(C_TEXT_DIM);
            panel.add(desc, gbc);
        }

        JOptionPane.showMessageDialog(this, panel, "Keyboard Shortcuts", JOptionPane.PLAIN_MESSAGE);
    }

    private void toggleLogSearch() {
        if (logSearchField == null) return;
        JPanel bar = (JPanel) logSearchField.getParent();
        if (bar == null) return;
        boolean nowVisible = !bar.isVisible();
        bar.setVisible(nowVisible);
        bar.revalidate();
        if (nowVisible) { logSearchField.requestFocusInWindow(); logSearchField.selectAll(); }
    }

    private int lastSearchPos = 0;
    private void searchLog(String query, boolean forward) {
        if (logArea == null || query == null || query.isEmpty()) { lastSearchPos = 0; return; }
        String text = logArea.getText();
        String lText = text.toLowerCase(), lQuery = query.toLowerCase();
        int start = forward ? lastSearchPos : 0;
        int idx = lText.indexOf(lQuery, start);
        if (idx < 0 && start > 0) idx = lText.indexOf(lQuery, 0); // wrap
        if (idx < 0) { logSearchField.setBackground(new Color(0x3D1A1A)); return; }
        logSearchField.setBackground(new Color(0x0D1A0D));
        logArea.setCaretPosition(idx);
        logArea.select(idx, idx + query.length());
        lastSearchPos = idx + 1;
    }

    // ===================== TRAY ICON / MINIMIZE =====================

    private java.awt.TrayIcon persistentTrayIcon = null;

    private void setupTrayIcon() {
        if (!java.awt.SystemTray.isSupported()) return;
        try {
            java.awt.SystemTray tray = java.awt.SystemTray.getSystemTray();
            persistentTrayIcon = new java.awt.TrayIcon(buildAppIcon(), "Gemini Launcher");
            persistentTrayIcon.setImageAutoSize(true);
            java.awt.PopupMenu menu = new java.awt.PopupMenu();
            java.awt.MenuItem showItem = new java.awt.MenuItem("Show / Hide");
            showItem.addActionListener(e -> SwingUtilities.invokeLater(() -> { setVisible(!isVisible()); if (isVisible()) { setState(java.awt.Frame.NORMAL); toFront(); } }));
            java.awt.MenuItem launchItem = new java.awt.MenuItem("Launch Selected");
            launchItem.addActionListener(e -> SwingUtilities.invokeLater(() -> { if (launchInstanceBtn.isEnabled()) { launchInstanceBtn.setEnabled(false); new LaunchWorker().execute(); } }));
            java.awt.MenuItem quitItem = new java.awt.MenuItem("Quit");
            quitItem.addActionListener(e -> System.exit(0));
            menu.add(showItem); menu.add(launchItem); menu.addSeparator(); menu.add(quitItem);
            persistentTrayIcon.setPopupMenu(menu);
            persistentTrayIcon.addActionListener(e -> SwingUtilities.invokeLater(() -> { setVisible(true); setState(java.awt.Frame.NORMAL); toFront(); }));
            tray.add(persistentTrayIcon);
            // Minimize to tray instead of taskbar
            addWindowStateListener(e -> {
                if ((e.getNewState() & java.awt.Frame.ICONIFIED) != 0) {
                    SwingUtilities.invokeLater(() -> { setVisible(false); setState(java.awt.Frame.NORMAL); });
                }
            });
        } catch (Exception e) { log("Tray icon unavailable: " + e.getMessage()); }
    }

    // ===================== PLAYTIME TRACKING =====================

    private void recordSessionEnd(InstanceConfig ic, long startEpoch) {
        if (ic == null || startEpoch <= 0) return;
        long seconds = Instant.now().getEpochSecond() - startEpoch;
        if (seconds < 5) return; // ignore sub-5s launches
        ic.totalPlaytimeSeconds += seconds;
        saveInstanceConfig(ic);
        SwingUtilities.invokeLater(() -> {
            if (detailPlaytime != null) detailPlaytime.setText(ic.formattedPlaytime());
            int idx = instanceListModel.indexOf(ic);
            if (idx >= 0) instanceListModel.set(idx, ic);
            log("Session ended. Played " + seconds + "s  |  Total: " + ic.formattedPlaytime());
        });
    }

    // ===================== DISK USAGE =====================

    private void showDiskUsage() {
        JDialog dlg = new JDialog(this, "Disk Usage — All Instances", false);
        dlg.setSize(520, 420); dlg.setLocationRelativeTo(this);
        dlg.getContentPane().setBackground(C_BG);
        dlg.setLayout(new BorderLayout(0, 8));

        JPanel listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBackground(C_BG);
        listPanel.setBorder(new EmptyBorder(12, 16, 12, 16));

        JLabel title = new JLabel("Calculating...");
        title.setFont(FONT_UI.deriveFont(Font.BOLD, 13f)); title.setForeground(C_TEXT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        listPanel.add(title); listPanel.add(Box.createVerticalStrut(10));

        JScrollPane scroll = new JScrollPane(listPanel);
        scroll.setBorder(null); scroll.getViewport().setBackground(C_BG);
        dlg.add(scroll, BorderLayout.CENTER);

        JButton closeBtn = new DarkButton("Close");
        closeBtn.addActionListener(e -> dlg.dispose());
        JPanel bot = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 6));
        bot.setBackground(C_SURFACE2); bot.add(closeBtn);
        dlg.add(bot, BorderLayout.SOUTH);
        dlg.setVisible(true);

        // Calculate in background
        new Thread(() -> {
            List<long[]> sizes = new ArrayList<>(); // [instanceIdx, bytes]
            long total = 0;
            for (InstanceConfig ic : allInstances) {
                long sz = dirSize(new File(BASE_DIR, "instances/" + ic.id));
                sizes.add(new long[]{allInstances.indexOf(ic), sz});
                total += sz;
            }
            // Shared assets
            long assetSz = dirSize(new File(BASE_DIR, "assets"));
            final long totalFinal = total + assetSz;
            final List<long[]> sizesFinal = sizes;

            SwingUtilities.invokeLater(() -> {
                title.setText("Total launcher storage: " + formatBytes(totalFinal));
                listPanel.removeAll();
                listPanel.add(title); listPanel.add(Box.createVerticalStrut(10));

                long maxSz = sizesFinal.stream().mapToLong(a -> a[1]).max().orElse(1);
                for (long[] entry : sizesFinal.stream().sorted((a, b) -> Long.compare(b[1], a[1])).collect(Collectors.toList())) {
                    InstanceConfig ic = allInstances.get((int)entry[0]);
                    long sz = entry[1];

                    JPanel row = new JPanel(new BorderLayout(8, 0));
                    row.setOpaque(false); row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
                    row.setAlignmentX(Component.LEFT_ALIGNMENT);

                    JLabel nameLabel = new JLabel(ic.name);
                    nameLabel.setFont(FONT_UI.deriveFont(12f)); nameLabel.setForeground(C_TEXT);
                    nameLabel.setPreferredSize(new Dimension(160, 20));

                    // Mini bar
                    int barW = (int)(200 * sz / Math.max(maxSz, 1));
                    JPanel bar = new JPanel() {
                        @Override protected void paintComponent(Graphics g) {
                            g.setColor(C_SURFACE2); g.fillRect(0, 6, 200, 10);
                            g.setColor(C_ACCENT);   g.fillRect(0, 6, barW, 10);
                        }
                    };
                    bar.setOpaque(false); bar.setPreferredSize(new Dimension(200, 22));

                    JLabel sizeLabel = new JLabel(formatBytes(sz));
                    sizeLabel.setFont(FONT_MONO.deriveFont(11f)); sizeLabel.setForeground(C_TEXT_DIM);

                    row.add(nameLabel, BorderLayout.WEST);
                    row.add(bar, BorderLayout.CENTER);
                    row.add(sizeLabel, BorderLayout.EAST);
                    listPanel.add(row); listPanel.add(Box.createVerticalStrut(4));
                }
                // Shared assets row
                JLabel assetsRow = new JLabel("  Shared assets: " + formatBytes(assetSz));
                assetsRow.setFont(FONT_MONO.deriveFont(11f)); assetsRow.setForeground(C_TEXT_DIM);
                assetsRow.setAlignmentX(Component.LEFT_ALIGNMENT);
                listPanel.add(Box.createVerticalStrut(8)); listPanel.add(assetsRow);
                listPanel.revalidate(); listPanel.repaint();
            });
        }).start();
    }

    private long dirSize(File dir) {
        if (dir == null || !dir.exists()) return 0;
        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0; } }).sum();
        } catch (IOException e) { return 0; }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024*1024) return String.format("%.1f KB", bytes/1024.0);
        if (bytes < 1024*1024*1024) return String.format("%.1f MB", bytes/(1024.0*1024));
        return String.format("%.2f GB", bytes/(1024.0*1024*1024));
    }

    // ===================== SNAPSHOTS =====================

    private void saveSnapshot(InstanceConfig ic) {
        String label = JOptionPane.showInputDialog(this, "Snapshot label:", "snapshot-" + Instant.now().toString().substring(0, 10));
        if (label == null || label.trim().isEmpty()) return;
        label = label.trim().replaceAll("[^a-zA-Z0-9_\\-]", "_");
        File snapshotDir = new File(BASE_DIR, "instances/" + ic.id + "/.snapshots/" + label);
        final String finalLabel = label;
        runTask(() -> {
            File modsDir    = new File(BASE_DIR, "instances/" + ic.id + "/mods");
            File configDir  = new File(BASE_DIR, "instances/" + ic.id + "/config");
            snapshotDir.mkdirs();
            if (modsDir.exists())   copyDirectory(modsDir.toPath(),   new File(snapshotDir, "mods").toPath());
            if (configDir.exists()) copyDirectory(configDir.toPath(), new File(snapshotDir, "config").toPath());
            // Save a metadata file
            String meta = "{\"label\":\"" + finalLabel + "\",\"created\":\"" + Instant.now() + "\",\"mcVersion\":\"" + ic.mcVersion + "\"}";
            Files.write(new File(snapshotDir, "snapshot.json").toPath(), meta.getBytes());
            log("Snapshot saved: " + finalLabel + " for " + ic.name);
        },
        () -> JOptionPane.showMessageDialog(this, "Snapshot '" + finalLabel + "' saved!", "Snapshot Saved", JOptionPane.INFORMATION_MESSAGE),
        err -> JOptionPane.showMessageDialog(this, "Snapshot failed: " + err, "Error", JOptionPane.ERROR_MESSAGE));
    }

    private void restoreSnapshot(InstanceConfig ic) {
        File snapshotsRoot = new File(BASE_DIR, "instances/" + ic.id + "/.snapshots");
        if (!snapshotsRoot.exists()) { JOptionPane.showMessageDialog(this, "No snapshots found for this instance.", "No Snapshots", JOptionPane.INFORMATION_MESSAGE); return; }
        File[] snaps = snapshotsRoot.listFiles(File::isDirectory);
        if (snaps == null || snaps.length == 0) { JOptionPane.showMessageDialog(this, "No snapshots found.", "No Snapshots", JOptionPane.INFORMATION_MESSAGE); return; }
        Arrays.sort(snaps, Comparator.comparingLong(File::lastModified).reversed());
        String[] labels = Arrays.stream(snaps).map(File::getName).toArray(String[]::new);
        String chosen = (String) JOptionPane.showInputDialog(this, "Choose snapshot to restore:", "Restore Snapshot",
            JOptionPane.PLAIN_MESSAGE, null, labels, labels[0]);
        if (chosen == null) return;
        int confirm = JOptionPane.showConfirmDialog(this,
            "Restore '" + chosen + "'?\nThis will REPLACE your current mods and config folders.",
            "Confirm Restore", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;
        File snapDir = new File(snapshotsRoot, chosen);
        runTask(() -> {
            File modsTarget   = new File(BASE_DIR, "instances/" + ic.id + "/mods");
            File configTarget = new File(BASE_DIR, "instances/" + ic.id + "/config");
            File snapMods   = new File(snapDir, "mods");
            File snapConfig = new File(snapDir, "config");
            if (modsTarget.exists())   deleteRecursively(modsTarget.toPath());
            if (configTarget.exists()) deleteRecursively(configTarget.toPath());
            if (snapMods.exists())   copyDirectory(snapMods.toPath(), modsTarget.toPath());
            if (snapConfig.exists()) copyDirectory(snapConfig.toPath(), configTarget.toPath());
            log("Snapshot '" + chosen + "' restored to " + ic.name);
        },
        () -> JOptionPane.showMessageDialog(this, "Snapshot '" + chosen + "' restored!", "Restored", JOptionPane.INFORMATION_MESSAGE),
        err -> JOptionPane.showMessageDialog(this, "Restore failed: " + err, "Error", JOptionPane.ERROR_MESSAGE));
    }

    // ===================== BENCHMARK MODE =====================

    private void launchBenchmark(InstanceConfig ic) {
        int confirm = JOptionPane.showConfirmDialog(this,
            "Benchmark mode launches the game with verbose logging enabled.\n" +
            "After playing, check the log for FPS data parsed from output.\n\nContinue?",
            "Benchmark Mode", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;
        // Store original jvmArgs and append benchmark flags
        String origArgs = ic.jvmArgs;
        ic.jvmArgs = (origArgs != null ? origArgs + " " : "") + "-verbose:gc -Xlog:gc*:stdout";
        launchInstanceBtn.setEnabled(false);
        new LaunchWorker() {
            @Override protected void done() {
                ic.jvmArgs = origArgs; // restore
                SwingUtilities.invokeLater(() -> {
                    launchInstanceBtn.setEnabled(true);
                    parseBenchmarkResults();
                });
            }
        }.execute();
    }

    private void parseBenchmarkResults() {
        String logText = logArea.getText();
        // Look for FPS lines like "[Client thread/INFO]: 45 fps"
        java.util.regex.Pattern fpsPattern = java.util.regex.Pattern.compile("(\\d+) fps");
        java.util.regex.Matcher m = fpsPattern.matcher(logText);
        List<Integer> fpsSamples = new ArrayList<>();
        while (m.find()) { try { fpsSamples.add(Integer.parseInt(m.group(1))); } catch (NumberFormatException ignored) {} }
        if (fpsSamples.isEmpty()) { JOptionPane.showMessageDialog(this, "No FPS data found in log.\nMake sure to play for a bit before closing the game.", "Benchmark", JOptionPane.INFORMATION_MESSAGE); return; }
        int avg = (int) fpsSamples.stream().mapToInt(i->i).average().orElse(0);
        int min = fpsSamples.stream().mapToInt(i->i).min().orElse(0);
        int max = fpsSamples.stream().mapToInt(i->i).max().orElse(0);
        String report = String.format(
            "Benchmark Results\n" +
            "─────────────────\n" +
            "Samples : %d\n" +
            "Average : %d FPS\n" +
            "Min     : %d FPS\n" +
            "Max     : %d FPS\n",
            fpsSamples.size(), avg, min, max);
        JTextArea ta = new JTextArea(report);
        ta.setEditable(false); ta.setBackground(C_SURFACE); ta.setForeground(C_TEXT); ta.setFont(FONT_MONO.deriveFont(13f));
        JOptionPane.showMessageDialog(this, ta, "Benchmark Results", JOptionPane.PLAIN_MESSAGE);
    }

    // ===================== VERSION COMPARISON =====================

    private void showVersionComparison() {
        if (allMcVersions.isEmpty()) { JOptionPane.showMessageDialog(this, "Version list not loaded yet.", "Not Ready", JOptionPane.WARNING_MESSAGE); return; }
        String[] verArr = allMcVersions.toArray(new String[0]);
        JComboBox<String> ver1Box = new JComboBox<>(verArr); styleCombo(ver1Box);
        JComboBox<String> ver2Box = new JComboBox<>(verArr); styleCombo(ver2Box);
        if (verArr.length > 0) ver1Box.setSelectedIndex(0);
        if (verArr.length > 1) ver2Box.setSelectedIndex(1);
        Object[] result = showDarkDialog("Compare Minecraft Versions",
            new String[]{"Version A", "Version B"},
            new JComponent[]{ver1Box, ver2Box});
        if (result == null) return;
        String v1 = (String)ver1Box.getSelectedItem(), v2 = (String)ver2Box.getSelectedItem();
        runTask(() -> {
            String info1 = fetchVersionInfo(v1), info2 = fetchVersionInfo(v2);
            SwingUtilities.invokeLater(() -> showVersionDiff(v1, info1, v2, info2));
        }, null, err -> JOptionPane.showMessageDialog(this, "Could not fetch version data: " + err, "Error", JOptionPane.ERROR_MESSAGE));
    }

    private String fetchVersionInfo(String version) throws Exception {
        // Fetch the manifest entry for this version
        String manifestUrl = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";
        try { manifestUrl = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"; } catch (Exception ignored) {}
        JsonObject manifest = JsonParser.parseString(downloadString(manifestUrl)).getAsJsonObject();
        for (JsonElement ve : manifest.getAsJsonArray("versions")) {
            JsonObject v = ve.getAsJsonObject();
            if (version.equals(v.get("id").getAsString())) {
                String url = v.get("url").getAsString();
                JsonObject vMeta = JsonParser.parseString(downloadString(url)).getAsJsonObject();
                StringBuilder sb = new StringBuilder();
                sb.append("Type: ").append(v.get("type").getAsString()).append("\n");
                sb.append("Release: ").append(v.get("releaseTime").getAsString()).append("\n");
                if (vMeta.has("javaVersion")) sb.append("Java: ").append(vMeta.getAsJsonObject("javaVersion").get("majorVersion").getAsInt()).append("\n");
                if (vMeta.has("minimumLauncherVersion")) sb.append("Min launcher: ").append(vMeta.get("minimumLauncherVersion").getAsInt()).append("\n");
                if (vMeta.has("libraries")) sb.append("Libraries: ").append(vMeta.getAsJsonArray("libraries").size()).append("\n");
                if (vMeta.has("mainClass")) sb.append("Main class: ").append(vMeta.get("mainClass").getAsString()).append("\n");
                return sb.toString();
            }
        }
        return "Version not found in manifest.";
    }

    private void showVersionDiff(String v1, String info1, String v2, String info2) {
        JDialog dlg = new JDialog(this, "Version Comparison: " + v1 + " vs " + v2, false);
        dlg.setSize(700, 400); dlg.setLocationRelativeTo(this);
        dlg.getContentPane().setBackground(C_BG);
        dlg.setLayout(new GridLayout(1, 2, 4, 0));
        for (String[] pair : new String[][]{{v1, info1},{v2, info2}}) {
            JPanel side = new JPanel(new BorderLayout(0, 4));
            side.setBackground(C_SURFACE);
            side.setBorder(new EmptyBorder(12, 14, 12, 14));
            JLabel hdr = new JLabel(pair[0]); hdr.setFont(FONT_UI.deriveFont(Font.BOLD, 14f)); hdr.setForeground(C_ACCENT);
            JTextArea ta = new JTextArea(pair[1]); ta.setEditable(false);
            ta.setBackground(C_SURFACE); ta.setForeground(C_TEXT); ta.setFont(FONT_MONO.deriveFont(12f));
            side.add(hdr, BorderLayout.NORTH); side.add(new JScrollPane(ta) {{ setBorder(null); }}, BorderLayout.CENTER);
            dlg.add(side);
        }
        dlg.setVisible(true);
    }

    // ===================== MODPACK INSTALLER =====================

    private void showModpackInstaller() {
        JTextField urlField = new JTextField(40);
        styleField(urlField); urlField.putClientProperty("JTextField.placeholderText", "Modrinth modpack URL or ID...");
        JComboBox<String> targetBox = new JComboBox<>();
        styleCombo(targetBox);
        for (int i = 0; i < instanceListModel.getSize(); i++) targetBox.addItem(instanceListModel.getElementAt(i).name);
        if (targetBox.getItemCount() == 0) { JOptionPane.showMessageDialog(this, "Create an instance first.", "No Instances", JOptionPane.WARNING_MESSAGE); return; }

        Object[] result = showDarkDialog("Install Modpack from Modrinth",
            new String[]{"Modrinth Project URL or Slug", "Install into Instance"},
            new JComponent[]{urlField, targetBox});
        if (result == null) return;

        String input = urlField.getText().trim();
        // Extract slug from URL if needed
        String slug = input.replaceAll("https?://modrinth\\.com/modpack/", "").replaceAll("/.*", "").trim();
        if (slug.isEmpty()) { JOptionPane.showMessageDialog(this, "Invalid modpack URL.", "Error", JOptionPane.ERROR_MESSAGE); return; }

        int targetIdx = targetBox.getSelectedIndex();
        InstanceConfig targetIc = null;
        int listCount = 0;
        for (int i = 0; i < instanceListModel.getSize(); i++) { if (listCount++ == targetIdx) { targetIc = instanceListModel.getElementAt(i); break; } }
        if (targetIc == null) return;
        final InstanceConfig ic = targetIc;
        final String finalSlug = slug;

        runTask(() -> {
            log("Fetching modpack info: " + finalSlug);
            String projJson = downloadString("https://api.modrinth.com/v2/project/" + finalSlug);
            JsonObject proj = JsonParser.parseString(projJson).getAsJsonObject();
            String projName = proj.has("title") ? proj.get("title").getAsString() : finalSlug;

            // Get latest version for this MC version
            String versUrl = "https://api.modrinth.com/v2/project/" + finalSlug + "/version";
            JsonElement versEl = JsonParser.parseString(downloadString(versUrl));
            if (!versEl.isJsonArray() || versEl.getAsJsonArray().size() == 0) throw new IOException("No versions found for modpack: " + finalSlug);
            JsonObject latestVer = versEl.getAsJsonArray().get(0).getAsJsonObject();

            // Find the .mrpack file
            File modsDir = new File(instanceDir(ic), "mods");
            modsDir.mkdirs();
            int installed = 0;
            if (latestVer.has("files")) {
                for (JsonElement fe : latestVer.getAsJsonArray("files")) {
                    JsonObject file = fe.getAsJsonObject();
                    String url = file.getAsJsonObject("url") != null ? null : file.get("url").getAsString();
                    if (url == null && file.has("url")) url = file.get("url").getAsString();
                    if (url == null) continue;
                    String fname = file.has("filename") ? file.get("filename").getAsString() : "mod-" + installed + ".jar";
                    if (fname.endsWith(".jar")) {
                        downloadFile(url, new File(modsDir, fname));
                        log("Downloaded: " + fname); installed++;
                    }
                }
            }
            // Also get dependencies (individual mod files)
            if (latestVer.has("dependencies")) {
                for (JsonElement dep : latestVer.getAsJsonArray("dependencies")) {
                    JsonObject d = dep.getAsJsonObject();
                    if (!d.has("project_id")) continue;
                    try {
                        String depId = d.get("project_id").getAsString();
                        String depVers = downloadString("https://api.modrinth.com/v2/project/" + depId + "/version?loaders=[%22" + ic.modLoader.toLowerCase() + "%22]");
                        JsonElement dvEl = JsonParser.parseString(depVers);
                        if (dvEl.isJsonArray() && dvEl.getAsJsonArray().size() > 0) {
                            JsonObject depVer = dvEl.getAsJsonArray().get(0).getAsJsonObject();
                            if (depVer.has("files") && depVer.getAsJsonArray("files").size() > 0) {
                                JsonObject df = depVer.getAsJsonArray("files").get(0).getAsJsonObject();
                                String durl = df.get("url").getAsString(), dfname = df.get("filename").getAsString();
                                if (dfname.endsWith(".jar")) { downloadFile(durl, new File(modsDir, dfname)); installed++; }
                            }
                        }
                    } catch (Exception depEx) { log("Dep skip: " + depEx.getMessage()); }
                }
            }
            final int total = installed;
            final String name = projName;
            SwingUtilities.invokeLater(() -> log("Modpack '" + name + "' installed: " + total + " files into " + ic.name));
        },
        () -> JOptionPane.showMessageDialog(this, "Modpack installed!", "Done", JOptionPane.INFORMATION_MESSAGE),
        err -> JOptionPane.showMessageDialog(this, "Modpack install failed:\n" + err, "Error", JOptionPane.ERROR_MESSAGE));
    }

    // ===================== AUTO-UPDATER =====================

    private void checkForLauncherUpdate() {
        new Thread(() -> {
            try {
                // Check a GitHub releases API endpoint — update URL if you host this on GitHub
                String relUrl = "https://api.github.com/repos/NinjaBot2010/gemini-launcher/releases/latest";
                String json = downloadString(relUrl);
                JsonObject rel = JsonParser.parseString(json).getAsJsonObject();
                String latestTag = rel.has("tag_name") ? rel.get("tag_name").getAsString() : null;
                String currentVersion = "v1.0.0"; // bump this on each release
                if (latestTag != null && !latestTag.equals(currentVersion)) {
                    String body = rel.has("body") ? rel.get("body").getAsString() : "";
                    String assets = "";
                    if (rel.has("assets") && rel.getAsJsonArray("assets").size() > 0)
                        assets = rel.getAsJsonArray("assets").get(0).getAsJsonObject().get("browser_download_url").getAsString();
                    final String dlUrl = assets;
                    SwingUtilities.invokeLater(() -> {
                        int choice = JOptionPane.showConfirmDialog(this,
                            "A new version of Gemini Launcher is available!\n\n" +
                            "Latest: " + latestTag + "  (you have " + currentVersion + ")\n\n" +
                            body.substring(0, Math.min(body.length(), 300)) +
                            "\n\nDownload now?",
                            "Update Available", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
                        if (choice == JOptionPane.YES_OPTION && !dlUrl.isEmpty()) {
                            try { Desktop.getDesktop().browse(new java.net.URI(dlUrl)); }
                            catch (Exception ex) { log("Cannot open browser: " + ex.getMessage()); }
                        }
                    });
                }
            } catch (Exception e) {
                // Silently ignore — update check is best-effort
            }
        }).start();
    }

    // ===================== THEME EDITOR =====================

    private void showThemeEditor() {
        JDialog dlg = new JDialog(this, "Theme Editor", true);
        dlg.setSize(420, 380); dlg.setLocationRelativeTo(this);
        dlg.getContentPane().setBackground(C_BG);
        dlg.setLayout(new BorderLayout(0, 0));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(C_SURFACE);
        form.setBorder(new EmptyBorder(16, 20, 16, 20));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 0, 6, 12); gbc.anchor = GridBagConstraints.WEST;

        // Colour slots to edit
        String[] slotNames = {"Accent (green)", "Accent 2 (blue)", "Danger (red)", "Background", "Surface", "Text"};
        Color[] currentColors = {C_ACCENT, C_ACCENT2, C_DANGER, C_BG, C_SURFACE, C_TEXT};
        JButton[] swatches = new JButton[slotNames.length];

        for (int i = 0; i < slotNames.length; i++) {
            final int idx = i;
            JLabel lbl = new JLabel(slotNames[i]);
            lbl.setFont(FONT_UI.deriveFont(12f)); lbl.setForeground(C_TEXT_DIM);
            swatches[i] = new JButton() {
                @Override protected void paintComponent(Graphics g) {
                    g.setColor(currentColors[idx]); g.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                    g.setColor(C_BORDER); ((Graphics2D)g).setStroke(new BasicStroke(1));
                    ((Graphics2D)g).drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 6, 6);
                }
            };
            swatches[i].setPreferredSize(new Dimension(60, 26));
            swatches[i].setContentAreaFilled(false); swatches[i].setBorderPainted(false);
            swatches[i].setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            swatches[i].addActionListener(e -> {
                Color chosen = JColorChooser.showDialog(dlg, "Choose " + slotNames[idx], currentColors[idx]);
                if (chosen != null) { currentColors[idx] = chosen; swatches[idx].repaint(); }
            });
            gbc.gridx = 0; gbc.gridy = i; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            form.add(lbl, gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1;
            form.add(swatches[i], gbc);
        }

        JButton applyBtn = new DarkButton("Apply", new Color(0x1A3D1A)) {{ setForeground(C_ACCENT); }};
        JButton cancelBtn = new DarkButton("Cancel");
        JButton resetBtn  = new DarkButton("Reset to Default");
        applyBtn.addActionListener(e -> {
            C_ACCENT  = currentColors[0]; C_ACCENT2 = currentColors[1];
            C_DANGER  = currentColors[2]; C_BG      = currentColors[3];
            C_SURFACE = currentColors[4]; C_TEXT    = currentColors[5];
            dlg.dispose();
            SwingUtilities.updateComponentTreeUI(this);
            repaint(); revalidate();
            log("Theme updated. Some elements may need a restart to fully refresh.");
        });
        cancelBtn.addActionListener(e -> dlg.dispose());
        resetBtn.addActionListener(e -> {
            C_ACCENT  = new Color(0x39D353); C_ACCENT2 = new Color(0x58A6FF);
            C_DANGER  = new Color(0xF85149); C_BG      = new Color(0x0D1117);
            C_SURFACE = new Color(0x161B22); C_TEXT    = new Color(0xCDD9E5);
            dlg.dispose(); SwingUtilities.updateComponentTreeUI(this); repaint(); revalidate();
        });

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnRow.setBackground(C_BG); btnRow.setBorder(new EmptyBorder(10, 16, 12, 16));
        btnRow.add(resetBtn); btnRow.add(cancelBtn); btnRow.add(applyBtn);

        dlg.add(form, BorderLayout.CENTER); dlg.add(btnRow, BorderLayout.SOUTH);
        dlg.setVisible(true);
    }

    // ===================== GROUP MANAGEMENT =====================

    private void onManageGroups() {
        InstanceConfig ic = instanceList.getSelectedValue();
        if (ic == null) { JOptionPane.showMessageDialog(this, "Select an instance first.", "No Instance", JOptionPane.WARNING_MESSAGE); return; }

        // Build set of existing groups
        java.util.Set<String> existing = new java.util.LinkedHashSet<>();
        existing.add("Default");
        for (InstanceConfig i2 : allInstances) if (i2.group != null) existing.add(i2.group);

        JComboBox<String> groupBox = new JComboBox<>(existing.toArray(new String[0]));
        groupBox.setEditable(true); styleCombo(groupBox);
        groupBox.setSelectedItem(ic.group != null ? ic.group : "Default");

        Object[] result = showDarkDialog("Assign Group: " + ic.name,
            new String[]{"Group (type new or pick existing)"},
            new JComponent[]{groupBox});
        if (result == null) return;

        String newGroup = Objects.toString(groupBox.getSelectedItem(), "Default").trim();
        if (newGroup.isEmpty()) newGroup = "Default";
        ic.group = newGroup;
        saveInstanceConfig(ic);
        refreshGroupFilter();
        int idx = instanceListModel.indexOf(ic);
        if (idx >= 0) instanceListModel.set(idx, ic);
        log("Instance '" + ic.name + "' moved to group: " + newGroup);
    }

    private class FetchVersionsWorker extends SwingWorker<Void, Void> {
        @Override protected Void doInBackground() { fetchVersions(); return null; }
    }

    private class LaunchWorker extends SwingWorker<Void, Void> {
        @Override protected Void doInBackground() { startProcess(); return null; }
        @Override protected void done() { SwingUtilities.invokeLater(() -> launchInstanceBtn.setEnabled(true)); }
    }

    // Fallback version list used when Mojang's servers are unreachable (e.g. school networks).
    // Update this list manually if newer versions are needed.
    private static final List<String> FALLBACK_VERSIONS = Arrays.asList(
        "1.21.4", "1.21.3", "1.21.2", "1.21.1", "1.21",
        "1.20.6", "1.20.5", "1.20.4", "1.20.3", "1.20.2", "1.20.1", "1.20",
        "1.19.4", "1.19.3", "1.19.2", "1.19.1", "1.19",
        "1.18.2", "1.18.1", "1.18",
        "1.17.1", "1.17",
        "1.16.5", "1.16.4", "1.16.3", "1.16.2", "1.16.1", "1.16",
        "1.15.2", "1.15.1", "1.15",
        "1.14.4", "1.14.3", "1.14.2", "1.14.1", "1.14",
        "1.13.2", "1.13.1", "1.13",
        "1.12.2", "1.12.1", "1.12",
        "1.11.2", "1.11",
        "1.10.2", "1.10",
        "1.9.4", "1.9",
        "1.8.9", "1.8",
        "1.7.10"
    );

    private void fetchVersions() {
        new Thread(() -> {
            String[] allManifests = new String[1 + MANIFEST_MIRRORS.length];
            allManifests[0] = MANIFEST_URL;
            System.arraycopy(MANIFEST_MIRRORS, 0, allManifests, 1, MANIFEST_MIRRORS.length);

            for (String mUrl : allManifests) {
                try {
                    log("Fetching version manifest from " + mUrl.replaceAll("https?://", "").split("/")[0] + "...");
                    String manifestJson = downloadString(mUrl);
                    JsonObject manifest = JsonParser.parseString(manifestJson).getAsJsonObject();
                    List<String> vList = new ArrayList<>();
                    for (JsonElement v : manifest.getAsJsonArray("versions")) {
                        JsonObject vo = v.getAsJsonObject();
                        if ("release".equals(vo.has("type") ? vo.get("type").getAsString() : "")) vList.add(vo.get("id").getAsString());
                    }
                    SwingUtilities.invokeLater(() -> { allMcVersions.clear(); allMcVersions.addAll(vList); log("Loaded " + vList.size() + " release versions."); });
                    return; // success
                } catch (Exception e) {
                    log("Failed (" + mUrl.replaceAll("https?://", "").split("/")[0] + "): " + e.getMessage().split("\n")[0]);
                }
            }
            // All mirrors failed — use built-in list
            log("All manifest sources blocked. Using built-in version list.");
            SwingUtilities.invokeLater(() -> { allMcVersions.clear(); allMcVersions.addAll(FALLBACK_VERSIONS); log("Loaded " + FALLBACK_VERSIONS.size() + " built-in versions."); });
        }).start();
    }

    // ===================== LAUNCH LOGIC =====================

    private void startProcess() {
        InstanceConfig ic = instanceList.getSelectedValue();
        if (ic == null) { log("No instance selected."); return; }
        try {
            String vId = ic.mcVersion;
            String instanceDir = instanceDir(ic);
            File bin = new File(instanceDir, "bin"), lib = new File(instanceDir, "libraries");
            File assets = new File(BASE_DIR, "assets"), jar = new File(bin, "client.jar");

            if (!offlineToggle.isSelected()) { log("Syncing " + vId + "..."); syncVersion(vId, bin, lib, assets, jar); }

            // Silently refresh token if expired
            if (currentSession != null && currentSession.isExpired()) {
                log("Access token expired, refreshing silently...");
                try { refreshSession(); SwingUtilities.invokeLater(() -> updateAuthUI()); }
                catch (Exception ex) { log("Token refresh failed: " + ex.getMessage() + "\nLaunching in offline mode."); currentSession = null; SwingUtilities.invokeLater(() -> updateAuthUI()); }
            }

            File versionsDir = new File(instanceDir, "versions");
            File[] fabricFolders = versionsDir.listFiles(f -> f.getName().startsWith("fabric-loader"));
            File[] forgeFolders  = versionsDir.listFiles(f -> f.isDirectory() && f.getName().contains("forge"));

            String mainClass, cp, launchVId;
            if (fabricFolders != null && fabricFolders.length > 0) {
                log("Fabric detected! Modded launch...");
                launchVId = fabricFolders[0].getName();
                mainClass = "net.fabricmc.loader.impl.launch.knot.KnotClient";
                cp = buildFabricClasspath(jar, lib);
            } else if (forgeFolders != null && forgeFolders.length > 0) {
                log("Forge detected! Modded launch...");
                File forgeFolder = forgeFolders[0];
                launchVId = forgeFolder.getName();
                // Read mainClass from Forge's generated version JSON
                File forgeJson = new File(forgeFolder, forgeFolder.getName() + ".json");
                mainClass = "cpw.mods.bootstraplauncher.BootstrapLauncher"; // Forge 1.17+ default
                if (forgeJson.exists()) {
                    try {
                        JsonObject fd = JsonParser.parseString(new String(Files.readAllBytes(forgeJson.toPath()))).getAsJsonObject();
                        if (fd.has("mainClass")) mainClass = fd.get("mainClass").getAsString();
                    } catch (Exception ignored) {}
                }
                cp = buildForgeClasspath(new File(instanceDir), jar);
            } else {
                launchVId = vId;
                mainClass = getMainClassFromMeta(instanceDir);
                cp = buildClasspath(jar, lib);
            }

            log("Launching " + ic.name + "...");
            ic.lastPlayed = Instant.now().toString();
            launchEpochSeconds = Instant.now().getEpochSecond();
            saveInstanceConfig(ic);
            SwingUtilities.invokeLater(() -> {
                detailLastPlayed.setText(ic.lastPlayed);
                int selIdx = instanceList.getSelectedIndex();
                if (selIdx >= 0) instanceListModel.set(selIdx, ic);
            });

            launch(instanceDir, launchVId, cp, assets, mainClass, getAssetIndex(vId, instanceDir), ic.ram);
        } catch (Exception e) { log("Error: " + e.getMessage()); debugException(e); }
    }

    private String getMainClassFromMeta(String instanceDir) {
        try {
            File meta = new File(instanceDir, "bin/version.json");
            if (meta.exists()) {
                JsonObject vData = JsonParser.parseString(new String(Files.readAllBytes(meta.toPath()))).getAsJsonObject();
                if (vData.has("mainClass")) return vData.get("mainClass").getAsString();
            }
        } catch (Exception ignored) {}
        return "net.minecraft.client.main.Main";
    }

    private String buildFabricClasspath(File clientJar, File libDir) throws IOException {
        StringBuilder sb = new StringBuilder(clientJar.getAbsolutePath()).append(File.pathSeparator);
        File patchDir = new File(BASE_DIR, "tools/lwjgl_patch");
        boolean hasPatch = patchDir.exists() && patchDir.listFiles((d, n) -> n.endsWith(".jar")) != null
                           && patchDir.listFiles((d, n) -> n.endsWith(".jar")).length > 0;

        if (libDir.exists()) {
            Files.walk(libDir.toPath()).filter(p -> p.toString().endsWith(".jar")).forEach(p -> {
                String name = p.getFileName().toString().toLowerCase();
                // If we have patched LWJGL jars, skip the originals so patched ones take precedence.
                // If no patch dir, include all LWJGL jars normally — they're required.
                if (hasPatch && name.contains("lwjgl")) return;
                sb.append(p.toAbsolutePath()).append(File.pathSeparator);
            });
        }
        if (hasPatch) {
            File[] patchedJars = patchDir.listFiles((d, n) -> n.endsWith(".jar"));
            if (patchedJars != null) for (File p : patchedJars) { sb.append(p.getAbsolutePath()).append(File.pathSeparator); log("Injected patched: " + p.getName()); }
        }
        return sb.toString();
    }

    private void launch(String dir, String vId, String cp, File assets, String mainClass, String assetIndex, String ram) throws IOException {
        if (!validateRam(ram)) { log("Invalid RAM: " + ram); return; }
        File natives = new File(dir, "bin/natives");
        if (!natives.exists() || !containsLwjglNative(natives)) { log("ERROR: No LWJGL native in: " + natives.getAbsolutePath()); return; }

        List<String> cmd = new ArrayList<>();
        cmd.add(findJava17Executable());
        cmd.add("-Xmx" + ram);
        cmd.add("--add-opens"); cmd.add("java.base/java.io=ALL-UNNAMED");
        cmd.add("--add-opens"); cmd.add("java.base/java.lang=ALL-UNNAMED");
        cmd.add("--add-opens"); cmd.add("java.base/java.lang.reflect=ALL-UNNAMED");
        cmd.add("--add-opens"); cmd.add("java.base/java.net=ALL-UNNAMED");
        cmd.add("--add-opens"); cmd.add("java.base/java.nio=ALL-UNNAMED");
        cmd.add("--add-opens"); cmd.add("java.base/java.util=ALL-UNNAMED");
        cmd.add("--add-opens"); cmd.add("java.base/sun.nio.ch=ALL-UNNAMED");
        cmd.add("-Djava.library.path=" + natives.getAbsolutePath());
        cmd.add("-Dorg.lwjgl.librarypath=" + natives.getAbsolutePath());
        cmd.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=" + natives.getAbsolutePath());
        cmd.add("-Dorg.lwjgl.system.SharedLibraryExtractDirectory=.");
        cmd.add("-XX:CompileThreshold=10000");
        cmd.add("-Xss4m");
        cmd.add("-XX:-UseCompressedClassPointers");
        cmd.add("-XX:+UnlockExperimentalVMOptions");
        cmd.add("-XX:+UseG1GC");
        cmd.add("-XX:G1NewSizePercent=20");
        cmd.add("-XX:G1ReservePercent=20");
        cmd.add("-XX:MaxGCPauseMillis=50");
        cmd.add("-XX:G1HeapRegionSize=32M");
        cmd.add("-XX:-UseGCOverheadLimit");
        cmd.add("-Dlog4j2.formatMsgNoLookups=true");
        // Inject per-instance custom JVM args
        InstanceConfig selectedIc = instanceList.getSelectedValue();
        if (selectedIc != null && selectedIc.jvmArgs != null && !selectedIc.jvmArgs.trim().isEmpty()) {
            for (String arg : selectedIc.jvmArgs.trim().split("\\s+")) if (!arg.isEmpty()) cmd.add(arg);
        }
        cmd.add("-cp"); cmd.add(cp); cmd.add(mainClass);
        // Use real Minecraft token if logged in, otherwise offline mode
        String username, accessToken, uuid;
        if (currentSession != null && currentSession.minecraftToken != null) {
            username    = currentSession.minecraftName;
            accessToken = currentSession.minecraftToken;
            uuid        = currentSession.minecraftUuid;
        } else {
            username    = nameField.getText();
            accessToken = "0";
            uuid        = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        }
        cmd.add("--username"); cmd.add(username);
        cmd.add("--version"); cmd.add(vId);
        cmd.add("--gameDir"); cmd.add(dir);
        cmd.add("--assetsDir"); cmd.add(assets.getAbsolutePath());
        cmd.add("--assetIndex"); cmd.add(assetIndex != null ? assetIndex : vId);
        cmd.add("--accessToken"); cmd.add(accessToken);
        cmd.add("--uuid"); cmd.add(uuid);
        // Quick-join server if requested
        if (serverJoinTarget != null && !serverJoinTarget.isEmpty()) {
            String[] parts = serverJoinTarget.contains(":") ? serverJoinTarget.split(":", 2) : new String[]{serverJoinTarget, "25565"};
            cmd.add("--server"); cmd.add(parts[0]);
            cmd.add("--port");   cmd.add(parts[1]);
            log("Connecting to server: " + serverJoinTarget);
        }
        log("Launch command: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd); pb.directory(new File(dir)); pb.redirectErrorStream(true);
        Process p = pb.start();
        startRamMonitor(p);
        new Thread(() -> streamToLog(p.getInputStream())).start();
        final String instanceDirCapture = dir;
        final InstanceConfig icCapture = instanceList.getSelectedValue();
        final long sessionStart = launchEpochSeconds;
        new Thread(() -> {
            try {
                int exit = p.waitFor();
                log("Game exited: " + exit);
                recordSessionEnd(icCapture, sessionStart);
                SwingUtilities.invokeLater(() -> trayNotify("Game Closed", "Minecraft exited" + (exit != 0 ? " with code " + exit : " normally") + "."));
                if (exit != 0 && icCapture != null) {
                    File crashDir = new File(instanceDirCapture, "crash-reports");
                    File[] crashes = crashDir.listFiles(f -> f.getName().endsWith(".txt"));
                    if (crashes != null && crashes.length > 0) {
                        Arrays.sort(crashes, Comparator.comparingLong(File::lastModified).reversed());
                        SwingUtilities.invokeLater(() -> {
                            int choice = JOptionPane.showConfirmDialog(this,
                                "The game crashed (exit code " + exit + ").\nView the crash report?",
                                "Game Crashed", JOptionPane.YES_NO_OPTION, JOptionPane.ERROR_MESSAGE);
                            if (choice == JOptionPane.YES_OPTION) showCrashViewer(icCapture);
                        });
                    }
                }
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }).start();
    }


    // ===================== MICROSOFT / MINECRAFT AUTH =====================

    /**
     * Full OAuth 2.0 → Xbox Live → XSTS → Minecraft login flow.
     *
     * Step 1:  Open Microsoft's OAuth consent page in the system browser.
     * Step 2:  Spin up a local HTTP server on 127.0.0.1:9999 to catch the redirect.
     *          (Microsoft allows "localhost" redirects for native/desktop apps even with
     *           the live.com redirect URI — the browser follows the redirect and we
     *           intercept it at the OS level via a custom URI scheme… but that's complex.
     *           Instead we show a dialog asking the user to paste the redirect URL,
     *           which is what many launchers do to avoid needing a registered URI scheme.)
     * Step 3:  Exchange auth code for MS access + refresh tokens.
     * Step 4:  Exchange MS access token for Xbox Live (XBL) token.
     * Step 5:  Exchange XBL token for XSTS token.
     * Step 6:  Exchange XSTS token for Minecraft bearer token.
     * Step 7:  Fetch Minecraft profile (UUID + username).
     * Step 8:  Persist session to BASE_DIR/auth.json.
     */
    private void onLoginClicked() {
        loginButton.setEnabled(false);
        log("Starting Microsoft login...");

        // Open the auth URL in the default browser
        try {
            Desktop.getDesktop().browse(new URI(MS_AUTH_URL));
        } catch (Exception ex) {
            log("Could not open browser automatically: " + ex.getMessage());
            log("Please open this URL manually:\n" + MS_AUTH_URL);
        }

        // Show a dialog asking for the redirect URL the browser lands on.
        // After login, Microsoft redirects to:
        //   https://login.live.com/oauth20_desktop.srf?code=XXXX&...
        // The browser will show a blank/error page — that's expected.
        // The user copies the URL from the address bar and pastes it here.
        JTextArea urlInput = new JTextArea(3, 50);
        urlInput.setLineWrap(true); urlInput.setWrapStyleWord(true);
        urlInput.setBorder(BorderFactory.createTitledBorder("Paste the redirect URL here:"));
        JPanel hint = new JPanel(new BorderLayout(4, 4));
        hint.add(new JLabel("<html><b>Your browser opened Microsoft's login page.</b><br>"
            + "1. Sign in with your Microsoft account that owns Minecraft.<br>"
            + "2. After logging in, the browser will show a blank/error page.<br>"
            + "3. Copy the full URL from the address bar and paste it below.</html>"),
            BorderLayout.NORTH);
        hint.add(new JScrollPane(urlInput), BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(this, hint,
            "Microsoft Login — Step 2 of 2", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) { loginButton.setEnabled(true); return; }

        String redirectUrl = urlInput.getText().trim();
        if (redirectUrl.isEmpty()) { loginButton.setEnabled(true); return; }

        progressBar.setVisible(true); progressBar.setIndeterminate(true);
        runTask(
            () -> {
                String code = extractQueryParam(redirectUrl, "code");
                if (code == null || code.isEmpty()) throw new IOException("No 'code' found in redirect URL.\nDid you copy the full URL from the address bar?");
                log("Got auth code. Exchanging for tokens...");
                AuthSession session = performFullAuthFlow(code);
                currentSession = session;
                saveSession();
                SwingUtilities.invokeLater(() -> updateAuthUI());
                log("Logged in as: " + session.minecraftName + " (" + session.minecraftUuid + ")");
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                    "Logged in as: " + session.minecraftName + "\nYou can now launch Minecraft on online servers.",
                    "Login Successful", JOptionPane.INFORMATION_MESSAGE));
            },
            null,
            err -> JOptionPane.showMessageDialog(this, "Login failed:\n" + err, "Login Error", JOptionPane.ERROR_MESSAGE),
            loginButton);
    }

    /**
     * Performs all token exchange steps and returns a complete AuthSession.
     */
    private AuthSession performFullAuthFlow(String authCode) throws Exception {
        // Step 1: Auth code → MS access token + refresh token
        String msTokenBody = "client_id=" + MS_CLIENT_ID
            + "&code=" + URLEncoder.encode(authCode, "UTF-8")
            + "&grant_type=authorization_code"
            + "&redirect_uri=" + URLEncoder.encode(MS_REDIRECT_URI, "UTF-8");
        JsonObject msTokens = postForm(MS_TOKEN_URL, msTokenBody);
        if (!msTokens.has("access_token")) throw new IOException("MS token exchange failed: " + msTokens);
        String msAccessToken  = msTokens.get("access_token").getAsString();
        String msRefreshToken = msTokens.has("refresh_token") ? msTokens.get("refresh_token").getAsString() : null;
        long   expiresIn      = msTokens.has("expires_in") ? msTokens.get("expires_in").getAsLong() : 3600;
        log("Microsoft access token obtained.");

        return completeXboxFlow(msAccessToken, msRefreshToken, expiresIn);
    }

    /**
     * Given a valid MS access token, completes the Xbox → XSTS → Minecraft flow.
     * Also called during silent token refresh.
     */
    private AuthSession completeXboxFlow(String msAccessToken, String msRefreshToken, long expiresIn) throws Exception {
        // Step 2: MS access token → Xbox Live (XBL) token
        log("Authenticating with Xbox Live...");
        JsonObject xblBody = new JsonObject();
        JsonObject xblProperties = new JsonObject();
        xblProperties.addProperty("AuthMethod", "RPS");
        xblProperties.addProperty("SiteName", "user.auth.xboxlive.com");
        xblProperties.addProperty("RpsTicket", "d=" + msAccessToken);
        xblBody.add("Properties", xblProperties);
        xblBody.addProperty("RelyingParty", "http://auth.xboxlive.com");
        xblBody.addProperty("TokenType", "JWT");
        JsonObject xblResp = postJson(XBL_AUTH_URL, xblBody.toString());
        if (!xblResp.has("Token")) throw new IOException("XBL auth failed: " + xblResp);
        String xblToken = xblResp.get("Token").getAsString();
        // The UHS (user hash) is needed for the XSTS→MC step
        String uhs = xblResp.getAsJsonObject("DisplayClaims")
            .getAsJsonArray("xui").get(0).getAsJsonObject()
            .get("uhs").getAsString();
        log("Xbox Live token obtained.");

        // Step 3: XBL token → XSTS token
        log("Getting XSTS token...");
        JsonObject xstsBody = new JsonObject();
        JsonObject xstsProperties = new JsonObject();
        xstsProperties.addProperty("SandboxId", "RETAIL");
        JsonArray userTokens = new JsonArray(); userTokens.add(xblToken);
        xstsProperties.add("UserTokens", userTokens);
        xstsBody.add("Properties", xstsProperties);
        xstsBody.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        xstsBody.addProperty("TokenType", "JWT");
        JsonObject xstsResp = postJson(XSTS_AUTH_URL, xstsBody.toString());
        if (!xstsResp.has("Token")) {
            // Check for known error codes
            String err = xstsResp.toString();
            if (xstsResp.has("XErr")) {
                long xerr = xstsResp.get("XErr").getAsLong();
                if (xerr == 2148916233L) throw new IOException("This Microsoft account has no Xbox Live profile.\nPlease go to xbox.com and create one first.");
                if (xerr == 2148916238L) throw new IOException("This is a child account. A parent must add it to a Microsoft Family.");
                throw new IOException("XSTS error " + xerr + ": " + err);
            }
            throw new IOException("XSTS auth failed: " + err);
        }
        String xstsToken = xstsResp.get("Token").getAsString();
        log("XSTS token obtained.");

        // Step 4: XSTS token → Minecraft bearer token
        log("Logging into Minecraft services...");
        JsonObject mcBody = new JsonObject();
        mcBody.addProperty("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);
        JsonObject mcResp = postJson(MC_LOGIN_URL, mcBody.toString());
        if (!mcResp.has("access_token")) throw new IOException("Minecraft auth failed: " + mcResp);
        String mcToken = mcResp.get("access_token").getAsString();
        log("Minecraft token obtained.");

        // Step 5: Fetch Minecraft profile (UUID + username)
        log("Fetching Minecraft profile...");
        JsonObject profile = getWithBearer(MC_PROFILE_URL, mcToken);
        if (!profile.has("id")) {
            // This usually means the account doesn't own Minecraft Java Edition
            throw new IOException("No Minecraft Java Edition profile found on this account.\n"
                + "Make sure this Microsoft account has purchased Minecraft: Java Edition.");
        }
        String mcUuid = profile.get("id").getAsString();
        String mcName = profile.get("name").getAsString();

        AuthSession session = new AuthSession();
        session.minecraftToken  = mcToken;
        session.minecraftUuid   = mcUuid;
        session.minecraftName   = mcName;
        session.msRefreshToken  = msRefreshToken;
        session.tokenExpiresAt  = System.currentTimeMillis() / 1000 + expiresIn;
        return session;
    }

    /**
     * Silently refreshes the session using the stored refresh token.
     * Called automatically before launch if the token is expired.
     */
    private void refreshSession() throws Exception {
        if (currentSession == null || currentSession.msRefreshToken == null) throw new IOException("No refresh token stored. Please log in again.");
        log("Refreshing Microsoft token silently...");
        String body = "client_id=" + MS_CLIENT_ID
            + "&refresh_token=" + URLEncoder.encode(currentSession.msRefreshToken, "UTF-8")
            + "&grant_type=refresh_token"
            + "&redirect_uri=" + URLEncoder.encode(MS_REDIRECT_URI, "UTF-8");
        JsonObject resp = postForm(MS_TOKEN_URL, body);
        if (!resp.has("access_token")) throw new IOException("Token refresh failed: " + resp);
        String newMsAccess   = resp.get("access_token").getAsString();
        String newMsRefresh  = resp.has("refresh_token") ? resp.get("refresh_token").getAsString() : currentSession.msRefreshToken;
        long   newExpiresIn  = resp.has("expires_in") ? resp.get("expires_in").getAsLong() : 3600;
        AuthSession refreshed = completeXboxFlow(newMsAccess, newMsRefresh, newExpiresIn);
        currentSession = refreshed;
        saveSession();
        log("Session refreshed for: " + currentSession.minecraftName);
    }

    private void onLogout() {
        int confirm = JOptionPane.showConfirmDialog(this,
            "Sign out of " + (currentSession != null ? currentSession.minecraftName : "current account") + "?",
            "Sign Out", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;
        currentSession = null;
        File authFile = new File(BASE_DIR, "auth.json");
        authFile.delete();
        updateAuthUI();
        log("Signed out.");
    }

    private void updateAuthUI() {
        boolean loggedIn = currentSession != null && currentSession.minecraftName != null;
        loginButton.setVisible(!loggedIn);
        logoutButton.setVisible(loggedIn);
        if (loggedIn) {
            accountLabel.setText("● " + currentSession.minecraftName);
            accountLabel.setForeground(C_ACCENT);
            accountLabel.setFont(FONT_UI.deriveFont(Font.BOLD));
            nameField.setText(currentSession.minecraftName);
            nameField.setEditable(false);
        } else {
            accountLabel.setText("● Offline");
            accountLabel.setForeground(C_TEXT_DIM);
            accountLabel.setFont(FONT_UI.deriveFont(Font.ITALIC));
            nameField.setEditable(true);
        }
    }

    private void saveSession() {
        try {
            File f = new File(BASE_DIR, "auth.json");
            f.getParentFile().mkdirs();
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(currentSession.toJson());
            Files.write(f.toPath(), json.getBytes());
        } catch (IOException ex) { log("Warning: could not save session: " + ex.getMessage()); }
    }

    private void loadSession() {
        File f = new File(BASE_DIR, "auth.json");
        if (!f.exists()) return;
        try {
            JsonObject obj = JsonParser.parseString(new String(Files.readAllBytes(f.toPath()))).getAsJsonObject();
            AuthSession s = AuthSession.fromJson(obj);
            if (s.minecraftToken != null && s.minecraftName != null) {
                currentSession = s;
                updateAuthUI();
                log("Loaded saved session for: " + s.minecraftName + (s.isExpired() ? " (token expired, will refresh on launch)" : ""));
            }
        } catch (Exception ex) { log("Could not load saved session: " + ex.getMessage()); }
    }

    // ---- Auth HTTP helpers ----

    /** POST application/x-www-form-urlencoded, returns parsed JSON. */
    private JsonObject postForm(String url, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("User-Agent", "GeminiLauncher/1.0");
        conn.setConnectTimeout(15_000); conn.setReadTimeout(30_000);
        try (OutputStream os = conn.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) throw new IOException("No response body from " + url + " (HTTP " + code + ")");
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(); String l; while ((l = br.readLine()) != null) sb.append(l);
            JsonObject resp = JsonParser.parseString(sb.toString()).getAsJsonObject();
            if (code >= 400 && !resp.has("access_token") && !resp.has("Token")) throw new IOException("HTTP " + code + ": " + resp);
            return resp;
        } finally { conn.disconnect(); }
    }

    /** POST application/json, returns parsed JSON. */
    private JsonObject postJson(String url, String jsonBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "GeminiLauncher/1.0");
        conn.setConnectTimeout(15_000); conn.setReadTimeout(30_000);
        try (OutputStream os = conn.getOutputStream()) { os.write(jsonBody.getBytes(StandardCharsets.UTF_8)); }
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) throw new IOException("No response body (HTTP " + code + ")");
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(); String l; while ((l = br.readLine()) != null) sb.append(l);
            return JsonParser.parseString(sb.toString()).getAsJsonObject();
        } finally { conn.disconnect(); }
    }

    /** GET with Bearer token, returns parsed JSON. */
    private JsonObject getWithBearer(String url, String bearerToken) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        conn.setRequestProperty("User-Agent", "GeminiLauncher/1.0");
        conn.setConnectTimeout(15_000); conn.setReadTimeout(30_000);
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) throw new IOException("No response body (HTTP " + code + ")");
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(); String l; while ((l = br.readLine()) != null) sb.append(l);
            return JsonParser.parseString(sb.toString()).getAsJsonObject();
        } finally { conn.disconnect(); }
    }

    /** Extracts a query parameter from a URL string. */
    private String extractQueryParam(String url, String param) {
        try {
            // Handle both full URLs and just query strings
            String query = url.contains("?") ? url.substring(url.indexOf('?') + 1) : url;
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2 && kv[0].equals(param)) return URLDecoder.decode(kv[1], "UTF-8");
            }
        } catch (Exception ignored) {}
        return null;
    }



    private String mojangComponentForMcVersion(String mcVersion) {
        if (mcVersion == null || mcVersion.isEmpty()) return COMPONENT_JAVA17;
        try { int minor = Integer.parseInt(mcVersion.split("\\.")[1]); if (minor >= 21) return COMPONENT_JAVA21; if (minor >= 17) return COMPONENT_JAVA17; return COMPONENT_JAVA8; } catch (Exception e) { return COMPONENT_JAVA17; }
    }

    private String mojangOsPlatformKey() {
        String os = System.getProperty("os.name","").toLowerCase(), arch = System.getProperty("os.arch","").toLowerCase();
        if (os.contains("win")) { if (arch.contains("aarch64")) return "windows-arm64"; if (arch.contains("amd64") || arch.contains("x86_64")) return "windows-x64"; return "windows-x86"; }
        if (os.contains("mac")) return arch.contains("aarch64") ? "mac-os-arm64" : "mac-os";
        return arch.contains("i386") ? "linux-i386" : "linux";
    }

    private String installMojangJre(String component) throws Exception {
        String platformKey = mojangOsPlatformKey();
        log("Mojang JRE: component=" + component + " platform=" + platformKey);
        JsonObject all = JsonParser.parseString(downloadString(MOJANG_JRE_ALL_URL)).getAsJsonObject();
        if (!all.has(platformKey)) throw new IOException("Platform '" + platformKey + "' not found.");
        JsonObject platform = all.getAsJsonObject(platformKey);
        if (!platform.has(component) || platform.getAsJsonArray(component).size() == 0) throw new IOException("Component '" + component + "' not available.");
        JsonObject entry = platform.getAsJsonArray(component).get(0).getAsJsonObject();
        String manifestUrl = entry.getAsJsonObject("manifest").get("url").getAsString();
        if (entry.has("version")) log("JRE version: " + entry.getAsJsonObject("version").get("name").getAsString());
        JsonObject files = JsonParser.parseString(downloadString(manifestUrl)).getAsJsonObject().getAsJsonObject("files");
        File baseDir = new File(BASE_DIR, "mojang_jre/" + component);
        if (baseDir.exists()) deleteRecursively(baseDir.toPath()); baseDir.mkdirs();
        int total = files.entrySet().size(), done = 0, dl = 0;
        SwingUtilities.invokeLater(() -> { progressBar.setIndeterminate(false); progressBar.setMaximum(total); progressBar.setValue(0); progressBar.setString("Installing Mojang JRE..."); });
        for (Map.Entry<String, JsonElement> fe : files.entrySet()) {
            String rel = fe.getKey(); JsonObject fm = fe.getValue().getAsJsonObject();
            String type = fm.has("type") ? fm.get("type").getAsString() : "file";
            File dest = new File(baseDir, rel);
            if ("directory".equals(type)) { dest.mkdirs(); }
            else if ("link".equals(type)) { /* skip on Windows */ }
            else {
                if (!fm.has("downloads") || !fm.getAsJsonObject("downloads").has("raw")) { done++; continue; }
                JsonObject raw = fm.getAsJsonObject("downloads").getAsJsonObject("raw");
                String url = raw.has("url") ? raw.get("url").getAsString() : null; if (url == null) { done++; continue; }
                String sha1 = raw.has("sha1") ? raw.get("sha1").getAsString() : null;
                if (!dest.exists() || (sha1 != null && !sha1OfFile(dest).equalsIgnoreCase(sha1))) {
                    dest.getParentFile().mkdirs(); downloadFile(url, dest);
                    if (sha1 != null && !sha1OfFile(dest).equalsIgnoreCase(sha1)) { dest.delete(); throw new IOException("SHA1 mismatch: " + rel); }
                    if (fm.has("executable") && fm.get("executable").getAsBoolean()) try { dest.setExecutable(true, false); } catch (Exception ig) {}
                    dl++;
                }
                done++; final int prog = done; SwingUtilities.invokeLater(() -> { progressBar.setValue(prog); progressBar.setString("JRE file " + prog + "/" + total); });
            }
        }
        log("JRE install: " + dl + " downloaded.");
        File javaExe = findMojangJavaExe(baseDir); if (javaExe == null) throw new IOException("java exe not found in JRE");
        javaExe.setExecutable(true, false);
        ProcessBuilder pb = new ProcessBuilder(javaExe.getAbsolutePath(), "-version"); pb.redirectErrorStream(true);
        Process p = pb.start(); log("java -version: " + readStreamFully(p.getInputStream()).trim()); if (p.waitFor() != 0) throw new IOException("java -version failed");
        Files.write(new File(BASE_DIR, "mojang_jre/" + component + ".installed.txt").toPath(), javaExe.getAbsolutePath().getBytes());
        log("Mojang JRE ready: " + javaExe.getAbsolutePath());
        return javaExe.getAbsolutePath();
    }

    private File findMojangJavaExe(File jreRoot) {
        String exeName = System.getProperty("os.name","").toLowerCase().contains("win") ? "java.exe" : "java";
        try (Stream<Path> walk = Files.walk(jreRoot.toPath())) {
            return walk.filter(p -> p.getFileName().toString().equals(exeName) && p.toString().contains(File.separator + "bin" + File.separator)).findFirst().map(Path::toFile).orElse(null);
        } catch (IOException e) { return null; }
    }

    private void onMojangJreClicked(String component) {
        runTask(
            () -> { String p = installMojangJre(component); SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Mojang JRE installed!\n" + p, "Success", JOptionPane.INFORMATION_MESSAGE)); },
            null,
            err -> JOptionPane.showMessageDialog(this, "Failed:\n" + err, "Error", JOptionPane.ERROR_MESSAGE),
            mojangJre21Button, mojangJre17Button, mojangJre8Button, mojangJreAutoButton);
    }

    private void onMojangJreAutoClicked() {
        InstanceConfig ic = instanceList.getSelectedValue();
        if (ic == null) { JOptionPane.showMessageDialog(this, "Select an instance first.", "No Instance", JOptionPane.WARNING_MESSAGE); return; }
        String component = mojangComponentForMcVersion(ic.mcVersion);
        if (JOptionPane.showConfirmDialog(this, "Install Mojang JRE: " + component + "\nfor Minecraft " + ic.mcVersion + "\n\nProceed?", "Auto-install JRE", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        onMojangJreClicked(component);
    }


    // ===================== JAVA DETECTION =====================

    private String findJava17Executable() {
        File adoptiumDir = new File(System.getProperty("user.home") + "\\AppData\\Local\\Programs\\Eclipse Adoptium");
        if (adoptiumDir.exists()) {
            for (int major : new int[]{17, 8, 21, 25}) {
                File[] subdirs = adoptiumDir.listFiles(File::isDirectory); if (subdirs == null) break;
                Arrays.sort(subdirs, Comparator.comparing(File::getName).reversed());
                for (File jdk : subdirs) {
                    String name = jdk.getName().toLowerCase();
                    if (!name.startsWith("jdk-" + major + ".") && !name.startsWith("jre-" + major + ".")) continue;
                    File exe = new File(jdk, "bin\\java.exe"); if (!exe.exists()) exe = new File(jdk, "bin/java");
                    if (exe.canExecute()) { log("Using Adoptium " + major + ": " + exe); return exe.getAbsolutePath(); }
                }
            }
        }
        String javaHomeEnv = System.getenv("JAVA_HOME");
        if (javaHomeEnv != null && javaHomeEnv.contains("17")) {
            Path p = Paths.get(javaHomeEnv, "bin", "java.exe"); if (!Files.isExecutable(p)) p = Paths.get(javaHomeEnv, "bin", "java");
            if (Files.isExecutable(p)) { log("Using JAVA_HOME(17): " + p); return p.toString(); }
        }
        for (String comp : new String[]{COMPONENT_JAVA17, COMPONENT_JAVA17B, COMPONENT_JAVA8, COMPONENT_JAVA21}) {
            File marker = new File(BASE_DIR, "mojang_jre/" + comp + ".installed.txt");
            if (marker.exists()) { try { String path = new String(Files.readAllBytes(marker.toPath())).trim(); if (!path.isEmpty() && Files.isExecutable(Paths.get(path))) { log("Using Mojang JRE(" + comp + "): " + path); return path; } } catch (Exception ig) {} }
        }
        File portableMarker = new File(BASE_DIR, "portable_jdk/installed.txt");
        if (portableMarker.exists()) { try { String p = new String(Files.readAllBytes(portableMarker.toPath())).trim(); if (!p.isEmpty() && Files.isExecutable(Paths.get(p))) return p; } catch (Exception ig) {} }
        File vendorRoot = new File(BASE_DIR, "portable_jdks");
        if (vendorRoot.exists()) { File[] ms = vendorRoot.listFiles(f -> f.getName().endsWith(".txt")); if (ms != null) for (File m : ms) { try { String p = new String(Files.readAllBytes(m.toPath())).trim(); if (!p.isEmpty() && Files.isExecutable(Paths.get(p))) return p; } catch (Exception ig) {} } }
        String javaHomeProp = System.getProperty("java.home");
        if (javaHomeProp != null) { for (String rel : new String[]{"bin/java.exe", "bin/java", "../bin/java.exe", "../bin/java"}) { Path p = Paths.get(javaHomeProp, rel).normalize(); if (Files.isExecutable(p)) { log("Using running JVM fallback: " + p); return p.toString(); } } }
        return "java";
    }

    // ===================== JAVA TOOL HANDLERS =====================

    private void onInstallJavaClicked(ActionEvent ev) {
        JTextField urlField = new JTextField(""); JPanel panel = new JPanel(new GridLayout(2, 1, 4, 4));
        panel.add(new JLabel("Direct ZIP URL for JDK:")); panel.add(urlField);
        if (JOptionPane.showConfirmDialog(this, panel, "Install Portable JDK", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        String url = urlField.getText().trim(); if (url.isEmpty()) return;
        runTask(
            () -> installPortableJdk(url, ""),
            () -> JOptionPane.showMessageDialog(this, "JDK installed.", "Success", JOptionPane.INFORMATION_MESSAGE),
            err -> JOptionPane.showMessageDialog(this, "Failed: " + err, "Error", JOptionPane.ERROR_MESSAGE),
            installJavaButton);
    }

    private void onInstallFabricClicked(ActionEvent ev) {
        InstanceConfig ic = instanceList.getSelectedValue();
        if (ic == null) { JOptionPane.showMessageDialog(this, "Select an instance first.", "No Instance", JOptionPane.WARNING_MESSAGE); return; }
        installFabricForInstance(ic, instanceDir(ic));
    }

    private void installFabricForInstance(InstanceConfig ic, String dir) {
        if (JOptionPane.showConfirmDialog(this, "Install Fabric into \"" + ic.name + "\" (" + ic.mcVersion + ")?", "Install Fabric", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        runTask(
            () -> { installFabric(ic.mcVersion, dir); ic.modLoader = "FABRIC"; saveInstanceConfig(ic); },
            () -> { int idx = instanceListModel.indexOf(ic); if (idx >= 0) instanceListModel.set(idx, ic); detailLoader.setText("FABRIC"); JOptionPane.showMessageDialog(this, "Fabric installed.", "Success", JOptionPane.INFORMATION_MESSAGE); },
            err -> JOptionPane.showMessageDialog(this, "Failed: " + err, "Error", JOptionPane.ERROR_MESSAGE),
            installFabricButton, installFabricForInstanceBtn);
    }

    private void onInstallModrinthClicked(ActionEvent ev) {
        InstanceConfig ic = instanceList.getSelectedValue();
        if (ic == null) { JOptionPane.showMessageDialog(this, "Select an instance first.", "No Instance", JOptionPane.WARNING_MESSAGE); return; }
        String slug = JOptionPane.showInputDialog(this, "Enter Modrinth project slug:", "Install Mod", JOptionPane.PLAIN_MESSAGE);
        if (slug == null || slug.trim().isEmpty()) return;
        final String s = slug.trim();
        runTask(
            () -> installModFromModrinth(s, ic.mcVersion, ic.modLoader, instanceDir(ic)),
            () -> JOptionPane.showMessageDialog(this, "Mod installed.", "Success", JOptionPane.INFORMATION_MESSAGE),
            err -> JOptionPane.showMessageDialog(this, "Failed: " + err, "Error", JOptionPane.ERROR_MESSAGE),
            installModrinthButton);
    }

    private void onInstallRecommendedJavaClicked(int major) {
        List<JdkVendor> vendors = vendorsForMajor(major); if (vendors.isEmpty()) return;
        JButton src = major == 25 ? installJava25Button : major == 21 ? installJava21Button : major == 17 ? installJava17Button : installJava8Button;
        runTask(
            () -> { String r = tryInstallFromVendors(vendors); SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, r != null ? "Installed at:\n" + r : "All vendors failed.", r != null ? "Success" : "Failed", r != null ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE)); },
            null, null, src);
    }

    // ===================== SYNC / DOWNLOAD =====================

    private void syncVersion(String vId, File bin, File lib, File assets, File jar) throws Exception {
        // 1. Check for a locally cached version JSON first (pre-downloaded at home)
        File cachedVersionJson = new File(BASE_DIR, "version_cache/" + vId + ".json");
        String vJson = null;
        JsonObject vData = null;

        if (cachedVersionJson.exists()) {
            log("Using locally cached version JSON for " + vId);
            vJson = new String(Files.readAllBytes(cachedVersionJson.toPath()));
            vData = JsonParser.parseString(vJson).getAsJsonObject();
        } else {
            // 2. Try to fetch from manifest / mirrors
            String vUrl = null;
            String[] allManifests = new String[1 + MANIFEST_MIRRORS.length];
            allManifests[0] = MANIFEST_URL;
            System.arraycopy(MANIFEST_MIRRORS, 0, allManifests, 1, MANIFEST_MIRRORS.length);

            for (String mUrl : allManifests) {
                try {
                    log("Trying manifest: " + mUrl.replaceAll("https?://", "").split("/")[0]);
                    JsonObject manifest = JsonParser.parseString(downloadString(mUrl)).getAsJsonObject();
                    for (JsonElement e : manifest.getAsJsonArray("versions")) {
                        JsonObject vo = e.getAsJsonObject();
                        if (vId.equals(vo.get("id").getAsString())) { vUrl = vo.get("url").getAsString(); break; }
                    }
                    if (vUrl != null) break;
                    log("Version " + vId + " not found in this manifest, trying next...");
                } catch (Exception e) {
                    log("Manifest unreachable (" + mUrl.replaceAll("https?://", "").split("/")[0] + "): " + e.getMessage().split("\n")[0]);
                }
            }
            if (vUrl == null) throw new IOException(
                "Could not fetch version manifest from any source.\n" +
                "To play on this network, pre-download the version files at home:\n" +
                "  1. Run the launcher at home to download the game normally.\n" +
                "  2. Copy " + BASE_DIR + "version_cache/" + vId + ".json to your laptop.\n" +
                "     (The launcher saves it there automatically after a successful download.)\n" +
                "  3. Also copy the instances/" + vId + "/ folder contents (jar, libraries, assets).\n" +
                "  4. Use Offline mode to launch.");
            log("Downloading version metadata...");
            vJson = downloadString(vUrl);
            vData = JsonParser.parseString(vJson).getAsJsonObject();
            // Cache it for future offline/school use
            File cacheDir = new File(BASE_DIR, "version_cache");
            cacheDir.mkdirs();
            Files.write(cachedVersionJson.toPath(), vJson.getBytes());
            log("Version JSON cached to " + cachedVersionJson.getAbsolutePath());
        }
        if (!bin.exists() && !bin.mkdirs()) throw new IOException("Failed to create bin dir");
        Files.write(new File(bin, "version.json").toPath(), vJson.getBytes());
        String assetIndex = null, assetIndexUrl = null;
        if (vData.has("assetIndex")) { JsonObject ai = vData.getAsJsonObject("assetIndex"); if (ai.has("id")) assetIndex = ai.get("id").getAsString(); if (ai.has("url")) assetIndexUrl = ai.get("url").getAsString(); }
        if (assetIndex != null && assetIndexUrl != null) {
            File indexesDir = new File(BASE_DIR, "assets/indexes"); indexesDir.mkdirs();
            File indexFile = new File(indexesDir, assetIndex + ".json");
            if (!indexFile.exists()) { log("Downloading asset index: " + assetIndex); downloadFile(assetIndexUrl, indexFile); }
            downloadAssetsFromIndex(indexFile);
        }
        if (!jar.exists() && vData.has("downloads") && vData.getAsJsonObject("downloads").has("client")) {
            log("Downloading client jar..."); downloadFile(vData.getAsJsonObject("downloads").getAsJsonObject("client").get("url").getAsString(), jar); log("Client downloaded.");
        }
        if (vData.has("libraries")) {
            for (JsonElement e : vData.getAsJsonArray("libraries")) {
                JsonObject l = e.getAsJsonObject();
                if (l.has("downloads") && l.getAsJsonObject("downloads").has("artifact")) {
                    JsonObject art = l.getAsJsonObject("downloads").getAsJsonObject("artifact");
                    File f = new File(lib, art.get("path").getAsString());
                    if (!f.exists()) { log("Downloading library: " + art.get("path").getAsString()); downloadFile(art.get("url").getAsString(), f); }
                }
                if (l.has("downloads") && l.getAsJsonObject("downloads").has("classifiers")) {
                    JsonObject classifiers = l.getAsJsonObject("downloads").getAsJsonObject("classifiers");
                    String chosenKey = findClassifierKeyForOs(classifiers, detectOsKey());
                    if (chosenKey != null) {
                        JsonObject nObj = classifiers.getAsJsonObject(chosenKey);
                        if (nObj.has("url")) {
                            String path = nObj.has("path") ? nObj.get("path").getAsString() : ("natives/" + UUID.randomUUID() + ".jar");
                            File nf = new File(lib, path);
                            if (!nf.exists()) { log("Downloading native: " + nf.getName()); downloadFile(nObj.get("url").getAsString(), nf); }
                            File nd = new File(bin, "natives"); nd.mkdirs(); extractNativeBinaries(nf, nd);
                        }
                    }
                }
            }
        }
        extractAllNatives(lib, new File(bin, "natives"));
        patchNativesForModernJvm(vId, new File(bin, "natives"));
    }

    private void patchNativesForModernJvm(String mcVersion, File nativesDir) {
        if (!needsLwjglUpgrade(mcVersion)) return;
        log("Upgrading LWJGL 3.2.x natives to 3.3.1...");
        String base = "https://repo1.maven.org/maven2/org/lwjgl/";
        String[][] modules = {
            {"lwjgl","lwjgl-3.3.1-natives-windows.jar"},{"lwjgl-glfw","lwjgl-glfw-3.3.1-natives-windows.jar"},
            {"lwjgl-openal","lwjgl-openal-3.3.1-natives-windows.jar"},{"lwjgl-opengl","lwjgl-opengl-3.3.1-natives-windows.jar"},
            {"lwjgl-jemalloc","lwjgl-jemalloc-3.3.1-natives-windows.jar"},{"lwjgl-stb","lwjgl-stb-3.3.1-natives-windows.jar"},
            {"lwjgl-tinyfd","lwjgl-tinyfd-3.3.1-natives-windows.jar"},
        };
        File cacheDir = new File(BASE_DIR, "patch_cache/lwjgl331"); cacheDir.mkdirs();
        int totalPatched = 0;
        for (String[] mod : modules) {
            String modName = mod[0], jarName = mod[1]; File cachedJar = new File(cacheDir, jarName);
            try {
                if (!cachedJar.exists() || cachedJar.length() < 1024) {
                    if (cachedJar.exists()) cachedJar.delete();
                    log("Downloading " + jarName + "..."); downloadFile(base + modName + "/3.3.1/" + jarName, cachedJar);
                    if (!cachedJar.exists() || cachedJar.length() < 1024) { log("ERROR: download failed for " + jarName); continue; }
                    log("Downloaded " + jarName + " (" + (cachedJar.length()/1024) + " KB)");
                }
                try (ZipInputStream zis = new ZipInputStream(new FileInputStream(cachedJar))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        if (entry.isDirectory()) { zis.closeEntry(); continue; }
                        String name = Paths.get(entry.getName()).getFileName().toString();
                        if (name.toLowerCase().endsWith(".dll")) {
                            try (OutputStream os = new FileOutputStream(new File(nativesDir, name))) { byte[] buf = new byte[8192]; int r; while ((r = zis.read(buf)) != -1) os.write(buf, 0, r); }
                            totalPatched++;
                        }
                        zis.closeEntry();
                    }
                }
                log("Patched: " + modName);
            } catch (Exception ex) { log("Warning: failed to patch " + modName + ": " + ex.getMessage()); debugException(ex); }
        }
        log("LWJGL 3.3.1 upgrade: " + totalPatched + " DLL(s) replaced.");
    }

    private boolean needsLwjglUpgrade(String v) {
        if (v == null) return false;
        try { int minor = Integer.parseInt(v.split("\\.")[1].split("-")[0]); return minor >= 13 && minor <= 16; } catch (Exception e) { return false; }
    }

    private void installFabric(String mcVersion, String instanceDir) throws Exception {
        File toolsDir = new File(BASE_DIR, "tools"); toolsDir.mkdirs();
        File installerJar = new File(toolsDir, "fabric-installer.jar");
        if (!installerJar.exists()) { log("Downloading Fabric installer..."); downloadFile(FABRIC_INSTALLER_URL, installerJar); }
        List<String> cmd = new ArrayList<>();
        cmd.add(findJava17Executable()); cmd.add("-jar"); cmd.add(installerJar.getAbsolutePath());
        cmd.add("client"); cmd.add("-dir"); cmd.add(instanceDir); cmd.add("-mcversion"); cmd.add(mcVersion); cmd.add("-noprofile");
        log("Running Fabric installer: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd); pb.directory(new File(instanceDir)); pb.redirectErrorStream(true);
        Process p = pb.start(); streamToLog(p.getInputStream()); int exit = p.waitFor();
        if (exit != 0) throw new IOException("Fabric installer exited " + exit);
        log("Fabric installed.");
        File bin = new File(instanceDir, "bin"), lib = new File(instanceDir, "libraries");
        try { syncVersion(mcVersion, bin, lib, new File(BASE_DIR, "assets"), new File(bin, "client.jar")); } catch (Exception ex) { log("Post-install sync warning: " + ex.getMessage()); }
    }

    private void installModFromModrinth(String projectSlug, String mcVersion, String modLoader, String instanceDir) throws Exception {
        log("Querying Modrinth: " + projectSlug);
        downloadString(MODRINTH_API + "/project/" + URLEncoder.encode(projectSlug, "UTF-8"));
        JsonArray versions = JsonParser.parseString(downloadString(MODRINTH_API + "/project/" + URLEncoder.encode(projectSlug, "UTF-8") + "/version")).getAsJsonArray();

        // Preferred loader (fabric/forge/quilt/neoforge) — derived from instance setting
        String preferredLoader = (modLoader != null && !modLoader.equalsIgnoreCase("VANILLA"))
            ? modLoader.toLowerCase() : null;

        List<JsonObject> candidates = new ArrayList<>();
        // First pass: match both game version AND loader
        for (JsonElement ve : versions) {
            JsonObject v = ve.getAsJsonObject(); boolean gameMatch = false;
            if (v.has("game_versions")) for (JsonElement gv : v.getAsJsonArray("game_versions")) if (gv.getAsString().equalsIgnoreCase(mcVersion)) { gameMatch = true; break; }
            if (!gameMatch) continue;
            if (preferredLoader != null && v.has("loaders")) {
                for (JsonElement l : v.getAsJsonArray("loaders")) if (l.getAsString().equalsIgnoreCase(preferredLoader)) { candidates.add(v); break; }
            } else { candidates.add(v); }
        }
        // Fallback: any version for this game version
        if (candidates.isEmpty()) for (JsonElement ve : versions) {
            JsonObject v = ve.getAsJsonObject();
            if (v.has("game_versions")) for (JsonElement gv : v.getAsJsonArray("game_versions")) if (gv.getAsString().equalsIgnoreCase(mcVersion)) { candidates.add(v); break; }
        }
        if (candidates.isEmpty()) throw new IOException("No compatible mod files for MC " + mcVersion + (preferredLoader != null ? " + " + preferredLoader : ""));

        String[] choices = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            JsonObject v = candidates.get(i);
            String loaderStr = "";
            if (v.has("loaders")) {
                List<String> ls = new ArrayList<>();
                v.getAsJsonArray("loaders").forEach(l -> ls.add(l.getAsString()));
                loaderStr = " [" + String.join(", ", ls) + "]";
            }
            choices[i] = (v.has("name") ? v.get("name").getAsString() : v.get("version_number").getAsString()) + " (" + v.get("version_number").getAsString() + ")" + loaderStr;
        }
        int pick = JOptionPane.showOptionDialog(this, "Select version for " + projectSlug, "Choose Mod Version", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, choices, choices[0]);
        if (pick < 0) return;
        JsonObject chosen = candidates.get(pick);
        if (!chosen.has("files")) throw new IOException("No files listed");
        JsonObject fileObj = chosen.getAsJsonArray("files").get(0).getAsJsonObject();
        String fileUrl  = fileObj.has("url")      ? fileObj.get("url").getAsString()      : null;
        String fileName = fileObj.has("filename") ? fileObj.get("filename").getAsString() : "mod.jar";
        String sha256   = (fileObj.has("hashes") && fileObj.getAsJsonObject("hashes").has("sha256")) ? fileObj.getAsJsonObject("hashes").get("sha256").getAsString() : null;
        if (fileUrl == null) throw new IOException("No download URL");
        File modsDir = new File(instanceDir, "mods"); modsDir.mkdirs();
        File modJar = new File(modsDir, fileName);
        log("Downloading mod: " + fileName); downloadFile(fileUrl, modJar);
        if (sha256 != null && !sha256.isEmpty()) { if (!sha256OfFile(modJar).equalsIgnoreCase(sha256)) { modJar.delete(); throw new IOException("Checksum mismatch"); } log("Checksum OK"); }
        log("Installed: " + modJar.getAbsolutePath());
    }

    // ===================== FORGE INSTALLATION =====================

    /**
     * Installs Forge for the given Minecraft version into the instance directory.
     *
     * Forge's installer is a fat JAR that handles everything:
     *   java -jar forge-installer.jar --installClient <instanceDir>
     *
     * We:
     *  1. Fetch Forge's maven-metadata.json to find the latest Forge version for the MC version
     *  2. Download the installer JAR from files.minecraftforge.net
     *  3. Run it with --installClient pointing at our instance directory
     *  4. Detect the resulting version folder (e.g. versions/1.20.1-forge-47.3.0/)
     *
     * The Forge installer creates a standard Minecraft launcher profile structure.
     * We parse the generated version JSON to extract mainClass and classpath.
     */
    private void installForgeForInstance(InstanceConfig ic, String instanceDir) {
        if (JOptionPane.showConfirmDialog(this,
                "Install Forge into \"" + ic.name + "\" (" + ic.mcVersion + ")?\n\n"
                + "Forge requires Java 17+ to run its installer. Make sure the game files\n"
                + "for " + ic.mcVersion + " have been downloaded first.",
                "Install Forge", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        runTask(
            () -> { installForge(ic.mcVersion, instanceDir); ic.modLoader = "FORGE"; saveInstanceConfig(ic); },
            () -> { int idx = instanceListModel.indexOf(ic); if (idx >= 0) instanceListModel.set(idx, ic); detailLoader.setText("FORGE"); JOptionPane.showMessageDialog(this, "Forge installed for " + ic.mcVersion + ".", "Success", JOptionPane.INFORMATION_MESSAGE); },
            err -> JOptionPane.showMessageDialog(this, "Forge install failed:\n" + err, "Error", JOptionPane.ERROR_MESSAGE),
            installFabricButton, installFabricForInstanceBtn);
    }

    /**
     * Core Forge installer logic.
     * Downloads the recommended Forge installer for the given MC version and runs it.
     */
    private void installForge(String mcVersion, String instanceDir) throws Exception {
        log("Looking up Forge versions for MC " + mcVersion + "...");
        String forgeVersion = findLatestForgeVersion(mcVersion);
        if (forgeVersion == null) throw new IOException("No Forge release found for Minecraft " + mcVersion + ".\n" +
            "Check https://files.minecraftforge.net/ for supported versions.");
        log("Found Forge version: " + forgeVersion);

        // Forge installer URL pattern:
        // https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.3.0/forge-1.20.1-47.3.0-installer.jar
        String installerUrl = FORGE_INSTALLER_BASE + forgeVersion + "/forge-" + forgeVersion + "-installer.jar";
        File toolsDir = new File(BASE_DIR, "tools/forge"); toolsDir.mkdirs();
        File installerJar = new File(toolsDir, "forge-" + forgeVersion + "-installer.jar");
        if (!installerJar.exists() || installerJar.length() < 10_000) {
            log("Downloading Forge installer: " + installerJar.getName());
            downloadFile(installerUrl, installerJar);
        }

        // Forge installer needs a "minecraft" directory structure.
        // We point --installClient at our instance dir. Forge will create:
        //   <instanceDir>/versions/<mcVersion>-forge-<forgeVer>/
        //   <instanceDir>/libraries/
        log("Running Forge installer (this may take 1-2 minutes)...");
        List<String> cmd = new ArrayList<>();
        cmd.add(findJava17Executable());
        cmd.add("-jar"); cmd.add(installerJar.getAbsolutePath());
        cmd.add("--installClient");
        cmd.add(instanceDir);
        log("Command: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(instanceDir));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        streamToLog(p.getInputStream());
        int exit = p.waitFor();
        if (exit != 0) throw new IOException("Forge installer exited with code " + exit + ". Check console for details.");
        log("Forge installer completed. Verifying installation...");

        // Verify: check that a forge version folder appeared
        File versionsDir = new File(instanceDir, "versions");
        File[] forgeFolders = versionsDir.exists()
            ? versionsDir.listFiles(f -> f.isDirectory() && f.getName().contains("forge"))
            : null;
        if (forgeFolders == null || forgeFolders.length == 0) {
            throw new IOException("Forge install appeared to succeed but no version folder was created.\n"
                + "Check the console output above for errors.");
        }
        log("Forge installed: " + forgeFolders[0].getName());
    }

    /**
     * Queries Forge's maven-metadata.json to find the recommended/latest Forge
     * build number for the given Minecraft version.
     *
     * maven-metadata.json structure:
     * { "1.20.1": { "latest": "47.3.0", "recommended": "47.3.0", "versions": [...] }, ... }
     */
    private String findLatestForgeVersion(String mcVersion) {
        try {
            String json = downloadString(FORGE_MAVEN_META);
            JsonObject meta = JsonParser.parseString(json).getAsJsonObject();
            if (!meta.has(mcVersion)) {
                // Try prefix match (e.g. "1.20" might match "1.20.1")
                for (Map.Entry<String, JsonElement> entry : meta.entrySet()) {
                    if (entry.getKey().startsWith(mcVersion + ".") || entry.getKey().equals(mcVersion)) {
                        mcVersion = entry.getKey(); break;
                    }
                }
            }
            if (!meta.has(mcVersion)) return null;
            JsonObject versionData = meta.getAsJsonObject(mcVersion);
            String build = versionData.has("recommended") ? versionData.get("recommended").getAsString()
                         : versionData.has("latest")      ? versionData.get("latest").getAsString()
                         : null;
            if (build == null) {
                // Fall back to latest in the versions array
                JsonArray versions = versionData.getAsJsonArray("versions");
                if (versions != null && versions.size() > 0) build = versions.get(versions.size()-1).getAsString();
            }
            if (build == null) return null;
            return mcVersion + "-" + build; // e.g. "1.20.1-47.3.0"
        } catch (Exception ex) {
            log("Warning: could not fetch Forge metadata: " + ex.getMessage());
            return null;
        }
    }

    /**
     * Builds the classpath for a Forge-modded launch.
     * Forge's version JSON (in versions/<mc>-forge-<ver>/<mc>-forge-<ver>.json)
     * contains a "libraries" array that specifies every JAR needed.
     * We parse that and build the full classpath.
     */
    private String buildForgeClasspath(File instanceDir, File clientJar) throws Exception {
        // Find the Forge version JSON
        File versionsDir = new File(instanceDir, "versions");
        File[] forgeFolders = versionsDir.listFiles(f -> f.isDirectory() && f.getName().contains("forge"));
        if (forgeFolders == null || forgeFolders.length == 0) throw new IOException("No Forge version folder found.");

        File forgeFolder = forgeFolders[0];
        File forgeJson = new File(forgeFolder, forgeFolder.getName() + ".json");
        if (!forgeJson.exists()) throw new IOException("Forge version JSON not found: " + forgeJson);

        JsonObject vData = JsonParser.parseString(new String(Files.readAllBytes(forgeJson.toPath()))).getAsJsonObject();
        StringBuilder cp = new StringBuilder(clientJar.getAbsolutePath());

        // Forge version JSONs use a Maven-style library list with "downloads" or just "name"
        if (vData.has("libraries")) {
            File libRoot = new File(instanceDir, "libraries");
            for (JsonElement le : vData.getAsJsonArray("libraries")) {
                JsonObject lib = le.getAsJsonObject();

                // Get the Maven coordinates path
                String path = null;
                if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("artifact")) {
                    path = lib.getAsJsonObject("downloads").getAsJsonObject("artifact").has("path")
                        ? lib.getAsJsonObject("downloads").getAsJsonObject("artifact").get("path").getAsString()
                        : null;
                }
                if (path == null && lib.has("name")) {
                    path = mavenCoordToPath(lib.get("name").getAsString());
                }
                if (path == null) continue;

                File f = new File(libRoot, path);
                if (f.exists()) cp.append(File.pathSeparator).append(f.getAbsolutePath());
            }
        }

        // Also add Forge's own JAR (the -universal or -shim jar in the version folder)
        File[] forgeJars = forgeFolder.listFiles((d, n) -> n.endsWith(".jar") && !n.endsWith("-slim.jar"));
        if (forgeJars != null) for (File fj : forgeJars) cp.append(File.pathSeparator).append(fj.getAbsolutePath());

        return cp.toString();
    }

    /** Converts a Maven coordinate string like "net.minecraftforge:forge:1.20.1-47.3.0" to a relative path. */
    private String mavenCoordToPath(String coord) {
        // coord = "groupId:artifactId:version" or "groupId:artifactId:version:classifier"
        String[] parts = coord.split(":");
        if (parts.length < 3) return null;
        String group    = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version  = parts[2];
        String classifier = parts.length > 3 ? "-" + parts[3] : "";
        return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + ".jar";
    }

    private void downloadAssetsFromIndex(File indexFile) {
        try {
            JsonObject objects = JsonParser.parseString(new String(Files.readAllBytes(indexFile.toPath()))).getAsJsonObject().getAsJsonObject("objects");
            if (objects == null) return;
            File objectsDir = new File(BASE_DIR, "assets/objects");

            // Collect all assets that need downloading
            List<String[]> needed = new ArrayList<>();
            for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
                JsonObject meta = entry.getValue().getAsJsonObject(); if (!meta.has("hash")) continue;
                String hash = meta.get("hash").getAsString();
                File objFile = new File(objectsDir, hash.substring(0, 2) + "/" + hash);
                if (!objFile.exists()) needed.add(new String[]{entry.getKey(), hash});
            }
            if (needed.isEmpty()) { log("Assets already up to date."); return; }
            log("Downloading " + needed.size() + " assets (parallel)...");

            // Parallel download with 8-thread pool
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
            java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger(0);
            int total = needed.size();
            SwingUtilities.invokeLater(() -> { progressBar.setIndeterminate(false); progressBar.setMaximum(total); progressBar.setValue(0); });

            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (String[] asset : needed) {
                futures.add(pool.submit(() -> {
                    String hash = asset[1];
                    String sub = hash.substring(0, 2);
                    File objFile = new File(objectsDir, sub + "/" + hash);
                    objFile.getParentFile().mkdirs();
                    try { downloadFile("https://resources.download.minecraft.net/" + sub + "/" + hash, objFile); }
                    catch (IOException ioe) { log("Asset fail: " + asset[0]); failed.incrementAndGet(); }
                    int n = done.incrementAndGet();
                    SwingUtilities.invokeLater(() -> progressBar.setValue(n));
                }));
            }
            pool.shutdown();
            for (java.util.concurrent.Future<?> f : futures) try { f.get(); } catch (Exception ignored) {}
            log("Assets sync complete. " + done.get() + "/" + total + " downloaded" + (failed.get() > 0 ? ", " + failed.get() + " failed." : "."));
        } catch (Exception e) { log("Asset error: " + e.getMessage()); debugException(e); }
    }

    // ===================== HELPERS =====================

    private String buildClasspath(File jar, File lib) {
        StringBuilder cp = new StringBuilder(jar.getAbsolutePath());
        if (lib.exists()) {
            try (Stream<Path> walk = Files.walk(lib.toPath())) {
                List<Path> jars = walk.filter(p -> p.toString().endsWith(".jar"))
                    .sorted((a, b) -> { String sa = a.toString(), sb = b.toString(); boolean aL = sa.contains("lwjgl"), bL = sb.contains("lwjgl"); if (aL && bL) return sb.compareTo(sa); return sa.compareTo(sb); })
                    .collect(Collectors.toList());
                for (Path p : jars) cp.append(File.pathSeparator).append(p);
            } catch (IOException e) { debugException(e); }
        }
        return cp.toString();
    }

    private boolean containsLwjglNative(File d) {
        String[] files = d.list(); if (files == null) return false;
        for (String f : files) { String l = f.toLowerCase(); if (l.contains("lwjgl") && (l.endsWith(".dll") || l.endsWith(".so") || l.endsWith(".dylib"))) return true; }
        return false;
    }

    private void extractNativeBinaries(File zipFile, File destDir) {
        if (!zipFile.exists()) return;
        Set<String> exts = new HashSet<>(Arrays.asList(".dll", ".so", ".dylib", ".jnilib", ".bundle"));
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry; int extracted = 0;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) { zis.closeEntry(); continue; }
                String name = Paths.get(entry.getName()).getFileName().toString();
                if (exts.stream().noneMatch(name.toLowerCase()::endsWith)) { zis.closeEntry(); continue; }
                File out = new File(destDir, name);
                try (OutputStream os = new FileOutputStream(out)) { byte[] buf = new byte[8192]; int r; while ((r = zis.read(buf)) != -1) os.write(buf, 0, r); }
                try { out.setExecutable(true, false); } catch (Exception ig) {}
                extracted++; zis.closeEntry();
            }
            log("Extracted " + extracted + " natives.");
        } catch (IOException e) { log("Extract failed: " + e.getMessage()); debugException(e); }
    }

    private void extractAllNatives(File librariesDir, File nativesDir) {
        if (!librariesDir.exists()) return; nativesDir.mkdirs();
        try (Stream<Path> walk = Files.walk(librariesDir.toPath())) {
            for (Path jarPath : walk.filter(p -> p.toString().toLowerCase().contains("natives") && p.toString().endsWith(".jar")).collect(Collectors.toList())) {
                try (ZipInputStream zis = new ZipInputStream(new FileInputStream(jarPath.toFile()))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        if (entry.isDirectory()) { zis.closeEntry(); continue; }
                        String name = Paths.get(entry.getName()).getFileName().toString(); String lower = name.toLowerCase();
                        if (lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib") || lower.endsWith(".jnilib")) {
                            File out = new File(nativesDir, name);
                            try (OutputStream os = new FileOutputStream(out)) { byte[] buf = new byte[8192]; int r; while ((r = zis.read(buf)) != -1) os.write(buf, 0, r); }
                            try { out.setExecutable(true, false); } catch (Exception ig) {}
                        }
                        zis.closeEntry();
                    }
                } catch (IOException ex) { log("Extract fail from " + jarPath.getFileName() + ": " + ex.getMessage()); }
            }
        } catch (IOException e) { log("Scan error: " + e.getMessage()); }
    }

    private void installPortableJdk(String zipUrl, String expectedSha256) throws Exception {
        File portableRoot = new File(BASE_DIR, "portable_jdk"); portableRoot.mkdirs();
        File zipFile = new File(portableRoot, "jdk_download.zip");
        log("Downloading JDK..."); downloadFile(zipUrl, zipFile);
        if (expectedSha256 != null && !expectedSha256.isEmpty()) { if (!sha256OfFile(zipFile).equalsIgnoreCase(expectedSha256)) throw new IOException("Checksum mismatch"); }
        File extractDir = new File(portableRoot, "jdk"); if (extractDir.exists()) deleteRecursively(extractDir.toPath()); extractZip(zipFile, extractDir);
        File javaExe = findJavaExe(extractDir); if (javaExe == null) throw new IOException("java.exe not found");
        ProcessBuilder pb = new ProcessBuilder(javaExe.getAbsolutePath(), "-version"); pb.redirectErrorStream(true);
        Process p = pb.start(); log("java -version: " + readStreamFully(p.getInputStream()).trim()); if (p.waitFor() != 0) throw new IOException("java -version failed");
        Files.write(new File(portableRoot, "installed.txt").toPath(), javaExe.getAbsolutePath().getBytes());
        log("Portable JDK installed.");
    }

    private String hashFile(File f, String algo) throws Exception {
        MessageDigest md = MessageDigest.getInstance(algo);
        try (InputStream in = Files.newInputStream(f.toPath())) { byte[] buf = new byte[8192]; int r; while ((r = in.read(buf)) != -1) md.update(buf, 0, r); }
        StringBuilder sb = new StringBuilder(); for (byte b : md.digest()) sb.append(String.format("%02x", b)); return sb.toString();
    }
    private String sha1OfFile(File f) throws Exception { return hashFile(f, "SHA-1"); }
    private String sha256OfFile(File f) throws Exception { return hashFile(f, "SHA-256"); }

    private String downloadString(String url) throws IOException {
        try {
            return downloadStringOnce(url);
        } catch (IOException e) {
            // If HTTPS was blocked/intercepted, retry with HTTP
            if (url.startsWith("https://")) {
                log("HTTPS failed (" + e.getMessage().split("\n")[0] + "), retrying over HTTP...");
                return downloadStringOnce(url.replaceFirst("^https://", "http://"));
            }
            throw e;
        }
    }

    private String downloadStringOnce(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "GeminiLauncher/1.0");
        conn.setConnectTimeout(15_000); conn.setReadTimeout(30_000);
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) throw new IOException("No response body (HTTP " + code + ") from " + url);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            String body = sb.toString();
            String trimmed = body.trim();
            if (trimmed.startsWith("<") || trimmed.startsWith("<!")) {
                String snippet = trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
                throw new IOException(
                    "Proxy/firewall returned HTML instead of JSON.\n" +
                    "The site may be blocked on this network.\n" +
                    "URL: " + url + "\nHTTP " + code + "\nPreview: " + snippet);
            }
            if (code >= 400) throw new IOException("HTTP " + code + " from " + url + ": " + body);
            return body;
        } finally { conn.disconnect(); }
    }

    private void downloadFile(String url, File dest) throws IOException {
        try {
            downloadFileOnce(url, dest);
        } catch (IOException e) {
            if (url.startsWith("https://")) {
                log("HTTPS failed, retrying over HTTP...");
                downloadFileOnce(url.replaceFirst("^https://", "http://"), dest);
                return;
            }
            throw e;
        }
    }

    private void downloadFileOnce(String url, File dest) throws IOException {
        if (!dest.getParentFile().exists() && !dest.getParentFile().mkdirs()) throw new IOException("Failed to create dirs: " + dest.getParent());
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "GeminiLauncher/1.0"); conn.setConnectTimeout(15_000); conn.setReadTimeout(0); conn.setInstanceFollowRedirects(true);
        File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
        try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192]; int r; long total = 0, cl = conn.getContentLengthLong();
            SwingUtilities.invokeLater(() -> { if (cl > 0) { progressBar.setIndeterminate(false); progressBar.setMaximum(1000); } else progressBar.setIndeterminate(true); });
            while ((r = in.read(buf)) != -1) { out.write(buf, 0, r); total += r; if (cl > 0) { final int prog = (int) Math.min(1000, (total * 1000) / cl); SwingUtilities.invokeLater(() -> progressBar.setValue(prog)); } }
            out.flush();
        } finally { conn.disconnect(); }
        try { Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        finally { SwingUtilities.invokeLater(() -> { progressBar.setIndeterminate(false); progressBar.setValue(0); }); }
    }

    private void extractZip(File zipFile, File destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry; while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) { zis.closeEntry(); continue; }
                File out = new File(destDir, entry.getName()); out.getParentFile().mkdirs();
                try (OutputStream os = new FileOutputStream(out)) { byte[] buf = new byte[8192]; int r; while ((r = zis.read(buf)) != -1) os.write(buf, 0, r); }
                zis.closeEntry();
            }
        }
    }

    private File findJavaExe(File root) {
        try (Stream<Path> walk = Files.walk(root.toPath())) { return walk.filter(p -> p.getFileName().toString().equalsIgnoreCase("java.exe")).findFirst().map(Path::toFile).orElse(null); } catch (IOException e) { return null; }
    }

    private void streamToLog(InputStream in) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
            String line; while ((line = br.readLine()) != null) { final String l = line; SwingUtilities.invokeLater(() -> { logArea.append(l + "\n"); logArea.setCaretPosition(logArea.getDocument().getLength()); }); }
        } catch (IOException e) { debugException(e); }
    }

    private String getAssetIndex(String vId, String instanceDir) {
        try { File vJson = new File(instanceDir, "bin/version.json"); if (vJson.exists()) { JsonObject d = JsonParser.parseString(new String(Files.readAllBytes(vJson.toPath()))).getAsJsonObject(); if (d.has("assetIndex")) return d.getAsJsonObject("assetIndex").get("id").getAsString(); } } catch (Exception ig) {}
        return vId.contains(".") ? vId.substring(0, vId.lastIndexOf('.')) : vId;
    }

    private String readStreamFully(InputStream in) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) { StringBuilder sb = new StringBuilder(); String l; while ((l = br.readLine()) != null) sb.append(l).append("\n"); return sb.toString(); }
    }

    private void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        Files.walk(p).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }

    @SuppressWarnings("unused")
    private void zipDirectory(Path sourceDirPath, Path zipFilePath) throws IOException {
        try (ZipOutputStream zs = new ZipOutputStream(Files.newOutputStream(zipFilePath))) {
            Files.walk(sourceDirPath).filter(p -> !Files.isDirectory(p)).forEach(path -> {
                ZipEntry ze = new ZipEntry(sourceDirPath.relativize(path).toString().replace("\\", "/"));
                try { zs.putNextEntry(ze); Files.copy(path, zs); zs.closeEntry(); } catch (IOException e) { throw new UncheckedIOException(e); }
            });
        } catch (UncheckedIOException u) { throw u.getCause(); }
    }

    private String findClassifierKeyForOs(JsonObject classifiers, String osKey) {
        if (classifiers == null || osKey == null) return null;
        if (classifiers.has(osKey)) return osKey;
        for (String c : new String[]{"natives-" + osKey, "natives_" + osKey}) if (classifiers.has(c)) return c;
        for (Map.Entry<String, JsonElement> en : classifiers.entrySet()) if (en.getKey().toLowerCase().contains(osKey)) return en.getKey();
        return null;
    }

    private boolean validateRam(String s) {
        if (s == null || s.isEmpty()) return false; s = s.trim().toUpperCase();
        try {
            if (s.endsWith("G")) return Integer.parseInt(s.substring(0, s.length()-1)) >= 1;
            if (s.endsWith("M")) return Integer.parseInt(s.substring(0, s.length()-1)) >= 128;
            return Integer.parseInt(s) >= 128;
        } catch (NumberFormatException e) { return false; }
    }

    private String detectOsKey() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "windows"; if (os.contains("mac")) return "osx"; return "linux";
    }

    private String sanitizeName(String s) { return s.replaceAll("[^A-Za-z0-9\\-_.]", "_"); }

    private void log(String s) {
        SwingUtilities.invokeLater(() -> { logArea.append(s + "\n"); logArea.setCaretPosition(logArea.getDocument().getLength()); });
    }

    private void debugException(Exception e) {
        try {
            StringWriter sw = new StringWriter(); e.printStackTrace(new PrintWriter(sw));
            File debug = new File(BASE_DIR, "debug.log"); debug.getParentFile().mkdirs();
            Files.write(debug.toPath(), (new Date() + " - " + sw + "\n").getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    // ===================== VENDOR JDK INSTALL =====================

    private static class JdkVendor {
        final String name, zipUrl, sha256; final int expectedMajor;
        JdkVendor(String n, String u, String s, int m) { name=n; zipUrl=u; sha256=s; expectedMajor=m; }
    }

    private List<JdkVendor> vendorsForMajor(int major) {
        List<JdkVendor> list = new ArrayList<>();
        switch (major) {
            case 25: list.add(new JdkVendor("Temurin 25","https://github.com/adoptium/temurin25-binaries/releases/latest/download/OpenJDK25U-jdk_x64_windows_hotspot.zip","",25)); list.add(new JdkVendor("Corretto 25","https://corretto.aws/downloads/latest/amazon-corretto-25-x64-windows-jdk.zip","",25)); break;
            case 21: list.add(new JdkVendor("Temurin 21","https://github.com/adoptium/temurin21-binaries/releases/latest/download/OpenJDK21U-jdk_x64_windows_hotspot.zip","",21)); list.add(new JdkVendor("Corretto 21","https://corretto.aws/downloads/latest/amazon-corretto-21-x64-windows-jdk.zip","",21)); break;
            case 17: list.add(new JdkVendor("Temurin 17","https://github.com/adoptium/temurin17-binaries/releases/latest/download/OpenJDK17U-jdk_x64_windows_hotspot.zip","",17)); list.add(new JdkVendor("Corretto 17","https://corretto.aws/downloads/latest/amazon-corretto-17-x64-windows-jdk.zip","",17)); break;
            case 8:  list.add(new JdkVendor("Temurin 8","https://github.com/adoptium/temurin8-binaries/releases/latest/download/OpenJDK8U-jdk_x64_windows_hotspot.zip","",8)); list.add(new JdkVendor("Corretto 8","https://corretto.aws/downloads/latest/amazon-corretto-8-x64-windows-jdk.zip","",8)); break;
        }
        return list;
    }

    private String tryInstallFromVendors(List<JdkVendor> vendors) {
        File root = new File(BASE_DIR, "portable_jdks"); root.mkdirs();
        for (JdkVendor v : vendors) {
            log("Trying vendor: " + v.name);
            File vendorDir = new File(root, sanitizeName(v.name));
            try {
                if (vendorDir.exists()) deleteRecursively(vendorDir.toPath()); vendorDir.mkdirs();
                File zipFile = new File(vendorDir, "download.zip");
                log("Downloading " + v.zipUrl); downloadFile(v.zipUrl, zipFile);
                if (v.sha256 != null && !v.sha256.isEmpty() && !sha256OfFile(zipFile).equalsIgnoreCase(v.sha256)) { log("Checksum mismatch"); zipFile.delete(); deleteRecursively(vendorDir.toPath()); continue; }
                log("Extracting " + v.name); extractZip(zipFile, vendorDir);
                File javaExe = findJavaExe(vendorDir); if (javaExe == null) { deleteRecursively(vendorDir.toPath()); continue; }
                ProcessBuilder pb = new ProcessBuilder(javaExe.getAbsolutePath(), "-version"); pb.redirectErrorStream(true);
                Process p = pb.start(); String out = readStreamFully(p.getInputStream()); if (p.waitFor() != 0) { deleteRecursively(vendorDir.toPath()); continue; }
                Integer major = parseMajorFromJavaVersionOutput(out); if (major == null || major != v.expectedMajor) { deleteRecursively(vendorDir.toPath()); continue; }
                Files.write(new File(root, "installed_" + sanitizeName(v.name) + ".txt").toPath(), javaExe.getAbsolutePath().getBytes());
                log("Installed " + v.name + " at " + javaExe.getAbsolutePath()); return javaExe.getAbsolutePath();
            } catch (Exception ex) { log("Error for " + v.name + ": " + ex.getMessage()); try { deleteRecursively(vendorDir.toPath()); } catch (Exception ig) {} }
        }
        log("All vendors failed."); return null;
    }

    private Integer parseMajorFromJavaVersionOutput(String out) {
        try (BufferedReader br = new BufferedReader(new StringReader(out))) {
            String line; while ((line = br.readLine()) != null) {
                int q = line.indexOf('"'); if (q < 0) continue; int r = line.indexOf('"', q+1); if (r <= q) continue;
                String ver = line.substring(q+1, r); String[] parts = ver.split("\\.");
                if (ver.startsWith("1.") && parts.length >= 2) return Integer.parseInt(parts[1]);
                if (parts.length >= 1) return Integer.parseInt(parts[0]);
            }
        } catch (Exception ig) {}
        return null;
    }

    private void disableSSLVerification() throws Exception {
        TrustManager[] t = new TrustManager[]{ new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
        }};
        // Must use "TLS" not "SSL" — covers TLS 1.0/1.1/1.2/1.3 which all modern connections use
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, t, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
    }

    public static void main(String[] args) { SwingUtilities.invokeLater(launcher::new); }
}