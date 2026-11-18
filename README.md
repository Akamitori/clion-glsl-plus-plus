# GLSL++ for CLion

GLSL shader support for CLion, including CLion Nova:

- **Navigation and hover docs** come from the [glsl_analyzer](https://github.com/nolanderc/glsl_analyzer) language server
  (Ctrl+B / Ctrl+click, completion, formatting, `#include` awareness).
- **Error checking** (undeclared identifiers, type errors, syntax errors) comes from
  [glslang](https://github.com/KhronosGroup/glslang), the Khronos reference compiler.
- **Highlighting** comes from a built-in lexer (instant) plus a semantic pass that colors your own functions,
  parameters, globals, structs, fields, swizzles, macros and layout qualifiers, with built-in functions in their own color.

Both tools are **bundled** for Linux x64 and Windows x64, so there is nothing else to install there.

Requires CLion 2026.2 or newer.

- **Rename** (Shift+F6) and **Find Usages** (Alt+F7) across files, **folding**, **structure view** and **breadcrumbs**.

## Install

CLion > Settings > Plugins > **Marketplace**, search for **GLSL++**, install, restart.

Or build the zip yourself (see *Build*) and use Plugins > gear icon > **Install Plugin from Disk…**.

That's all on Linux x64 and Windows x64. On macOS or ARM, install the tools yourself and the plugin finds them on
`PATH` (or set their paths in Settings > Languages & Frameworks > GLSL):

```sh
# glsl_analyzer: https://github.com/nolanderc/glsl_analyzer/releases
# glslang:       https://github.com/KhronosGroup/glslang/releases  (or: brew install glslang)
```

## Build

Needs any JDK 17+ to launch Gradle. Gradle itself, JDK 21 and the bundled tools are downloaded by the build,
and the tool archives are checked against pinned SHA-256 hashes.

```sh
./gradlew buildPlugin     # Linux;   Windows: gradlew.bat buildPlugin
                          # -> build/distributions/clion-glsl-<version>.zip
./gradlew runIde          # launch a sandboxed CLion with the plugin loaded
```

The build compiles against a locally installed CLion if `localClionPath` in your **`~/.gradle/gradle.properties`**
(per machine, not in the repo) points at one, e.g. `localClionPath=/home/you/.local/share/JetBrains/Toolbox/apps/clion`,
and otherwise downloads CLion 2026.2 once (several GB, cached in `~/.gradle`).
`./gradlew verifyPlugin` checks the built plugin against CLion 2026.2 and the local CLion.

To bump a bundled tool, change its URL and SHA-256 in `bundled_files` in `build.gradle.kts`.

## Settings

Settings > Languages & Frameworks > GLSL.

**Error checking (per project, saved in `.idea/glsl.xml` so it can be committed):**

- **Target**: OpenGL (default) or Vulkan 1.0 to 1.4. Pick Vulkan if your shaders use `layout(set = …)`,
  `push_constant`, `subpassInput` and similar; in OpenGL mode those are reported as errors.
- **Defines**: e.g. `MAX_LIGHTS=8 USE_SHADOWS`, as if each shader started with those `#define`s.
- **Include folders**: `;`-separated, relative to the project root or absolute. Searched after the shader's own
  folder and the project root.

**Tools (all projects):**

- **glsl_analyzer path** / **glslangValidator path**: leave empty to use the bundled copy, then `PATH`.
  Changing the glsl_analyzer path restarts the server.

Colors: Settings > Editor > Color Scheme > GLSL.

File extensions: `.glsl .vert .frag .geom .comp .tesc .tese .mesh .task .rgen .rint .rahit .rchit .rmiss .rcall .vsh .fsh`

## Notes

- **CLion Nova** sends the stock Go to Declaration to its C++ engine, which doesn't know GLSL. The plugin binds
  its own GLSL-only action to Ctrl+B / Ctrl+click, so you'll see two actions on Ctrl+B in the keymap; that's expected.
- **Error checking** compiles stage files (`.frag`, `.vert`, `.comp`, …) as themselves. A bare `.glsl` is treated
  as an include library and checked through a shader in the project that includes it (preferring one in the same
  folder); its errors show up in the library, marked "checked as part of <shader>". A library nothing includes
  isn't checked.
  `#include "..."` / `<...>` is resolved relative to the file, then the project root, then the configured include folders.
- All errors in a file are reported at once, except after a syntax error, where glslang has to stop.

## Troubleshooting

- **No completion / navigation**: check the LSP widget in the status bar. Help > Show Log in Files, search for `glsl_analyzer`.
- **"glsl_analyzer not found" balloon**: you're on a platform without a bundled copy, or a configured path is wrong.
- **"Some shader files are not handled by this GLSL plugin"**: another GLSL plugin (or a File Types setting) owns
  those extensions. Disable the other plugin, or reassign them in Settings > Editor > File Types.

## Licenses

The plugin itself is MIT-licensed (`LICENSE`). It bundles unmodified releases of glsl_analyzer (GPL-3.0) and glslang (BSD-3-Clause and others),
which run as separate processes. Their license texts are in the plugin's `licenses/` folder.

## Development

Architecture and the non-obvious parts (CLion Nova, glslang quirks, build notes) are in `CLAUDE.md`.
Tests: `./gradlew test`. A manual test plan with a sample project: `docs/testing-windows.md`, `testdata/sample-project/`.
