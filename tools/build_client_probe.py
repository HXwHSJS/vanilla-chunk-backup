"""Build the isolated client test helper, which is never part of the release JAR."""
from pathlib import Path
import argparse
import json
import os
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--java', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
build = root / 'build'
dependencies = json.loads((build / 'build-receipt.json').read_text())['dependencySha256']
classes = build / 'client-probe-classes'
classes.mkdir(exist_ok=True)
for previous in classes.rglob('*.class'):
    previous.unlink()
options = ['-encoding', 'UTF-8', '-proc:none', '-source', '25', '-target', '25',
           '-classpath', os.pathsep.join([str(build / 'classes'), *dependencies]), '-d', str(classes)]
options += [str(path) for path in (root / 'src/clientTest/java').rglob('*.java')]
arguments = build / 'client-probe-javac.args'
arguments.write_text('\n'.join('"' + str(value).replace('\\', '\\\\').replace('"', '\\"') + '"' for value in options), encoding='utf-8')
subprocess.run([args.java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '@' + str(arguments)], check=True)
with zipfile.ZipFile(build / 'vcb-client-smoke.jar', 'w', compression=zipfile.ZIP_DEFLATED) as jar:
    jar.writestr('fabric.mod.json', json.dumps({
        'schemaVersion': 1, 'id': 'vcb_client_smoke', 'version': '1.0.0',
        'environment': 'client', 'entrypoints': {'client': ['local.hxvia.chunkbackup.clienttest.ClientSmoke']},
        'mixins': ['vcb_client_smoke.mixins.json'],
        'depends': {'vanilla_chunk_backup': '*', 'fabric-api': '*'}}))
    jar.writestr('vcb_client_smoke.mixins.json', json.dumps({
        'required': True, 'package': 'local.hxvia.chunkbackup.clienttest.mixin', 'compatibilityLevel': 'JAVA_25',
        'client': ['HiddenWindowMixin', 'NoMouseCaptureMixin'], 'injectors': {'defaultRequire': 1}}))
    for path in classes.rglob('*.class'):
        jar.write(path, path.relative_to(classes).as_posix())
print('Isolated client helper built')
