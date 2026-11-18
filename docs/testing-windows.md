# Windows test plan (for an agent on the Windows machine)

Goal: confirm the plugin works on **Windows x64** before calling it 1.0.0. Everything here has already passed on
Linux; what's untested on Windows is the bundled `.exe` tools, Windows paths (drive letters, `\`, spaces), CRLF line
endings and PATH handling. Work through the parts in order and finish with the report in part E.

You (the agent) can do parts A, B and D yourself. Part C needs the user to click in CLion; ask them to do each step
and tell you what they see, then confirm it in the log where the table says so.

## What you need

- **CLion 2026.2 or newer** installed (the plugin refuses older versions).
- For part A: a **JDK 17+** to start Gradle (Gradle downloads JDK 21 and the bundled tools itself), network access.
- The source: a clone of this repo.
- The plugin zip: `clion-glsl-<version>.zip`, built in A3.

Paths you'll need (replace `2026.x` with the installed CLion version):

| What | Where |
|---|---|
| CLion log | `%LOCALAPPDATA%\JetBrains\CLion2026.x\log\idea.log` |
| Installed plugin | `%APPDATA%\JetBrains\CLion2026.x\plugins\clion-glsl\` |
| Per-user Gradle settings | `%USERPROFILE%\.gradle\gradle.properties` |
| Sample project | `testdata\sample-project\` in the repo |

## A. Automated checks (agent)

1. **Point the build at the installed CLion** (otherwise it downloads CLion 2026.2, several GB). Find the CLion
   install folder (it contains `product-info.json`; with Toolbox usually `%LOCALAPPDATA%\Programs\CLion` or
   `%LOCALAPPDATA%\JetBrains\Toolbox\apps\clion`) and add to `%USERPROFILE%\.gradle\gradle.properties`:
   ```
   localClionPath=C:\\path\\to\\clion
   ```
   (Backslashes doubled, it's a Java properties file. Forward slashes also work.)
2. **Run the tests** from the repo root: `gradlew.bat test`.
   - Expect **BUILD SUCCESSFUL** with **0 failures and 0 skipped**. Count them from
     `build\test-results\test\TEST-*.xml` (`tests=`, `skipped=`, `failures=` attributes).
   - A skipped test means the bundled glslang wasn't found; that's a failure for this plan.
   - The ones that matter most on Windows: `GlslSampleProjectTest` (real `glslang.exe` on the sample project, Windows
     paths, CRLF if git converted line endings) and the `real glslang ...` tests in `GlslangAnnotatorTest`.
3. **Build the plugin**: `gradlew.bat buildPlugin` and check `build\distributions\clion-glsl-*.zip` contains
   `clion-glsl/bin/windows-x64/glsl_analyzer.exe` and `glslang.exe`.
4. **The bundled tools run**:
   - `build\bundled\bin\windows-x64\glsl_analyzer.exe --version` prints `v1.7.1`.
   - `build\bundled\bin\windows-x64\glslang.exe --version` prints `Glslang Version: 11:16.6.0`.
   If Windows Defender / SmartScreen blocks either, note it: it would break the plugin for users too.
5. Optional: `gradlew.bat verifyPlugin` (downloads CLion 2026.2 for the Plugin Verifier). Expect "Compatible".

## B. Install and log checks (agent, with one restart by the user)

1. Ask the user to install the zip: CLion > Settings > Plugins > gear > **Install Plugin from Disk...** > pick the
   zip itself (not unpacked) > restart CLion. Then open `testdata\sample-project` as a project (or a copy of it in a
   folder **with a space in its path**, e.g. `C:\Users\<user>\My Shaders\sample-project`, which is a better test).
2. Ask the user to open `shader.frag` and wait a few seconds. Then read `idea.log` and confirm:
   - `starting LSP server: ...\plugins\clion-glsl\bin\windows-x64\glsl_analyzer.exe` (the **bundled** copy, not one
     found on PATH),
   - `LSP server initialized` shortly after,
   - no `ERROR` lines mentioning `dev.akamitori.glsl`, `Glsl` or `LspClient`.
   Useful: lines from glsl_analyzer itself appear as `... STDERR: debug: method: '<lsp method>'`.
3. Check that the executable lookup didn't fall back: there should be **no** "glsl_analyzer not found" notification.

## C. Manual checks (user clicks, agent confirms)

The sample project's settings (`.idea\glsl.xml`) are: target **Vulkan 1.3**, defines **`SCALE=2.0`**.

| # | Ask the user to... | Expected | Agent can confirm in idea.log |
|---|---|---|---|
| 1 | Hover `lambert` in `shader.frag` | popup with its signature | `method: 'textDocument/hover'` |
| 2 | **Ctrl+B** on `lambert` | opens `common\lighting.glsl` at `lambert` | `method: 'textDocument/definition'` without `could not find definition` after it |
| 3 | **Ctrl+click** on `"common/lighting.glsl"` in the `#include` line | opens that file | (no LSP call; it's ours) |
| 4 | Look at `shader.frag` | exactly one red underline: `undefined_thing` on line 12 | |
| 5 | Open `errors.frag` | five red underlines at once, lines 9 to 13 | |
| 6 | Open `vulkan.frag` | no errors (Vulkan syntax accepted, `SCALE` defined) | |
| 7 | In `common\lighting.glsl`, change `0.0` to `oops` | `oops` underlined, message ends with `(checked as part of shader.frag)`; undo afterwards | |
| 8 | **Shift+F6** on `lambert`, rename to `lambert2` | dialog says **3 occurrences in 3 files** (the declaration plus one call each in `shader.frag` and `vulkan.frag`); all three updated, **no new errors** even before saving; then Ctrl+Z | |
| 9 | **Alt+F7** on `lambert` | Find tool window lists the declaration and both calls | |
| 10 | Mess up indentation in `errors.frag`, **Ctrl+Alt+L** | reformatted; Ctrl+Z | `method: 'textDocument/formatting'` |
| 11 | Type `lamb` on a new line in `main()` of `shader.frag` | completion offers `lambert` | `method: 'textDocument/completion'` |
| 12 | Colours: `lambert` vs `normalize` vs `v_normal` | three different colours (function, built-in italic, global) | |
| 13 | **Alt+7**, folding arrows, breadcrumbs bar | structure tree, folding of `main()`, breadcrumb `main()` when inside it | |
| 14 | Settings > Languages & Frameworks > **GLSL**, Target -> OpenGL, OK | `vulkan.frag` now shows errors on its `set =` / `push_constant` lines; switch back to Vulkan 1.3 | |

Line endings: if the files came from a git checkout with `core.autocrlf=true` they are CRLF, which is worth
testing. Check with `git ls-files --eol testdata` (look for `w/crlf`).

## D. If something fails

- **Tools don't start** (B2/B3, A4): Defender or SmartScreen blocking unsigned `.exe`s; or the plugin folder layout.
  The lookup is `bundled_executable` / `find_executable` in `src/main/kotlin/dev/akamitori/glsl/GlslLsp.kt`
  (bundled dir is derived from the plugin jar's location via `PathManager.getJarPathForClass`).
- **Navigation lands nowhere / wrong file** (C2): URI/path conversion with drive letters; compare the URI in the
  `goto definition:` log line with the real path.
- **Errors missing or on the wrong lines** (C4-C7): run `GlslSampleProjectTest` (A2) first; it isolates glslang +
  include expansion from the IDE. Code: `GlslangAnnotator.kt` (`expand_includes`, `problems_in`).
- **Rename/format leaves stray characters**: CRLF handling; check the file's line endings.
- Capture the exact error text and the relevant `idea.log` lines for the report.

## E. Report

Reply to the user (and, if they want it, save as `docs/windows-test-results.md`) with:

- Windows version, CLion version and build (`Help > About`), whether files were CRLF.
- Part A: test totals (tests / skipped / failures), tool versions, any Defender prompts.
- Part B: the `starting LSP server:` line and any ERROR lines.
- Part C: a table of the 14 checks, pass/fail, with notes for failures.
- For each failure: what happened, the log lines, and the likely spot in the code from part D.
