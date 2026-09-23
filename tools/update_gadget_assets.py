"""Download pinned official Gadget assets and record compressed/uncompressed hashes."""
import argparse
import hashlib
import json
import lzma
from pathlib import Path
import re
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--version', default='17.18.0')
parser.add_argument('--proxy')
args = parser.parse_args()
if not re.fullmatch(r'\d+\.\d+\.\d+', args.version):
    raise SystemExit('Expected a stable release version')
opener = urllib.request.build_opener(urllib.request.ProxyHandler(
    {'https': args.proxy, 'http': args.proxy} if args.proxy else None))
opener.addheaders = [('User-Agent', 'ZygiskFrida-Manager-Assets')]
with opener.open(f'https://api.github.com/repos/frida/frida/releases/tags/{args.version}', timeout=60) as response:
    release = json.load(response)
root = Path(__file__).resolve().parents[1] / 'app/src/main/assets/gadget'
root.mkdir(parents=True, exist_ok=True)
manifest = {'schemaVersion': 1, 'version': args.version, 'releaseUrl': release['html_url'], 'libraries': {}}
architectures = {'armeabi-v7a': ('arm', 1, 40), 'arm64-v8a': ('arm64', 2, 183),
                 'x86': ('x86', 1, 3), 'x86_64': ('x86_64', 2, 62)}
for abi, (arch, elf_class, machine) in architectures.items():
    name = f'frida-gadget-{args.version}-android-{arch}.so.xz'
    asset = next(item for item in release['assets'] if item['name'] == name)
    expected = asset.get('digest', '')
    if not expected.startswith('sha256:'):
        raise SystemExit(f'Missing upstream digest: {name}')
    destination = root / name
    if not destination.exists() or hashlib.sha256(destination.read_bytes()).hexdigest() != expected[7:]:
        temporary = destination.with_suffix('.download')
        with opener.open(asset['browser_download_url'], timeout=90) as response, temporary.open('wb') as output:
            while data := response.read(1024 * 1024):
                output.write(data)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != expected[7:]:
            temporary.unlink()
            raise SystemExit(f'Download digest mismatch: {name}')
        temporary.replace(destination)
    digest = hashlib.sha256()
    size = 0
    with lzma.open(destination) as source:
        header = source.read(64)
        if header[:4] != b'\x7fELF' or header[4] != elf_class or header[5] != 1 or int.from_bytes(header[18:20], 'little') != machine:
            raise SystemExit(f'Unexpected ELF architecture: {name}')
        digest.update(header)
        size += len(header)
        while data := source.read(1024 * 1024):
            digest.update(data)
            size += len(data)
    manifest['libraries'][abi] = {'asset': name, 'compressedSha256': expected[7:],
                                  'sha256': digest.hexdigest(), 'size': size,
                                  'elfClass': elf_class, 'machine': machine}
    print(f'Verified {abi}: {size} bytes')
(root / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf8')
