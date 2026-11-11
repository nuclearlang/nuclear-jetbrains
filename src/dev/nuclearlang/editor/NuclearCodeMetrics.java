package dev.nuclearlang.editor;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.platform.lsp.api.LspServer;
import com.intellij.platform.lsp.api.LspServerManager;
import com.intellij.platform.lsp.api.LspServerState;
import org.eclipse.lsp4j.ExecuteCommandParams;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Code metrics (a port of the CodeMetrics extension): settings and the calls to nuclear-lsp, which computes
 * everything. The defaults come from `codemetrics-defaults.json`, generated from `nuclear-lsp
 * --codemetrics-defaults`; only the values the user changed are stored (`nuclear.codemetrics.<group>.<name>`).
 */
public final class NuclearCodeMetrics {
    public static final String TOGGLE_DISPLAYED = "nuclear.toggleCodeMetricsDisplayed";
    public static final String TOGGLE_ARROW_FUNCTIONS = "nuclear.toggleCodeMetricsForArrowFunctions";
    public static final String CODE_METRICS = "nuclear.codeMetrics";
    public static final String SHOW_DETAILS = "nuclear.showCodeMetricsCodeLensInfo";
    public static final String[] GROUPS = {"basics", "nodeconfiguration"};
    private static final String PREFIX = "nuclear.codemetrics.";
    private static JsonObject defaults;

    private NuclearCodeMetrics() {}

    /** `{ basics: { Name: { default, description } }, nodeconfiguration: { Node: { default, description, nuclear } } }` */
    public static synchronized JsonObject defaults() {
        if (defaults == null) {
            try (InputStream in = NuclearCodeMetrics.class.getResourceAsStream("/codemetrics-defaults.json")) {
                defaults = in == null ? new JsonObject()
                    : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                defaults = new JsonObject();
            }
            for (String g : GROUPS) {
                if (!defaults.has(g)) defaults.add(g, new JsonObject());
            }
        }
        return defaults;
    }

    /** The value of a setting: the stored one, else the default. */
    public static JsonElement value(String group, String name) {
        String stored = PropertiesComponent.getInstance().getValue(PREFIX + group + "." + name);
        if (stored != null) {
            try {
                return JsonParser.parseString(stored);
            } catch (Exception ignored) {
                // a broken value falls back to the default
            }
        }
        return defaultValue(group, name);
    }

    /** The default of a setting; `<Node>Description` defaults to the node's description. */
    public static JsonElement defaultValue(String group, String name) {
        JsonObject entry = defaults().getAsJsonObject(group).getAsJsonObject(name);
        if (entry != null) return entry.get("default");
        if (group.equals("nodeconfiguration") && name.endsWith("Description")) {
            return nodeDescriptionDefault(name.substring(0, name.length() - "Description".length()));
        }
        return null;
    }

    /** Stores a value; the default itself is not stored. */
    public static void setValue(String group, String name, JsonElement value) {
        JsonElement def = defaultValue(group, name);
        String key = PREFIX + group + "." + name;
        if (def != null && def.equals(value)) {
            PropertiesComponent.getInstance().unsetValue(key);
        } else {
            PropertiesComponent.getInstance().setValue(key, value.toString());
        }
    }

    /** Every setting with its current value, as the server reads it (`initializationOptions.codemetrics`). */
    public static Map<String, Object> settings() {
        Gson gson = new Gson();
        Map<String, Object> out = new LinkedHashMap<>();
        for (String g : GROUPS) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (String name : defaults().getAsJsonObject(g).keySet()) {
                values.put(name, gson.fromJson(value(g, name), Object.class));
                if (g.equals("nodeconfiguration")) {
                    String d = name + "Description";
                    values.put(d, gson.fromJson(value(g, d), Object.class));
                }
            }
            out.put(g, values);
        }
        return out;
    }

    /** Default description of a node (`ReturnStatement` → "Return statement"), stored as `<Node>Description`. */
    static JsonElement nodeDescriptionDefault(String node) {
        JsonObject entry = defaults().getAsJsonObject("nodeconfiguration").getAsJsonObject(node);
        return entry == null ? null : entry.get("description");
    }

    /** The running Nuclear server of a project, if any. */
    public static LspServer server(Project project) {
        for (LspServer s : LspServerManager.getInstance(project).getServersForProvider(NuclearLspServerSupportProvider.class)) {
            if (s.getState() == LspServerState.Running) return s;
        }
        return null;
    }

    /** Runs a server command (`workspace/executeCommand`) and returns its result as JSON. Not on the EDT. */
    public static JsonElement execute(Project project, String command, List<Object> arguments) {
        LspServer server = server(project);
        if (server == null) return null;
        ExecuteCommandParams params = new ExecuteCommandParams(command, arguments);
        Object result = server.sendRequestSync(5000, ls -> ls.getWorkspaceService().executeCommand(params));
        return result == null ? null : new Gson().toJsonTree(result);
    }
}
