#!/usr/bin/env python3
"""Grep-based guards for OpenFlow dev-harness rules.

Each guard encodes a single MUST / MUST NOT from docs/dev-harness/<area>/rules.md
as a fast structural check against source files. Failures print:

    [<area>] <message>: <file>:<line>

and exit with code 1 if any guard fires.

Add new guards by appending to the GUARDS list at the bottom.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable


ROOT = Path(
    subprocess.check_output(["git", "rev-parse", "--show-toplevel"]).decode().strip()
)


@dataclass
class Hit:
    area: str
    message: str
    file: str
    line: int


def lines_of(rel: str) -> list[tuple[int, str]]:
    path = ROOT / rel
    if not path.exists():
        return []
    out: list[tuple[int, str]] = []
    for i, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
        out.append((i, line))
    return out


# --- Guards ---------------------------------------------------------------


def guard_cmake_subdir_order() -> Iterable[Hit]:
    """llama.cpp add_subdirectory must come before whisper.cpp."""
    rel = "app/src/main/cpp/CMakeLists.txt"
    lines = lines_of(rel)
    if not lines:
        return
    llama_line = next(
        (i for i, l in lines if "add_subdirectory(third_party/llama.cpp" in l), None
    )
    whisper_line = next(
        (i for i, l in lines if "add_subdirectory(third_party/whisper.cpp" in l), None
    )
    if llama_line is None or whisper_line is None:
        return
    if llama_line >= whisper_line:
        yield Hit(
            area="native",
            message=(
                f"CMakeLists: llama.cpp must be added before whisper.cpp "
                f"(llama@{llama_line}, whisper@{whisper_line})"
            ),
            file=rel,
            line=llama_line,
        )


def guard_abi_filter_supported_only() -> Iterable[Hit]:
    """abiFilters must only contain arm64-v8a and x86_64."""
    rel = "app/build.gradle.kts"
    lines = lines_of(rel)
    forbidden = ("armeabi-v7a", '"x86"')
    required = ('"arm64-v8a"', '"x86_64"')
    content = "\n".join(line for _, line in lines)
    abi_line = next((i for i, line in lines if "abiFilters" in line), 1)
    for i, line in lines_of(rel):
        if "abiFilters" not in line:
            continue
        for token in forbidden:
            if token in line:
                yield Hit(
                    area="native",
                    message=f"abiFilters must only contain arm64-v8a and x86_64; found {token}",
                    file=rel,
                    line=i,
                )
    for token in required:
        if token not in content:
            yield Hit(
                area="native",
                message=f"abiFilters must include {token}",
                file=rel,
                line=abi_line,
            )


def guard_fgs_type_microphone() -> Iterable[Hit]:
    """RecordingForegroundService manifest must declare foregroundServiceType=microphone."""
    rel = "app/src/main/AndroidManifest.xml"
    content = (ROOT / rel).read_text(encoding="utf-8", errors="replace") if (ROOT / rel).exists() else ""
    if "RecordingForegroundService" in content:
        # crude check: look for foregroundServiceType="microphone" within 200 chars of service decl
        m = re.search(
            r"RecordingForegroundService[^>]{0,400}foregroundServiceType=\"microphone\"",
            content,
            flags=re.DOTALL,
        )
        if not m:
            yield Hit(
                area="service",
                message="RecordingForegroundService missing foregroundServiceType=\"microphone\" in manifest",
                file=rel,
                line=1,
            )


def guard_local_lifecycle_owner_import() -> Iterable[Hit]:
    """LocalLifecycleOwner must come from androidx.lifecycle.compose, not androidx.compose.ui.platform."""
    bad = "androidx.compose.ui.platform.LocalLifecycleOwner"
    for kt in (ROOT / "app/src/main").rglob("*.kt"):
        rel = str(kt.relative_to(ROOT))
        for i, line in lines_of(rel):
            if bad in line:
                yield Hit(
                    area="ui",
                    message="LocalLifecycleOwner must be imported from androidx.lifecycle.compose",
                    file=rel,
                    line=i,
                )


def guard_no_android_util_log_in_business_code() -> Iterable[Hit]:
    """Business Kotlin code uses android.util.Log only inside service/ and audio/ packages
    (where logcat is the only practical channel). UI / domain logic should bubble errors up.
    This guard is informational for OpenFlow v0.x; tighten as project grows.
    """
    allowed_prefixes = (
        "app/src/main/java/com/hank/flow/open/service/",
        "app/src/main/java/com/hank/flow/open/audio/",
        "app/src/main/java/com/hank/flow/open/asr/",
        "app/src/main/java/com/hank/flow/open/llm/",
        "app/src/main/java/com/hank/flow/open/insertion/",
        "app/src/main/java/com/hank/flow/open/model/",
    )
    pattern = re.compile(r"\bandroid\.util\.Log\b")
    for kt in (ROOT / "app/src/main/java").rglob("*.kt"):
        rel = str(kt.relative_to(ROOT))
        if any(rel.startswith(p) for p in allowed_prefixes):
            continue
        for i, line in lines_of(rel):
            if pattern.search(line):
                yield Hit(
                    area="ui",
                    message="android.util.Log not allowed outside service/audio/asr/llm/insertion/model packages",
                    file=rel,
                    line=i,
                )


def guard_jni_function_naming() -> Iterable[Hit]:
    """JNI Java_ entries must follow Kotlin package layout com.hank.flow.open.<sub>.<Class>."""
    cpp_files = list((ROOT / "app/src/main/cpp").glob("jni_*.cpp"))
    if not cpp_files:
        return
    valid_prefix = "Java_com_hank_flow_open_"
    for cpp in cpp_files:
        rel = str(cpp.relative_to(ROOT))
        for i, line in lines_of(rel):
            m = re.search(r"\bJava_[A-Za-z0-9_]+\(", line)
            if not m:
                continue
            sym = m.group(0).rstrip("(")
            if not sym.startswith(valid_prefix):
                yield Hit(
                    area="native",
                    message=f"JNI symbol {sym} does not match com.hank.flow.open.* layout",
                    file=rel,
                    line=i,
                )


GUARDS: list[Callable[[], Iterable[Hit]]] = [
    guard_cmake_subdir_order,
    guard_abi_filter_supported_only,
    guard_fgs_type_microphone,
    guard_local_lifecycle_owner_import,
    guard_no_android_util_log_in_business_code,
    guard_jni_function_naming,
]


def main() -> int:
    hits: list[Hit] = []
    for guard in GUARDS:
        for hit in guard():
            hits.append(hit)
    if not hits:
        print("OK: no guard violations")
        return 0
    for h in hits:
        print(f"[{h.area}] {h.message}: {h.file}:{h.line}", file=sys.stderr)
    print(f"FAIL: {len(hits)} guard violation(s)", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
