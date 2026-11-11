package dev.nuclearlang.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.platform.lsp.api.LspServerManager;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings > Tools > NuclearLang > Code Metrics: the "basics" settings of the original CodeMetrics extension
 * (thresholds, messages, which declarations get a code lens...) and the complexity of each node. Applying
 * restarts the language server.
 */
public final class NuclearCodeMetricsConfigurable implements Configurable {
    /** Settings the IntelliJ plugin does not use (the square is always drawn like the default template). */
    private static final java.util.Set<String> VSCODE_ONLY = java.util.Set.of("DecorationTemplate");
    private final Map<String, JComponent> fields = new LinkedHashMap<>();
    private DefaultTableModel nodes;

    @Override
    public String getDisplayName() {
        return "Code Metrics";
    }

    @Override
    public JComponent createComponent() {
        JsonObject basics = NuclearCodeMetrics.defaults().getAsJsonObject("basics");
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 0, 2, 8);
        int row = 0;
        for (String name : basics.keySet()) {
            if (VSCODE_ONLY.contains(name)) continue;
            JsonObject entry = basics.getAsJsonObject(name);
            JsonElement def = entry.get("default");
            String tip = entry.get("description").getAsString();
            JComponent field;
            c.gridy = row++;
            if (def.isJsonPrimitive() && def.getAsJsonPrimitive().isBoolean()) {
                JCheckBox box = new JCheckBox(name);
                field = box;
                c.gridx = 0; c.gridwidth = 2; c.fill = GridBagConstraints.NONE; c.weightx = 0;
                form.add(box, c);
                c.gridwidth = 1;
            } else {
                JTextField text = new JTextField(def.isJsonArray() || name.endsWith("Description") || name.endsWith("Template") ? 30 : 10);
                field = text;
                c.gridx = 0; c.fill = GridBagConstraints.NONE; c.weightx = 0;
                form.add(new JLabel(name + ":"), c);
                c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
                form.add(text, c);
            }
            field.setToolTipText(def.isJsonArray() ? tip + " (comma separated)" : tip);
            fields.put(name, field);
        }

        nodes = new DefaultTableModel(new Object[] {"Node", "Nuclear", "Complexity", "Description"}, 0) {
            @Override
            public boolean isCellEditable(int r, int column) {
                return column >= 2;
            }
        };
        JBTable table = new JBTable(nodes);
        table.getColumnModel().getColumn(1).setPreferredWidth(260);

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.add(form, BorderLayout.NORTH);
        JPanel bottom = new JPanel(new BorderLayout(0, 4));
        bottom.add(new JLabel("Complexity of each node (an approximation of cyclomatic complexity):"), BorderLayout.NORTH);
        JBScrollPane scroll = new JBScrollPane(table);
        scroll.setPreferredSize(new Dimension(600, 300));
        bottom.add(scroll, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.CENTER);
        reset();
        return new JBScrollPane(root);
    }

    @Override
    public boolean isModified() {
        for (Map.Entry<String, JComponent> f : fields.entrySet()) {
            JsonElement v = read(f.getKey(), f.getValue());
            if (v != null && !v.equals(NuclearCodeMetrics.value("basics", f.getKey()))) return true;
        }
        for (int r = 0; r < nodes.getRowCount(); r++) {
            String node = (String) nodes.getValueAt(r, 0);
            JsonElement cost = number(String.valueOf(nodes.getValueAt(r, 2)));
            if (cost != null && !cost.equals(NuclearCodeMetrics.value("nodeconfiguration", node))) return true;
            JsonElement desc = new JsonPrimitive(String.valueOf(nodes.getValueAt(r, 3)));
            if (!desc.equals(NuclearCodeMetrics.value("nodeconfiguration", node + "Description"))) return true;
        }
        return false;
    }

    @Override
    public void apply() {
        for (Map.Entry<String, JComponent> f : fields.entrySet()) {
            JsonElement v = read(f.getKey(), f.getValue());
            if (v != null) NuclearCodeMetrics.setValue("basics", f.getKey(), v);
        }
        for (int r = 0; r < nodes.getRowCount(); r++) {
            String node = (String) nodes.getValueAt(r, 0);
            JsonElement cost = number(String.valueOf(nodes.getValueAt(r, 2)));
            if (cost != null) NuclearCodeMetrics.setValue("nodeconfiguration", node, cost);
            NuclearCodeMetrics.setValue("nodeconfiguration", node + "Description",
                new JsonPrimitive(String.valueOf(nodes.getValueAt(r, 3))));
        }
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            LspServerManager.getInstance(project).stopAndRestartIfNeeded(NuclearLspServerSupportProvider.class);
        }
        NuclearCodeMetricsMarkers.refreshAll();
    }

    @Override
    public void reset() {
        for (Map.Entry<String, JComponent> f : fields.entrySet()) {
            JsonElement v = NuclearCodeMetrics.value("basics", f.getKey());
            if (f.getValue() instanceof JCheckBox box) {
                box.setSelected(v != null && v.getAsBoolean());
            } else if (f.getValue() instanceof JTextField text) {
                text.setText(v == null ? "" : display(v));
            }
        }
        nodes.setRowCount(0);
        JsonObject defaults = NuclearCodeMetrics.defaults().getAsJsonObject("nodeconfiguration");
        for (String node : defaults.keySet()) {
            nodes.addRow(new Object[] {
                node,
                defaults.getAsJsonObject(node).get("nuclear").getAsString(),
                display(NuclearCodeMetrics.value("nodeconfiguration", node)),
                NuclearCodeMetrics.value("nodeconfiguration", node + "Description").getAsString(),
            });
        }
    }

    /** The value of a field with the type of its default; `null` when the text is not valid. */
    private static JsonElement read(String name, JComponent field) {
        if (field instanceof JCheckBox box) return new JsonPrimitive(box.isSelected());
        String text = ((JTextField) field).getText();
        JsonElement def = NuclearCodeMetrics.defaultValue("basics", name);
        if (def != null && def.isJsonArray()) {
            JsonArray list = new JsonArray();
            for (String part : text.split(",")) {
                if (!part.isBlank()) list.add(part.trim());
            }
            return list;
        }
        if (def != null && def.getAsJsonPrimitive().isNumber()) return number(text);
        return new JsonPrimitive(text);
    }

    private static JsonElement number(String text) {
        try {
            return new JsonPrimitive(Double.parseDouble(text.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** `1.0` → `1`, `["a","b"]` → `a, b`. */
    private static String display(JsonElement v) {
        if (v.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement e : v.getAsJsonArray()) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(e.getAsString());
            }
            return sb.toString();
        }
        if (v.getAsJsonPrimitive().isNumber()) {
            double d = v.getAsDouble();
            return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
        return v.getAsString();
    }
}
