#!/usr/bin/env python3
"""Static check of Roblox API usage against an API dump (API-Dump.json).

Usage: python3 tests/api_check.py path/to/API-Dump.json

Checks
  - Instance.new("Class") uses a creatable class
  - Enum.Type.Item exists
  - game:GetService("Name") names a service
  - properties/methods/events used on variables whose class is known
    (local x = Instance.new("Class"), local x = game:GetService("Class"), workspace)
  - property tables passed to Theme.create / create / Theme.panel / Theme.label / Theme.button
  - every ":Method(" call is a Roblox method, a datatype method, or one of ours
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

dump = json.load(open(sys.argv[1]))
classes = {c["Name"]: c for c in dump["Classes"]}
enums = {e["Name"]: {i["Name"] for i in e["Items"]} for e in dump["Enums"]}


def members(name):
    out = {}
    while name and name in classes:
        for m in classes[name]["Members"]:
            out.setdefault(m["Name"], m["MemberType"])
        name = classes[name].get("Superclass")
    return out


all_methods = set()
for c in dump["Classes"]:
    for m in c["Members"]:
        if m["MemberType"] in ("Function", "Callback"):
            all_methods.add(m["Name"])

# datatype / signal / our own methods called with ":"
ALLOWED_METHODS = {
    # RBXScriptSignal / connection
    "Connect", "Once", "Wait", "Disconnect",
    # datatypes
    "Lerp", "ToHex", "Dot", "Cross", "Inverse", "ToWorldSpace", "ToObjectSpace",
    "PointToWorldSpace", "PointToObjectSpace", "GetComponents", "NextNumber",
    "NextInteger", "AddToFilter", "FuzzyEq", "Max", "Min", "Abs", "Floor", "Ceil",
    "GetEnumItems",
    # our modules
    "set", "get", "remove", "builder", "cellCenter", "standCFrame", "toCell",
    "show", "destroy", "isSolid", "next", "int", "chance", "pick", "shuffle",
    "mask", "spawnPart", "refresh", "decor", "materialize",
    "ToEulerAnglesYXZ", "ToEulerAnglesXYZ",
    # 로블록스 기본 PlayerModule (ControlModule)
    "GetControls", "GetMoveVector",
}

errors = []


def err(path, line, msg):
    errors.append(f"{path.relative_to(ROOT)}:{line}: {msg}")


def line_of(text, index):
    return text.count("\n", 0, index) + 1


def check_props(path, text, start, cls):
    """Check the keys of a { ... } table starting at text[start] == '{'."""
    depth = 0
    i = start
    keys = []
    while i < len(text):
        ch = text[i]
        if ch in "{(":
            depth += 1
        elif ch in "})":
            depth -= 1
            if depth == 0:
                break
        elif depth == 1:
            m = re.match(r"([A-Za-z_]\w*)\s*=(?!=)", text[i:])
            if m and (i == 0 or not (text[i - 1].isalnum() or text[i - 1] in "_.")):
                keys.append((m.group(1), i))
                i += m.end()
                continue
        i += 1
    known = members(cls)
    for key, pos in keys:
        if key not in known:
            err(path, line_of(text, pos), f"{cls} has no member '{key}'")


for path in sorted((ROOT / "src").rglob("*.luau")):
    text = path.read_text()
    # strip comments
    text = re.sub(r"--\[\[.*?\]\]", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    text = re.sub(r"--[^\n]*", "", text)

    for m in re.finditer(r'Instance\.new\("(\w+)"\)', text):
        c = classes.get(m.group(1))
        if not c:
            err(path, line_of(text, m.start()), f"unknown class {m.group(1)}")
        elif "NotCreatable" in (c.get("Tags") or []):
            err(path, line_of(text, m.start()), f"class {m.group(1)} is not creatable")

    for m in re.finditer(r"\bEnum\.(\w+)\.(\w+)", text):
        e, item = m.group(1), m.group(2)
        if e not in enums:
            err(path, line_of(text, m.start()), f"unknown enum {e}")
        elif item not in enums[e] and item != "GetEnumItems":
            err(path, line_of(text, m.start()), f"enum {e} has no item {item}")

    for m in re.finditer(r'GetService\("(\w+)"\)', text):
        c = classes.get(m.group(1))
        if not c or "Service" not in (c.get("Tags") or []):
            err(path, line_of(text, m.start()), f"{m.group(1)} is not a service")

    # variable -> class
    var_class = {"workspace": "Workspace"}
    for m in re.finditer(r'local\s+(\w+)(?:\s*:\s*[\w.?]+)?\s*=\s*Instance\.new\("(\w+)"\)', text):
        var_class[m.group(1)] = m.group(2)
    for m in re.finditer(r'local\s+(\w+)\s*=\s*game:GetService\("(\w+)"\)', text):
        var_class[m.group(1)] = m.group(2)

    for var, cls in var_class.items():
        known = members(cls)
        for m in re.finditer(r"(?<![\w.])" + re.escape(var) + r"\.(\w+)\s*=(?!=)", text):
            if m.group(1) not in known:
                err(path, line_of(text, m.start()), f"{cls} ({var}) has no property '{m.group(1)}'")
        for m in re.finditer(r"(?<![\w.])" + re.escape(var) + r":(\w+)\(", text):
            if m.group(1) not in known:
                err(path, line_of(text, m.start()), f"{cls} ({var}) has no method '{m.group(1)}'")
        for m in re.finditer(r"(?<![\w.])" + re.escape(var) + r"\.(\w+):Connect", text):
            if m.group(1) not in known:
                err(path, line_of(text, m.start()), f"{cls} ({var}) has no event '{m.group(1)}'")
        for m in re.finditer(r"(?<![\w.])" + re.escape(var) + r':GetPropertyChangedSignal\("(\w+)"\)', text):
            if m.group(1) not in known:
                err(path, line_of(text, m.start()), f"{cls} ({var}) has no property '{m.group(1)}'")

    for m in re.finditer(r'\bcreate\("(\w+)",\s*\{', text):
        check_props(path, text, m.end() - 1, m.group(1))
    for m in re.finditer(r"\bTheme\.panel\(\{", text):
        check_props(path, text, m.end() - 1, "Frame")
    for m in re.finditer(r"\bTheme\.label\([^,]+,\s*\{", text):
        check_props(path, text, m.end() - 1, "TextLabel")
    for m in re.finditer(r"\bTheme\.button\([^,]+,\s*\{", text):
        check_props(path, text, m.end() - 1, "TextButton")

    for m in re.finditer(r":(\w+)\(", text):
        name = m.group(1)
        if name not in all_methods and name not in ALLOWED_METHODS:
            err(path, line_of(text, m.start()), f"unknown method ':{name}('")

if errors:
    print("\n".join(errors))
    print(f"\n{len(errors)} problem(s)")
    sys.exit(1)
print("API check passed")
