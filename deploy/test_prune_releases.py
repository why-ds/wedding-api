import importlib.util
import os
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("pruner", Path(__file__).with_name("prune-releases.py"))
pruner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pruner)


class RetentionTest(unittest.TestCase):
    def test_preserves_active_rollback_unrelated_and_symlinks(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "releases"
            root.mkdir()
            names = [f"{i:040x}" for i in range(8)]
            for i, name in enumerate(names):
                (root / name).mkdir()
                (root / name / "artifact").write_text("test")
                os.utime(root / name, (i + 1, i + 1))
            (root / "unrelated").mkdir()
            outside = Path(temp) / "outside"
            outside.mkdir()
            (root / ("f" * 40)).symlink_to(outside, target_is_directory=True)
            pruner.prune(root, {names[0], names[1]})
            self.assertTrue((root / names[0]).exists())
            self.assertTrue((root / names[1]).exists())
            self.assertFalse((root / names[2]).exists())
            self.assertTrue(all((root / name).exists() for name in names[3:]))
            self.assertTrue((root / "unrelated").exists())
            self.assertTrue(outside.exists())
            with self.assertRaises(ValueError):
                pruner.prune(root / ("f" * 40), set())


if __name__ == "__main__":
    unittest.main()
