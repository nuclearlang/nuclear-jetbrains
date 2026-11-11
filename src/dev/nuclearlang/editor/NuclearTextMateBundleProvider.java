package dev.nuclearlang.editor;

import org.jetbrains.plugins.textmate.api.TextMateBundleProvider;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Registers the same TextMate grammar the VS Code extension uses (shipped in `textmate/nuclear`). */
public final class NuclearTextMateBundleProvider implements TextMateBundleProvider {
    @Override
    public @NotNull List<PluginBundle> getBundles() {
        Path root = NuclearSettings.pluginPath();
        if (root == null) {
            return List.of();
        }
        Path bundle = root.resolve("textmate").resolve("nuclear");
        return Files.isDirectory(bundle) ? List.of(new PluginBundle("NuclearLang", bundle)) : List.of();
    }
}
