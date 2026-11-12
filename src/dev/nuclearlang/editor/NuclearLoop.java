package dev.nuclearlang.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.treeStructure.Tree;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The "Nuclear Loop" tool window (ADR-0102): the specs (`specs/<id>/spec.md`) and runs (`.nuclear/loop/runs/*.json`)
 * of every project, each run as its stages and steps with who did them, and actions that run `nuclear-loop` (the same
 * engine as the VS Code Loop tool): run a spec with the local agents or with Claude leading, the server stages, and the
 * CI export. Alt+Shift+P opens it.
 */
public final class NuclearLoop {
    static final JBColor PASSED = new JBColor(new Color(0x64FF00), new Color(0x2E8B57));
    static final JBColor FAILED = new JBColor(new Color(0xFF4B4B), new Color(0xD32F2F));
    static final JBColor RUNNING = new JBColor(new Color(0xFFD600), new Color(0xB8860B));

    private NuclearLoop() {}

    /** A node of the tree: what it shows and what a click opens. */
    record Item(String text, String detail, String status, Path file, String log, NuclearHttp.Proj project, Path spec) {
        @Override
        public String toString() {
            return text;
        }
    }

    public static final class Window implements ToolWindowFactory {
        @Override
        public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow window) {
            Panel panel = new Panel(project);
            window.getContentManager().addContent(ContentFactory.getInstance().createContent(panel, "", false));
            window.setTitleActions(List.of(
                    panel.action("New Spec", AllIcons.General.Add, panel::newSpec),
                    panel.action("Run the Spec (local agents)", AllIcons.Actions.Execute, () -> panel.runSpec("local")),
                    panel.action("Run the Spec (Claude leads)", AllIcons.Actions.RunAll, () -> panel.runSpec("claude")),
                    panel.action("Server Stages (nuclear-loop ci)", AllIcons.Actions.Compile, panel::ci),
                    panel.action("Export CI", AllIcons.Actions.Upload, panel::export),
                    panel.action("Stop", AllIcons.Actions.Suspend, panel::stop),
                    panel.action("Refresh", AllIcons.Actions.Refresh, panel::refresh)));
        }
    }

    /** Alt+Shift+P (plugin.xml): opens the tool window from anywhere. */
    public static final class Open extends AnAction {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            if (project == null) return;
            ToolWindow w = ToolWindowManager.getInstance(project).getToolWindow("Nuclear Loop");
            if (w != null) w.activate(null);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }

    static final class Panel extends JPanel {
        private final Project project;
        private final Path base;
        private final Tree tree = new Tree();
        private final JBTextArea log = new JBTextArea();
        private final Timer poll = new Timer(1500, e -> refresh());
        private Process running;
        private boolean restoring;

        Panel(Project project) {
            super(new BorderLayout());
            this.project = project;
            this.base = Path.of(project.getBasePath() == null ? "." : project.getBasePath());
            tree.setRootVisible(false);
            tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
            tree.setCellRenderer(new ColoredTreeCellRenderer() {
                @Override
                public void customizeCellRenderer(@NotNull JTree t, Object value, boolean sel, boolean exp, boolean leaf, int row, boolean focus) {
                    if (!(((DefaultMutableTreeNode) value).getUserObject() instanceof Item item)) return;
                    JBColor color = switch (item.status() == null ? "" : item.status()) {
                        case "passed" -> PASSED;
                        case "failed" -> FAILED;
                        case "running" -> RUNNING;
                        default -> null;
                    };
                    String mark = switch (item.status() == null ? "" : item.status()) {
                        case "passed" -> "✓ ";
                        case "failed" -> "✗ ";
                        case "running" -> "▶ ";
                        case "skipped" -> "· ";
                        default -> "";
                    };
                    append(mark + item.text(), color == null ? SimpleTextAttributes.REGULAR_ATTRIBUTES : new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color));
                    if (!item.detail().isEmpty()) append("  " + item.detail(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
            });
            tree.addTreeSelectionListener(e -> {
                Item item = selected();
                if (item == null || restoring) return;
                if (item.log() != null) log.setText(item.log());
                if (item.file() != null && Files.isRegularFile(item.file())) open(item.file());
            });
            log.setEditable(false);
            log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, log.getFont().getSize()));
            OnePixelSplitter split = new OnePixelSplitter(true, 0.55f);
            split.setFirstComponent(new JBScrollPane(tree));
            split.setSecondComponent(new JBScrollPane(log));
            add(split, BorderLayout.CENTER);
            refresh();
        }

        AnAction action(String text, Icon icon, Runnable run) {
            return new AnAction(text, text, icon) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    run.run();
                }

                @Override
                public @NotNull ActionUpdateThread getActionUpdateThread() {
                    return ActionUpdateThread.BGT;
                }
            };
        }

        private Item selected() {
            Object n = tree.getLastSelectedPathComponent();
            return n instanceof DefaultMutableTreeNode d && d.getUserObject() instanceof Item i ? i : null;
        }

        /** The project of the selection, or the only project. */
        private NuclearHttp.Proj projectOf() {
            Item item = selected();
            if (item != null && item.project() != null) return item.project();
            List<NuclearHttp.Proj> all = NuclearHttp.findProjects(base);
            if (all.size() == 1) return all.get(0);
            Messages.showInfoMessage(project, "Select a project, a spec or a run in the Nuclear Loop window first.", "Nuclear Loop");
            return null;
        }

        void refresh() {
            // the selection is kept by its labels: the tree is read again from the files
            List<String> keep = new ArrayList<>();
            if (tree.getSelectionPath() != null) for (Object o : tree.getSelectionPath().getPath()) keep.add(String.valueOf(o));
            DefaultMutableTreeNode root = new DefaultMutableTreeNode();
            for (NuclearHttp.Proj p : NuclearHttp.findProjects(base)) {
                DefaultMutableTreeNode pn = new DefaultMutableTreeNode(new Item(p.name(), (p.platform() == null ? "" : p.platform() + " · ") + p.dialect(), null, null, null, p, null));
                DefaultMutableTreeNode specs = new DefaultMutableTreeNode(new Item("Specs", "", null, null, null, p, null));
                for (Path spec : specs(p.root())) {
                    String id = spec.getParent().getFileName().toString();
                    specs.add(new DefaultMutableTreeNode(new Item(id, "", null, spec, null, p, spec)));
                }
                pn.add(specs);
                DefaultMutableTreeNode runs = new DefaultMutableTreeNode(new Item("Runs", "", null, null, null, p, null));
                for (JsonObject run : runs(p.root())) runs.add(runNode(p, run));
                pn.add(runs);
                root.add(pn);
            }
            restoring = true;
            tree.setModel(new DefaultTreeModel(root));
            for (int i = 0; i < tree.getRowCount() && i < 64; i++) tree.expandRow(i);
            DefaultMutableTreeNode at = root;
            for (String label : keep.subList(Math.min(1, keep.size()), keep.size())) {
                DefaultMutableTreeNode next = null;
                for (int i = 0; i < at.getChildCount() && next == null; i++) {
                    DefaultMutableTreeNode c = (DefaultMutableTreeNode) at.getChildAt(i);
                    if (String.valueOf(c).equals(label)) next = c;
                }
                if (next == null) break;
                at = next;
            }
            if (at != root) tree.setSelectionPath(new javax.swing.tree.TreePath(at.getPath()));
            restoring = false;
            if (running == null || !running.isAlive()) poll.stop();
        }

        private DefaultMutableTreeNode runNode(NuclearHttp.Proj p, JsonObject run) {
            JsonObject spec = run.getAsJsonObject("spec");
            Path specFile = p.root().resolve("specs").resolve(str(run, "id")).resolve("spec.md");
            double cost = run.has("costUsd") ? run.get("costUsd").getAsDouble() : 0;
            DefaultMutableTreeNode rn = new DefaultMutableTreeNode(new Item(str(spec, "title"), str(spec, "lead") + (cost > 0 ? String.format(" · USD %.2f", cost) : ""), str(run, "status"), null, null, p, specFile));
            for (JsonElement se : run.getAsJsonArray("stages")) {
                JsonObject stage = se.getAsJsonObject();
                DefaultMutableTreeNode sn = new DefaultMutableTreeNode(new Item(str(stage, "title"), str(stage, "about"), str(stage, "status"), null, str(stage, "about"), p, specFile));
                for (JsonElement st : stage.getAsJsonArray("steps")) {
                    JsonObject step = st.getAsJsonObject();
                    String file = str(step, "file");
                    StringBuilder text = new StringBuilder();
                    if (step.has("trace")) for (JsonElement t : step.getAsJsonArray("trace")) {
                        JsonObject ti = t.getAsJsonObject();
                        text.append("· ").append(str(ti, "step")).append(": ").append(str(ti, "detail")).append('\n');
                    }
                    if (!str(step, "input").isEmpty()) text.append("\n# input\n").append(str(step, "input")).append('\n');
                    if (!str(step, "code").isEmpty()) text.append("\n# code\n").append(str(step, "code")).append('\n');
                    text.append("\n# output\n").append(str(step, "log"));
                    sn.add(new DefaultMutableTreeNode(new Item(str(step, "title"), str(step, "actor") + (str(step, "summary").isEmpty() ? "" : " — " + str(step, "summary")),
                            str(step, "status"), file.isEmpty() ? null : p.root().resolve(file), text.toString(), p, specFile)));
                }
                rn.add(sn);
            }
            return rn;
        }

        private static List<Path> specs(Path root) {
            try (Stream<Path> s = Files.list(root.resolve("specs"))) {
                return s.map(d -> d.resolve("spec.md")).filter(Files::isRegularFile).sorted().toList();
            } catch (IOException e) {
                return List.of();
            }
        }

        private static List<JsonObject> runs(Path root) {
            List<JsonObject> out = new ArrayList<>();
            try (Stream<Path> s = Files.list(root.resolve(".nuclear/loop/runs"))) {
                for (Path f : s.filter(x -> x.toString().endsWith(".json")).toList()) {
                    try {
                        out.add(JsonParser.parseString(Files.readString(f)).getAsJsonObject());
                    } catch (Exception ignored) {
                        // a run being written: the next refresh reads it
                    }
                }
            } catch (IOException ignored) {
            }
            out.sort((a, b) -> Long.compare(b.get("created").getAsLong(), a.get("created").getAsLong()));
            return out;
        }

        private static String str(JsonObject o, String key) {
            return o != null && o.has(key) && !o.get(key).isJsonNull() && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
        }

        private void open(Path file) {
            VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
            if (vf != null) FileEditorManager.getInstance(project).openFile(vf, false);
        }

        void newSpec() {
            NuclearHttp.Proj p = projectOf();
            if (p == null) return;
            String title = Messages.showInputDialog(project, "Title of the spec", "New Nuclear Loop Spec", null);
            if (title == null || title.isBlank()) return;
            String id = title.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            Path file = p.root().resolve("specs").resolve(id.isEmpty() ? "spec" : id).resolve("spec.md");
            try {
                Files.createDirectories(file.getParent());
                if (!Files.exists(file)) Files.writeString(file, "# " + title + "\n\n- Use case: feature\n- Source: local\n- Lead: local agents only\n\n## Description\n\n\n\n## Acceptance criteria\n\n- [ ] \n");
            } catch (IOException e) {
                Messages.showErrorDialog(project, e.getMessage(), "Nuclear Loop");
                return;
            }
            open(file);
            refresh();
        }

        void runSpec(String lead) {
            Item item = selected();
            if (item == null || item.spec() == null || !Files.isRegularFile(item.spec())) {
                Messages.showInfoMessage(project, "Select a spec (or a run of it) first.", "Nuclear Loop");
                return;
            }
            start(item.project(), List.of("run", item.project().root().relativize(item.spec()).toString(), "--lead", lead));
        }

        void ci() {
            NuclearHttp.Proj p = projectOf();
            if (p != null) start(p, List.of("ci"));
        }

        void export() {
            NuclearHttp.Proj p = projectOf();
            if (p == null) return;
            String[] providers = {"github", "gitlab", "bitbucket"};
            int i = Messages.showChooseDialog(project, "Where the pipeline runs", "Export CI", null, providers, providers[0]);
            if (i >= 0) start(p, List.of("export", providers[i]));
        }

        void stop() {
            if (running != null && running.isAlive()) running.destroy();
        }

        /** `nuclear-loop <args>` in the project; its output streams into the log and the tree follows the run file. */
        private void start(NuclearHttp.Proj p, List<String> args) {
            if (running != null && running.isAlive()) {
                Messages.showInfoMessage(project, "A Loop command is still running (Stop ends it).", "Nuclear Loop");
                return;
            }
            List<String> command = new ArrayList<>();
            command.add("nuclear-loop");
            command.addAll(args);
            log.setText("$ " + String.join(" ", command) + "\n");
            try {
                running = new ProcessBuilder(command).directory(p.root().toFile()).redirectErrorStream(true).start();
            } catch (IOException e) {
                log.append("nuclear-loop is not installed (./install.sh of NuclearLang): " + e.getMessage() + "\n");
                return;
            }
            poll.start();
            Process proc = running;
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line; (line = r.readLine()) != null; ) {
                        String l = line;
                        SwingUtilities.invokeLater(() -> log.append(l + "\n"));
                    }
                    int code = proc.waitFor();
                    SwingUtilities.invokeLater(() -> {
                        log.append("\nexit " + code + "\n");
                        refresh();
                        LocalFileSystem.getInstance().refresh(true);
                    });
                } catch (IOException | InterruptedException e) {
                    SwingUtilities.invokeLater(() -> log.append(e.getMessage() + "\n"));
                }
            });
        }
    }
}
