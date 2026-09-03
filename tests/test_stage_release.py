import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

spec = importlib.util.spec_from_file_location("stage_release", Path(__file__).parents[1] / "scripts/stage_release.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "build").mkdir()
        (self.root / "TwentyFiveHD/build").mkdir(parents=True)
        self.cs3 = self.root / "TwentyFiveHD/build/TwentyFiveHD.cs3"
        # Synthetic archive only, never distributed as a real CS3.
        with ZipFile(self.cs3, "w") as archive:
            archive.writestr("classes.dex", b"dex\n035\x00synthetic test fixture")
            archive.writestr("manifest.json", json.dumps({"pluginClassName": "com.demos.hd25.TwentyFiveHDPlugin"}))
        self.entry = {
            "url": "https://raw.githubusercontent.com/demo/repo/builds/TwentyFiveHD.cs3",
            "fileSize": self.cs3.stat().st_size,
            "fileHash": "sha256-" + hashlib.sha256(self.cs3.read_bytes()).hexdigest(),
        }
        self.write_index()

    def write_index(self):
        (self.root / "build/plugins.json").write_text(json.dumps([self.entry]))

    def test_manifest_uses_actual_repository(self):
        module.stage(self.root, "demo/repo", self.root / "dist")
        manifest = json.loads((self.root / "dist/repo.json").read_text())
        self.assertEqual(manifest["pluginLists"], ["https://raw.githubusercontent.com/demo/repo/builds/plugins.json"])
        self.assertTrue((self.root / "dist/TwentyFiveHD.cs3").exists())

    def test_rejects_wrong_fork(self):
        with self.assertRaises(ValueError):
            module.stage(self.root, "other/fork", self.root / "dist")
        self.assertFalse((self.root / "dist").exists())

    def test_rejects_changed_binary(self):
        self.cs3.write_bytes(self.cs3.read_bytes() + b"changed")
        with self.assertRaises(ValueError):
            module.stage(self.root, "demo/repo", self.root / "dist")

    def test_rejects_source_zip_disguised_as_cs3(self):
        with ZipFile(self.cs3, "w") as archive:
            archive.writestr("source.kt", "class Provider")
        with self.assertRaises(ValueError):
            module.stage(self.root, "demo/repo", self.root / "dist")

    def test_rejects_invalid_repository(self):
        with self.assertRaises(ValueError):
            module.stage(self.root, "../a/b", self.root / "dist")


if __name__ == "__main__":
    unittest.main()
