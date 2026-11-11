package dev.nuclearlang.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.execution.ExecutionListener;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Dragonfly projects (a folder with `build/<name>.code-workspace`, written by every `dragonfly build`) work like a
 * Flutter project: the Flutter app in `build/app` is a module of the project, so the Flutter plugin gives the
 * device selector, the `main.dart` run configuration, Run/Debug and hot reload; saving a `.nuc` transpiles the
 * project and hot reloads the running app.
 */
public final class NuclearDragonfly {
    private static final Logger LOG = Logger.getInstance(NuclearDragonfly.class);
    private static final String GROUP = "NuclearLang";
    /** The Flutter plugin's "Hot Reload All" action: it does nothing when no app is running. */
    private static final String RELOAD_ACTION = "Flutter.Toolbar.ReloadAllAction";
    private static final String RESTART_ACTION = "Flutter.Toolbar.RestartAllAction";
    /** Exit code of `dragonfly build` when the `.nuc` have errors: repairing the app cannot fix them. */
    private static final int CODE_ERRORS = 2;
    // Flutter's own text (flutter_tools run_hot.dart) and a Dart compile error.
    private static final Pattern RELOAD_REJECTED = Pattern.compile("Hot reload was rejected");
    private static final Pattern RELOAD_FAILED = Pattern.compile("\\.dart:\\d+:\\d+: Error: ");
    /** The running app of the project, when the last Ctrl+S reloaded it and when it was last repaired. */
    private static final Key<ExecutionEnvironment> RUNNING = Key.create("dragonfly.running");
    private static final Key<Long> RELOAD_AT = Key.create("dragonfly.reloadAt");
    private static final Key<Long> REPAIRED_AT = Key.create("dragonfly.repairedAt");

    record Info(Path root, Path app, String dragonfly, Map<String, String> env) {}

    /** Exit code and output of a `dragonfly` command. */
    record Outcome(int code, String out) {
        boolean ok() {
            return code == 0;
        }
    }

    private NuclearDragonfly() {}

    /** Reads `build/<name>.code-workspace` under the project folder: the app, the dragonfly binary and its env. */
    static @Nullable Info find(@NotNull Project project) {
        String base = project.getBasePath();
        if (base == null) return null;
        Path root = Path.of(base);
        try (Stream<Path> files = Files.list(root.resolve("build"))) {
            for (Path ws : files.filter(p -> p.toString().endsWith(".code-workspace")).toList()) {
                JsonObject json = JsonParser.parseString(Files.readString(ws)).getAsJsonObject();
                Path app = null;
                for (JsonElement f : json.getAsJsonArray("folders")) {
                    String name = f.getAsJsonObject().get("name").getAsString();
                    if (name.endsWith("(Flutter app)")) app = ws.getParent().resolve(f.getAsJsonObject().get("path").getAsString()).normalize();
                }
                for (JsonElement t : json.getAsJsonObject("tasks").getAsJsonArray("tasks")) {
                    JsonObject task = t.getAsJsonObject();
                    if (app == null || !"dragonfly: build".equals(task.get("label").getAsString())) continue;
                    Map<String, String> env = new HashMap<>();
                    JsonObject options = task.getAsJsonObject("options");
                    if (options != null && options.has("env")) {
                        options.getAsJsonObject("env").entrySet().forEach(e -> env.put(e.getKey(), e.getValue().getAsString()));
                    }
                    return new Info(root, app, task.get("command").getAsString(), env);
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;
    }

    /** `dragonfly build` (transpile, plus pub get and build_runner when needed), one at a time. */
    static boolean build(@NotNull Info d) {
        return run(d, "build").ok();
    }

    /** `dragonfly build`, and `dragonfly repair` when it failed for something else than the `.nuc` code. */
    static Outcome buildOrRepair(@NotNull Info d) {
        Outcome o = run(d, "build");
        return o.ok() || o.code() == CODE_ERRORS ? o : run(d, "repair");
    }

    /** `dragonfly <command>` (build or repair), one at a time. */
    static synchronized Outcome run(@NotNull Info d, String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(d.dragonfly(), command).directory(d.root().toFile()).redirectErrorStream(true);
            pb.environment().putAll(d.env());
            pb.environment().put("NO_COLOR", "1");
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();
            if (code != 0) LOG.warn("dragonfly " + command + " failed:\n" + out);
            LocalFileSystem.getInstance().refreshNioFiles(
                    List.of(d.app().resolve("lib"), d.app().resolve("pubspec.yaml"), d.app().resolve(".dart_tool/nuclear_lines.json")),
                    true, true, null);
            return new Outcome(code, out);
        } catch (IOException e) {
            LOG.warn("cannot run " + d.dragonfly(), e);
            return new Outcome(-1, "cannot run " + d.dragonfly() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(-1, "interrupted");
        }
    }

    static void notifyError(@NotNull Project project, String title, String out) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP).createNotification(
                title, "<pre>" + escape(tail(out)) + "</pre>", NotificationType.ERROR).notify(project);
    }

    private static String tail(String out) {
        String[] lines = out.strip().split("\n");
        return String.join("\n", java.util.Arrays.copyOfRange(lines, Math.max(0, lines.length - 15), lines.length));
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * `dragonfly repair` in the background (build; pub get + build_runner from scratch; flutter clean and everything
     * again) and, if it worked, the same Run/Debug again.
     */
    // shortcut: at most one automatic repair every 5 minutes (a restart may build a new environment, so a flag on
    // it would not stop a loop); track the run configuration instead if 5 minutes turns out wrong.
    static void repairAndRestart(@NotNull Project project, @NotNull ExecutionEnvironment env, String why) {
        Info d = find(project);
        if (d == null) return;
        Long last = project.getUserData(REPAIRED_AT);
        if (last != null && System.currentTimeMillis() - last < 5 * 60_000) {
            notifyError(project, "Dragonfly: " + why + " again after repairing the app",
                    "flutter clean included; see the Run console");
            return;
        }
        project.putUserData(REPAIRED_AT, System.currentTimeMillis());
        new Task.Backgroundable(project, "Dragonfly: " + why + ", repairing the app", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                Outcome o = NuclearDragonfly.run(d, "repair");
                project.putUserData(REPAIRED_AT, System.currentTimeMillis());
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) return;
                    if (o.ok()) ExecutionUtil.restart(env);
                    else notifyError(project, "Dragonfly: " + why + "; it still fails after flutter clean", o.out());
                });
            }
        }.queue();
    }

    private static void runAction(@NotNull Project project, String id) {
        AnAction action = ActionManager.getInstance().getAction(id);
        if (action == null || project.isDisposed()) return;
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        ActionManager.getInstance().tryToExecute(action, null,
                editor == null ? null : editor.getContentComponent(), ActionPlaces.UNKNOWN, true);
    }

    /**
     * Run/Debug of any configuration (the Flutter plugin's `main.dart`) first runs `dragonfly build`, so the app
     * gets the packages, assets and native splash its `.nuc` files need before Gradle or Xcode compile it. It
     * takes milliseconds when nothing changed. Only when something fails (the build, the launch or a hot reload)
     * it runs `dragonfly repair` and the same Run/Debug again.
     */
    public static final class BeforeRun implements ExecutionListener {
        private final Project project;

        public BeforeRun(@NotNull Project project) {
            this.project = project;
        }

        @Override
        public void processStartScheduled(@NotNull String executorId, @NotNull ExecutionEnvironment env) {
            Info d = find(project);
            if (d == null) return;
            Outcome[] result = {null};
            Runnable task = () -> result[0] = buildOrRepair(d);
            if (ApplicationManager.getApplication().isDispatchThread()) {
                ProgressManager.getInstance().runProcessWithProgressSynchronously(task, "dragonfly build", false, project);
            } else {
                task.run();
            }
            if (!result[0].ok()) notifyError(project, "dragonfly build failed", result[0].out());
        }

        @Override
        public void processStarted(@NotNull String executorId, @NotNull ExecutionEnvironment env, @NotNull ProcessHandler handler) {
            if (find(project) == null) return;
            project.putUserData(RUNNING, env);
            NuclearDebug.OutputErrors errors = new NuclearDebug.OutputErrors(project);
            handler.addProcessListener(new ProcessListener() {
                @Override
                public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                    errors.feed(event.getText());
                    // only right after a reload of Ctrl+S: a compile error during the launch ends the process
                    Long at = project.getUserData(RELOAD_AT);
                    if (at == null || System.currentTimeMillis() - at > 30_000) return;
                    String text = event.getText();
                    boolean rejected = RELOAD_REJECTED.matcher(text).find();
                    if (!rejected && !RELOAD_FAILED.matcher(text).find()) return;
                    project.putUserData(RELOAD_AT, null);
                    // a reload the VM cannot apply needs a restart; a compile error after a clean transpile means
                    // stale generated code or packages
                    if (rejected) ApplicationManager.getApplication().invokeLater(() -> runAction(project, RESTART_ACTION));
                    else repairAndRestart(project, env, "hot reload failed");
                }
            });
        }

        /** Ended with an error and not stopped by the user: the launch failed (Gradle, Xcode, pub, stale build). */
        @Override
        public void processTerminated(@NotNull String executorId, @NotNull ExecutionEnvironment env,
                @NotNull ProcessHandler handler, int exitCode) {
            if (project.getUserData(RUNNING) == env) project.putUserData(RUNNING, null);
            if (exitCode == 0 || Boolean.TRUE.equals(handler.getUserData(ProcessHandler.TERMINATION_REQUESTED))) return;
            if (find(project) != null) repairAndRestart(project, env, "the app did not start");
        }
    }

    /** On open: transpile, and make the Flutter app a module (once; the Flutter plugin sets it up on reopen). */
    public static final class Startup implements ProjectActivity {
        @Override
        public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
            Info d = find(project);
            if (d == null) return Unit.INSTANCE;
            build(d);
            NuclearDebug.syncLater(project);
            ModuleManager modules = ModuleManager.getInstance(project);
            for (Module m : modules.getModules()) {
                Path file = m.getModuleNioFile();
                if (d.app().equals(file.getParent())) return Unit.INSTANCE;
            }
            // flutter create writes <name>.iml in the app; a copy with another name avoids clashing with the
            // project's own module, which usually has the same name
            Path iml;
            try (Stream<Path> files = Files.list(d.app())) {
                Path original = files.filter(p -> p.toString().endsWith(".iml")).findFirst().orElse(null);
                if (original == null) return Unit.INSTANCE;
                String name = original.getFileName().toString().replace(".iml", "");
                iml = d.app().resolve(name + "_flutter_app.iml");
                if (!Files.exists(iml)) Files.copy(original, iml);
            } catch (IOException e) {
                LOG.warn("cannot prepare the Flutter app module", e);
                return Unit.INSTANCE;
            }
            ApplicationManager.getApplication().invokeAndWait(() -> WriteAction.run(() -> {
                try {
                    modules.loadModule(iml);
                } catch (Exception e) {
                    LOG.warn("cannot load " + iml, e);
                }
            }));
            Notification n = NotificationGroupManager.getInstance().getNotificationGroup(GROUP).createNotification(
                    "Dragonfly",
                    "The Flutter app is now part of the project. Reopen it to enable devices, Run and hot reload.",
                    NotificationType.INFORMATION);
            n.addAction(NotificationAction.createSimpleExpiring("Reopen Project",
                    () -> ProjectManager.getInstance().reloadProject(project)));
            n.notify(project);
            return Unit.INSTANCE;
        }
    }

    /** Saving a `.nuc` of the project (Ctrl+S) transpiles it and hot reloads the running app. */
    public static final class OnSave implements BulkFileListener {
        private final Project project;

        public OnSave(@NotNull Project project) {
            this.project = project;
        }

        @Override
        public void after(@NotNull List<? extends @NotNull VFileEvent> events) {
            Info d = null;
            for (VFileEvent e : events) {
                if (!(e instanceof VFileContentChangeEvent) || !e.getPath().endsWith(".nuc")) continue;
                d = find(project);
                if (d == null) return;
                Path p = Path.of(e.getPath());
                if (p.startsWith(d.root()) && !p.startsWith(d.root().resolve("build"))) break;
                d = null;
            }
            if (d == null) return;
            Info info = d;
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                Outcome o = run(info, "build");
                ExecutionEnvironment running = project.getUserData(RUNNING);
                if (!o.ok()) {
                    if (o.code() != CODE_ERRORS && running != null) repairAndRestart(project, running, "the build failed");
                    return;
                }
                project.putUserData(RELOAD_AT, System.currentTimeMillis());
                ApplicationManager.getApplication().invokeLater(() -> runAction(project, RELOAD_ACTION));
            });
        }
    }
}
