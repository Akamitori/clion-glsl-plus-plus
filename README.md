# clion-glsl

GLSL support for CLion:

- **Navigation and hover docs** come from the [glsl_analyzer](https://github.com/nolanderc/glsl_analyzer) language server
  (Ctrl+B / Ctrl+click, Ctrl+hover underline, completion, formatting, `#include` awareness).
- **Error checking** (undeclared identifiers, type errors, syntax errors) comes from
  [glslang](https://github.com/KhronosGroup/glslang), the Khronos reference compiler.
- **Highlighting** comes from a built-in lexer (instant) plus a semantic pass that colors your own functions,
  parameters, globals, structs, fields, swizzles, macros and layout qualifiers, with built-in functions in their own color.

Both tools are **bundled** for Linux x64 and Windows x64, so there is nothing else to install there.

Requires CLion 2025.3 or newer.

## Install on a new machine

1. Copy `clion-glsl-<version>.zip` over (from `build/distributions/`, see below).
2. CLion > Settings > Plugins > gear icon > **Install Plugin from Disk…** > pick the zip.
3. Restart CLion, then open any `.vert`/`.frag`/`.comp`/`.glsl`… file.

That's it on Linux x64 and Windows x64. On macOS or ARM, install the tools yourself and the plugin finds them on
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

The build compiles against a locally installed CLion if `localClionPath` in `gradle.properties` points at one,
and otherwise downloads CLion 2025.3 once (several GB, cached in `~/.gradle`).

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
- **Error checking** runs on files with a stage extension only. A bare `.glsl` is treated as an include library,
  since it usually has no `#version` or `main()` and would only produce noise on its own.
  `#include "..."` / `<...>` is resolved relative to the file, then the project root, then the configured include folders.
- glslang stops at the first undeclared identifier, so those show up one at a time.

## Troubleshooting

- **No completion / navigation**: check the LSP widget in the status bar. Help > Show Log in Files, search for `glsl_analyzer`.
- **"glsl_analyzer not found" balloon**: you're on a platform without a bundled copy, or a configured path is wrong.
- **Another GLSL plugin installed**: disable it so file types don't clash.

## Licenses

The plugin bundles unmodified releases of glsl_analyzer (GPL-3.0) and glslang (BSD-3-Clause and others),
which run as separate processes. Their license texts are in the plugin's `licenses/` folder.
