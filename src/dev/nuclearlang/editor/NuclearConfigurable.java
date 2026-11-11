package dev.nuclearlang.editor;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.platform.lsp.api.LspServerManager;

import javax.swing.*;
import java.awt.*;

/** Settings > Tools > NuclearLang: dialect and server path. Applying restarts the language server. */
public final class NuclearConfigurable implements Configurable {
    private JComboBox<String> dialect;
    private JTextField serverPath;
    private JCheckBox follow;
    private JCheckBox explain;

    @Override
    public String getDisplayName() {
        return "NuclearLang";
    }

    @Override
    public JComponent createComponent() {
        dialect = new JComboBox<>(new String[] {"flutter", "base"});
        dialect.setEditable(true);
        serverPath = new JTextField(40);
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 0, 4, 8);
        c.gridx = 0; c.gridy = 0;
        form.add(new JLabel("Dialect:"), c);
        c.gridx = 1;
        form.add(dialect, c);
        c.gridx = 0; c.gridy = 1;
        form.add(new JLabel("nuclear-lsp path:"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
        form.add(serverPath, c);
        c.gridx = 1; c.gridy = 2;
        form.add(new JLabel("<html><small>Empty: the server bundled with the plugin, then nuclear-lsp on the PATH.<br>"
            + "'base' checks only the base language.</small></html>"), c);
        follow = new JCheckBox("Debugging a Dragonfly app: show the .nuc line where it pauses");
        explain = new JCheckBox("Dart run-time errors: mark their .nuc line and explain them (Nuclear Error window)");
        c.gridx = 0; c.gridy = 3; c.gridwidth = 2;
        form.add(follow, c);
        c.gridy = 4;
        form.add(explain, c);
        JPanel root = new JPanel(new BorderLayout());
        root.add(form, BorderLayout.NORTH);
        reset();
        return root;
    }

    @Override
    public boolean isModified() {
        return !NuclearSettings.dialect().equals(selectedDialect()) || !NuclearSettings.serverPath().equals(serverPath.getText().trim())
            || NuclearSettings.followInNuclear() != follow.isSelected() || NuclearSettings.explainErrors() != explain.isSelected();
    }

    @Override
    public void apply() {
        NuclearSettings.setDialect(selectedDialect());
        NuclearSettings.setServerPath(serverPath.getText().trim());
        NuclearSettings.setFollowInNuclear(follow.isSelected());
        NuclearSettings.setExplainErrors(explain.isSelected());
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            LspServerManager.getInstance(project).stopAndRestartIfNeeded(NuclearLspServerSupportProvider.class);
        }
    }

    @Override
    public void reset() {
        dialect.setSelectedItem(NuclearSettings.dialect());
        serverPath.setText(NuclearSettings.serverPath());
        follow.setSelected(NuclearSettings.followInNuclear());
        explain.setSelected(NuclearSettings.explainErrors());
    }

    private String selectedDialect() {
        Object v = dialect.getSelectedItem();
        return v == null || v.toString().isBlank() ? "flutter" : v.toString().trim();
    }
}
