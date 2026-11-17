# clion-glsl: GLSL plugin for CLion (handoff brief)

## Goal
A CLion plugin that properly understands GLSL. Existing marketplace plugins (glsl4idea, "GLSL" by rmordechay, better-glsl) are subpar or unmaintained.

## Decided approach: LSP wrapper + tiny native lexer
- Semantics come from **glsl_analyzer** (github.com/nolanderc/glsl_analyzer), a Zig language server: completion, goto-def, find refs, hover docs, diagnostics, formatter, #include handling. It talks over **stdio by default** (`glsl_analyzer`, no args), or `--port <PORT>` for TCP. Install: release binary from GitHub into `~/.local/bin`, or `zig build install -Doptimize=ReleaseSafe --prefix ~/.local/`.
- The plugin uses the **IntelliJ Platform LSP API** (`com.intellij.platform.lsp.api.*`). CLion supports it. From 2025.2.1 on, the dependency is `<depends>com.intellij.modules.lsp</depends>` (before that it was `com.intellij.modules.ultimate`).
- A hand-written, stateless Kotlin lexer handles instant highlighting, brace matching and commenting only. Do NOT write a real grammar/PSI. The parser is flat: one root node over all tokens.

## Build setup (already written)
- `build.gradle.kts`: Kotlin JVM 2.2.21, `org.jetbrains.intellij.platform` 2.20.1 (requires **Gradle 9.x**), `clion("2025.3")`, JVM toolchain 21, sinceBuild 253, untilBuild open (`provider { null }`), `buildSearchableOptions = false`.
- No Gradle wrapper yet. Generate it: `gradle wrapper --gradle-version 9.2.1` (or the latest 9.x).
- Commands: `./gradlew runIde` (sandbox CLion), `./gradlew buildPlugin` (zip goes to `build/distributions/`, install via Settings > Plugins > Install from Disk).

## Files done
- `src/main/resources/META-INF/plugin.xml`: registers every extension below. Keep the class names in step with it.
- `src/main/resources/icons/glsl.svg`
- `GlslLanguage.kt`: `GlslLanguage`, `GlslIcons`, `GlslFileType` (Kotlin object; plugin.xml uses fieldName="INSTANCE"), `GlslFile`.
- `GlslLexer.kt`: `GlslTokenType`, `GlslTokens` (incl. COMMENTS, STRINGS TokenSets), `GlslLexer` (keywords, types incl. opaque-type regex, gl_* builtins, builtin funcs, numbers with suffixes, `#directive`, `#include <...>` treated as a string).

## Files still to write (names must match plugin.xml)
1. `GlslParserDefinition`: lexer = GlslLexer; flat PsiParser (mark, advance to EOF, done(root)); `IFileElementType(GlslLanguage)`; comment/string token sets; createElement = ASTWrapperPsiElement; createFile = GlslFile.
2. `GlslSyntaxHighlighter` + `GlslSyntaxHighlighterFactory`: TextAttributesKeys with `GLSL_` prefixes that fall back to DefaultLanguageHighlighterColors (TYPE falls back to KEYWORD, BUILTIN_VAR to PREDEFINED_SYMBOL, BUILTIN_FUNC to STATIC_METHOD, PP_DIRECTIVE to METADATA, plus the usual comment/number/string/brace/paren/bracket/semicolon/comma/dot/operator keys, and BAD_CHARACTER).
3. `GlslColorSettingsPage`: demo shader text and AttributesDescriptors for every key.
4. `GlslCommenter`: `//`, `/* */`.
5. `GlslBraceMatcher`: PairedBraceMatcher for () {} [] ({} structural).
6. `GlslSettings`: `@Service(APP)`, `@State(name="GlslSettings", storages=[Storage("glsl.xml")])`, PersistentStateComponent with `data class State(var analyzerPath: String = "")`.
7. `GlslConfigurable`: BoundConfigurable, Kotlin UI DSL; a text field bound to analyzerPath with a comment "empty = search PATH and ~/.local/bin". On apply, restart servers in all open projects: `LspServerManager.getInstance(p).stopAndRestartIfNeeded(GlslLspServerSupportProvider::class.java)`.
8. Executable lookup: use the configured path if set; otherwise `PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS("glsl_analyzer")`; otherwise check `~/.local/bin`, `/usr/local/bin` and `/opt/homebrew/bin`. A CLion started from the desktop often lacks the shell PATH, which is why the explicit fallbacks are there.
9. `GlslLspServerSupportProvider`: in `fileOpened`, if `file.fileType == GlslFileType`, resolve the exe. If it is missing, show a one-time-per-session warning in the "GLSL" notification group with a "Configure…" action (`ShowSettingsUtil.showSettingsDialog(project, GlslConfigurable::class.java)`). Otherwise call `serverStarter.ensureServerStarted(GlslLspServerDescriptor(project, exe))`. Also override `createLspServerWidgetItem` to return `LspServerWidgetItem(lspServer, currentFile, GlslIcons.FILE, GlslConfigurable::class.java)`.
10. `GlslLspServerDescriptor`: `ProjectWideLspServerDescriptor(project, "GLSL")`; `isSupportedFile` = GlslFileType; `createCommandLine` = `GeneralCommandLine(exe).withWorkDirectory(project.basePath)`.
11. `README.md`: install glsl_analyzer, build, install, troubleshooting.

## Known risks / verify on first build
- Nothing here has been compiled yet. The workspace it was written in had no access to JetBrains or Maven repos.
- If `com.intellij.platform.lsp.*` is not on the compile classpath with `clion("2025.3")`, check the IntelliJ Platform Gradle Plugin docs for how to add the LSP module (e.g. `bundledModule(...)`).
- 2026.2 open-sourced the LSP client API and introduced new names (`LspIntegrationProvider`, `ProjectWideLspClientDescriptor`). The older `LspServerSupportProvider` / `ProjectWideLspServerDescriptor` names are used here for 2025.3 compatibility. Fine if they show as deprecated on newer IDEs.
- Check whether glsl_analyzer sends semantic tokens. If it does, they layer on top of the lexer highlighting automatically.

## Style
Akamitori's preference: snake_case for locals and private helpers where Kotlin/IntelliJ API overrides don't force camelCase. Keep it minimal and explicit, with no framework magic beyond what the platform requires.
