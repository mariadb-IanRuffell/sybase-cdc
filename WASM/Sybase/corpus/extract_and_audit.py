"""Extract procedure fixtures from pinned sources; report parsing/compilation without executing SQL.

C# extraction handles literal strings and simple interpolation of literal const
strings in the same source file. It never evaluates C# or unresolved expressions.
"""
import hashlib
import json
from pathlib import Path
import re
import sys
from tempfile import TemporaryDirectory
from urllib.parse import quote

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from ase_parser import mask_comments, parse_sql
from gg9_compiler import compile_script, ASECapabilityError

CS = re.compile(r'//[^\n]*|/\*.*?\*/|(?P<vp>\$?@|@\$)"(?P<v>(?:""|[^"])*)"|(?P<np>\$?)"(?P<n>(?:\\.|[^"\\])*)"', re.S)
CREATE = re.compile(r'(?im)^\s*create\s+(?:or\s+replace\s+)?proc(?:edure)?\b')
GO = re.compile(r'(?im)^\s*go\s*(?:--[^\n]*)?$')


def literals(source):
    constants = {m[1]: json.loads(m[2]) for m in re.finditer(r'const\s+string\s+(\w+)\s*=\s*("(?:\\.|[^"\\])*")\s*;', source)}
    for match in CS.finditer(source):
        if match.group('v') is not None:
            sql = match.group('v').replace('""', '"')
            interpolated = '$' in match.group('vp')
        elif match.group('n') is not None:
            try: sql = json.loads('"'+match.group('n')+'"')
            except ValueError: continue
            interpolated = bool(match.group('np'))
        else: continue
        if interpolated:
            sql = re.sub(r'\{(\w+)\}', lambda m: constants.get(m[1], m[0]), sql)
        if CREATE.search(sql):
            yield sql, source.count('\n', 0, match.start())+1, interpolated and ('{' in sql or '}' in sql)


def procedure_batches(sql):
    # All downloaded SQL fixtures use line-delimited GO batches. Comments retain offsets.
    masked = re.sub(r'--[^\n]*', lambda m: ' '*len(m[0]), mask_comments(sql))
    boundaries = [0, *(m.end() for m in GO.finditer(masked)), len(sql)]
    for start, end in zip(boundaries, boundaries[1:]):
        match = CREATE.search(masked[start:end])
        if match:
            begin = start + match.start()
            text = GO.sub('', sql[begin:end]).strip()
            yield text, sql.count('\n', 0, begin)+1


def execution_digest():
    """Invalidate saved execution evidence when the compiler, wrapper or harness changes."""
    root = HERE.parent
    example = root/'GG-9-WASM-Sybase'
    paths = [root/name for name in ('gg9_compiler.py', 'compiler.py', 'ase_parser.py', 'ase.lark', 'requirements.txt')]
    paths += [example/name for name in ('prepare.py', 'build.gradle', 'source-build.gradle', 'sql/corpus_schema.sql')]
    paths += sorted((example/'src/main/java/example/sybase').glob('*.java'))
    digest = hashlib.sha256()
    for path in paths:
        digest.update(str(path.relative_to(root)).encode() + b'\0' + path.read_bytes() + b'\0')
    return digest.hexdigest()


def artifact_digest(directory, name):
    digest = hashlib.sha256()
    for suffix in ('wasm', 'abi.json', 'bindings.json'):
        digest.update((Path(directory)/f'{name}.{suffix}').read_bytes())
    return digest.hexdigest()


def main():
    manifest = json.loads((HERE/'manifest.json').read_text())
    evidence_path = HERE/'gridgain-results.json'
    evidence = json.loads(evidence_path.read_text()).get('fixtures', {}) if evidence_path.exists() else {}
    runtime_digest = execution_digest()
    schema = HERE.parent/'GG-9-WASM-Sybase/sql/corpus_schema.sql'
    records = []
    for item in manifest['files']:
        path = HERE/item['local']
        if path.suffix not in ('.sql', '.cs'): continue
        source = path.read_text(encoding='utf-8-sig')
        candidates = literals(source) if path.suffix == '.cs' else [(source, 1, False)]
        for candidate, origin_line, unresolved in candidates:
            if unresolved:
                records.append({'source': item['local'], 'line': origin_line, 'skipped': 'unresolved C# interpolation'})
                continue
            for sql, line in procedure_batches(candidate):
                original_line = origin_line + line - 1
                digest = hashlib.sha256(sql.encode()).hexdigest()
                filename = f"{item['repository'].split('/')[0]}_{path.stem}_{original_line}_{digest[:8]}.sql"
                local = Path('procedures')/filename
                target = HERE/local
                target.parent.mkdir(parents=True,exist_ok=True)
                header = f"-- Extracted fixture; not executed.\n-- Source: {item['url']} (near line {original_line})\n-- License: {item['license']}; retain upstream license and source notices.\n"
                target.write_text(header+sql+'\nGO\n')
                record = {'file':str(local), 'source':item['local'], 'upstream_url':item['url'], 'line':original_line,
                          'license':item['license'], 'sql_sha256':digest, 'parsed':False, 'compiled':False,
                          'executed_on_ase':False, 'executed_on_gridgain':False}
                try:
                    ast=parse_sql(sql)
                    record['parsed']=True
                    record['ast_kinds']=sorted({str(n.data) for n in ast.iter_subtrees()})
                    with TemporaryDirectory() as temp:
                        record['procedures']=compile_script(target,temp,schema)
                        record['artifact_digest']=artifact_digest(temp, record['procedures'][0])
                    record['compiled']=True
                except Exception as error:
                    if isinstance(error, ASECapabilityError): record['capabilities'] = error.capabilities
                    record['diagnostic']=str(error).split('\n')[0]
                saved = evidence.get(target.stem, {})
                matches = (saved.get('source_sha256') == hashlib.sha256(target.read_bytes()).hexdigest()
                           and saved.get('runtime_digest') == runtime_digest
                           and saved.get('artifact_digest', '') == record.get('artifact_digest', ''))
                if matches:
                    record['wrapper_state'] = saved.get('state')
                    record['executed_on_gridgain'] = record['compiled'] and saved.get('state') == 'passed'
                    record['wrapper_rejection_verified'] = (not record['compiled'] and saved.get('state') == 'blocked'
                                                            and saved.get('capabilities') == record.get('capabilities'))
                    record['wrapper_checks'] = saved.get('checks', [])
                records.append(record)
    summary = {'upstream_files':len(manifest['files']), 'extracted':sum('file' in r for r in records),
               'parsed':sum(r.get('parsed',False) for r in records),
               'compiled':sum(r.get('compiled',False) for r in records),
               'executed_on_ase':0, 'executed_on_gridgain':sum(r.get('executed_on_gridgain',False) for r in records),
               'wrapper_rejections_verified':sum(r.get('wrapper_rejection_verified',False) for r in records)}
    (HERE/'coverage.json').write_text(json.dumps({'summary':summary,'procedures':records},indent=2)+'\n')
    lines=['# Downloaded ASE procedure coverage','',
           'Generated by `extract_and_audit.py`. Live results come from [gridgain-results.json](gridgain-results.json), checked against source, artifact and runtime hashes. They are not ASE equivalence tests.',
           '',f"Upstream files: {summary['upstream_files']}; extracted fixtures: {summary['extracted']}; parsed: {summary['parsed']}; compiled: {summary['compiled']}; executed on ASE/GridGain: {summary['executed_on_ase']}/{summary['executed_on_gridgain']}; verified wrapper rejections: {summary['wrapper_rejections_verified']}.",
           '', '| Fixture | Parsed | Compiled | Java wrapper | First diagnostic |', '| --- | --- | --- | --- | --- |']
    for r in records:
        if 'file' not in r: continue
        file=r['file']
        message=r.get('diagnostic','').replace('|','\\|').replace('\n',' ')
        lines.append(f"| [{Path(file).name}]({quote(file)}) | {r['parsed']} | {r['compiled']} | {r.get('wrapper_state', 'not verified')} | {message} |")
    (HERE/'COVERAGE.md').write_text('\n'.join(lines)+'\n')
    print(json.dumps(summary))


if __name__=='__main__': main()
