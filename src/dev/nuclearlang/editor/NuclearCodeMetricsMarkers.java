package dev.nuclearlang.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspServer;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * The colored square at the end of the first line of every function / module with a code lens, and its mark on
 * the error stripe (the overview ruler of VS Code): low, normal, high and extreme complexity. Updated after
 * each change, like the original's decorations.
 */
public final class NuclearCodeMetricsMarkers implements EditorFactoryListener {
    private static final Key<Marks> KEY = Key.create("nuclear.codemetrics.marks");

    @Override
    public void editorCreated(@NotNull EditorFactoryEvent event) {
        Editor editor = event.getEditor();
        Project project = editor.getProject();
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (project == null || file == null || !NuclearLspServerDescriptor.isNuclear(file)) return;
        Marks marks = new Marks(editor, project, file);
        editor.putUserData(KEY, marks);
        marks.schedule(1500);
    }

    @Override
    public void editorReleased(@NotNull EditorFactoryEvent event) {
        Marks marks = event.getEditor().getUserData(KEY);
        if (marks != null) {
            Disposer.dispose(marks);
            event.getEditor().putUserData(KEY, null);
        }
    }

    /** Settings changed or the metrics were toggled: redraw every open Nuclear editor. */
    public static void refreshAll() {
        for (Editor editor : com.intellij.openapi.editor.EditorFactory.getInstance().getAllEditors()) {
            Marks marks = editor.getUserData(KEY);
            if (marks != null) marks.schedule(300);
        }
    }

    private static final class Marks implements Disposable {
        private final Editor editor;
        private final Project project;
        private final VirtualFile file;
        private final Alarm alarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this);
        private final List<RangeHighlighter> highlighters = new ArrayList<>();
        private final List<Inlay<?>> inlays = new ArrayList<>();
        private int attempts;

        Marks(Editor editor, Project project, VirtualFile file) {
            this.editor = editor;
            this.project = project;
            this.file = file;
            editor.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void documentChanged(@NotNull DocumentEvent e) {
                    schedule(1000);
                }
            }, this);
        }

        void schedule(int delay) {
            if (alarm.isDisposed()) return;
            alarm.cancelAllRequests();
            alarm.addRequest(this::update, delay);
        }

        /** Asks the server (pooled thread) and draws on the EDT. */
        private void update() {
            LspServer server = NuclearCodeMetrics.server(project);
            if (server == null) {
                // the server starts with the first .nuc file: try again a few times
                if (attempts++ < 10) schedule(2000);
                return;
            }
            String uri = server.getDocumentIdentifier(file).getUri();
            JsonElement answer;
            try {
                answer = NuclearCodeMetrics.execute(project, NuclearCodeMetrics.CODE_METRICS, List.of(uri));
            } catch (Exception e) {
                return;
            }
            if (answer == null || !answer.isJsonObject()) return;
            JsonObject result = answer.getAsJsonObject();
            ApplicationManager.getApplication().invokeLater(() -> draw(result), x -> editor.isDisposed());
        }

        private void draw(JsonObject result) {
            clear();
            JsonObject s = result.getAsJsonObject("settings");
            boolean squares = s.get("DecorationModeEnabled").getAsBoolean();
            boolean stripe = s.get("OverviewRulerModeEnabled").getAsBoolean();
            if (!squares && !stripe) return;
            Color[] colors = {
                color(s, "ComplexityColorLow"), color(s, "ComplexityColorNormal"),
                color(s, "ComplexityColorHigh"), color(s, "ComplexityColorExtreme"),
            };
            Document doc = editor.getDocument();
            for (JsonElement e : result.getAsJsonArray("items")) {
                JsonObject item = e.getAsJsonObject();
                int line = item.getAsJsonObject("range").getAsJsonObject("start").get("line").getAsInt();
                if (line >= doc.getLineCount()) continue;
                Color color = colors[Math.max(0, Math.min(3, item.get("level").getAsInt()))];
                String message = item.get("message").getAsString();
                int end = doc.getLineEndOffset(line);
                if (stripe) {
                    RangeHighlighter h = editor.getMarkupModel().addRangeHighlighter(
                        doc.getLineStartOffset(line), end, HighlighterLayer.ADDITIONAL_SYNTAX, new TextAttributes(),
                        HighlighterTargetArea.LINES_IN_RANGE);
                    h.setErrorStripeMarkColor(color);
                    h.setErrorStripeTooltip(message);
                    h.setThinErrorStripeMark(false);
                    highlighters.add(h);
                }
                if (squares) {
                    Inlay<?> inlay = editor.getInlayModel().addAfterLineEndElement(end, false, new Square(color));
                    if (inlay != null) inlays.add(inlay);
                }
            }
        }

        private static Color color(JsonObject s, String name) {
            try {
                return Color.decode(s.get(name).getAsString());
            } catch (Exception e) {
                return Color.GRAY;
            }
        }

        private void clear() {
            for (RangeHighlighter h : highlighters) editor.getMarkupModel().removeHighlighter(h);
            highlighters.clear();
            for (Inlay<?> i : inlays) Disposer.dispose(i);
            inlays.clear();
        }

        @Override
        public void dispose() {
            if (!editor.isDisposed()) clear();
        }
    }

    /** The default DecorationTemplate of the original: a square of the level's color with a white border. */
    private record Square(Color color) implements EditorCustomElementRenderer {
        @Override
        public int calcWidthInPixels(@NotNull Inlay inlay) {
            return size(inlay) * 2;
        }

        @Override
        public void paint(@NotNull Inlay inlay, @NotNull Graphics g, @NotNull Rectangle r, @NotNull TextAttributes attrs) {
            int size = size(inlay);
            int x = r.x + size / 2;
            int y = r.y + (r.height - size) / 2;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(color);
            g2.fill(new Rectangle2D.Float(x, y, size, size));
            g2.setColor(Color.WHITE);
            g2.draw(new Rectangle2D.Float(x, y, size, size));
            g2.dispose();
        }

        private static int size(Inlay<?> inlay) {
            return Math.max(8, inlay.getEditor().getLineHeight() * 2 / 3);
        }
    }
}
