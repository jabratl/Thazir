#!/usr/bin/env python3
"""Smart clean-up of old HANDOFF / CHANGELOG_PREPEND files (0.0.478).

Why: every release adds HANDOFF+Thazir+<ver>.txt (100+ KB each in the old days) and CHANGELOG_PREPEND_<ver>.txt.
After 250 releases they were 15 MB of the repo. Nothing is lost by this tool, it only moves the useful part into two small files:
  - HANDOFF_INDEX.txt      one line per pruned handoff (version, size, first STATUS line) - grep it to find "when was X done"
  - CHANGELOG_ARCHIVE.txt  the text of every pruned CHANGELOG_PREPEND file (newest first) - nothing is dropped
and git history still has the full old files.

Run from the repo root:
  python3 tools/handoff_prune.py                    # DRY RUN: shows what would go and why
  python3 tools/handoff_prune.py --apply            # writes HANDOFF_INDEX.txt + CHANGELOG_ARCHIVE.txt, deletes the old files
  python3 tools/handoff_prune.py --delete-list DELETE.txt   # also writes the CI "DELETE.txt" (see build.yml: a DELETE.txt at the
                                                    # root of an uploaded zip removes those paths) - use this when the repo is
                                                    # changed by zips: put DELETE.txt + the two files into the next zip.
Options: --keep N (newest handoffs per series, default 5)  --keep-plan N (plan series, default 1)  --keep-changelogs N (default 10)
         --baseline-kb N (also keep the newest file this big in a series = last "full" handoff, default 100)  --threshold N (do nothing if fewer files would go)

What is ALWAYS kept:
  - HANDOFF.txt, HANDOFF_OLD.txt, HANDOFF_A_ACCESS.txt, HANDOFF_PLAYER.txt and any file that is the only one of its series
  - the newest --keep files of each series, and the newest "baseline" (big) file of the series
  - a file named as "Plan = <file>" in the newest 3 STATUS entries of HANDOFF.txt (the plan that is still being worked on)
  - the files of the newest --keep-changelogs versions (rollback notes) and anything listed in .handoff-keep (one glob per line)
"""
import argparse, fnmatch, os, re, sys

VER = re.compile(r"(\d+)[._](\d+)[._](\d+)")
PINNED = {"HANDOFF.txt", "HANDOFF_OLD.txt", "HANDOFF_A_ACCESS.txt", "HANDOFF_PLAYER.txt",
          "HANDOFF_INDEX.txt", "CHANGELOG_ARCHIVE.txt", "CHANGELOG.txt"}


def ver_of(name):
    m = list(VER.finditer(name))
    return tuple(int(x) for x in m[-1].groups()) if m else None


def series_of(name):
    if name.startswith("CHANGELOG_PREPEND_"):
        return "CHANGELOG_PREPEND_#.txt"  # _included variants belong to the same version
    m = list(VER.finditer(name))
    if not m:
        return name
    s = name[:m[-1].start()] + "#" + name[m[-1].end():]
    return s


def is_candidate(n):
    return (n.startswith("HANDOFF+") or n.startswith("HANDOFF_") or n.startswith("CHANGELOG_PREPEND_")) and n.endswith(".txt") and n not in PINNED


def read(p):
    with open(p, encoding="utf-8", errors="replace") as f:
        return f.read()


def first_status(text, limit=380):
    for ln in text.split("\n"):
        t = ln.strip()
        if t.startswith("STATUS ") or t.startswith("HANDOFF - "):
            return re.sub(r"\s+", " ", t)[:limit]
    for ln in text.split("\n"):
        t = ln.strip()
        if t and not set(t) <= set("=-"):
            return re.sub(r"\s+", " ", t)[:limit]
    return ""


def protected_by_master(files):
    out = set()
    if not os.path.exists("HANDOFF.txt"):
        return out
    parts = re.split(r"\s\|\s+(?=STATUS )", read("HANDOFF.txt"))[:3]
    for part in parts:
        for m in re.finditer(r"Plan\s*[=:]\s*([A-Za-z0-9+_.\-]+\.txt)", part):
            if m.group(1) in files:
                out.add(m.group(1))
    return out


def keep_globs():
    if not os.path.exists(".handoff-keep"):
        return []
    return [l.strip() for l in read(".handoff-keep").split("\n") if l.strip() and not l.startswith("#")]


def plan(args):
    files = sorted(n for n in os.listdir(".") if os.path.isfile(n) and is_candidate(n))
    allnames = set(os.listdir("."))
    keepers = {}  # name -> reason
    delete = {}   # name -> reason
    series = {}
    for n in files:
        series.setdefault(series_of(n), []).append(n)
    prot = protected_by_master(allnames)
    globs = keep_globs()
    for key, names in series.items():
        names = sorted(names, key=lambda x: (ver_of(x) or (0, 0, 0), x))
        newest_first = list(reversed(names))
        is_changelog = key.startswith("CHANGELOG_PREPEND_")
        is_plan = "PLAN" in key
        keep_n = args.keep_changelogs if is_changelog else (args.keep_plan if is_plan else args.keep)
        # CHANGELOG_PREPEND_x.txt and CHANGELOG_PREPEND_x_included.txt belong to the same version
        vers = []
        for n in newest_first:
            v = ver_of(n)
            if v not in vers:
                vers.append(v)
        keep_vers = set(vers[:keep_n])
        baseline = None
        if not is_changelog and not is_plan:
            for n in newest_first:
                if os.path.getsize(n) >= args.baseline_kb * 1024:
                    baseline = n
                    break
        for n in names:
            why = None
            if ver_of(n) in keep_vers:
                why = "newest %d of its series" % keep_n
            elif n == baseline:
                why = "baseline (last full handoff, >= %d KB)" % args.baseline_kb
            elif n in prot:
                why = "named as Plan = in a recent STATUS of HANDOFF.txt"
            elif any(fnmatch.fnmatch(n, g) for g in globs):
                why = ".handoff-keep"
            elif len(names) == 1:
                why = "only file of its series"
            if why:
                keepers[n] = why
            else:
                delete[n] = "older than the newest %d of %s" % (keep_n, key)
    return files, keepers, delete


def merge_index(delete, path="HANDOFF_INDEX.txt"):
    rows = {}
    if os.path.exists(path):
        for ln in read(path).split("\n"):
            m = re.match(r"^(\S+) \| (\S+) \|", ln)
            if m:
                rows[m.group(2)] = ln
    for n in sorted(delete):
        if n.startswith("CHANGELOG_PREPEND_"):
            continue
        v = ver_of(n)
        vs = ".".join(map(str, v)) if v else "-"
        rows[n] = "%s | %s | %dKB | %s" % (vs, n, max(1, os.path.getsize(n) // 1024), first_status(read(n)))
    head = ["# HANDOFF_INDEX.txt - one line per handoff that was pruned (tools/handoff_prune.py). The full text is in git history:",
            "#   git log --all --oneline -- 'HANDOFF+Thazir+0.0.290.txt'   then   git show <commit>:'HANDOFF+Thazir+0.0.290.txt'",
            "# version | file | size | first STATUS line"]

    def key(ln):
        m = re.match(r"^(\d+)\.(\d+)\.(\d+)", ln)
        return tuple(int(x) for x in m.groups()) if m else (0, 0, 0)

    body = sorted(rows.values(), key=key)
    return "\n".join(head + body) + "\n"


def merge_archive(delete, path="CHANGELOG_ARCHIVE.txt"):
    chunks = []
    old = read(path) if os.path.exists(path) else ""
    have = set(re.findall(r"^=== (CHANGELOG_PREPEND_\S+\.txt) ===$", old, flags=re.M))
    add = [n for n in delete if n.startswith("CHANGELOG_PREPEND_") and n not in have]
    if not add and not old:
        return None
    for n in sorted(add, key=lambda x: (ver_of(x) or (0, 0, 0), x), reverse=True):
        chunks.append("=== %s ===\n%s\n" % (n, read(n).rstrip("\n")))
    head = "# CHANGELOG_ARCHIVE.txt - the text of every pruned CHANGELOG_PREPEND_<ver>.txt, newest first (tools/handoff_prune.py).\n\n"
    body = old[len(head):] if old.startswith(head) else old
    return head + "".join(c + "\n" for c in chunks) + body


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--keep", type=int, default=5)
    ap.add_argument("--keep-plan", type=int, default=1)
    ap.add_argument("--keep-changelogs", type=int, default=10)
    ap.add_argument("--baseline-kb", type=int, default=100)
    ap.add_argument("--threshold", type=int, default=0)
    ap.add_argument("--delete-list", default="")
    ap.add_argument("--quiet", action="store_true")
    a = ap.parse_args()
    if not os.path.exists("app/build.gradle.kts"):
        sys.exit("Run this from the repo root (the folder with app/build.gradle.kts).")
    files, keepers, delete = plan(a)
    total = sum(os.path.getsize(n) for n in delete)
    if not a.quiet:
        print("handoff / changelog files: %d   keep: %d   prune: %d (%.1f MB)" % (len(files), len(keepers), len(delete), total / 1048576.0))
        for n in sorted(keepers, key=lambda x: (series_of(x), ver_of(x) or (0, 0, 0))):
            print("  KEEP  %-46s %s" % (n, keepers[n]))
        for n in sorted(delete, key=lambda x: (series_of(x), ver_of(x) or (0, 0, 0))):
            print("  PRUNE %-46s %s" % (n, delete[n]))
        stale = [n for n in ("KIT_FILES.txt", "PATCH_STEPS.txt", "REVERT.txt", "edits.patch", "edits_0.0.405.patch", "edits_0.0.406.patch",
                             "edits_0.0.407.patch", "SettingsScreen_T6.patch") if os.path.exists(n)]
        if stale:
            print("old kit / patch leftovers (NOT touched, delete by hand if you do not need them): " + ", ".join(stale))
    if len(delete) < a.threshold:
        print("fewer than %d files to prune: nothing done" % a.threshold)
        return
    if a.delete_list or a.apply:
        idx = merge_index(delete)
        arc = merge_archive(delete)
    if a.delete_list:
        with open(a.delete_list, "w", encoding="utf-8") as f:
            f.write("# written by tools/handoff_prune.py: old handoffs / changelog notes (summaries are in HANDOFF_INDEX.txt / CHANGELOG_ARCHIVE.txt)\n")
            for n in sorted(delete):
                f.write(n + "\n")
        print("wrote " + a.delete_list)
    if a.apply:
        with open("HANDOFF_INDEX.txt", "w", encoding="utf-8") as f:
            f.write(idx)
        if arc is not None:
            with open("CHANGELOG_ARCHIVE.txt", "w", encoding="utf-8") as f:
                f.write(arc)
        for n in delete:
            os.remove(n)
        print("pruned %d files, wrote HANDOFF_INDEX.txt%s" % (len(delete), " + CHANGELOG_ARCHIVE.txt" if arc is not None else ""))
    elif not a.delete_list:
        print("\nDRY RUN - nothing changed. Add --apply to do it.")


if __name__ == "__main__":
    main()
