package dev.nuclearlang.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.icons.AllIcons;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
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
import com.intellij.ui.table.JBTable;
import com.intellij.ui.treeStructure.Tree;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The "Nuclear HTTP" tool window (ADR-0098), the same as the VS Code view: every project under the IDE's project
 * (`.nuclear/project.json`) grouped by platform, its components and endpoints read from `.nuclear/contract.json`,
 * the contracts a client declares with an Update action, OpenAPI documents, environments in `.nuclear/http.json`
 * (variables, headers, base urls) and a request panel. Alt+Shift+H opens it.
 */
public final class NuclearHttp {
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> SKIP = Set.of("node_modules", "build", "dist", "target", ".git", ".dart_tool", ".venv", ".idea", "__pycache__");
    private static final Map<String, String> PLATFORMS = new LinkedHashMap<>(Map.of("backend", "Backend", "mobile", "Mobile", "web", "Web"));
    private static final Pattern CONTRACTS = Pattern.compile("^\\s*contracts\\s*\\(([^)]*)\\)", Pattern.MULTILINE);
    private static final Pattern PAIR = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_.-]*)\\s*}}");

    // the Chernobyl palette (dark / light): verbs, platforms, projects, components
    static final JBColor GET = new JBColor(new Color(0x64FF00), new Color(0x2E8B57));
    static final JBColor POST = new JBColor(new Color(0x2FE0F0), new Color(0x1E6FD9));
    static final JBColor PUT = new JBColor(new Color(0xFFD600), new Color(0xB8860B));
    static final JBColor PATCH = new JBColor(new Color(0xFFB347), new Color(0xC46A2B));
    static final JBColor DELETE = new JBColor(new Color(0xFF4B4B), new Color(0xD32F2F));
    static final JBColor PLATFORM = new JBColor(new Color(0xA7FF00), new Color(0xB59400));
    static final JBColor PROJECT = new JBColor(new Color(0xC586C0), new Color(0x8A4FAE));
    static final JBColor COMPONENT = new JBColor(new Color(0x9CDCFE), new Color(0x2B6CB0));

    private NuclearHttp() {}

    // ---------- data ----------

    record Proj(Path root, String name, String platform, String dialect, JsonObject contract, List<Declared> contracts) {}

    record Declared(String name, String source, String version) {}

    record Param(String name, String type, boolean optional, String def, String in) {}

    /** A backend route, a client call or an OpenAPI operation: one request the tree offers. */
    record Endpoint(String project, String kind, String owner, String component, String verb, String path, String description,
                    List<Param> params, String returns, String body, String connection) {}

    record OpenApiDoc(String source, String title, List<Endpoint> endpoints, String error) {}

    record Environment(Map<String, String> vars, Map<String, String> headers, Map<String, String> baseUrls, List<String> openapi) {}

    record Environments(String active, Map<String, Environment> environments) {}

    static List<Proj> findProjects(Path base) {
        List<Proj> out = new ArrayList<>();
        walk(base, 0, out);
        out.sort((a, b) -> a.name().compareTo(b.name()));
        return out;
    }

    private static void walk(Path dir, int depth, List<Proj> out) {
        if (Files.exists(dir.resolve(".nuclear/project.json"))) {
            Proj p = readProject(dir);
            if (p != null) out.add(p);
        }
        if (depth >= 4) return;
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path e : entries.toList()) {
                String n = e.getFileName().toString();
                if (Files.isDirectory(e) && !SKIP.contains(n) && !n.startsWith(".")) walk(e, depth + 1, out);
            }
        } catch (IOException ignored) {
        }
    }

    private static Proj readProject(Path root) {
        try {
            JsonObject card = JsonParser.parseString(Files.readString(root.resolve(".nuclear/project.json"))).getAsJsonObject();
            JsonObject contract = null;
            Path c = root.resolve(".nuclear/contract.json");
            if (Files.exists(c)) contract = JsonParser.parseString(Files.readString(c)).getAsJsonObject();
            String platform = card.has("platform") && !card.get("platform").isJsonNull() ? card.get("platform").getAsString() : null;
            return new Proj(root, card.get("name").getAsString(), platform, card.get("dialect").getAsString(), contract, declaredContracts(root));
        } catch (Exception e) {
            return null;
        }
    }

    static List<Declared> declaredContracts(Path root) {
        List<Declared> out = new ArrayList<>();
        String text;
        try {
            text = Files.readString(root.resolve("config.nuc"));
        } catch (IOException e) {
            return out;
        }
        Matcher section = CONTRACTS.matcher(text);
        while (section.find()) {
            Matcher pair = PAIR.matcher(section.group(1));
            while (pair.find()) {
                String version = null;
                try {
                    Path copy = root.resolve("contracts").resolve(pair.group(1)).resolve("contract.json");
                    if (Files.exists(copy)) version = JsonParser.parseString(Files.readString(copy)).getAsJsonObject().get("version").getAsString();
                } catch (Exception ignored) {
                }
                out.add(new Declared(pair.group(1), pair.group(2), version));
            }
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    static List<Endpoint> endpointsOf(Proj p) {
        List<Endpoint> out = new ArrayList<>();
        if (p.contract() == null) return out;
        boolean backend = "backend".equals(p.platform());
        JsonObject apis = p.contract().getAsJsonObject(backend ? "controllers" : "repositories");
        if (apis == null) return out;
        JsonObject models = p.contract().getAsJsonObject("models");
        for (String owner : apis.keySet()) {
            JsonObject api = apis.getAsJsonObject(owner);
            String base = str(api, "path") == null ? "" : str(api, "path");
            String component = str(api, "component");
            if (component == null || component.isEmpty()) component = "shared";
            for (JsonElement r : api.getAsJsonArray("routes")) {
                JsonObject route = r.getAsJsonObject();
                List<Param> params = new ArrayList<>();
                String body = null;
                for (JsonElement pe : route.getAsJsonArray("params")) {
                    JsonObject pm = pe.getAsJsonObject();
                    String in = str(pm, "in");
                    params.add(new Param(str(pm, "name"), str(pm, "type"), pm.has("optional"), pm.has("default") ? pm.get("default").toString() : null, in));
                    if ("body".equals(in)) body = PRETTY.toJson(sampleOf(str(pm, "type"), models, 0));
                }
                String full = "/" + String.join("/", Stream.of(base, str(route, "path") == null ? "" : str(route, "path"))
                        .map(s -> s.replaceAll("^/|/$", "")).filter(s -> !s.isEmpty()).toList());
                full = full.replaceAll(":([A-Za-z_][A-Za-z0-9_]*)", "{$1}");
                out.add(new Endpoint(p.name(), backend ? "route" : "call", owner, component, str(route, "verb"), full,
                        str(route, "description"), params, str(route, "returns"), body, backend ? null : str(api, "connection")));
            }
        }
        return out;
    }

    /** A sample value of a Nuclear type from the contract's models (what a request body looks like). */
    static JsonElement sampleOf(String type, JsonObject models, int depth) {
        String t = type == null ? "any" : type.replaceAll("\\?$", "");
        if (t.endsWith("[]")) {
            JsonArray a = new JsonArray();
            a.add(sampleOf(t.substring(0, t.length() - 2), models, depth + 1));
            return a;
        }
        if (t.startsWith("<")) return new JsonObject();
        switch (t) {
            case "int", "double", "number" -> { return new com.google.gson.JsonPrimitive(0); }
            case "string" -> { return new com.google.gson.JsonPrimitive(""); }
            case "boolean", "bool" -> { return new com.google.gson.JsonPrimitive(false); }
            case "DateTime" -> { return new com.google.gson.JsonPrimitive(java.time.Instant.now().toString()); }
            default -> { }
        }
        JsonObject model = models == null ? null : models.getAsJsonObject(t.split("<")[0]);
        if (model == null || depth > 3) return com.google.gson.JsonNull.INSTANCE;
        JsonObject out = new JsonObject();
        for (JsonElement fe : model.getAsJsonArray("fields")) {
            JsonObject f = fe.getAsJsonObject();
            if (f.has("optional") && !f.has("default")) continue;
            String key = str(f, "json") != null ? str(f, "json") : str(f, "name");
            out.add(key, f.has("default") ? f.get("default") : sampleOf(str(f, "type"), models, depth + 1));
        }
        return out;
    }

    static Environments environments(Path base) {
        Environments fallback = new Environments("local", new LinkedHashMap<>(Map.of("local",
                new Environment(new LinkedHashMap<>(Map.of("baseUrl", "http://localhost:3000")), new LinkedHashMap<>(), new LinkedHashMap<>(), new ArrayList<>()))));
        Path file = base.resolve(".nuclear/http.json");
        if (!Files.exists(file)) return fallback;
        try {
            JsonObject read = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            Map<String, Environment> envs = new LinkedHashMap<>();
            JsonObject all = read.getAsJsonObject("environments");
            if (all != null) {
                for (String name : all.keySet()) {
                    JsonObject e = all.getAsJsonObject(name);
                    List<String> openapi = new ArrayList<>();
                    if (e.has("openapi")) for (JsonElement x : e.getAsJsonArray("openapi")) openapi.add(x.getAsString());
                    envs.put(name, new Environment(strings(e.getAsJsonObject("vars")), strings(e.getAsJsonObject("headers")), strings(e.getAsJsonObject("baseUrls")), openapi));
                }
            }
            if (envs.isEmpty()) return fallback;
            String active = str(read, "active");
            if (active == null || !envs.containsKey(active)) active = envs.keySet().iterator().next();
            return new Environments(active, envs);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Map<String, String> strings(JsonObject o) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o != null) for (String k : o.keySet()) out.put(k, o.get(k).getAsString());
        return out;
    }

    static void writeEnvironments(Path base, Environments envs) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("active", envs.active());
        JsonObject all = new JsonObject();
        for (Map.Entry<String, Environment> e : envs.environments().entrySet()) {
            JsonObject o = new JsonObject();
            o.add("vars", PRETTY.toJsonTree(e.getValue().vars()));
            o.add("headers", PRETTY.toJsonTree(e.getValue().headers()));
            o.add("baseUrls", PRETTY.toJsonTree(e.getValue().baseUrls()));
            o.add("openapi", PRETTY.toJsonTree(e.getValue().openapi()));
            all.add(e.getKey(), o);
        }
        root.add("environments", all);
        Files.createDirectories(base.resolve(".nuclear"));
        Files.writeString(base.resolve(".nuclear/http.json"), PRETTY.toJson(root) + "\n");
    }

    static OpenApiDoc loadOpenApi(Path base, String source) {
        try {
            String text;
            if (source.startsWith("http://") || source.startsWith("https://")) {
                HttpResponse<String> res = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(source)).timeout(Duration.ofSeconds(8)).build(), HttpResponse.BodyHandlers.ofString());
                text = res.body();
            } else {
                text = Files.readString(base.resolve(source));
            }
            JsonObject doc = JsonParser.parseString(text).getAsJsonObject();
            String title = doc.has("info") ? str(doc.getAsJsonObject("info"), "title") : null;
            List<Endpoint> endpoints = new ArrayList<>();
            JsonObject paths = doc.getAsJsonObject("paths");
            if (paths != null) {
                for (String p : paths.keySet()) {
                    JsonObject ops = paths.getAsJsonObject(p);
                    for (String verb : ops.keySet()) {
                        if (!List.of("get", "post", "put", "patch", "delete").contains(verb)) continue;
                        JsonObject op = ops.getAsJsonObject(verb);
                        List<Param> params = new ArrayList<>();
                        if (op.has("parameters")) for (JsonElement pe : op.getAsJsonArray("parameters")) {
                            JsonObject pm = pe.getAsJsonObject();
                            String in = str(pm, "in");
                            if (!"path".equals(in) && !"query".equals(in)) continue;
                            String type = pm.has("schema") ? str(pm.getAsJsonObject("schema"), "type") : "string";
                            params.add(new Param(str(pm, "name"), type == null ? "string" : type, !(pm.has("required") && pm.get("required").getAsBoolean()), null, in));
                        }
                        String tag = op.has("tags") && op.getAsJsonArray("tags").size() > 0 ? op.getAsJsonArray("tags").get(0).getAsString() : "default";
                        endpoints.add(new Endpoint(source, "openapi", str(op, "operationId") == null ? "" : str(op, "operationId"), tag, verb, p, str(op, "summary"), params, "", op.has("requestBody") ? "{}" : null, null));
                    }
                }
            }
            return new OpenApiDoc(source, title == null ? source : title, endpoints, null);
        } catch (Exception e) {
            return new OpenApiDoc(source, source, List.of(), e.toString());
        }
    }

    // ---------- the tool window ----------

    public static final class Window implements ToolWindowFactory {
        @Override
        public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow window) {
            Panel panel = new Panel(project);
            window.getContentManager().addContent(ContentFactory.getInstance().createContent(panel, "", false));
            window.setTitleActions(List.of(panel.action("Select Environment", AllIcons.General.Settings, panel::selectEnvironment),
                    panel.action("Add an OpenAPI Document", AllIcons.General.Add, panel::addOpenApi),
                    panel.action("Update Contracts (nuclear contracts update)", AllIcons.Actions.Download, panel::updateContracts),
                    panel.action("Refresh", AllIcons.Actions.Refresh, panel::refresh)));
        }
    }

    /** Alt+Shift+H (plugin.xml): opens the tool window from anywhere. */
    public static final class Open extends AnAction {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            if (project == null) return;
            ToolWindow w = ToolWindowManager.getInstance(project).getToolWindow("Nuclear HTTP");
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
        private final JComboBox<String> method = new JComboBox<>(new String[]{"GET", "POST", "PUT", "PATCH", "DELETE"});
        private final JTextField url = new JTextField();
        private final DefaultTableModel params = new DefaultTableModel(new Object[]{"Parameter", "Value"}, 0);
        private final DefaultTableModel headers = new DefaultTableModel(new Object[]{"Header", "Value"}, 0);
        private final JBTextArea body = new JBTextArea(6, 40);
        private final JLabel status = new JLabel("—");
        private final JBTextArea response = new JBTextArea();
        private final JLabel envLabel = new JLabel();
        private Endpoint current;
        private String baseUrl = "{{baseUrl}}";
        private List<Proj> projects = List.of();

        Panel(Project project) {
            super(new BorderLayout());
            this.project = project;
            this.base = Path.of(project.getBasePath() == null ? "." : project.getBasePath());
            tree.setRootVisible(false);
            tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
            tree.setCellRenderer(new Renderer());
            tree.addTreeSelectionListener(e -> {
                DefaultMutableTreeNode n = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
                if (n != null && n.getUserObject() instanceof Endpoint ep) load(ep);
            });
            OnePixelSplitter split = new OnePixelSplitter(false, 0.38f);
            split.setFirstComponent(new JBScrollPane(tree));
            split.setSecondComponent(requestPanel());
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

        private JComponent requestPanel() {
            JPanel p = new JPanel(new BorderLayout(6, 6));
            p.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            JPanel top = new JPanel(new BorderLayout(6, 0));
            JButton send = new JButton("Send");
            send.addActionListener(e -> send());
            top.add(method, BorderLayout.WEST);
            top.add(url, BorderLayout.CENTER);
            top.add(send, BorderLayout.EAST);
            JPanel head = new JPanel(new BorderLayout());
            head.add(top, BorderLayout.NORTH);
            envLabel.setForeground(JBColor.GRAY);
            head.add(envLabel, BorderLayout.SOUTH);
            p.add(head, BorderLayout.NORTH);
            JTabbedPane tabs = new JTabbedPane();
            JBTable paramsTable = new JBTable(params);
            params.addTableModelListener(e -> buildUrl());
            tabs.addTab("Params", new JBScrollPane(paramsTable));
            JPanel headersPanel = new JPanel(new BorderLayout());
            headersPanel.add(new JBScrollPane(new JBTable(headers)), BorderLayout.CENTER);
            JButton addHeader = new JButton("+ header");
            addHeader.addActionListener(e -> headers.addRow(new Object[]{"", ""}));
            headersPanel.add(addHeader, BorderLayout.SOUTH);
            tabs.addTab("Headers", headersPanel);
            tabs.addTab("Body", new JBScrollPane(body));
            response.setEditable(false);
            JPanel resp = new JPanel(new BorderLayout(6, 4));
            JPanel respHead = new JPanel(new BorderLayout());
            respHead.add(status, BorderLayout.WEST);
            JButton curl = new JButton("copy as curl");
            curl.addActionListener(e -> copyCurl());
            respHead.add(curl, BorderLayout.EAST);
            resp.add(respHead, BorderLayout.NORTH);
            resp.add(new JBScrollPane(response), BorderLayout.CENTER);
            OnePixelSplitter v = new OnePixelSplitter(true, 0.45f);
            v.setFirstComponent(tabs);
            v.setSecondComponent(resp);
            p.add(v, BorderLayout.CENTER);
            return p;
        }

        // ---------- tree ----------

        void refresh() {
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                List<Proj> found = findProjects(base);
                Environments envs = environments(base);
                Environment env = envs.environments().get(envs.active());
                List<OpenApiDoc> docs = new ArrayList<>();
                if (env != null) for (String s : env.openapi()) docs.add(loadOpenApi(base, s));
                ApplicationManager.getApplication().invokeLater(() -> {
                    projects = found;
                    envLabel.setText("environment: " + envs.active() + "   variables: " + (env == null ? "" : String.join(" ", env.vars().keySet().stream().map(k -> "{{" + k + "}}").toList())));
                    tree.setModel(new DefaultTreeModel(buildTree(found, docs)));
                    for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
                });
            });
        }

        private DefaultMutableTreeNode buildTree(List<Proj> found, List<OpenApiDoc> docs) {
            DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
            for (Map.Entry<String, String> pl : PLATFORMS.entrySet()) {
                List<Proj> ps = found.stream().filter(p -> pl.getKey().equals(p.platform())).toList();
                if (ps.isEmpty()) continue;
                DefaultMutableTreeNode platform = new DefaultMutableTreeNode(new Label(pl.getValue(), "platform"));
                root.add(platform);
                for (Proj p : ps) {
                    DefaultMutableTreeNode pn = new DefaultMutableTreeNode(p);
                    platform.add(pn);
                    if (!p.contracts().isEmpty()) {
                        DefaultMutableTreeNode cn = new DefaultMutableTreeNode(new Label("Contracts", "contracts"));
                        pn.add(cn);
                        for (Declared d : p.contracts()) cn.add(new DefaultMutableTreeNode(d));
                    }
                    Map<String, DefaultMutableTreeNode> components = new LinkedHashMap<>();
                    for (Endpoint e : endpointsOf(p)) {
                        components.computeIfAbsent(e.component(), c -> {
                            DefaultMutableTreeNode n = new DefaultMutableTreeNode(new Label(c, "component"));
                            pn.add(n);
                            return n;
                        }).add(new DefaultMutableTreeNode(e));
                    }
                    if (components.isEmpty() && p.contracts().isEmpty()) pn.add(new DefaultMutableTreeNode(new Label("no endpoints in the contract", "message")));
                }
            }
            if (!docs.isEmpty()) {
                DefaultMutableTreeNode on = new DefaultMutableTreeNode(new Label("OpenAPI documents", "platform"));
                root.add(on);
                for (OpenApiDoc d : docs) {
                    DefaultMutableTreeNode dn = new DefaultMutableTreeNode(d);
                    on.add(dn);
                    Map<String, DefaultMutableTreeNode> tags = new LinkedHashMap<>();
                    for (Endpoint e : d.endpoints()) {
                        tags.computeIfAbsent(e.component(), c -> {
                            DefaultMutableTreeNode n = new DefaultMutableTreeNode(new Label(c, "component"));
                            dn.add(n);
                            return n;
                        }).add(new DefaultMutableTreeNode(e));
                    }
                }
            }
            if (root.getChildCount() == 0) root.add(new DefaultMutableTreeNode(new Label("No Nuclear project found: build one, or add an OpenAPI document", "message")));
            return root;
        }

        record Label(String text, String kind) {}

        static final class Renderer extends ColoredTreeCellRenderer {
            @Override
            public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
                Object o = ((DefaultMutableTreeNode) value).getUserObject();
                if (o instanceof Label l) {
                    switch (l.kind()) {
                        case "platform" -> { setIcon(AllIcons.Nodes.Module); append(l.text(), new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, PLATFORM)); }
                        case "component" -> { setIcon(AllIcons.Nodes.Package); append(l.text(), new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, COMPONENT)); }
                        case "contracts" -> { setIcon(AllIcons.Nodes.PpLib); append(l.text(), new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, PROJECT)); }
                        default -> { setIcon(AllIcons.General.Information); append(l.text(), SimpleTextAttributes.GRAYED_ATTRIBUTES); }
                    }
                } else if (o instanceof Proj p) {
                    setIcon(AllIcons.Nodes.Folder);
                    append(p.name(), new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, PROJECT));
                    append("  " + p.dialect(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                } else if (o instanceof Declared d) {
                    setIcon(d.version() == null ? AllIcons.General.Warning : AllIcons.Actions.Checked);
                    append(d.name(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                    append("  " + (d.version() == null ? "not fetched" : d.version()) + " · " + d.source(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                } else if (o instanceof OpenApiDoc d) {
                    setIcon(d.error() == null ? AllIcons.Nodes.PpWeb : AllIcons.General.Warning);
                    append(d.title(), new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, PLATFORM));
                    append("  " + (d.error() == null ? d.source() : "could not load"), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                } else if (o instanceof Endpoint e) {
                    setIcon(AllIcons.Nodes.Method);
                    append(e.verb().toUpperCase() + " ", new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, verbColor(e.verb())));
                    append(e.path(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                    append("  " + e.owner() + (e.connection() == null || e.connection().isEmpty() ? "" : " · " + e.connection()), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                } else {
                    append(String.valueOf(o));
                }
            }
        }

        static JBColor verbColor(String verb) {
            return switch (verb) {
                case "post" -> POST;
                case "put" -> PUT;
                case "patch" -> PATCH;
                case "delete" -> DELETE;
                default -> GET;
            };
        }

        // ---------- request ----------

        private void load(Endpoint e) {
            current = e;
            Environments envs = environments(base);
            Environment env = envs.environments().get(envs.active());
            baseUrl = "{{baseUrl}}";
            if (env != null) {
                if (e.connection() != null && env.baseUrls().containsKey(e.connection())) baseUrl = env.baseUrls().get(e.connection());
                else if (env.baseUrls().containsKey(e.project())) baseUrl = env.baseUrls().get(e.project());
            }
            method.setSelectedItem(e.verb().toUpperCase());
            params.setRowCount(0);
            for (Param p : e.params()) {
                if ("body".equals(p.in())) continue;
                String sample = "path".equals(p.in()) ? ("int".equals(p.type()) || "integer".equals(p.type()) || "number".equals(p.type()) || "double".equals(p.type()) ? "1" : "") : (p.def() == null ? "" : p.def().replace("\"", ""));
                params.addRow(new Object[]{p.name() + " (" + p.in() + ": " + p.type() + (p.optional() ? "?" : "") + ")", sample});
            }
            headers.setRowCount(0);
            if (env != null) for (Map.Entry<String, String> h : env.headers().entrySet()) headers.addRow(new Object[]{h.getKey(), h.getValue()});
            body.setText(e.body() == null ? "" : e.body());
            status.setText(e.description() == null ? "—" : e.description());
            response.setText("");
            buildUrl();
        }

        private void buildUrl() {
            if (current == null) return;
            String path = current.path();
            List<String> query = new ArrayList<>();
            for (int i = 0; i < params.getRowCount(); i++) {
                String label = String.valueOf(params.getValueAt(i, 0));
                String name = label.split(" ")[0];
                String value = String.valueOf(params.getValueAt(i, 1));
                if (label.contains("(path")) path = path.replace("{" + name + "}", URLEncoder.encode(value, StandardCharsets.UTF_8));
                else if (!value.isEmpty()) query.add(URLEncoder.encode(name, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
            url.setText(baseUrl.replaceAll("/$", "") + path + (query.isEmpty() ? "" : "?" + String.join("&", query)));
        }

        private Map<String, String> requestHeaders() {
            Map<String, String> out = new LinkedHashMap<>();
            for (int i = 0; i < headers.getRowCount(); i++) {
                String k = String.valueOf(headers.getValueAt(i, 0)).trim();
                if (!k.isEmpty()) out.put(k, String.valueOf(headers.getValueAt(i, 1)));
            }
            return out;
        }

        private String resolve(String text, Map<String, String> vars) {
            Matcher m = VAR.matcher(text);
            StringBuilder sb = new StringBuilder();
            while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(vars.getOrDefault(m.group(1), m.group(0))));
            m.appendTail(sb);
            return sb.toString();
        }

        private void send() {
            Environments envs = environments(base);
            Environment env = envs.environments().get(envs.active());
            Map<String, String> vars = env == null ? Map.of() : env.vars();
            String target = resolve(url.getText(), vars);
            String verb = String.valueOf(method.getSelectedItem());
            String payload = body.getText().isBlank() ? null : resolve(body.getText(), vars);
            Map<String, String> hs = new LinkedHashMap<>();
            for (Map.Entry<String, String> h : requestHeaders().entrySet()) hs.put(h.getKey(), resolve(h.getValue(), vars));
            if (payload != null && hs.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("content-type"))) hs.put("content-type", "application/json");
            status.setText("sending…");
            long started = System.currentTimeMillis();
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                String text;
                String line;
                try {
                    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(30));
                    for (Map.Entry<String, String> h : hs.entrySet()) b.header(h.getKey(), h.getValue());
                    b.method(verb, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
                    HttpResponse<String> res = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
                    text = res.body();
                    try {
                        text = PRETTY.toJson(JsonParser.parseString(text));
                    } catch (Exception ignored) {
                    }
                    line = res.statusCode() + "  ·  " + (System.currentTimeMillis() - started) + " ms  ·  " + res.body().length() + " bytes  ·  " + target;
                } catch (Exception ex) {
                    text = ex.toString();
                    line = "failed  ·  " + target;
                }
                String finalText = text;
                String finalLine = line;
                ApplicationManager.getApplication().invokeLater(() -> {
                    status.setText(finalLine);
                    response.setText(finalText);
                    response.setCaretPosition(0);
                });
            });
        }

        private void copyCurl() {
            StringBuilder sb = new StringBuilder("curl -X ").append(method.getSelectedItem());
            for (Map.Entry<String, String> h : requestHeaders().entrySet()) sb.append(" -H '").append(h.getKey()).append(": ").append(h.getValue()).append("'");
            if (!body.getText().isBlank()) sb.append(" -d '").append(body.getText().replace("'", "'\\''")).append("'");
            sb.append(" '").append(url.getText()).append("'");
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(sb.toString()), null);
            status.setText("copied as curl");
        }

        // ---------- actions ----------

        void selectEnvironment() {
            Environments envs = environments(base);
            List<String> names = new ArrayList<>(envs.environments().keySet());
            names.add("New environment…");
            int i = Messages.showChooseDialog(project, "Environment for the requests (variables, headers, base urls)", "Nuclear HTTP",
                    null, names.toArray(new String[0]), envs.active());
            if (i < 0) return;
            if (i == names.size() - 1) {
                String name = Messages.showInputDialog(project, "Name of the environment", "Nuclear HTTP", null, "staging", null);
                if (name == null || name.isBlank()) return;
                envs.environments().put(name, new Environment(new LinkedHashMap<>(Map.of("baseUrl", "https://")), new LinkedHashMap<>(), new LinkedHashMap<>(), new ArrayList<>()));
                envs = new Environments(name, envs.environments());
                write(envs);
                editEnvironments();
            } else {
                write(new Environments(names.get(i), envs.environments()));
            }
            refresh();
        }

        private void write(Environments envs) {
            try {
                writeEnvironments(base, envs);
            } catch (IOException e) {
                Messages.showErrorDialog(project, e.toString(), "Nuclear HTTP");
            }
        }

        void editEnvironments() {
            Path file = base.resolve(".nuclear/http.json");
            if (!Files.exists(file)) write(environments(base));
            VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
            if (vf != null) FileEditorManager.getInstance(project).openFile(vf, true);
        }

        void addOpenApi() {
            String source = Messages.showInputDialog(project, "OpenAPI document of an API that is not a Nuclear project: a url or a file path", "Nuclear HTTP", null, "http://localhost:8000/openapi.json", null);
            if (source == null || source.isBlank()) return;
            Environments envs = environments(base);
            Environment env = envs.environments().get(envs.active());
            if (env != null && !env.openapi().contains(source)) env.openapi().add(source);
            write(envs);
            refresh();
        }

        void updateContracts() {
            List<Proj> with = projects.stream().filter(p -> !p.contracts().isEmpty()).toList();
            if (with.isEmpty()) {
                Messages.showInfoMessage(project, "No project declares contracts (contracts(name = ...) in config.nuc).", "Nuclear HTTP");
                return;
            }
            Proj target = with.get(0);
            if (with.size() > 1) {
                int i = Messages.showChooseDialog(project, "Project whose contracts to update", "Nuclear HTTP", null, with.stream().map(Proj::name).toArray(String[]::new), with.get(0).name());
                if (i < 0) return;
                target = with.get(i);
            }
            Proj chosen = target;
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                String out;
                boolean ok;
                try {
                    Path local = Path.of(System.getProperty("user.home"), ".local", "bin", "nuclear");
                    Process p = new ProcessBuilder(Files.exists(local) ? local.toString() : "nuclear", "contracts", "update").directory(chosen.root().toFile()).redirectErrorStream(true).start();
                    out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    ok = p.waitFor() == 0;
                } catch (Exception e) {
                    out = e.toString();
                    ok = false;
                }
                String text = out;
                boolean fine = ok;
                ApplicationManager.getApplication().invokeLater(() -> {
                    NotificationGroupManager.getInstance().getNotificationGroup("NuclearLang")
                            .createNotification("Nuclear contracts of " + chosen.name(), text.trim(), fine ? NotificationType.INFORMATION : NotificationType.ERROR)
                            .notify(project);
                    refresh();
                });
            });
        }
    }
}
