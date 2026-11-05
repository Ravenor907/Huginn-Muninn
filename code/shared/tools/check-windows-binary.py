#!/usr/bin/env python3
"""Checks the Windows portable folder for anything that looks like what malware does.

Usage: check-windows-binary.py FOLDER

Fails (exit 1, with GitHub annotations) when Huginn.exe:
  - holds text that runs scripts or hides windows (PowerShell, ExecutionPolicy, -WindowStyle Hidden...), or names the
    registry key sandbox checks read (HARDWARE\\DESCRIPTION\\System), in plain ASCII or in UTF-16 (how Qt string
    literals are stored);
  - imports Windows functions for starting programs suspended or writing into other programs
    (ResumeThread, CreateProcess, WinExec, ShellExecute, WriteProcessMemory...), for opening or ending other
    programs (OpenProcess), or for redirecting the registry (RegOverridePredefKey). Huginn starts its new copy after
    an update with Qt's QProcess, which lives in Qt6Core.dll, not in Huginn.exe.
  - imports Windows functions for writing the registry (RegCreateKey, RegSetValue, RegSetKeyValue...): the portable
    build keeps its settings in an INI file in the data folder beside Huginn.exe. Moving an older version's settings
    out of the registry once goes through Qt (Qt6Core.dll), not Huginn.exe.
  - lacks version details (VERSIONINFO) or an application manifest.
  - lacks the hardening Windows looks for: Control Flow Guard, CET shadow stack support, a random
    load address (high entropy, 64-bit) and no running code from data (DEP).
The other DLLs and EXEs (Qt, OpenSSL, FFmpeg...) are only reported, not failed on.
"""
import os
import struct
import sys

FORBIDDEN_TEXT = ["powershell", "executionpolicy", "-windowstyle", "windowstyle hidden", "expand-archive",
                  "robocopy", "cmd.exe /c", "wscript", "cscript", "mshta", "-encodedcommand", "bypass -file",
                  "hardware\\description\\system"]
FORBIDDEN_IMPORTS = {"resumethread", "createprocessa", "createprocessw", "createprocessasusera", "createprocessasuserw",
                     "winexec", "shellexecutea", "shellexecutew", "shellexecuteexa", "shellexecuteexw",
                     "writeprocessmemory", "virtualallocex", "createremotethread", "createremotethreadex",
                     "setthreadcontext", "ntunmapviewofsection", "queueuserapc", "setwindowshookexa", "setwindowshookexw",
                     "openprocess", "regoverridepredefkey", "queryfullprocessimagenamew",
                     "regcreatekeya", "regcreatekeyw", "regcreatekeyexa", "regcreatekeyexw", "regcreatekeytransacteda",
                     "regcreatekeytransactedw", "regsetvaluea", "regsetvaluew", "regsetvalueexa", "regsetvalueexw",
                     "regsetkeyvaluea", "regsetkeyvaluew"}


def find_text(data):
    low = data.lower()
    hits = []
    for word in FORBIDDEN_TEXT:
        if word.encode("ascii") in low:
            hits.append(word + " (text)")
        if word.encode("utf-16-le") in low:
            hits.append(word + " (UTF-16 text)")
    return hits


def pe_sections(data):
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    if data[pe:pe + 4] != b"PE\0\0":
        raise ValueError("not a PE file")
    nsec, = struct.unpack_from("<H", data, pe + 6)
    optsize, = struct.unpack_from("<H", data, pe + 20)
    opt = pe + 24
    magic, = struct.unpack_from("<H", data, opt)
    ddir = opt + (112 if magic == 0x20B else 96)
    sections = []
    for i in range(nsec):
        s = opt + optsize + 40 * i
        vsize, va, rawsize, raw = struct.unpack_from("<IIII", data, s + 8)
        sections.append((va, max(vsize, rawsize), raw))
    dirs = [struct.unpack_from("<II", data, ddir + 8 * i) for i in range(16)]
    return sections, dirs, magic == 0x20B


def rva_to_off(sections, rva):
    for va, size, raw in sections:
        if va <= rva < va + size:
            return raw + rva - va
    raise ValueError("RVA %x outside sections" % rva)


def cstr(data, off):
    end = data.index(b"\0", off)
    return data[off:end].decode("ascii", "replace")


def imports(data):
    sections, dirs, is64 = pe_sections(data)
    rva, size = dirs[1]
    found = []
    if not rva:
        return found
    off = rva_to_off(sections, rva)
    while True:
        oft, _, _, name_rva, ft = struct.unpack_from("<IIIII", data, off)
        if not name_rva:
            break
        dll = cstr(data, rva_to_off(sections, name_rva))
        thunk = rva_to_off(sections, oft or ft)
        step, flag = (8, 1 << 63) if is64 else (4, 1 << 31)
        while True:
            v = struct.unpack_from("<Q" if is64 else "<I", data, thunk)[0]
            if not v:
                break
            if not v & flag:
                found.append((dll, cstr(data, rva_to_off(sections, v & 0x7FFFFFFF) + 2)))
            thunk += step
        off += 20
    return found


# DllCharacteristics bits, and the one extended bit (in the debug directory) for CET shadow stacks.
HARDENING = [(0x0020, "HIGH_ENTROPY_VA"), (0x0040, "DYNAMIC_BASE"), (0x0100, "NX_COMPAT"), (0x4000, "GUARD_CF")]
EX_CET_COMPAT = 0x01
DEBUG_TYPE_EX_DLLCHARACTERISTICS = 20


def hardening(data):
    """Names of the hardening flags Huginn.exe is missing (empty when it has them all)."""
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    # DllCharacteristics sits at the same offset in 32- and 64-bit optional headers.
    flags, = struct.unpack_from("<H", data, pe + 24 + 70)
    missing = [name for bit, name in HARDENING if not flags & bit]
    sections, dirs, _ = pe_sections(data)
    rva, size = dirs[6]
    ex = 0
    if rva:
        off = rva_to_off(sections, rva)
        for i in range(size // 28):
            kind, length, _, raw = struct.unpack_from("<IIII", data, off + 28 * i + 12)
            if kind == DEBUG_TYPE_EX_DLLCHARACTERISTICS and length >= 4:
                ex |= struct.unpack_from("<I", data, raw)[0]
    if not ex & EX_CET_COMPAT:
        missing.append("CET_COMPAT")
    return missing


def resource_types(data):
    sections, dirs, _ = pe_sections(data)
    rva, _ = dirs[2]
    if not rva:
        return set()
    base = rva_to_off(sections, rva)
    named, ids = struct.unpack_from("<HH", data, base + 12)
    types = set()
    for i in range(named + ids):
        name, _ = struct.unpack_from("<II", data, base + 16 + 8 * i)
        if not name & 0x80000000:
            types.add(name)
    return types


def annotate(level, title, msg):
    print("::%s title=%s::%s" % (level, title, msg.replace("%", "%25").replace("\n", "%0A")))


def main():
    folder = sys.argv[1]
    bad = 0
    main_exe = os.path.join(folder, "Huginn.exe")
    for root, _, files in os.walk(folder):
        for f in sorted(files):
            if not f.lower().endswith((".exe", ".dll")):
                continue
            path = os.path.join(root, f)
            rel = os.path.relpath(path, folder)
            with open(path, "rb") as fh:
                data = fh.read()
            ours = os.path.normcase(os.path.abspath(path)) == os.path.normcase(os.path.abspath(main_exe))
            hits = find_text(data)
            if ours:
                try:
                    hits += ["imports %s!%s" % (d, fn) for d, fn in imports(data) if fn.lower() in FORBIDDEN_IMPORTS]
                    types = resource_types(data)
                    if 16 not in types:
                        hits.append("no VERSIONINFO resource")
                    if 24 not in types:
                        hits.append("no application manifest")
                    hits += ["not built with %s" % name for name in hardening(data)]
                except (ValueError, struct.error) as e:
                    hits.append("unreadable PE: %s" % e)
            if hits and ours:
                annotate("error", "Huginn.exe looks like malware here", "; ".join(hits))
                bad = 1
            elif hits:
                print("note: %s: %s" % (rel, "; ".join(hits)))
            elif ours:
                print("Huginn.exe: no script, hidden-window, process-injection or registry-writing traces; has version details and a manifest; "
                      "built with CFG, CET, ASLR (high entropy) and DEP")
    if not os.path.exists(main_exe):
        annotate("error", "Binary check", "No Huginn.exe at the top of " + folder)
        bad = 1
    return bad


if __name__ == "__main__":
    sys.exit(main())
