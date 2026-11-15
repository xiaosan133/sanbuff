#!/usr/bin/env python3
"""Add a file into an APK (ZIP archive)."""
import sys, zipfile, os

apk_path = sys.argv[1]
file_path = sys.argv[2]

# Support --arcname to set the path inside the ZIP
arcname = os.path.basename(file_path)
if len(sys.argv) >= 5 and sys.argv[3] == '--arcname':
    arcname = sys.argv[4]

with zipfile.ZipFile(apk_path, 'a', zipfile.ZIP_DEFLATED) as zf:
    # Check if entry already exists; if so, remove it first (zipfile can't overwrite directly)
    if arcname in zf.namelist():
        # Workaround: create temp zip without the old entry, then copy back
        tmp = apk_path + '.tmp'
        with zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as tmpzf:
            for name in zf.namelist():
                if name != arcname:
                    tmpzf.writestr(name, zf.read(name))
        os.replace(tmp, apk_path)
        zf = zipfile.ZipFile(apk_path, 'a', zipfile.ZIP_DEFLATED)

    zf.write(file_path, arcname)

print(f"Added {file_path} -> {arcname} in {apk_path}")
