# NuclearLang for Android Studio / IntelliJ

The NuclearLang plugin for JetBrains IDEs: the language server (`nuclear-lsp`, from the `nuclear` repository), the TextMate grammar and the Chernobyl themes of `nuclear-vscode`.

```sh
cd nuclear-jetbrains          # beside nuclear-vscode, inside the nuclear working tree
IDE_HOME=/opt/android-studio ./build.sh     # → build/nuclearlang-0.37.0.zip
```

Install: *Settings → Plugins → ⚙ → Install Plugin from Disk…* → the zip. Then: *Settings → Appearance → Theme* →
**Chernobyl** (it includes the editor's color scheme). Settings in *Settings → Tools → NuclearLang* (dialect and
server path).

A Dragonfly project: *File → Open* → the project folder. The first time, the plugin adds the app (`build/app`) as
a module and offers *Reopen Project*; on reopening, the Flutter plugin gives the device picker, the `main.dart`
configuration, Run / Debug, hot reload and DevTools. Opening the project runs `dragonfly build`, and **Ctrl+S on a
`.nuc`** transpiles and does a *Hot Reload* of the running app.

It uses the platform's native LSP API (`com.intellij.modules.lsp`, present in Android Studio 2026.2) and the
TextMate plugin bundled with the IDE for the grammar. It is compiled with `javac` against the installed IDE's jars:
no Gradle needed. If you only want the color scheme: *Settings → Editor → Color Scheme → ⚙ → Import* →
`jetbrains/Chernobyl.icls`.


