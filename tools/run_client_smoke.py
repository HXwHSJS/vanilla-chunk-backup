"""Run the test-only helper in a fresh synthetic profile, using installed game libraries."""
from pathlib import Path
import argparse
import datetime
import json
import os
import platform
import shutil
import subprocess
import sys

parser = argparse.ArgumentParser()
parser.add_argument('--client', type=Path, required=True, help='Installed Fabric 26.3 version directory')
parser.add_argument('--java', required=True)
parser.add_argument('--fixture', type=Path, required=True, help='Synthetic world; never a real player save')
parser.add_argument('--timeout', type=int, default=270)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
build = root / 'build'
profile = json.loads((args.client / (args.client.name + '.json')).read_text(encoding='utf-8'))
library_root = args.client.parents[1] / 'libraries'
version = json.loads((root / 'src/main/resources/fabric.mod.json').read_text())['version']
game = build / ('client-smoke-' + datetime.datetime.now().strftime('%Y%m%d-%H%M%S'))
game.mkdir()
(game / 'SYNTHETIC-CLIENT-TEST.txt').write_text('ISOLATED SYNTHETIC TEST; NO REAL ACCOUNT OR USER WORLD\n')
shutil.copytree(args.fixture, game / 'saves/VCB_Test')
(game / 'mods').mkdir()
for artifact in (build / ('vanilla-chunk-backup-' + version + '.jar'), build / 'vcb-client-smoke.jar'):
    shutil.copy2(artifact, game / 'mods' / artifact.name)
api = list((args.client / 'mods').glob('fabric-api-*.jar'))
if len(api) != 1:
    raise SystemExit('Expected exactly one installed Fabric API')
shutil.copy2(api[0], game / 'mods' / api[0].name)
(game / 'options.txt').write_text('\n'.join([
    'version:4955', 'lang:zh_cn', 'soundCategory_master:0.0', 'renderDistance:2',
    'simulationDistance:5', 'maxFps:20', 'pauseOnLostFocus:false', 'tutorialStep:none',
    'onboardAccessibility:false', 'startedCleanly:true', 'hideServerAddress:true',
    'enableVsync:false', 'guiScale:2', 'fullscreen:false', 'chatVisibility:0',
]) + '\n')

system = {'Windows': 'windows', 'Darwin': 'osx', 'Linux': 'linux'}[platform.system()]
classpath = [args.client / (args.client.name + '.jar')]
for library in profile['libraries']:
    allowed = not library.get('rules')
    for rule in library.get('rules', []):
        operating_system = rule.get('os', {})
        if operating_system.get('name', system) not in (system, 'universal'):
            continue
        if rule.get('features'):
            continue
        allowed = rule['action'] == 'allow'
    if not allowed or ('arm64' in library['name'] and platform.machine().lower() not in ('arm64', 'aarch64')):
        continue
    relative = library.get('downloads', {}).get('artifact', {}).get('path')
    if not relative:
        group, name, version, *classifier = library['name'].split(':')
        relative = group.replace('.', '/') + '/' + name + '/' + version + '/' + name + '-' + version
        relative += ('-' + classifier[0] if classifier else '') + '.jar'
    dependency = library_root / relative
    if not dependency.is_file():
        raise SystemExit('Missing installed library: ' + str(dependency))
    classpath.append(dependency)

arguments = [
    '-Xms256M', '-Xmx2G', '-XX:ActiveProcessorCount=4', '--enable-native-access=ALL-UNNAMED',
    '--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED',
    '-Dminecraft.launcher.brand=VCBSyntheticTest', '-Dminecraft.launcher.version=1',
    '-cp', os.pathsep.join(str(path) for path in classpath),
    'net.fabricmc.loader.impl.launch.knot.KnotClient',
    '--username', 'VCB_Test', '--uuid', '00000000000000000000000000000001',
    '--accessToken', '0', '--userType', 'mojang', '--version', '26.3',
    '--versionType', 'release', '--gameDir', str(game),
    '--assetsDir', str(args.client.parents[1] / 'assets'), '--assetIndex', profile['assetIndex']['id'],
    '--quickPlaySingleplayer', 'VCB_Test', '--width', '640', '--height', '360',
]
argument_file = game / 'launch.args'
argument_file.write_text('\n'.join('"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"' for value in arguments), encoding='utf-8')
print('Synthetic profile: ' + str(game), flush=True)
with (game / 'launcher.log').open('w', encoding='utf-8') as log:
    process = subprocess.Popen([args.java, '@' + str(argument_file)], cwd=game, stdout=log, stderr=subprocess.STDOUT,
                               creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    try:
        code = process.wait(timeout=args.timeout)
    except subprocess.TimeoutExpired:
        process.terminate()
        process.wait(timeout=30)
        raise SystemExit('Synthetic test timed out; inspect ' + str(game / 'launcher.log'))
result_path = game / 'client-smoke-result.json'
if not result_path.exists():
    print((game / 'launcher.log').read_text(encoding='utf-8', errors='replace')[-7500:])
    raise SystemExit('Client exited without test result; exit=' + str(code))
result = json.loads(result_path.read_text(encoding='utf-8'))
print(json.dumps(result, ensure_ascii=False, indent=2))
sys.exit(0 if result['status'] == 'passed' else 1)
