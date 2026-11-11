package dev.nuclearlang.editor;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspServer;
import com.intellij.platform.lsp.api.customization.LspCodeLensSupport;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.awt.RelativePoint;
import org.eclipse.lsp4j.Command;
import org.jetbrains.annotations.NotNull;

import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Click on a "Complexity is N ..." code lens: lists every entry that adds complexity (rows computed by the
 * server). Moving through the list moves the caret to the entry, like the original CodeMetrics extension.
 */
public final class NuclearCodeLensSupport extends LspCodeLensSupport {
    record Row(String label, String code, int line, int character) {}

    @Override
    public void codeLensClicked(@NotNull LspClient client, @NotNull VirtualFile file, @NotNull Command command, @NotNull MouseEvent event) {
        if (!NuclearCodeMetrics.SHOW_DETAILS.equals(command.getCommand())) {
            super.codeLensClicked(client, file, command, event);
            return;
        }
        showDetails(client.getProject(), file, command, event);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void codeLensClicked(@NotNull LspServer server, @NotNull VirtualFile file, @NotNull Command command, @NotNull MouseEvent event) {
        if (!NuclearCodeMetrics.SHOW_DETAILS.equals(command.getCommand())) {
            super.codeLensClicked(server, file, command, event);
            return;
        }
        showDetails(server.getProject(), file, command, event);
    }

    private static void showDetails(Project project, VirtualFile file, Command command, MouseEvent event) {
        List<Row> rows = rows(command);
        if (rows.isEmpty()) return;
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        Document document = FileDocumentManager.getInstance().getDocument(file);
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(rows)
            .setRenderer((list, row, index, selected, focused) -> {
                SimpleColoredComponent c = new SimpleColoredComponent();
                c.append(row.label());
                c.append("  " + row.code(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                c.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
                c.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
                c.setOpaque(true);
                return c;
            })
            .setItemSelectedCallback(row -> jump(editor, document, row))
            .setItemChosenCallback(row -> jump(editor, document, row))
            .setNamerForFiltering(Row::label)
            .createPopup()
            .show(new RelativePoint(event));
    }

    /** Moves the caret of the editor showing the file to the entry. */
    private static void jump(Editor editor, Document document, Row row) {
        if (row == null || editor == null || document == null || editor.getDocument() != document) return;
        if (row.line() >= document.getLineCount()) return;
        int offset = Math.min(document.getLineStartOffset(row.line()) + row.character(), document.getLineEndOffset(row.line()));
        editor.getCaretModel().moveToOffset(offset);
        editor.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
    }

    /** The rows sent by the server: `arguments[0] = { uri, items: [{ label, description, line, character }] }`. */
    private static List<Row> rows(Command command) {
        List<Row> rows = new ArrayList<>();
        if (command.getArguments() == null || command.getArguments().isEmpty()) return rows;
        JsonElement arg = new Gson().toJsonTree(command.getArguments().get(0));
        if (!arg.isJsonObject() || !arg.getAsJsonObject().has("items")) return rows;
        for (JsonElement e : arg.getAsJsonObject().getAsJsonArray("items")) {
            JsonObject o = e.getAsJsonObject();
            rows.add(new Row(o.get("label").getAsString(), o.get("description").getAsString(),
                o.get("line").getAsInt(), o.get("character").getAsInt()));
        }
        return rows;
    }
}
