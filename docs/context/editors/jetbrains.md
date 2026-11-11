---
layer: L3
component: editors
read-when: you change the Android Studio / IntelliJ plugin (nuclear-jetbrains)
---

# JetBrains plugin (`nuclear-jetbrains`)

Built with `jetbrains/build.sh` (`javac` against `$IDE_HOME`, no Gradle) into a zip; installed unpacked in
`~/.local/share/Google/AndroidStudio2026.2.1/nuclearlang`. Compiled against Android Studio 2026.2; never loaded
in a running IDE.

| File | What it does |
|---|---|
| `src/…` base | `LspServerSupportProvider`, server descriptor, TextMate bundle, file icon (`NuclearFileIconProvider` + `$Psi` IconProvider `first` + `$Tree` decorator `last`: they beat icon plugins like Atom Material Icons, which maps `.nuc` to Nuclide), settings; metrics (`NuclearCodeMetrics`, `NuclearCodeLensSupport`, `NuclearCodeMetricsMarkers`, `NuclearCodeMetricsConfigurable`, toggles); `NuclearCommentEnter` (Enter adds ` * `) |
| `NuclearDragonfly.java` | Dragonfly projects (reads `build/<name>.code-workspace`): `Startup` runs `dragonfly build` and, if the app is not a module, loads `build/app/<name>_flutter_app.iml` and offers *Reopen Project*; `OnSave` → build + `Flutter.Toolbar.ReloadAllAction`; `BeforeRun` (ExecutionListener) → `dragonfly build` with modal progress, `repair` if it failed with code ≠ 2; `processTerminated` ≠ 0 without `TERMINATION_REQUESTED` or a compile error after reload → `repairAndRestart` (at most once per 5 min); `Hot reload was rejected` → `Flutter.Toolbar.RestartAllAction` |
| `NuclearHttp.java` | the *Nuclear HTTP* tool window (0.30, ADR-0098; the VS Code view in Swing): `findProjects` walks the project folder for `.nuclear/project.json` + `contract.json`, `endpointsOf` (backend `controllers` as routes, mobile/web `repositories` as calls), `declaredContracts` from `config.nuc`, environments in `<project>/.nuclear/http.json`, OpenAPI documents (`loadOpenApi`); `Panel` = tree (`ColoredTreeCellRenderer`, Chernobyl colors per verb/platform/project/component) + request panel (`java.net.http`, pretty JSON with Gson, copy as curl); title actions select environment / add OpenAPI / update contracts (`~/.local/bin/nuclear contracts update`, a notification) / refresh; `Open` action with Alt+Shift+H (`keyboard-shortcut` in plugin.xml). Not loaded in a running IDE |
| `NuclearDebug.java` | `.nuc` debugging: `BreakpointType` (`nuclear-line`) with `Dart` mirrors, `Breakpoints`, `MapWatcher`, `Sessions` (pause → `.nuc` with `EXECUTIONPOINT_ATTRIBUTES`), `OutputErrors` (console → `dragonfly explain --json` → red line + tool window *Nuclear Error*). Mixed stepping is not done here |

- `build.sh` packages the grammar without the `@example` patterns (JetBrains cannot dim by context) and the
  `language-configuration.json` without `onEnterRules`.
- The IDE manages `flutter run` and the LSP processes itself.
