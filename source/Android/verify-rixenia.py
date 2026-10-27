#!/usr/bin/env python3
"""Verify the release configuration or the actual packaged Android ELF files."""
import argparse
import json
import os
from pathlib import Path
import re
import struct
import zipfile


def native(build):
    build = Path(build)
    cache = (build / "CMakeCache.txt").read_text()
    assert "CMAKE_BUILD_TYPE:STRING=Release" in cache, "Native build must be Release"
    commands = json.loads((build / "compile_commands.json").read_text())
    checked = []
    for suffix in ("xtLib/xtDevice.cpp", "dsp56kEmu/dsp.cpp", "rixeniaNative/RiXeniaNative.cpp"):
        entry = next(c for c in commands if c["file"].endswith(suffix))
        command = entry.get("command", " ".join(entry.get("arguments", [])))
        assert "-DNDEBUG" in command and "-O0" not in command, suffix
        assert any(flag in command.split() for flag in ("-O2", "-O3", "-Ofast")), suffix
        checked.append({"source": suffix, "command": command})
    return {"build": "Release", "commands": checked}


def elf_alignment(data, name):
    assert len(data) >= 64 and data[:6] == b"\x7fELF\x02\x01", name + ": expected ELF64 little endian"
    assert struct.unpack_from("<H", data, 18)[0] == 183, name + ": expected AArch64"
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 54)
    assert phentsize >= 56 and phnum > 0
    assert phoff + phentsize * phnum <= len(data)
    alignments = []
    for i in range(phnum):
        offset = phoff + phentsize * i
        if struct.unpack_from("<I", data, offset)[0] != 1:
            continue
        file_offset, virtual_address = struct.unpack_from("<QQ", data, offset + 8)
        alignment = struct.unpack_from("<Q", data, offset + 48)[0]
        assert alignment >= 16384 and alignment & (alignment - 1) == 0, name + ": insufficient ELF page alignment"
        assert (virtual_address - file_offset) % 16384 == 0, name + ": incongruent load segment"
        alignments.append(alignment)
    assert alignments, name + ": no loadable segments"
    return alignments


def apk(path, badging):
    info = Path(badging).read_text()
    assert "package: name='com.rinoize.rixenia.v2'" in info
    assert "versionName='0.1.2'" in info
    version_code = int(re.search(r"versionCode='(\d+)'", info).group(1))
    assert version_code >= 1
    assert "application-debuggable" not in info, "APK must not be debuggable"
    assert "launchable-activity: name='com.rinoize.rixenia.MainActivity'" in info
    assert "native-code: 'arm64-v8a'" in info
    signature = Path(badging).with_name("apk-signing.txt").read_text()
    match = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})", signature)
    assert match, "Missing APK signing certificate report"
    certificate = match.group(1)
    if os.environ.get("GITHUB_EVENT_NAME") != "pull_request":
        assert certificate == "30393306ce2796ea0effb1f199c0916c0cf7d2f2e88507c5a329e2f0f3e32775", "APK was not signed with the permanent V2 key"
    libraries = {}
    with zipfile.ZipFile(path) as package:
        names = package.namelist()
        for name in ("lib/arm64-v8a/librixenia.so", "lib/arm64-v8a/libc++_shared.so"):
            assert name in names, name + ": missing from APK"
            libraries[name] = elf_alignment(package.read(name), name)
        assert not any(name.startswith("lib/") and name.split("/")[1] != "arm64-v8a" for name in names)
    return {"applicationId": "com.rinoize.rixenia.v2", "versionName": "0.1.2",
            "versionCode": version_code, "debuggable": False, "signingCertificateSha256": certificate, "elfLoadAlignments": libraries}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("native", "apk"))
    parser.add_argument("path", help="Native build directory or APK")
    parser.add_argument("--badging", help="Output of aapt dump badging, required for APK")
    parser.add_argument("--report", required=True, help="JSON report destination")
    args = parser.parse_args()
    if args.mode == "apk" and not args.badging:
        parser.error("--badging is required for APK verification")
    result = native(args.path) if args.mode == "native" else apk(args.path, args.badging)
    result["commit"] = os.environ.get("GITHUB_SHA", "local")
    Path(args.report).parent.mkdir(parents=True, exist_ok=True)
    Path(args.report).write_text(json.dumps(result, indent=2) + "\n")
    print("RiXenia " + args.mode + " verification passed")


if __name__ == "__main__":
    main()
