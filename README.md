# Shard

Shard is an Android note-taking app for writing, organizing, and connecting ideas. It combines a block-based Markdown editor with wiki links, a graph view, tasks, and an infinite canvas. Notes are stored locally as plain files, and synchronization is optional.

## Features

- **Markdown editing:** headings, bulleted and numbered lists, checklists, quotes, callouts, code blocks, images, and editable tables. Switch to raw Markdown when you need direct control over the source.
- **Connected notes:** `[[wiki links]]`, backlinks, tags, aliases, and a graph for exploring relationships between notes.
- **Organization:** folders, collections, pinned notes, search, daily notes, and a calendar.
- **Tasks:** checklists, due dates, recurring tasks, and reminders.
- **Canvas:** text, note, image, and group cards, connections, freehand drawing, panning, and zooming.
- **TeX editing:** a dedicated source editor with syntax highlighting and an in-app preview. The preview uses Shard's renderer rather than a full LaTeX toolchain.
- **Import and export:** import Markdown folders and supported document files; export notes as Markdown, plain text, HTML, or PDF, and create ZIP backups.
- **Optional synchronization:** Google Drive, HTTPS Git repositories, and folders selected through Android's file picker.
- **Customization:** light and dark themes, typography settings, and an English or Ukrainian interface.
- **Home-screen widget:** shortcuts for creating notes, opening daily notes, and viewing tasks.

## Getting Started

Create a note and start typing. On an empty line, enter `/` to open the block command menu.

Common Markdown shortcuts include:

| Input | Block type |
| --- | --- |
| `# ` | Heading |
| `- ` | Bulleted list |
| `1. ` | Numbered list |
| `- [ ] ` | Checklist |
| `> ` | Quote |
| Three backticks | Code block |

Use `[[Note title]]` to link another note and `#tag` to add a tag. Long-press a block's six-dot handle to drag it. Tap the handle to open block actions.

## Storage and Backups

Markdown notes are saved as `.md` files and TeX documents as `.tex` files. The vault also contains attachments and supporting data:

```text
vault/
  notes.md
  document.tex
  folders/
  attachments/
  .shard/
```

The default vault uses Android app-specific storage. Export a vault backup before uninstalling the app or clearing its data, because Android can remove this storage with the app. Files in a separately selected synchronization folder are managed through Android's Storage Access Framework.

See [Privacy Policy](PRIVACY_POLICY.md) for details about data handling.

## Build the Android App

Requirements:

- JDK 17.
- Android SDK Platform 36 and the required build tools.
- Android Studio, or a terminal with the Android SDK configured.

Open the project root in Android Studio and allow Gradle to sync. For a command-line build, set `sdk.dir` in your local `local.properties` file to your Android SDK directory, or configure `ANDROID_HOME`.

```bash
./gradlew :app:assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

To install it on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For a Play Store release, configure release signing and generate an Android App Bundle:

```bash
./gradlew :app:bundleRelease
```

The project includes the Gradle wrapper, so a separate Gradle installation is not required.

## Tests

Run the local unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

Run instrumentation tests on a dedicated emulator or test device:

```bash
./gradlew :app:connectedDebugAndroidTest
```

Device test runs can install and remove the application. Use a disposable test environment and back up any existing vault before running them.

## Web Version

The `web/` directory contains a separate Go-based web implementation with a REST API and a responsive browser client. It stores its own notes in JSON; it does not automatically share the Android vault.

Requires Go 1.22 or later:

```bash
cd web
go run .
```

Open `http://localhost:8080` in a browser. See [Web Documentation](web/README.md) for configuration, deployment, and API details.

## Project Structure

| Directory | Purpose |
| --- | --- |
| `app/` | Android app, resources, unit tests, and instrumentation tests |
| `benchmark/` | Baseline profile generation and macrobenchmarks |
| `web/` | Go server and browser client |
| `templates/` | Sample TeX documents |
| `tools/` | Asset generation utilities |

## License

Shard is distributed under the GNU General Public License v3.0. See [LICENSE](LICENSE) for the full license text.
