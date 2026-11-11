package dev.nuclearlang.editor;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspServerSupportProvider;
import org.jetbrains.annotations.NotNull;

/** Starts nuclear-lsp when a `.nuc` file is opened. */
public final class NuclearLspServerSupportProvider implements LspServerSupportProvider {
    @Override
    public void fileOpened(@NotNull Project project, @NotNull VirtualFile file, @NotNull LspServerStarter starter) {
        if (NuclearLspServerDescriptor.isNuclear(file) && NuclearSettings.findServer() != null) {
            starter.ensureServerStarted(new NuclearLspServerDescriptor(project));
        }
    }
}
