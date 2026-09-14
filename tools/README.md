# tools

## logo.py

Generates every launcher-icon asset from one geometric definition, so the
adaptive layers, the themed-icon monochrome layer and the legacy API 21–25
bitmaps can never drift apart.

```bash
python3 tools/logo.py app/src/main/res build/icon-preview
```

Writes:

| Output | Purpose |
| :----- | :------ |
| `drawable/ic_launcher_foreground.xml` | adaptive foreground, inside the 66dp safe zone |
| `drawable/ic_launcher_background.xml` | adaptive background gradient |
| `drawable/ic_launcher_monochrome.xml` | silhouette for Android 13+ themed icons |
| `drawable/ic_shard_mark.xml` | flat-fill mark for use inside the app |
| `mipmap-*/ic_launcher.png` | legacy square icons (API 21–25) |
| `mipmap-*/ic_launcher_round.png` | legacy round icons |
| `<out>/playstore-icon.png` | 512×512 store listing icon |

Edit the geometry and colour constants at the top of the script, re-run it, and
check the generated previews before committing.

Requires Pillow (`pip3 install Pillow`).
