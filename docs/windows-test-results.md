# Windows test results

Run on 2026-11-17/18 by Claude Code following `docs/testing-windows.md`, built from commit `6ffe218`.

**Verdict: everything passes on Windows x64, after one fix.** The plugin crashed CLion on startup when
glsl4idea ("GLSL Support") was also installed (see *Failure 1*). With the fix, parts A to C all pass.
The fix is **not committed yet**: it is in `windows-fix.patch` next to this report on the USB copy.

## Environment

| | |
|---|---|
| Windows | Windows 10 Pro 22H2, build 10.0.19045, x64 |
| CLion | 2026.3.0.1, build CL-263.7307.257 (Nova), installed in `C:\Program Files\JetBrains\CLion 2025.2.1` (folder name is stale: upgraded in place) |
| JDK for Gradle | CLion's bundled JBR 25.0.4.1 (system Java is 1.8, too old) |
| Line endings | **CRLF**: `core.autocrlf=true`, `git ls-files --eol testdata` shows `i/lf w/crlf` for all sample files |
| Sample project | copy in a path **with a space**: `C:\Users\<user>\Desktop\clion-glsl-windows-test\My Shaders\sample-project` |
| Other plugins | glsl4idea "GLSL Support" 1.25 (`GLSL`) and rmordechay's "GLSL" 1.1.6 (`OpenGL-Plugin`) were installed |

## A. Automated checks

| # | Check | Result |
|---|---|---|
| A1 | `localClionPath` in `%USERPROFILE%\.gradle\gradle.properties` | set to the installed 2026.3 |
| A2 | `gradlew.bat test` | **pass**: 62 tests, 0 skipped, 0 failures (66 with the fix's new `GlslPluginXmlTest`). `GlslSampleProjectTest` (6) and all six `real glslang ...` tests passed on CRLF files |
| A3 | `gradlew.bat buildPlugin` | **pass**: zip contains `clion-glsl/bin/windows-x64/glsl_analyzer.exe` and `glslang.exe` |
| A4 | bundled tools run | **pass**: `glsl_analyzer.exe --version` = `v1.7.1`, `glslang.exe --version` = `Glslang Version: 11:16.6.0`. No Defender or SmartScreen prompt |
| A5 | `verifyPlugin` (optional) | not run (would download CLion 2026.2) |

## B. Install and log checks

First install: **fail**, see *Failure 1*. After the fix:

```
GlslLspClientDescriptor@sample-project: starting LSP server: C:\Users\<user>\AppData\Roaming\JetBrains\CLion2026.3\plugins\clion-glsl\bin\windows-x64\glsl_analyzer.exe []
GlslLspClientDescriptor@sample-project(Running;0): LSP server initialized in 0,225s, name = glsl_analyzer, version = null
```

- The **bundled** copy was used, not one from PATH.
- 0 `ERROR`/`SEVERE` lines after the restart. No "glsl_analyzer not found" notification.
- glsl_analyzer stderr showed `initialize`, `initialized`, `didOpen`, `hover` and `definition`.

## C. Manual checks

All checked by the user in CLion 2026.3.0.1 with the fixed build.

| # | Check | Result |
|---|---|---|
| 1 | Hover `lambert` | pass |
| 2 | Ctrl+B on `lambert` -> `common\lighting.glsl` | pass |
| 3 | Ctrl+click on the `#include` path | pass |
| 4 | `shader.frag`: one error, `undefined_thing` line 12 | pass |
| 5 | `errors.frag`: five errors, lines 9-13 | pass |
| 6 | `vulkan.frag`: no errors | pass |
| 7 | `oops` in `lighting.glsl`, `(checked as part of shader.frag)` | pass |
| 8 | Shift+F6 rename, 3 occurrences in 3 files | pass |
| 9 | Alt+F7 Find Usages | pass |
| 10 | Ctrl+Alt+L reformat (CRLF file) | pass |
| 11 | Completion offers `lambert` | pass |
| 12 | Three distinct colours | pass |
| 13 | Structure view, folding, breadcrumbs | pass |
| 14 | Target OpenGL -> errors in `vulkan.frag`, back to Vulkan 1.3 | pass |

CRLF, drive letters and the space in the project path caused no problems anywhere.

## Failure 1: crash when glsl4idea is installed (fixed)

**What happened.** After installing the plugin, CLion showed "Internal error" on every start, and the plugin did not work:

```
java.lang.ExceptionInInitializerError
    at dev.akamitori.glsl.GlslFileType.<init>(GlslLanguage.kt:19)
Caused by: com.intellij.diagnostic.ImplementationConflictException: Language with ID 'GLSL' is already registered:
  class glslplugin.lang.GLSLLanguage; ... plugin=PluginMainDescriptor(name=GLSL Support, id=GLSL, version=1.25 ...)
  current is class dev.akamitori.glsl.GlslLanguage ... Conflicting plugins: [dev.akamitori.glsl, GLSL]
    at dev.akamitori.glsl.GlslLanguage.<init>(GlslLanguage.kt:10)
```

**Cause.** Language IDs are global. Ours was `Language("GLSL")`, the same as glsl4idea's. It never showed up on Linux
because no other GLSL plugin was installed there. Since users are likely to switch *from* glsl4idea, this blocks 1.0.
rmordechay's plugin uses `Glsl` (no crash), but claims the same file extensions.

**Recovery on this machine.** The IDE could not reach the Plugins page, so with CLion closed the plugin folder was
replaced by the fixed build, and `GLSL` and `OpenGL-Plugin` were added to `%APPDATA%\JetBrains\CLion2026.3\disabled_plugins.txt`.

**Fix** (`windows-fix.patch`, applies to `6ffe218`):

- `GlslLanguage.kt`: language ID is now `dev.akamitori.glsl` (`GLSL_LANGUAGE_ID`), and `getDisplayName()` returns
  `"GLSL"`, so the UI is unchanged.
- `plugin.xml`: every `language="..."` attribute uses the new ID; added `<incompatible-with>GLSL</incompatible-with>` and
  `<incompatible-with>OpenGL-Plugin</incompatible-with>`, so CLion asks to disable those instead of crashing.
- New `GlslPluginXmlTest` (4 tests): registers languages with the other plugins' IDs next to ours, checks every
  `language=` in `plugin.xml` and the `incompatible-with` entries. It was confirmed to **fail** with the old ID
  (the same `ImplementationConflictException`) and pass with the new one.
- `CHANGELOG.md`: entry under Unreleased.

Still to do: run `verifyPlugin` with the fix, and check how CLion handles `incompatible-with` when a user installs
the plugin next to glsl4idea (expected: a prompt to disable one of them).
