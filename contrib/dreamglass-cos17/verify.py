"""Verify the reference contribution; never installs, builds or contacts a device."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess


def normalized(data):
    return data.decode("utf-8").replace("\r\n", "\n").encode("utf-8")


def digest(data):
    return hashlib.sha256(normalized(data)).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-tree", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    manifest = json.loads((root / "source-manifest.json").read_text(encoding="utf-8"))
    checked = 0
    originals = {}
    for entry in manifest["files"]:
        target = (root / entry["path"]).resolve()
        if not target.is_relative_to(root):
            raise ValueError("Manifest path escapes contribution directory")
        actual = target.read_bytes()
        if digest(actual) != entry["sha256Lf"]:
            raise ValueError("Changed or missing reference file: " + entry["path"])
        if args.source_tree and entry.get("sourcePath"):
            original = subprocess.check_output(
                ["git", "show", manifest["sourceCommit"] + ":" + entry["sourcePath"]],
                cwd=args.source_tree,
            )
            if normalized(actual) != normalized(original):
                raise ValueError("Does not match source commit: " + entry["path"])
        checked += 1
    sections = json.loads((root / "cloud/excerpts-index.json").read_text(encoding="utf-8"))
    excerpt = normalized((root / "cloud/BlurDrawHook.excerpts.kt.txt").read_bytes())
    if args.source_tree:
        for section in sections:
            path = section["sourcePath"]
            if path not in originals:
                originals[path] = normalized(subprocess.check_output(
                    ["git", "show", manifest["sourceCommit"] + ":" + path], cwd=args.source_tree,
                )).decode("utf-8").splitlines(keepends=True)
            body = "".join(originals[path][section["firstLine"] - 1:section["lastLine"]]).encode("utf-8")
            if digest(body) != section["sha256"] or body not in excerpt:
                raise ValueError("Changed or incomplete excerpt: " + section["name"])
    print(json.dumps({"passed": True, "referenceFiles": checked,
                      "cloudMembers": len(sections), "sourceCommit": manifest["sourceCommit"],
                      "sourceCompared": args.source_tree is not None,
                      "runtimeIntegrated": False, "physicalDeviceTested": False}))


if __name__ == "__main__":
    main()
