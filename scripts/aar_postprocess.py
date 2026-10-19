#!/usr/bin/env python3
"""Post-process the gomobile AAR into ca.psiphon.aar.

Mirrors the "Post-process AAR" step of .github/workflows/build.yml:
replace the manifest, add backup rules, compile PsiphonTunnel.java into
classes.jar, append proguard rules and repackage.
"""

import argparse
import os
import shutil
import subprocess
import sys
import zipfile


def extract_all(aar_path, dest):
    with zipfile.ZipFile(aar_path) as zf:
        names = zf.namelist()
        zf.extractall(dest)
    return names


def copy_into(src, dst):
    if not os.path.isfile(src):
        raise SystemExit("missing file: %s" % src)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copyfile(src, dst)


def compile_java(src_file, classes_jar, android_jar, out_dir, javac):
    os.makedirs(out_dir, exist_ok=True)
    classpath = classes_jar + os.pathsep + android_jar
    cmd = [
        javac,
        "-source", "8",
        "-target", "8",
        "-bootclasspath", android_jar,
        "-classpath", classpath,
        "-d", out_dir,
        src_file,
    ]
    print("javac:", " ".join(cmd))
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.stdout.strip():
        print(result.stdout.strip())
    if result.stderr.strip():
        print(result.stderr.strip(), file=sys.stderr)
    if result.returncode != 0:
        raise SystemExit("javac failed with exit code %d" % result.returncode)


def merge_dir_into_jar(directory, jar_path):
    added = 0
    with zipfile.ZipFile(jar_path, "a", zipfile.ZIP_DEFLATED) as zf:
        for root, _dirs, files in os.walk(directory):
            for name in files:
                full = os.path.join(root, name)
                rel = os.path.relpath(full, directory).replace(os.sep, "/")
                zf.write(full, rel)
                added += 1
    print("classes.jar += %d class files" % added)


def append_text(path, lines):
    existing = ""
    if os.path.isfile(path):
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            existing = fh.read()
    with open(path, "a", encoding="utf-8", newline="\n") as fh:
        if existing and not existing.endswith("\n"):
            fh.write("\n")
        for line in lines:
            fh.write(line + "\n")


def repackage(src_dir, out_aar):
    if os.path.exists(out_aar):
        os.remove(out_aar)
    count = 0
    with zipfile.ZipFile(out_aar, "w", zipfile.ZIP_DEFLATED) as zf:
        for root, _dirs, files in os.walk(src_dir):
            for name in files:
                full = os.path.join(root, name)
                rel = os.path.relpath(full, src_dir).replace(os.sep, "/")
                if rel.endswith(".DS_Store"):
                    continue
                zf.write(full, rel)
                count += 1
    print("wrote %s (%d entries)" % (out_aar, count))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--aar", required=True)
    parser.add_argument("--workdir", required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--backup-rules", required=True)
    parser.add_argument("--java-src", required=True)
    parser.add_argument("--android-jar", required=True)
    parser.add_argument("--javac", default="javac")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    workdir = os.path.abspath(args.workdir)
    if os.path.isdir(workdir):
        shutil.rmtree(workdir)
    os.makedirs(workdir)

    extract_all(args.aar, workdir)

    copy_into(args.manifest, os.path.join(workdir, "AndroidManifest.xml"))
    copy_into(
        args.backup_rules,
        os.path.join(workdir, "res", "xml", "ca_psiphon_psiphontunnel_backup_rules.xml"),
    )

    classes_jar = os.path.join(workdir, "classes.jar")
    if not os.path.isfile(classes_jar):
        raise SystemExit("classes.jar not found inside AAR")

    compiled = os.path.join(workdir, "compiled_classes")
    compile_java(args.java_src, classes_jar, args.android_jar, compiled, args.javac)
    merge_dir_into_jar(compiled, classes_jar)
    shutil.rmtree(compiled)

    proguard = os.path.join(workdir, "proguard.txt")
    rules = []
    if os.path.isfile(proguard):
        with open(proguard, "r", encoding="utf-8") as existing:
            for line in existing:
                rule = line.strip()
                # The core ships an invalid "-keep psi.**" line that R8 rejects.
                if rule and not rule.startswith("-keep psi.**"):
                    rules.append(rule)
    for rule in ("-keep class go.** { *; }", "-keep class psi.** { *; }",
                 "-keep class ca.psiphon.** { *; }"):
        if rule not in rules:
            rules.append(rule)
    with open(proguard, "w", encoding="utf-8", newline="\n") as out:
        out.write("\n".join(rules) + "\n")

    repackage(workdir, os.path.abspath(args.out))


if __name__ == "__main__":
    main()
