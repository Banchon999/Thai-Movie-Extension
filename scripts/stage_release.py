"""Validate compiled outputs, then generate a repository manifest for this fork."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
from urllib.parse import urlparse
from zipfile import ZipFile


def stage(root: Path, repository: str, output: Path) -> None:
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("repository must be owner/repo")
    entries = json.loads((root / "build/plugins.json").read_text())
    if not isinstance(entries, list) or not entries:
        raise ValueError("plugins.json must contain at least one compiled plugin")
    prefix = f"https://raw.githubusercontent.com/{repository}/builds/"
    artifacts = []
    for entry in entries:
        url = entry["url"]
        if not url.startswith(prefix):
            raise ValueError("Plugin URL does not match this repository: " + url)
        filename = urlparse(url).path.rsplit("/", 1)[-1]
        if filename != "TwentyFiveHD.cs3":
            raise ValueError("Unexpected plugin filename: " + filename)
        source = root / "TwentyFiveHD/build" / filename
        with ZipFile(source) as archive:
            if archive.testzip() is not None:
                raise ValueError("Corrupt plugin archive")
            if not {"classes.dex", "manifest.json"}.issubset(archive.namelist()):
                raise ValueError("CS3 does not contain compiled classes.dex and manifest.json")
            manifest = json.loads(archive.read("manifest.json"))
            if not manifest.get("pluginClassName"):
                raise ValueError("Plugin entry point is missing")
            if not archive.read("classes.dex").startswith(b"dex\n"):
                raise ValueError("Plugin does not contain Android DEX data")
        for path, size_key, hash_key in [(source, "fileSize", "fileHash")]:
            if entry.get(size_key) != path.stat().st_size:
                raise ValueError("Compiled size mismatch")
            expected = "sha256-" + hashlib.sha256(path.read_bytes()).hexdigest()
            if entry.get(hash_key) != expected:
                raise ValueError("Compiled hash mismatch")
        artifacts.append(source)
        if entry.get("jarUrl"):
            if entry["jarUrl"] != prefix + "TwentyFiveHD.jar":
                raise ValueError("Unexpected JAR URL")
            jar = root / "TwentyFiveHD/build/TwentyFiveHD.jar"
            if entry.get("jarFileSize") != jar.stat().st_size:
                raise ValueError("JAR size mismatch")
            if entry.get("jarHash") != "sha256-" + hashlib.sha256(jar.read_bytes()).hexdigest():
                raise ValueError("JAR hash mismatch")
            artifacts.append(jar)
    # Validation completes before modifying the destination.
    output.mkdir(parents=True, exist_ok=True)
    for path in artifacts:
        shutil.copy2(path, output / path.name)
    shutil.copy2(root / "build/plugins.json", output / "plugins.json")
    manifest = {
        "name": "DEMOS · 25-HD (ทดลอง)",
        "description": "Cloudstream provider for 25-hd.com — experimental",
        "manifestVersion": 1,
        "pluginLists": [prefix + "plugins.json"],
    }
    (output / "repo.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Repository URL: " + prefix + "repo.json")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--output", type=Path, default=Path("dist"))
    args = parser.parse_args()
    stage(args.root, args.repository, args.output)
