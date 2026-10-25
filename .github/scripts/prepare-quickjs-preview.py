#!/usr/bin/env python3
"""Add the pinned QuickJS x86_64 library to the disposable emulator preview APK."""

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.request
import zipfile


WRAPPER_VERSION = "3.2.3"
WRAPPER_SHA256 = "b1d0c550818f07e411b6e6f0eb21b28d83ed2e227d6129395be9e5a2167f4f0c"
LIBRARY_SHA256 = "3728422a3a48450372e0e528da3c75da74e7e86825eeb766b2d814e54e5015d1"
LIBRARY = "libquickjs-android-wrapper.so"


def run(*args):
    return subprocess.check_output([str(arg) for arg in args], text=True)


def main():
    versions = Path("gradle/libs.versions.toml").read_text()
    if not re.search(r'^quickjs\s*=\s*"' + re.escape(WRAPPER_VERSION) + r'"\s*$', versions, re.M):
        raise SystemExit("Update the emulator QuickJS library pin when changing the app dependency")
    metadata_file = Path("app/build/outputs/apk/leanbackArm64_v8a/preview/output-metadata.json")
    metadata = json.loads(metadata_file.read_text())
    if not metadata["applicationId"].endswith(".preview") or len(metadata["elements"]) != 1:
        raise SystemExit("Only an isolated, single-output preview APK may be modified")
    apk = metadata_file.parent / metadata["elements"][0]["outputFile"]
    build_tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/37.0.0"
    keystore = Path.home() / ".android/debug.keystore"
    if not keystore.is_file():
        raise SystemExit("The preview must use the Android debug signing key")

    with tempfile.TemporaryDirectory(prefix="tv-quickjs-preview-") as temporary:
        directory = Path(temporary)
        wrapper = directory / "quickjs.aar"
        url = ("https://repo.maven.apache.org/maven2/wang/harlon/quickjs/wrapper-android/"
               f"{WRAPPER_VERSION}/wrapper-android-{WRAPPER_VERSION}.aar")
        with urllib.request.urlopen(url, timeout=60) as response:
            content = response.read(16 * 1024 * 1024 + 1)
        if hashlib.sha256(content).hexdigest() != WRAPPER_SHA256:
            raise SystemExit("QuickJS wrapper checksum mismatch")
        wrapper.write_bytes(content)
        with zipfile.ZipFile(wrapper) as bundle:
            library = bundle.read("jni/x86_64/" + LIBRARY)
        if hashlib.sha256(library).hexdigest() != LIBRARY_SHA256:
            raise SystemExit("QuickJS x86_64 library checksum mismatch")

        unsigned = directory / "preview-unsigned.apk"
        library_entry = "lib/x86_64/" + LIBRARY
        with zipfile.ZipFile(apk) as source, zipfile.ZipFile(unsigned, "w") as output:
            for item in source.infolist():
                if item.filename == library_entry:
                    continue
                if re.match(r"META-INF/[^/]+\.(SF|RSA|DSA|EC)$", item.filename, re.I):
                    continue
                if item.filename.upper() == "META-INF/MANIFEST.MF":
                    continue
                output.writestr(item, source.read(item.filename))
            output.writestr(library_entry, library, compress_type=zipfile.ZIP_STORED)
        aligned = directory / "preview.apk"
        run(build_tools / "zipalign", "-P", "16", "-f", "4", unsigned, aligned)
        run(build_tools / "apksigner", "sign", "--ks", keystore,
            "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android",
            "--key-pass", "pass:android", aligned)
        run(build_tools / "apksigner", "verify", aligned)
        with zipfile.ZipFile(aligned) as bundle:
            assert hashlib.sha256(bundle.read(library_entry)).hexdigest() == LIBRARY_SHA256
        apk.write_bytes(aligned.read_bytes())
    print("Prepared isolated preview with QuickJS x86_64 " + LIBRARY_SHA256)


if __name__ == "__main__":
    main()
