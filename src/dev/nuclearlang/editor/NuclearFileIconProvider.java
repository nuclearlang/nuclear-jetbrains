package dev.nuclearlang.editor;

import com.intellij.ide.FileIconProvider;
import com.intellij.ide.IconProvider;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.projectView.ProjectViewNode;
import com.intellij.ide.projectView.ProjectViewNodeDecorator;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

/**
 * The radioactive icon of `.nuc` files. Icon plugins (Atom Material Icons maps `.nuc` to Nuclide) override
 * file icons, so the icon is also given by an `IconProvider` ordered first (tabs, search, navigation) and a
 * project view decorator ordered last (the project tree).
 */
public final class NuclearFileIconProvider implements FileIconProvider {
    private static final Icon ICON = IconLoader.getIcon("/icons/nuc.svg", NuclearFileIconProvider.class);

    private static Icon iconOf(VirtualFile file) {
        return file != null && NuclearLspServerDescriptor.isNuclear(file) ? ICON : null;
    }

    @Override
    public Icon getIcon(@NotNull VirtualFile file, int flags, Project project) {
        return iconOf(file);
    }

    public static final class Psi extends IconProvider implements DumbAware {
        @Override
        public Icon getIcon(@NotNull PsiElement element, int flags) {
            return element instanceof PsiFile file ? iconOf(file.getVirtualFile()) : null;
        }
    }

    public static final class Tree implements ProjectViewNodeDecorator {
        @Override
        public void decorate(@NotNull ProjectViewNode<?> node, @NotNull PresentationData data) {
            Icon icon = iconOf(node.getVirtualFile());
            if (icon != null) data.setIcon(icon);
        }
    }
}
