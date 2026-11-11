package dev.nuclearlang.editor;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor;
import com.intellij.platform.lsp.api.customization.LspCodeLensCustomizer;
import com.intellij.platform.lsp.api.customization.LspCustomization;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/** How to run nuclear-lsp for a project. The server finds the dialects shipped next to its binary. */
public final class NuclearLspServerDescriptor extends ProjectWideLspServerDescriptor {
    public NuclearLspServerDescriptor(@NotNull Project project) {
        super(project, "Nuclear");
    }

    static boolean isNuclear(VirtualFile file) {
        return "nuc".equals(file.getExtension());
    }

    @Override
    public boolean isSupportedFile(@NotNull VirtualFile file) {
        return isNuclear(file);
    }

    @Override
    public @NotNull String getLanguageId(@NotNull VirtualFile file) {
        return "nuclear";
    }

    @Override
    public @NotNull GeneralCommandLine createCommandLine() throws ExecutionException {
        String server = NuclearSettings.findServer();
        if (server == null) {
            throw new ExecutionException("nuclear-lsp was not found. Set its path in Settings > Tools > NuclearLang.");
        }
        GeneralCommandLine cmd = new GeneralCommandLine(server);
        String base = getProject().getBasePath();
        if (base != null) {
            cmd.withWorkDirectory(base);
        }
        return cmd;
    }

    @Override
    public Object createInitializationOptions() {
        return Map.of("dialect", NuclearSettings.dialect(), "dialectPaths", List.of(),
            "codemetrics", NuclearCodeMetrics.settings());
    }

    /** Code lenses of the code metrics open the details list when clicked. */
    @Override
    public @NotNull LspCustomization getLspCustomization() {
        return new LspCustomization() {
            @Override
            public @NotNull LspCodeLensCustomizer getCodeLensCustomizer() {
                return new NuclearCodeLensSupport();
            }
        };
    }
}
