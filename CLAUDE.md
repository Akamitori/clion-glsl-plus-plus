# clion-glsl: GLSL plugin for CLion

A CLion plugin that properly understands GLSL. Existing marketplace plugins (glsl4idea, "GLSL" by rmordechay,
better-glsl) are subpar or unmaintained. Targets CLion 2025.3+ (sinceBuild 253), developed against CLion 2026.3 Nova.

## Architecture

| Concern | Source | File |
|---|---|---|
| Instant highlighting, brace matching, commenting | hand-written stateless lexer | `GlslLexer.kt`, `GlslHighlighting.kt`, `GlslEditor.kt` |
| PSI | flat: one file node over all lexer tokens, **no grammar** | `GlslParserDefinition.kt`, `GlslLanguage.kt` |
| Semantic colours (functions, params, globals, structs, fields, swizzles, macros, layout qualifiers) | heuristic token scan, an `Annotator` | `GlslSemanticAnnotator.kt` |
| Hover, completion | glsl_analyzer over the IntelliJ LSP API (stdio) | `GlslLsp.kt` |
| Go to declaration, Ctrl+hover underline | glsl_analyzer `textDocument/definition`, called **directly** by our action | `GlslNavigation.kt` |
| Reformat | glsl_analyzer `textDocument/formatting`, called directly | `GlslFormat.kt` |
| Rename | our own token-level rename; glsl_analyzer only locates the declaration | `GlslRename.kt` |
| Diagnostics | glslang (`glslangValidator`) as an `ExternalAnnotator` | `GlslangAnnotator.kt` |
| Find Usages | same occurrence search as rename (`find_symbol_occurrences`), shown in the Find tool window | `GlslFindUsages.kt` |
| Folding | multi-line `{}` blocks and block comments from the lexer (`fold_regions`) | `GlslFolding.kt` |
| Structure view | heuristic top-level outline (`outline`): functions, structs/blocks + members, globals, macros | `GlslStructure.kt` |
| `#include` navigation | Ctrl+B / Ctrl+click on the path (`include_at` + `resolve_include`) | `GlslNavigation.kt` |
| Settings | tool paths: app-level `GlslSettings`; target/defines/include dirs: project-level `GlslProjectSettings` (`.idea/glsl.xml`); one `projectConfigurable` | `GlslSettings.kt` |

glsl_analyzer 1.7.1 capabilities: completion, hover, definition, formatting. It sends **no diagnostics** (push or pull),
**no semantic tokens**, and has **no references/rename**. That is why diagnostics, semantic colours and rename are ours.

## CLion Nova: the big trap

Nova (Radler) routes several stock editor actions to its ReSharper C++ backend for *any* file in the project, and that
backend knows nothing about GLSL. Symptom: the action silently does nothing, or "Cannot find declaration to go to".
Confirmed for `GotoDeclaration` (Ctrl+B, Ctrl+click), `RenameElement` (Shift+F6) and `ReformatCode` (Ctrl+Alt+L);
Find Usages (Alt+F7) is assumed to be the same and gets the same treatment.
The routing is hard-coded (project-model entities in the data context -> `BackendOnly`) and can't be configured:
`backend.actions.support` / `RiderActionSupportPolicy` was tried and does **not** help. Subclassing
`GotoDeclarationAction` also fails, since its handler is rerouted further down.

What works: a GLSL-only action under **our own action id** bound to the same shortcut, which talks to glsl_analyzer
itself (`sendRequestSync`) and applies the result. Each implements the `GlslEditorAction` marker; `GlslActionPromoter`
puts them first and they disable themselves outside GLSL files, so C++ still gets the stock action.
Hover and completion go through the platform LSP client and work as-is.

**Debugging tip:** glsl_analyzer's stderr (`debug: method: '...'`) appears in the sandbox `idea.log`
(`.intellijPlatform/sandbox/.../log_runIde/idea.log`). If an LSP method never shows up there, Nova swallowed the action.

## glslang integration

- Mode follows the project **target**: OpenGL = plain mode; Vulkan = `-V --target-env vulkan1.x` (allows `set=`,
  `push_constant`). Never `-G`/`-V` for OpenGL: they enforce SPIR-V rules (`layout(location)` on every uniform) and give
  false errors on normal OpenGL shaders. SPIR-V mode writes a `.spv` on success, into the working dir by default
  (next to the shader!), so `-o` points at a temp file that is deleted.
- Plain mode can't process `#include`, so we **expand `#include` ourselves** (`expand_includes`) in both modes, which
  keeps them identical: each included file gets its own source-string number via
  `#line 1 N`, and errors with string != 0 are reported on the top-level `#include` line. Includes are resolved
  relative to the file, then the project root, then configured include dirs, and read from the editor buffer if open.
- **Defines** are inserted as `#define` lines after `#version` plus `#line` (`inject_defines`), because `-D` needs
  `-l` in plain mode. Done after include expansion so `IncludeSite` lines stay correct.
- Error source names: a number, or `stdin` for string 0 before the first `#line` (SPIR-V mode). Both are parsed.
- `#extension GL_GOOGLE_include_directive` lines are blanked (plain mode rejects them; line count is kept).
- Stage files are compiled as themselves. A bare `.glsl` (include library) is compiled **through a host**: the first
  project stage file whose include tree contains it (`find_host`, same folder first), with the library's text from
  the editor. `Expanded.strings` records each source string's path, parent and include line, and `problems_in`
  maps errors to any file in that tree: its own lines, or the `#include` line for errors in files it includes.
- glslang stops at the first undeclared identifier ("compilation terminated"): one at a time.
- Annotators must be `DumbAware`. CLion briefly re-enters dumb mode about 1.5 s after a project opens; a
  non-DumbAware annotator is skipped then and its errors only appear after the next edit.

## Bundled tools

`bundleTools` in `build.gradle.kts` downloads pinned glsl_analyzer 1.7.1 and glslang 16.6.0 for **Linux x64 and
Windows x64**, verifies SHA-256, and puts them in the plugin as `bin/<os>-x64/<exe>` plus `licenses/`.
It is wired into **every** `PrepareSandboxTask` (runIde uses `prepareSandbox_runIde`, not `prepareSandbox`).
Lookup order (`find_executable` in `GlslLsp.kt`): configured path -> bundled -> PATH -> `~/.local/bin` etc.
The executable bit is restored at runtime because plugin install doesn't keep it. glsl_analyzer is GPL-3.0,
shipped unmodified as a separate process; glslang is BSD-3 and others.

## Build

- Kotlin **2.4.21** (needed to read CLion 2026.3's metadata), `apiVersion = 2.2` so it still runs on 2025.3's stdlib.
  IntelliJ Platform Gradle Plugin 2.20.1, Gradle 9.9.1 wrapper, JDK 21 toolchain via foojay (auto-downloaded).
- `localClionPath` in `gradle.properties` builds against an installed CLion (ignored if the dir doesn't exist, then
  `clion("2025.3")` is downloaded). Compiling against 2026.3 won't catch APIs missing in 2025.3; run `verifyPlugin`.
- `./gradlew buildPlugin` -> `build/distributions/*.zip` (about 7 MB with the tools). `./gradlew runIde` for a sandbox.
- Config cache is on: lambdas in the build script must not capture script objects (copy to local vals).

## Tests

`./gradlew test`: plain JUnit 4 on pure functions, no headless IDE (fast, and independent of Nova starting up).
The IDE-facing classes are thin wrappers; the logic they call is `internal` and text-in/result-out:
`semantic_highlights`, `expand_includes`, `find_host`, `problems_in`, `inject_defines`, `parse_defines`, `glslang_args`,
`problem_range`, `rename_scope`, `rename_ranges`, `fold_regions`, `outline`, `include_at`, plus the lexer. Five tests run the real bundled glslang end to end
(OpenGL, Vulkan, defines, includes, library via host; skipped if it isn't built for the OS).
**Put new logic in a pure function and test it there.**

## Known gaps / ideas

- Breadcrumbs / sticky lines: need a nested PSI, which this plugin deliberately doesn't have.
- GLSL injection into C++ raw strings.
- Never tested on Windows or on CLion 2025.3. No macOS bundle (tools are found on PATH there).
- Rename: a local that shadows a global in another function is renamed with the global.

## Style

Akamitori's preference: snake_case for locals and private helpers where Kotlin/IntelliJ API overrides don't force
camelCase. Keep it minimal and explicit, with no framework magic beyond what the platform requires.
Kotlin gotcha already hit once: `Set<Path> + path` adds the path's *name components* (Path is Iterable); use `plusElement`.
