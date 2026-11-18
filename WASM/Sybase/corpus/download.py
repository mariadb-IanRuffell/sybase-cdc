"""Download pinned upstream ASE test sources, without executing any SQL or C#.

Run with --verify to check the saved files against manifest.json, offline.
"""
import argparse
import hashlib
import json
from pathlib import Path
from urllib.parse import quote
from urllib.request import urlopen

HERE = Path(__file__).resolve().parent
SOURCES = [
    ('DataAction/AdoNetCore.AseClient', 'a4c53a756e9a8af4e75b7e0ec882d07863967a2f', 'Apache-2.0'),
    ('Hruodland/sap_ase_procs', 'c11a36833b379470097fe3808343044793de4f60', 'MIT; see per-file notices'),
    ('linq2db/linq2db', 'd141f5671afe0b6d2d5ab23fcd3b5ae252c2dcc9', 'MIT'),
]


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('--verify', action='store_true')
    args = cli.parse_args()
    if args.verify:
        manifest = json.loads((HERE / 'manifest.json').read_text())
        for item in manifest['files']:
            assert hashlib.sha256((HERE / item['local']).read_bytes()).hexdigest() == item['sha256'], item['local']
        print(f"Verified {len(manifest['files'])} pinned upstream files")
        return
    manifest = {'purpose': 'ASE test corpus; downloaded only, not executed or ASE-16-certified', 'files': []}
    for repo, commit, license_name in SOURCES:
        with urlopen(f'https://api.github.com/repos/{repo}/git/trees/{commit}?recursive=1', timeout=60) as response:
            tree = json.load(response)['tree']
        for item in tree:
            path = item['path']
            if item['type'] != 'blob': continue
            legal = '/' not in path and ('license' in path.lower() or 'notice' in path.lower() or path == 'README.md')
            selected = (repo.startswith('DataAction') and '/Integration/' in path and path.endswith('.cs')) or (
                repo.startswith('linq2db') and path in ['Data/Create Scripts/Sybase.sql', 'Tests/Linq/DataProvider/SybaseTests.cs',
                                                       'Data/Setup Scripts/sybase-ase.cmd', 'Build/Azure/scripts/ydbsybase.sh'])
            selected |= repo.startswith('Hruodland') and path.startswith('sql/') and path.endswith('.sql')
            if not legal and not selected: continue
            url = f'https://raw.githubusercontent.com/{repo}/{commit}/' + quote(path)
            with urlopen(url, timeout=60) as response: data = response.read()
            local = Path('upstream') / repo.split('/')[0] / path
            destination = HERE / local
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
            manifest['files'].append({'repository': repo, 'commit': commit, 'license': license_name,
                                      'source': path, 'url': url, 'local': str(local),
                                      'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)})
        print(f'Downloaded {repo}@{commit}', flush=True)
    (HERE / 'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
    print(f"Saved {len(manifest['files'])} upstream files")


if __name__ == '__main__': main()
