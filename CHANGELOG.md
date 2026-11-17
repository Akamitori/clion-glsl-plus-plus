# Changelog

## 0.9.0

First feature-complete version. Pending before 1.0.0: verification on CLion 2025.3 and on Windows.

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
