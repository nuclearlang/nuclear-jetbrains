package dev.nuclearlang.editor;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** "Toggle code metrics" and "Toggle code lenses for arrow functions" (Tools > NuclearLang). */
public abstract class NuclearCodeMetricsActions extends AnAction {
    private final String command;

    NuclearCodeMetricsActions(String command) {
        this.command = command;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            NuclearCodeMetrics.execute(project, command, List.of());
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed()) {
                    // asks the editor for the code lenses again
                    DaemonCodeAnalyzer.getInstance(project).restart();
                }
            });
        });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(e.getProject() != null && NuclearCodeMetrics.server(e.getProject()) != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    public static final class ToggleDisplayed extends NuclearCodeMetricsActions {
        public ToggleDisplayed() {
            super(NuclearCodeMetrics.TOGGLE_DISPLAYED);
        }
    }

    public static final class ToggleArrowFunctions extends NuclearCodeMetricsActions {
        public ToggleArrowFunctions() {
            super(NuclearCodeMetrics.TOGGLE_ARROW_FUNCTIONS);
        }
    }
}
