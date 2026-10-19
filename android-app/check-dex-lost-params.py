"""Find parameters that D8 lost in methods with more than 256 registers.

Debug builds (D8, AGP 9.1) once lost a captured flag in QuranReader's main content lambda (361 registers, 136
parameters): three of its reads came from an unrelated register, and the review card showed with no review running.
Release builds (R8) were fine. Any method past 256 registers can hit it again, so check after a clean debug build.

For each such method in our package, every parameter's reads are counted twice:
  - in the JVM bytecode (javap -c), the loads of its local slot, leaving out a value only handed to a merge;
  - in the dex (dexdump -d), the register reads whose value, followed through moves, still comes from the
    parameter's register (forward data flow over the control-flow graph).
A parameter read fewer times in the dex than in the JVM bytecode lost a read: the dex reads some other value there.

usage: python check-dex-lost-params.py [apk] [kotlin classes dir]
  defaults: app/build/outputs/apk/debug/app-debug.apk and app/build/tmp/kotlin-classes/debug; dexdump comes from
  sdk.dir in local.properties (or ANDROID_HOME), javap from JAVA_HOME (or PATH).
  SHOW_PARAM=<index> prints every dex read and move of that parameter.
"""
import glob
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
PREFIX = "com.tunisianprayertimes."
MIN_REGS = 257

METHOD = re.compile(r"\|\[[0-9a-f]+\] (\S+?)\.([^.:]+):(\(.*)$")
INS = re.compile(r"^[0-9a-f]{6}: (?:[0-9a-f]{4} ?)+\s*\|([0-9a-f]{4}): (.*)$")
REG = re.compile(r"\bv(\d+)\b")
TARGET = re.compile(r"\b([0-9a-f]{4}) // [+-]")


def find_dexdump():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    props = os.path.join(HERE, "local.properties")
    if os.path.exists(props):
        for line in open(props, encoding="utf-8"):
            if line.startswith("sdk.dir="):
                sdk = line.split("=", 1)[1].strip().replace("\\:", ":").replace("\\\\", "\\")
    tools = sorted(glob.glob(os.path.join(sdk or "", "build-tools", "*", "dexdump*")))
    if not tools:
        sys.exit("dexdump not found: set sdk.dir in local.properties or ANDROID_HOME")
    return tools[-1]


def find_javap():
    home = os.environ.get("JAVA_HOME")
    for name in ("javap.exe", "javap"):
        if home and os.path.exists(os.path.join(home, "bin", name)):
            return os.path.join(home, "bin", name)
    found = shutil.which("javap")
    if not found:
        sys.exit("javap not found: set JAVA_HOME")
    return found


def split_params(desc):
    """JVM parameter type descriptors of a method descriptor."""
    inner = desc[1:desc.index(")")]
    out, i = [], 0
    while i < len(inner):
        j = i
        while inner[j] == "[":
            j += 1
        if inner[j] == "L":
            j = inner.index(";", j)
        out.append(inner[i:j + 1])
        i = j + 1
    return out


def size(t):
    return 2 if t in ("J", "D") else 1


def uses_defs(op, ops):
    """(uses, defs) register lists of one dex instruction."""
    r = [int(x) for x in REG.findall(ops.split("//")[0])]
    if op.startswith("invoke") or op.startswith("filled-new-array"):
        return r, []
    if op.startswith(("move-result", "move-exception", "const", "new-instance", "sget")):
        return [], r[:1]
    if op.startswith("move"):
        return r[1:2], r[:1]
    if op.startswith(("return", "monitor", "throw", "fill-array-data", "packed-switch", "sparse-switch", "if-",
                      "iput", "sput", "aput")):
        return r, []
    if op == "check-cast":
        return r[:1], []
    if op.startswith(("goto", "nop")) or op.endswith("-data"):
        return [], []
    if "/2addr" in op:
        return r[:2], r[:1]
    # aget, iget, instance-of, array-length, new-array, cmp, unops, binops, lit ops
    return r[1:], r[:1]


def jvm_loads(javap, class_file, name, desc):
    """Loads per local slot in one JVM method, and the slots ever stored to."""
    out = subprocess.run([javap, "-c", "-p", "-s", class_file], capture_output=True, text=True, encoding="utf-8",
                         errors="replace").stdout
    lines = out.splitlines()
    code = []  # (offset, instruction text)
    inside = False
    for i, line in enumerate(lines):
        if not inside:
            if re.search(r"\b%s\(" % re.escape(name), line) and i + 1 < len(lines) and \
                    lines[i + 1].strip() == "descriptor: " + desc:
                inside = True
            continue
        m = re.match(r"^\s+(\d+): (.*)$", line)
        if m:
            code.append((int(m.group(1)), m.group(2)))
        elif code and (line.strip() == "" or re.match(r"^  \S", line)):
            break
    targets = set()
    for _, ins in code:
        m = re.match(r"(?:if\w*|goto(?:_w)?)\s+(\d+)", ins)
        if m:
            targets.add(int(m.group(1)))
    loads, stores = defaultdict(int), set()
    for k, (off, ins) in enumerate(code):
        m = re.match(r"([ilfda])load(?:_(\d)\b|(?:_w)? +(\d+))", ins)
        if m:
            nxt = code[k + 1] if k + 1 < len(code) else (None, "")
            # a value handed to a merge (a store, a jump, or the next instruction is a jump target) is a move in dex
            if not re.match(r"[ilfda]store|goto", nxt[1]) and nxt[0] not in targets:
                loads[int(m.group(2) or m.group(3))] += 1
        m = re.match(r"[ilfda]store(?:_(\d)\b|(?:_w)? +(\d+))|iinc(?:_w)? +(\d+)", ins)
        if m:
            stores.add(int(m.group(1) or m.group(2) or m.group(3)))
    return loads, stores


def analyse(desc, regs, ins_count, body, catches):
    offs = [o for o, _, _ in body]
    index = {o: i for i, o in enumerate(offs)}
    params = split_params(desc)
    param_reg = []
    r = regs - ins_count
    for t in params:
        param_reg.append(r)
        r += size(t)
    origin_of_reg = {pr: p for p, pr in enumerate(param_reg)}

    succ = defaultdict(list)
    unknown_entry = set(catches)
    for i, (off, op, ops) in enumerate(body):
        nxt = offs[i + 1] if i + 1 < len(offs) else None
        if op.startswith("goto"):
            succ[off] += TARGET.findall(ops)
        elif op.startswith("if-"):
            succ[off] += TARGET.findall(ops)
            if nxt:
                succ[off].append(nxt)
        elif op.startswith(("return", "throw")) or op.endswith("-data"):
            pass
        elif nxt:
            # switch targets live in the payload: those blocks are entered with an unknown state below
            succ[off].append(nxt)
    has_pred = {s for v in succ.values() for s in v}
    for i, (off, op, ops) in enumerate(body):
        if i and off not in has_pred and not op.endswith("-data"):
            unknown_entry.add(off)

    # forward data flow: register -> frozenset of origins (a parameter index, or 'x' for anything else)
    TOP = "top"
    other = frozenset(["x"])
    state = {offs[0]: {pr: frozenset([p]) for p, pr in enumerate(param_reg)}}
    for off in unknown_entry:
        state[off] = TOP
    work = [offs[0]] + list(unknown_entry)
    while work:
        off = work.pop()
        st = state[off]
        _, op, ops = body[index[off]]
        if st is TOP:
            out = TOP
        else:
            u, d = uses_defs(op, ops)
            out = dict(st)
            if op.startswith("move") and not op.startswith(("move-result", "move-exception")) and u:
                out[d[0]] = st.get(u[0], other)
                if op.startswith("move-wide"):
                    out[d[0] + 1] = st.get(u[0] + 1, other)
            else:
                for reg in d:
                    out[reg] = other
                    if "wide" in op:
                        out[reg + 1] = other
        for s in succ[off]:
            if s not in index:
                continue
            old = state.get(s)
            if old is TOP:
                continue
            if out is TOP:
                new = TOP
            elif old is None:
                new = out
            else:
                new = {reg: old.get(reg, other) | out.get(reg, other) for reg in set(old) | set(out)}
            if new != old:
                state[s] = new
                work.append(s)

    show = os.environ.get("SHOW_PARAM")
    reads = defaultdict(int)
    unknown_reads = 0
    for off, op, ops in body:
        st = state.get(off)
        if st is None:
            continue  # unreachable
        u, _ = uses_defs(op, ops)
        if op.startswith("move") and not op.startswith(("move-result", "move-exception")):
            if show and st is not TOP and u and int(show) in st.get(u[0], ()):
                print("      move  at %s: %s %s" % (off, op, ops[:60]))
            continue  # a move is not a read of the program
        if st is TOP:
            unknown_reads += sum(1 for reg in u if reg in origin_of_reg)
            continue
        for reg in u:
            for o in st.get(reg, ()):
                if o != "x":
                    reads[o] += 1
                    if show == str(o):
                        print("      read  at %s: %s %s" % (off, op, ops[:110]))
    return params, param_reg, reads, unknown_reads


def methods_of(dexdump, dex):
    text = subprocess.run([dexdump, "-d", dex], capture_output=True, text=True, encoding="utf-8",
                          errors="replace").stdout
    cur, regs, ins, cur_regs, cur_ins = None, 0, 0, 0, 0
    body, catches = [], []
    for line in text.splitlines():
        mm = METHOD.search(line)
        if mm and re.match(r"^[0-9a-f]{6}:", line):
            if cur:
                yield cur, cur_regs, cur_ins, body, catches
            # the header (registers, ins) comes before the code, the catches after it
            cur, body, catches = (mm.group(1), mm.group(2), mm.group(3)), [], []
            cur_regs, cur_ins = regs, ins
            continue
        m = re.match(r"^\s+registers\s+: (\d+)", line)
        if m:
            regs = int(m.group(1))
        m = re.match(r"^\s+ins\s+: (\d+)", line)
        if m:
            ins = int(m.group(1))
        m = re.match(r"^\s+\S.* -> 0x([0-9a-f]{4})$", line)
        if m:
            catches.append(m.group(1))
        im = INS.match(line)
        if im and cur:
            op, _, ops = im.group(2).partition(" ")
            body.append((im.group(1), op, ops))
    if cur:
        yield cur, cur_regs, cur_ins, body, catches


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "app", "build", "outputs", "apk", "debug",
                                                             "app-debug.apk")
    classes_dir = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "app", "build", "tmp", "kotlin-classes",
                                                                     "debug")
    dexdump, javap = find_dexdump(), find_javap()
    hits = checked = 0
    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(apk) as z:
            names = [n for n in z.namelist() if re.match(r"classes\d*\.dex$", n)]
            for n in names:
                z.extract(n, tmp)
        for dex in sorted(os.path.join(tmp, n) for n in names):
            for (cls, name, desc), regs, ins, body, catches in methods_of(dexdump, dex):
                if regs < MIN_REGS or not cls.startswith(PREFIX) or not body:
                    continue
                class_file = os.path.join(classes_dir, *cls.split(".")) + ".class"
                if not os.path.exists(class_file):
                    print("?? no class file for", cls)
                    continue
                params, param_reg, reads, unknown = analyse(desc, regs, ins, body, catches)
                if sum(size(t) for t in params) != ins:
                    # an instance method: its dex ins start with `this`
                    print("?? instance method not checked:", cls, name, "registers", regs)
                    continue
                checked += 1
                loads, stores = jvm_loads(javap, class_file, name, desc)
                slot = 0
                bad = []
                for p, t in enumerate(params):
                    if slot not in stores and loads.get(slot, 0) > reads.get(p, 0):
                        bad.append((p, t, param_reg[p], loads.get(slot, 0), reads.get(p, 0)))
                    slot += size(t)
                print("%s.%s registers=%d params=%d unknown-state reads=%d lost=%d" % (
                    cls.split(".")[-1], name, regs, len(params), unknown, len(bad)))
                for p, t, reg, jl, dr in bad:
                    hits += 1
                    print("    param %d %s in v%d: %d reads in JVM, %d in dex" % (p, t, reg, jl, dr))
    print("methods past 256 registers checked: %d, lost parameter reads: %d" % (checked, hits))
    sys.exit(1 if hits else 0)


main()
