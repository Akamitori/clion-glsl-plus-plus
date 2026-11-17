# Changelog

## Unreleased

- **Fixed: the plugin broke when glsl4idea ("GLSL Support") was installed.** Both used the language ID `GLSL`, so
  our file type failed with `ImplementationConflictException` (found in the Windows test). Our language ID is now
  `dev.akamitori.glsl` (still shown as "GLSL"), and glsl4idea (`GLSL`) and rmordechay's GLSL (`OpenGL-Plugin`), which
  claim the same extensions, are declared `incompatible-with`.
- The file type's internal name is now `dev.akamitori.glsl` too (shown as "GLSL"); the platform rejects a second
  file type named `GLSL` the same way.
- **New: a warning when some shader extensions open with another file type** (another GLSL plugin, or a mapping in
  Settings | Editor | File Types), listing which extensions and the owner. Checked once per IDE session at project open.

- **Requires CLion 2026.2 or newer** (was 2025.3). Moved to the LspIntegrationProvider / LspClient API introduced
  in 2026.2; no deprecated, experimental or scheduled-for-removal API left. `verifyPlugin` passes on 2026.2 and 2026.3.
- Bundled-tool and PATH lookup no longer use internal or deprecated platform helpers.
- **All errors at once**: glslang used to stop at the first error of any kind; it now reports every error in the
  file (except after a syntax error). Falls back to first-error-only if glslang's error recovery crashes.
- **Breadcrumbs**: the editor bar shows the enclosing function, struct or block (and member).
- **Shadowing**: rename no longer renames a local that happens to share a global's name, and such a local is no
  longer coloured as the global.

## 0.9.0

First feature-complete version. Pending before 1.0.0: a test install on Windows.

- **Navigation**: Go to Declaration (Ctrl+B / Ctrl+click) with a Ctrl+hover underline, also on `#include` paths;
  hover docs; completion; Reformat Code (Ctrl+Alt+L). Via glsl_analyzer.
- **Rename** (Shift+F6) and **Find Usages** (Alt+F7) for functions, variables, parameters, struct and block members,
  and macros, across files.
- **Error checking** via glslang as you type: undeclared identifiers, type and syntax errors, `#include` resolved
  (including unsaved edits). Per-project target (OpenGL, Vulkan 1.0 to 1.4), defines and include folders.
  Include libraries (`.glsl`) are checked through a shader that includes them.
- **Highlighting**: instant lexer colors plus semantic colors for functions, parameters, globals, structs, fields,
  swizzles, macros and layout qualifiers; built-in functions get their own color in the bundled themes.
- **Editor**: folding of blocks and comments; structure view (Alt+7).
- **CLion Nova**: Go to Declaration, Rename, Find Usages and Reformat use GLSL-only actions on the usual shortcuts,
  because Nova sends the stock ones to its C++ engine.
- **Bundled tools**: glsl_analyzer 1.7.1 and glslang 16.6.0 for Linux x64 and Windows x64.
