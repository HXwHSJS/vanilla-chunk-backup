"""Build against the installed 26.3 API, without changing the installed instance.

Run on the build host with read access to --server and write access to this directory.
Only the JDK modules and the already installed game's dependencies are used.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import shutil
import zipfile

parser = argparse.ArgumentParser()
instance = parser.add_mutually_exclusive_group(required=True)
instance.add_argument('--server', type=Path, help='Existing Fabric 26.3 dedicated server directory')
instance.add_argument('--client', type=Path, help='Existing Fabric 26.3 client version directory')
parser.add_argument('--java', default='/usr/lib/jvm/java-25-openjdk-amd64/bin/java')
parser.add_argument('--skip-core', action='store_true')
args = parser.parse_args()
root = Path(__file__).resolve().parent
build = root / 'build'
build.mkdir(exist_ok=True)
classes = build / 'classes'
if classes.exists():
    shutil.rmtree(classes)
classes.mkdir(exist_ok=True)
def entry(archive, name, data):
    info = zipfile.ZipInfo(name)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_system = 3
    info.external_attr = 0o100644 << 16
    archive.writestr(info, data)
if args.client:
    profile = json.loads((args.client / (args.client.name + '.json')).read_text(encoding='utf-8'))
    library_root = args.client.parents[1] / 'libraries'
    dependencies = [args.client / (args.client.name + '.jar')]
    dependencies += sorted((args.client / '.fabric/processedMods').glob('*.jar'))
    for library in profile['libraries']:
        relative = library.get('downloads', {}).get('artifact', {}).get('path')
        if not relative:
            parts = library['name'].split(':')
            group, name, version = parts[:3]
            classifier = '-' + parts[3] if len(parts) == 4 else ''
            relative = group.replace('.', '/') + '/' + name + '/' + version + '/' + name + '-' + version + classifier + '.jar'
        path = library_root / relative
        if path.is_file():
            dependencies.append(path)
else:
    dependencies = [args.server / 'versions/26.3/server-26.3.jar']
    dependencies += sorted((args.server / 'libraries').rglob('*.jar'))
    dependencies += sorted((args.server / '.fabric/processedMods').glob('*.jar'))
classpath = os.pathsep.join(str(path) for path in dependencies)
compiler = [args.java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-encoding', 'UTF-8', '-source', '25', '-target', '25', '-proc:none']
def run(command):
    if command[:3] == compiler[:3]:
        arguments = build / 'javac.args'
        arguments.write_text('\n'.join('"' + str(value).replace('\\', '\\\\').replace('"', '\\"') + '"' for value in command[3:]) + '\n', encoding='utf-8')
        command = command[:3] + ['@' + str(arguments)]
    result = subprocess.run(command)
    if result.returncode:
        raise SystemExit(result.returncode)
sources = sorted((root / 'src/main/java').rglob('*.java'))
run(compiler + ['-classpath', classpath, '-d', str(classes)] + [str(path) for path in sources])
version = json.loads((root / 'src/main/resources/fabric.mod.json').read_text())['version']
jar = build / ('vanilla-chunk-backup-' + version + '.jar')
with zipfile.ZipFile(jar, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
    entry(archive, 'META-INF/MANIFEST.MF', b'Manifest-Version: 1.0\nMain-Class: local.hxvia.chunkbackup.OfflineCli\n\n')
    for base in (classes, root / 'src/main/resources'):
        for path in sorted(base.rglob('*')):
            if path.is_file():
                entry(archive, path.relative_to(base).as_posix(), path.read_bytes())
report = {
    'artifact': jar.name, 'bytes': jar.stat().st_size,
    'sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
    'java': subprocess.check_output([args.java, '-version'], stderr=subprocess.STDOUT, text=True).strip(),
    'sources': {path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources},
    'resources': {path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest() for path in (root / 'src/main/resources').rglob('*') if path.is_file()},
    'dependencySha256': {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in dependencies},
}
(build / 'build-receipt.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
print(json.dumps({key: report[key] for key in ('artifact', 'bytes', 'sha256')}))
test_sources = sorted((root / 'src/test/java').rglob('*.java'))
if test_sources and not args.skip_core:
    tests = build / 'test-classes'
    tests.mkdir(exist_ok=True)
    run(compiler + ['-classpath', str(classes), '-d', str(tests)] + [str(path) for path in test_sources])
    run([args.java, '-cp', os.pathsep.join((str(classes), str(tests))), 'local.hxvia.chunkbackup.CoreTests', str(build)])
probe_sources = sorted((root / 'src/trial/java').rglob('*.java'))
if probe_sources:
    probe = build / 'probe-classes'
    probe.mkdir(exist_ok=True)
    run(compiler + ['-classpath', classpath, '-d', str(probe)] + [str(path) for path in probe_sources])
    with zipfile.ZipFile(build / 'vcb-trial-probe.jar', 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        entry(archive, 'fabric.mod.json', json.dumps({'schemaVersion': 1, 'id': 'vcb_trial_probe', 'version': '1.0.0',
            'name': 'Isolated synthetic VCB test probe', 'environment': 'server',
            'entrypoints': {'main': ['local.hxvia.chunkbackup.trial.TrialProbe']},
            'depends': {'minecraft': '26.3', 'fabricloader': '>=0.20.1', 'fabric-api': '*'}}).encode())
        for path in sorted(probe.rglob('*.class')):
            entry(archive, path.relative_to(probe).as_posix(), path.read_bytes())
