package dev.nuclearlang.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.colors.CodeInsightColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.Alarm;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebugSessionListener;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XDebuggerManagerListener;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointListener;
import com.intellij.xdebugger.breakpoints.XBreakpointManager;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.breakpoints.XLineBreakpointType;
import com.intellij.xdebugger.ui.DebuggerColors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Debugging `.nuc` code of a Dragonfly project on top of the Dart plugin. `dragonfly build` writes
 * `build/app/.dart_tool/nuclear_lines.json` (for each generated `.dart`: its `.nuc` and the pairs (dart line, nuc
 * line)). With it a `.nuc` breakpoint gets a mirror Dart breakpoint in the generated file, a pause shows and
 * highlights the `.nuc` line, and a Dart error printed by Flutter is marked at its `.nuc` line with the
 * "Nuclear Error" tool window explaining it (`dragonfly explain`).
 */
public final class NuclearDebug {
    private static final String MAP_FILE = ".dart_tool/nuclear_lines.json";
    private static final String MIRRORS = "nuclear.debug.mirrors";
    private static final String WINDOW = "Nuclear Error";
    private static final Key<RangeHighlighter> CURRENT = Key.create("nuclear.debug.current");
    private static final Key<List<RangeHighlighter>> ERRORS = Key.create("nuclear.debug.errors");
    private static final Key<JEditorPane> PANE = Key.create("nuclear.debug.pane");
    private static final Key<List<Place>> PANE_FRAMES = Key.create("nuclear.debug.paneFrames");

    record Place(Path path, int line, String name) {} // line from 1

    private NuclearDebug() {}

    // ------------------------------------------------------------------ line map

    private static Path cachedFile;
    private static long cachedTime;
    private static JsonObject cachedFiles;

    private static synchronized @Nullable JsonObject files(NuclearDragonfly.Info d) {
        Path file = d.app().resolve(MAP_FILE);
        try {
            long time = Files.getLastModifiedTime(file).toMillis();
            if (!file.equals(cachedFile) || time != cachedTime) {
                cachedFiles = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("files");
                cachedFile = file;
                cachedTime = time;
            }
            return cachedFiles;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String rel(Path from, Path to) {
        return from.relativize(to).toString().replace('\\', '/');
    }

    /** The `.dart` line of a `.nuc` line: its first statement there, else the next one below. */
    static @Nullable Place toDart(NuclearDragonfly.Info d, Path nuc, int line) {
        JsonObject files = files(d);
        if (files == null) return null;
        String source = rel(d.root(), nuc);
        for (var e : files.entrySet()) {
            JsonObject entry = e.getValue().getAsJsonObject();
            if (!source.equals(entry.get("source").getAsString())) continue;
            int best = -1, bestNuc = Integer.MAX_VALUE;
            for (JsonElement p : entry.getAsJsonArray("lines")) {
                int dart = p.getAsJsonArray().get(0).getAsInt(), n = p.getAsJsonArray().get(1).getAsInt();
                if (n == line) return new Place(d.app().resolve(e.getKey()), dart, "");
                if (n > line && n < bestNuc) {
                    best = dart;
                    bestNuc = n;
                }
            }
            return best < 0 ? null : new Place(d.app().resolve(e.getKey()), best, "");
        }
        return null;
    }

    /** The `.nuc` line of a `.dart` line: the closest mapped line at or above it. */
    static @Nullable Place toNuc(NuclearDragonfly.Info d, Path dart, int line) {
        JsonObject files = files(d);
        if (files == null || !dart.startsWith(d.app())) return null;
        JsonElement entry = files.get(rel(d.app(), dart));
        if (entry == null) return null;
        int hit = -1;
        for (JsonElement p : entry.getAsJsonObject().getAsJsonArray("lines")) {
            if (p.getAsJsonArray().get(0).getAsInt() > line) break;
            hit = p.getAsJsonArray().get(1).getAsInt();
        }
        return hit < 0 ? null : new Place(d.root().resolve(entry.getAsJsonObject().get("source").getAsString()), hit, "");
    }

    // ------------------------------------------------------------------ breakpoints

    /** Line breakpoints in `.nuc` files of a Dragonfly project; they stop through their mirror in the `.dart`. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static final class BreakpointType extends XLineBreakpointType {
        public BreakpointType() {
            super("nuclear-line", "Nuclear line breakpoints");
        }

        @Override
        public boolean canPutAt(@NotNull VirtualFile file, int line, @NotNull Project project) {
            return "nuc".equals(file.getExtension()) && NuclearDragonfly.find(project) != null;
        }

        @Override
        public @Nullable XBreakpointProperties createBreakpointProperties(@NotNull VirtualFile file, int line) {
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static @Nullable XLineBreakpointType type(String id) {
        for (XLineBreakpointType<?> t : XDebuggerUtil.getInstance().getLineBreakpointTypes()) {
            if (t.getId().equals(id)) return t;
        }
        return null;
    }

    /**
     * Makes the mirrors again from the `.nuc` breakpoints and the current line map (on the EDT). The mirrors of the
     * last sync are kept in the project settings: the IDE saves them like any breakpoint.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void sync(@NotNull Project project) {
        NuclearDragonfly.Info d = NuclearDragonfly.find(project);
        XLineBreakpointType dart = type("Dart"), nuc = type("nuclear-line");
        if (d == null || dart == null || nuc == null || project.isDisposed()) return;
        PropertiesComponent props = PropertiesComponent.getInstance(project);
        List<String> old = props.getList(MIRRORS);
        Set<String> was = old == null ? Set.of() : new HashSet<>(old);
        List<String> now = new ArrayList<>();
        WriteAction.run(() -> {
            XBreakpointManager m = XDebuggerManager.getInstance(project).getBreakpointManager();
            for (Object o : new ArrayList<>(m.getBreakpoints(dart))) {
                XLineBreakpoint<?> b = (XLineBreakpoint<?>) o;
                if (was.contains(b.getFileUrl() + ":" + b.getLine())) m.removeBreakpoint(b);
            }
            for (Object o : m.getBreakpoints(nuc)) {
                XLineBreakpoint<?> b = (XLineBreakpoint<?>) o;
                String path = VfsUtilCore.urlToPath(b.getFileUrl());
                Place t = toDart(d, Path.of(path), b.getLine() + 1);
                if (t == null) continue;
                String url = VfsUtilCore.pathToUrl(t.path().toString());
                VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(t.path());
                if (file == null || !m.findBreakpointsAtLine(dart, file, t.line() - 1).isEmpty()) continue; // the user's own
                XLineBreakpoint mirror = m.addLineBreakpoint(dart, url, t.line() - 1, null);
                mirror.setEnabled(b.isEnabled());
                mirror.setSuspendPolicy(b.getSuspendPolicy());
                mirror.setConditionExpression(b.getConditionExpression());
                mirror.setLogExpressionObject(b.getLogExpressionObject());
                mirror.setLogMessage(b.isLogMessage());
                now.add(url + ":" + (t.line() - 1));
            }
        });
        props.setList(MIRRORS, now);
    }

    static void syncLater(@NotNull Project project) {
        ApplicationManager.getApplication().invokeLater(() -> sync(project), project.getDisposed());
    }

    /** A change of a `.nuc` breakpoint updates the mirrors. */
    public static final class Breakpoints implements XBreakpointListener<XBreakpoint<?>> {
        private final Project project;

        public Breakpoints(@NotNull Project project) {
            this.project = project;
        }

        private void changed(XBreakpoint<?> b) {
            if (b.getType() instanceof BreakpointType) syncLater(project);
        }

        @Override
        public void breakpointAdded(@NotNull XBreakpoint<?> b) {
            changed(b);
        }

        @Override
        public void breakpointRemoved(@NotNull XBreakpoint<?> b) {
            changed(b);
        }

        @Override
        public void breakpointChanged(@NotNull XBreakpoint<?> b) {
            changed(b);
        }
    }

    /** A new line map (every `dragonfly build` that changed it): the lines moved, old errors no longer apply. */
    public static final class MapWatcher implements BulkFileListener {
        private final Project project;

        public MapWatcher(@NotNull Project project) {
            this.project = project;
        }

        @Override
        public void after(@NotNull List<? extends @NotNull VFileEvent> events) {
            if (events.stream().noneMatch(e -> e.getPath().endsWith("/" + MAP_FILE))) return;
            ApplicationManager.getApplication().invokeLater(() -> {
                clearErrors(project);
                sync(project);
            }, project.getDisposed());
        }
    }

    // ------------------------------------------------------------------ paused frame

    /** A Debug session of the app: each pause shows and highlights the `.nuc` line of the paused code. */
    public static final class Sessions implements XDebuggerManagerListener {
        private final Project project;

        public Sessions(@NotNull Project project) {
            this.project = project;
        }

        @Override
        public void processStarted(@NotNull XDebugProcess process) {
            if (NuclearDragonfly.find(project) == null) return;
            XDebugSession session = process.getSession();
            session.addSessionListener(new XDebugSessionListener() {
                @Override
                public void sessionPaused() {
                    if (!NuclearSettings.followInNuclear()) return;
                    XSourcePosition pos = session.getCurrentPosition();
                    NuclearDragonfly.Info d = NuclearDragonfly.find(project);
                    if (pos == null || d == null) return;
                    Place nuc = toNuc(d, Path.of(pos.getFile().getPath()), pos.getLine() + 1);
                    if (nuc != null) ApplicationManager.getApplication().invokeLater(() -> showCurrent(project, nuc));
                }

                @Override
                public void sessionResumed() {
                    ApplicationManager.getApplication().invokeLater(() -> clearCurrent(project));
                }

                @Override
                public void sessionStopped() {
                    sessionResumed();
                }
            });
        }
    }

    private static @Nullable Editor open(Project project, Place place) {
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(place.path());
        if (file == null || project.isDisposed()) return null;
        return FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file, place.line() - 1, 0), false);
    }

    private static void showCurrent(Project project, Place place) {
        clearCurrent(project);
        Editor editor = open(project, place);
        if (editor == null || place.line() > editor.getDocument().getLineCount()) return;
        var attrs = EditorColorsManager.getInstance().getGlobalScheme().getAttributes(DebuggerColors.EXECUTIONPOINT_ATTRIBUTES);
        RangeHighlighter h = editor.getMarkupModel().addLineHighlighter(place.line() - 1, HighlighterLayer.SELECTION - 1, attrs);
        project.putUserData(CURRENT, h);
    }

    private static void clearCurrent(Project project) {
        RangeHighlighter h = project.getUserData(CURRENT);
        if (h != null) h.dispose();
        project.putUserData(CURRENT, null);
    }

    // ------------------------------------------------------------------ runtime errors

    // Flutter prints a framework error between `══╡ EXCEPTION CAUGHT BY … ╞══` (or `════ Exception caught by … ════`)
    // and a line of `═`; an error of async code as `Unhandled Exception: …` followed by its stack.
    private static final Pattern ERROR_START = Pattern.compile("═+╡? ?EXCEPTION CAUGHT BY|═+ Exception caught by|Unhandled Exception:", Pattern.CASE_INSENSITIVE);

    /** Collects an error block of the app's console and, when it ends, shows it (`feed` from any thread). */
    static final class OutputErrors {
        private final Project project;
        private final Alarm alarm;
        private final StringBuilder buffer = new StringBuilder();

        OutputErrors(@NotNull Project project) {
            this.project = project;
            this.alarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, project);
        }

        synchronized void feed(String text) {
            if (!NuclearSettings.explainErrors()) return;
            var m = ERROR_START.matcher(text);
            if (buffer.isEmpty()) {
                if (!m.find()) return;
                text = text.substring(m.start());
            }
            buffer.append(text);
            alarm.cancelAllRequests();
            // the whole block arrives in a few chunks
            alarm.addRequest(this::flush, 400);
        }

        private void flush() {
            String text;
            synchronized (this) {
                text = buffer.toString();
                buffer.setLength(0);
            }
            NuclearDragonfly.Info d = NuclearDragonfly.find(project);
            JsonObject r = d == null ? null : explain(d, text);
            if (r != null) ApplicationManager.getApplication().invokeLater(() -> showError(project, r, text), project.getDisposed());
        }
    }

    /** `dragonfly explain --json`: the message, an explanation and the `.nuc` frames of a Dart error text. */
    private static @Nullable JsonObject explain(NuclearDragonfly.Info d, String text) {
        try {
            ProcessBuilder pb = new ProcessBuilder(d.dragonfly(), "explain", "--json").directory(d.root().toFile());
            pb.environment().putAll(d.env());
            pb.environment().put("NO_COLOR", "1");
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            try (OutputStream in = p.getOutputStream()) {
                in.write(text.getBytes(StandardCharsets.UTF_8));
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return p.waitFor() == 0 ? JsonParser.parseString(out).getAsJsonObject() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static void showError(Project project, JsonObject r, String text) {
        List<Place> frames = new ArrayList<>();
        JsonArray list = r.getAsJsonArray("frames");
        for (JsonElement f : list) {
            JsonObject o = f.getAsJsonObject();
            frames.add(new Place(Path.of(o.get("source").getAsString()), o.get("line").getAsInt(), o.get("name").getAsString()));
        }
        if (frames.isEmpty()) return;
        String title = r.get("title").getAsString();
        clearErrors(project);
        Place top = frames.get(0);
        Editor editor = open(project, top);
        if (editor != null && top.line() <= editor.getDocument().getLineCount()) {
            var attrs = EditorColorsManager.getInstance().getGlobalScheme().getAttributes(CodeInsightColors.ERRORS_ATTRIBUTES);
            RangeHighlighter h = editor.getMarkupModel().addLineHighlighter(top.line() - 1, HighlighterLayer.ERROR, attrs);
            h.setErrorStripeTooltip("Runtime error: " + title);
            h.setGutterIconRenderer(new GutterIconRenderer() {
                @Override
                public @NotNull Icon getIcon() {
                    return AllIcons.General.Error;
                }

                @Override
                public String getTooltipText() {
                    return "Runtime error: " + title;
                }

                @Override
                public boolean equals(Object o) {
                    return o == this;
                }

                @Override
                public int hashCode() {
                    return System.identityHashCode(this);
                }
            });
            project.putUserData(ERRORS, List.of(h));
        }
        ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(WINDOW);
        if (window == null) return;
        window.show(() -> {
            JEditorPane pane = project.getUserData(PANE);
            if (pane == null) return;
            project.putUserData(PANE_FRAMES, frames);
            pane.setText(html(project, r, frames, text));
            pane.setCaretPosition(0);
        });
    }

    private static String html(Project project, JsonObject r, List<Place> frames, String text) {
        Place top = frames.get(0);
        StringBuilder excerpt = new StringBuilder();
        try {
            List<String> lines = Files.readAllLines(top.path());
            for (int n = Math.max(1, top.line() - 2); n <= Math.min(lines.size(), top.line() + 2); n++) {
                String row = String.format("%4d  %s", n, escape(lines.get(n - 1)));
                excerpt.append(n == top.line() ? "<b>" + row + "</b>" : row).append('\n');
            }
        } catch (IOException e) {
            // the .nuc is gone: the window shows the rest
        }
        StringBuilder stack = new StringBuilder();
        String base = project.getBasePath() == null ? "" : project.getBasePath();
        for (int i = 0; i < frames.size(); i++) {
            Place f = frames.get(i);
            String shown = f.path().toString().startsWith(base) ? Path.of(base).relativize(f.path()).toString() : f.path().toString();
            stack.append("<li><a href=\"nuc:").append(i).append("\">").append(escape(shown)).append(':').append(f.line())
                    .append("</a> ").append(escape(f.name())).append("</li>");
        }
        return "<html><body style='padding:6px'>"
                + "<h3 style='color:#e05252'>" + escape(r.get("title").getAsString()) + "</h3>"
                + "<p>" + escape(r.get("hint").getAsString()) + "</p>"
                + "<h4>Where in Nuclear</h4><pre>" + excerpt + "</pre>"
                + "<h4>Nuclear call stack</h4><ol>" + stack + "</ol>"
                + "<h4>Dart error</h4><pre>" + escape(text.strip()) + "</pre></body></html>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static void clearErrors(Project project) {
        List<RangeHighlighter> hs = project.getUserData(ERRORS);
        if (hs != null) hs.forEach(RangeHighlighter::dispose);
        project.putUserData(ERRORS, null);
    }

    /** The "Nuclear Error" tool window (only in Dragonfly projects): the last run-time error, explained. */
    public static final class ErrorWindow implements ToolWindowFactory {
        @Override
        public boolean shouldBeAvailable(@NotNull Project project) {
            return NuclearDragonfly.find(project) != null;
        }

        @Override
        public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow window) {
            JEditorPane pane = new JEditorPane("text/html", "<html><body style='padding:6px'>No run-time error yet.</body></html>");
            pane.setEditable(false);
            pane.addHyperlinkListener(e -> {
                if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED || e.getDescription() == null) return;
                List<Place> frames = project.getUserData(PANE_FRAMES);
                int i = Integer.parseInt(e.getDescription().substring("nuc:".length()));
                if (frames != null && i < frames.size()) open(project, frames.get(i));
            });
            project.putUserData(PANE, pane);
            window.getContentManager().addContent(ContentFactory.getInstance().createContent(new JBScrollPane(pane), "", false));
        }
    }
}
