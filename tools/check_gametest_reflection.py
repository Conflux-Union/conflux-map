#!/usr/bin/env python3
"""Compile-time audit of reflection probes in gametest sources.

The UI smoke gametests read screen internals through a stringly-typed
``field(owner, "name")`` helper, so a renamed or relocated field compiles
fine and only blows up in the gametest lane. This tool parses the
preprocessed gametest and main sources of one version, resolves every
reflected field path against the production class index, and fails the
build listing where each missing name actually lives.

Usage:
    python3 tools/check_gametest_reflection.py \
        --gametest versions/<v>/build/preprocessed/gametest/java \
        --main versions/<v>/build/preprocessed/main/java

Exit codes: 0 ok, 1 finding, 2 usage/IO error.
"""

import argparse
import re
import sys
from pathlib import Path

FIELD_HELPER = "field"
IDENTIFIERS_RE = re.compile(r"\b[A-Z]\w*\b")
STRING_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')
LITERAL_RE = re.compile(r'"(?:[^"\\]|\\.)*"')
FIELD_LINE_RE = re.compile(
    r"^\s*(?:(?:private|protected|public|static|final|transient|volatile)\s+)*"
    r"([\w<>\[\],.?@ ]+?)\s+([A-Za-z_]\w*(?:\s*,\s*[A-Za-z_]\w*)*)\s*(?:=\s*.+)?;\s*$"
)
NON_FIELD_KEYWORDS = {
    "for", "if", "while", "switch", "case", "return", "throw", "else",
    "try", "catch", "synchronized", "do", "assert", "break", "continue",
    "finally", "new", "this", "super",
}
TYPE_DECL_RE = re.compile(r"\b(?:class|interface|enum)\s+([A-Za-z_]\w*)")
RECORD_DECL_RE = re.compile(r"\brecord\s+([A-Za-z_]\w*)")
EXTENDS_RE = re.compile(r"\bextends\s+([A-Za-z_][\w.]*)")


def strip_noise(line: str) -> str:
    """Drop // comments while respecting string literals."""
    out = []
    in_string = False
    i = 0
    while i < len(line):
        ch = line[i]
        if in_string:
            out.append(ch)
            if ch == "\\" and i + 1 < len(line):
                out.append(line[i + 1])
                i += 2
                continue
            if ch == '"':
                in_string = False
        else:
            if ch == '"':
                in_string = True
                out.append(ch)
            elif line[i : i + 2] == "//":
                break
            else:
                out.append(ch)
        i += 1
    return "".join(out)


def brace_delta(text: str) -> int:
    return text.count("{") - text.count("}")


def balance(text: str, open_idx: int) -> str:
    """Return the balanced parenthesized expression starting at text[open_idx] == '('."""
    depth = 0
    in_string = False
    i = open_idx
    while i < len(text):
        ch = text[i]
        if in_string:
            if ch == "\\":
                i += 1
            elif ch == '"':
                in_string = False
        elif ch == '"':
            in_string = True
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return text[open_idx : i + 1]
        i += 1
    raise ValueError(f"unbalanced parentheses at offset {open_idx}")


def split_top_level_commas(args: str) -> list:
    parts = []
    depth = 0
    in_string = False
    start = 0
    i = 0
    while i < len(args):
        ch = args[i]
        if in_string:
            if ch == "\\":
                i += 1  # the escaped character cannot end the string
            elif ch == '"':
                in_string = False
        elif ch == '"':
            in_string = True
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        elif ch == "," and depth == 0:
            parts.append(args[start:i].strip())
            start = i + 1
        i += 1
    parts.append(args[start:].strip())
    return [p for p in parts if p]


def parse_field_calls(text: str, path_hint: str):
    """Find every field(...) call; return entries {line, literals, subs}."""
    calls = []
    needle = FIELD_HELPER + "("
    pos = 0
    while True:
        idx = text.find(needle, pos)
        if idx < 0:
            break
        pos = idx + len(needle)
        line = text.count("\n", 0, idx) + 1
        try:
            expr = balance(text, idx + len(FIELD_HELPER))
        except ValueError:
            print(f"warning: unbalanced {FIELD_HELPER}() in {path_hint}:{line}", file=sys.stderr)
            continue
        parts = split_top_level_commas(expr[1:-1])
        entry = {"line": line, "literals": [], "subs": []}
        for part in parts:
            if LITERAL_RE.fullmatch(part):
                entry["literals"].append(STRING_RE.fullmatch(part).group(1))
                continue
            sub_idx = part.find(needle)
            if sub_idx >= 0:
                offset = text.find(part, idx)
                if offset >= 0:
                    entry["subs"].extend(
                        parse_field_calls(text[offset : offset + len(part)], path_hint)
                    )
        if entry["literals"]:
            calls.append(entry)
        pos = idx + len(expr)
    return calls


def probe_paths(calls):
    """Flatten nested probes into linear [name, ...] paths with the outermost line."""
    paths = []
    for entry in calls:
        inner = probe_paths(entry["subs"])
        own = entry["literals"]
        if inner and own:
            for _, prefix in inner:
                paths.append((entry["line"], prefix + own))
        elif own:
            paths.append((entry["line"], own))
    return paths


def parse_main_sources(main_dir: Path):
    """Build {class: {field: [type texts]}}, extends map and nested-class map."""
    fields = {}
    extends = {}
    nested = {}
    for path in sorted(main_dir.rglob("*.java")):
        top = path.stem
        fields.setdefault(top, {})
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        stack = []  # innermost first: (class name, body depth)
        depth = 0
        for line in lines:
            leading = depth
            no_strings = re.sub(r'"(?:[^"\\]|\\.)*"', '""', strip_noise(line))
            decl = TYPE_DECL_RE.search(no_strings)
            record = RECORD_DECL_RE.search(no_strings)
            active = None
            if decl or record:
                name = (decl or record).group(1)
                fields.setdefault(name, {})
                if stack:
                    nested.setdefault(stack[-1][0], []).append(name)
                stack.append((name, leading + 1))
                active = name
                ext = EXTENDS_RE.search(no_strings)
                if ext and decl:
                    extends[name] = ext.group(1).split(".")[-1]
            else:
                while stack and stack[-1][1] > leading:
                    stack.pop()
                if stack:
                    active = stack[-1][0]
            if active and stack and leading >= stack[-1][1]:
                match = FIELD_LINE_RE.match(no_strings)
                if match:
                    mods = match.group(1).strip().split()
                    if mods and mods[0] not in NON_FIELD_KEYWORDS:
                        target = fields[active]
                        for name in re.split(r",\s*", match.group(2)):
                            target.setdefault(name.strip(), []).append(match.group(1))
            if record:
                try:
                    open_idx = no_strings.index("(", no_strings.find("record"))
                    paren = balance(no_strings, open_idx)
                except (ValueError, IndexError):
                    paren = None  # record header spans lines; components unknown
                if paren:
                    for component in split_top_level_commas(paren[1:-1]):
                        tokens = component.strip().split()
                        if len(tokens) >= 2:
                            fields[record.group(1)].setdefault(tokens[-1], []).append(tokens[-2])
            depth += brace_delta(no_strings)
            while stack and stack[-1][1] > depth:
                stack.pop()
    return fields, extends, nested


def parse_gametest_sources(gametest_dir: Path):
    """Yield (relative path, text) for preprocessed gametest sources."""
    for path in sorted(gametest_dir.rglob("*.java")):
        yield path.relative_to(gametest_dir), path.read_text(encoding="utf-8", errors="replace")


def domain_closure(seed, fields, extends, nested):
    """Grow the test's visible class set along field-type, extends and nested edges."""
    all_classes = set(fields)
    domain = set(seed)
    changed = True
    while changed:
        changed = False
        for cls in list(domain):
            additions = set(nested.get(cls, []))
            sup = extends.get(cls)
            if sup:
                additions.add(sup)
            for type_texts in fields.get(cls, {}).values():
                for type_text in type_texts:
                    additions |= set(IDENTIFIERS_RE.findall(type_text))
            additions &= all_classes
            fresh = additions - domain
            if fresh:
                domain |= fresh
                changed = True
    return domain


def hierarchy_fields(cls, fields, extends):
    """Field names visible on `cls` through its extends chain within main."""
    names = set()
    seen = set()
    cur = cls
    while cur and cur in fields and cur not in seen:
        seen.add(cur)
        names |= set(fields[cur])
        cur = extends.get(cur)
    return names


def owners_of(name, classes, fields, extends):
    """Classes among `classes` whose hierarchy declares `name`."""
    owners = []
    for cls in classes:
        if name in hierarchy_fields(cls, fields, extends):
            owners.append(cls)
    return owners


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gametest", required=True, type=Path)
    parser.add_argument("--main", required=True, type=Path)
    args = parser.parse_args()

    if not args.gametest.is_dir() or not args.main.is_dir():
        print(f"error: preprocessed views missing ({args.gametest}, {args.main})", file=sys.stderr)
        return 2

    fields, extends, nested = parse_main_sources(args.main)
    all_classes = set(fields)

    findings = []
    for rel, text in parse_gametest_sources(args.gametest):
        seed = set(IDENTIFIERS_RE.findall(text)) & all_classes
        domain = domain_closure(seed, fields, extends, nested)
        for line, probe in probe_paths(parse_field_calls(text, str(rel))):
            current = domain
            for i, name in enumerate(probe):
                visible = set()
                for cls in current:
                    visible |= hierarchy_fields(cls, fields, extends)
                if name not in visible:
                    where = sorted({cls for cls in all_classes if name in fields[cls]})
                    hint = f" now declared on {where}" if where else " — no declaration anywhere in main"
                    findings.append(
                        f"{rel}:{line}: reflected path {probe}\n"
                        f"    '{name}' is not reachable from this test's screens{hint}"
                    )
                    break
                if i + 1 < len(probe):
                    owners = owners_of(name, current, fields, extends)
                    types = set()
                    for cls in owners:
                        for type_text in fields[cls].get(name, []):
                            types |= set(IDENTIFIERS_RE.findall(type_text))
                    resolvable = types & all_classes
                    current = resolvable if resolvable else domain

    if findings:
        print(f"gametest reflection audit failed ({len(findings)} finding(s)):")
        for finding in findings:
            print("  " + finding)
        return 1
    print("gametest reflection audit: all reflected field paths resolve")
    return 0


if __name__ == "__main__":
    sys.exit(main())
