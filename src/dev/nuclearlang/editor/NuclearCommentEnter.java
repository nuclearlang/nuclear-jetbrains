package dev.nuclearlang.editor;

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

/**
 * Enter inside a `//` comment of a .nuc file continues it with ` * ` (the `*` under the second `/`, base §2.3);
 * Enter on an empty ` *` line removes that line and ends the comment, under the `//`. Same as the VS Code
 * onEnterRules + the server's on-type formatting.
 */
public final class NuclearCommentEnter extends EnterHandlerDelegateAdapter {
    @Override
    public Result postProcessEnter(@NotNull PsiFile file, @NotNull Editor editor, @NotNull DataContext context) {
        if (!file.getName().endsWith(".nuc")) return Result.Continue;
        Document doc = editor.getDocument();
        int line = doc.getLineNumber(editor.getCaretModel().getOffset());
        if (line < 1) return Result.Continue;
        String prev = text(doc, line - 1);
        String prevTrim = prev.strip();
        int prevIndent = prev.length() - prev.stripLeading().length();
        int indent;
        boolean removePrev = false;
        if (prevTrim.equals("*") && line >= 2 && isComment(text(doc, line - 2))) {
            indent = Math.max(prevIndent - 1, 0);
            removePrev = true;
        } else if (prevTrim.startsWith("//")) {
            indent = prevIndent + 1;
        } else if (prevTrim.startsWith("* ")) {
            indent = prevIndent;
        } else {
            return Result.Continue;
        }
        // the new line: its whitespace becomes the comment prefix (or the `//` indentation)
        int start = doc.getLineStartOffset(line);
        String cur = text(doc, line);
        int ws = cur.length() - cur.stripLeading().length();
        String prefix = " ".repeat(indent) + (removePrev ? "" : "* ");
        doc.replaceString(start, start + ws, prefix);
        int caret = start + prefix.length();
        if (removePrev) {
            int from = doc.getLineStartOffset(line - 1);
            doc.deleteString(from, start);
            caret -= start - from;
        }
        editor.getCaretModel().moveToOffset(caret);
        return Result.Stop;
    }

    private static boolean isComment(String line) {
        String t = line.strip();
        return t.startsWith("//") || t.equals("*") || t.startsWith("* ");
    }

    private static String text(Document doc, int line) {
        return doc.getText(new TextRange(doc.getLineStartOffset(line), doc.getLineEndOffset(line)));
    }
}
