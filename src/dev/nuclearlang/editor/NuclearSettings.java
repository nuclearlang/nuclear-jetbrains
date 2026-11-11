package dev.nuclearlang.editor;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.extensions.PluginId;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/** Application-wide settings of the plugin and the files it ships (server binary, dialects, TextMate bundle). */
public final class NuclearSettings {
    public static final String PLUGIN_ID = "dev.nuclearlang";
    private static final String DIALECT = "nuclear.dialect";
    private static final String SERVER_PATH = "nuclear.server.path";
    private static final String FOLLOW = "nuclear.debug.followInNuclear";
    private static final String EXPLAIN = "nuclear.debug.explainErrors";

    private NuclearSettings() {}

    public static String dialect() {
        return PropertiesComponent.getInstance().getValue(DIALECT, "flutter");
    }

    public static void setDialect(String value) {
        PropertiesComponent.getInstance().setValue(DIALECT, value, "flutter");
    }

    public static String serverPath() {
        return PropertiesComponent.getInstance().getValue(SERVER_PATH, "");
    }

    public static void setServerPath(String value) {
        PropertiesComponent.getInstance().setValue(SERVER_PATH, value, "");
    }

    /** When a Dragonfly app pauses, show and highlight the `.nuc` line of the paused code. */
    public static boolean followInNuclear() {
        return PropertiesComponent.getInstance().getBoolean(FOLLOW, true);
    }

    public static void setFollowInNuclear(boolean value) {
        PropertiesComponent.getInstance().setValue(FOLLOW, value, true);
    }

    /** Mark Dart run-time errors at their `.nuc` line and explain them in the "Nuclear Error" tool window. */
    public static boolean explainErrors() {
        return PropertiesComponent.getInstance().getBoolean(EXPLAIN, true);
    }

    public static void setExplainErrors(boolean value) {
        PropertiesComponent.getInstance().setValue(EXPLAIN, value, true);
    }

    /** Root directory of the installed plugin. */
    public static Path pluginPath() {
        IdeaPluginDescriptor plugin = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID));
        return plugin == null ? null : plugin.getPluginPath();
    }

    /** The nuclear-lsp binary: setting → bundled with the plugin → PATH. */
    public static String findServer() {
        String configured = serverPath();
        if (!configured.isBlank()) {
            return configured;
        }
        String exe = System.getProperty("os.name").toLowerCase().contains("win") ? "nuclear-lsp.exe" : "nuclear-lsp";
        Path root = pluginPath();
        if (root != null) {
            Path bundled = root.resolve("server").resolve(exe);
            if (Files.isRegularFile(bundled)) {
                bundled.toFile().setExecutable(true);
                return bundled.toString();
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                Path candidate = Path.of(dir, exe);
                if (Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return null;
    }
}
