#!/usr/bin/env python3
"""Sign a release APK and derive update metadata from that exact binary."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--apk', type=Path, required=True, help='Unsigned release APK from Gradle')
parser.add_argument('--keys', type=Path, required=True, help='Private signing directory kept outside the repository')
parser.add_argument('--out', type=Path, required=True, help='Output directory')
args = parser.parse_args()
sdk = Path(os.environ['ANDROID_HOME']) / 'build-tools' / '35.0.0'
env = os.environ.copy()
env['TAXLENS_RELEASE_PASSWORD'] = (args.keys / 'store-password.txt').read_text().strip()
if not env['TAXLENS_RELEASE_PASSWORD']:
    raise SystemExit('Release password is empty')

def run(*command):
    return subprocess.run([str(c) for c in command], env=env, check=True, capture_output=True, text=True).stdout

def info(apk):
    output = run(sdk / 'aapt', 'dump', 'badging', apk)
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", output)
    minimum = re.search(r"sdkVersion:'(\d+)'", output)
    if not match or not minimum:
        raise SystemExit('Cannot read APK metadata')
    package, code, name = match.groups()
    if package != 'io.github.taxray' or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', name):
        raise SystemExit('Unexpected release package or version')
    return dict(packageName=package, versionCode=int(code), versionName=name, minSdk=int(minimum[1]))

def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as file:
        for chunk in iter(lambda: file.read(65536), b''):
            digest.update(chunk)
    return digest.hexdigest()

metadata = info(args.apk)
args.out.mkdir(parents=True, exist_ok=True)
result = args.out / f"TaxLens-{metadata['versionName']}.apk"
if result.exists():
    raise SystemExit('Output APK already exists  use a new output directory')
with tempfile.TemporaryDirectory() as work:
    aligned = Path(work) / 'aligned.apk'
    run(sdk / 'zipalign', '-p', '-f', '4', args.apk, aligned)
    run(sdk / 'apksigner', 'sign', '--ks', args.keys / 'preview.keystore',
        '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android',
        '--next-signer', '--ks', args.keys / 'release.jks', '--ks-key-alias', 'taxlens-release',
        '--ks-pass', 'env:TAXLENS_RELEASE_PASSWORD', '--key-pass', 'env:TAXLENS_RELEASE_PASSWORD',
        '--lineage', args.keys / 'signing-lineage.bin', '--rotation-min-sdk-version', '33',
        '--out', result, aligned)
verification = run(sdk / 'apksigner', 'verify', '--verbose', '--print-certs', result)
run(sdk / 'zipalign', '-c', '-p', '4', result)
if info(result) != metadata:
    raise SystemExit('Signed APK metadata changed')
metadata.update(apkAssetName=result.name, apkSize=result.stat().st_size,
                sha256=sha256(result))
manifest = args.out / 'update.json'
manifest.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')
(args.out / 'signing-verification.txt').write_text(verification)
with (args.out / 'SHA256SUMS.txt').open('w') as sums:
    for path in (result, manifest):
        sums.write(f'{sha256(path)}  {path.name}\n')
print(json.dumps(metadata, indent=2))
