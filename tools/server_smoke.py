"""Exercise an already prepared, loopback-only synthetic Fabric instance on Linux."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import grp
import pwd
import re
import shutil
import subprocess
import time
import traceback

parser = argparse.ArgumentParser()
parser.add_argument('--instance', type=Path, required=True)
parser.add_argument('--unit', required=True)
parser.add_argument('--java', required=True)
parser.add_argument('--artifact', type=Path, required=True)
parser.add_argument('--sha256', required=True)
parser.add_argument('--probe', type=Path, required=True)
parser.add_argument('--watch-unit', action='append', default=[])
parser.add_argument('--result', type=Path, required=True)
args = parser.parse_args()
instance = args.instance.resolve()
unit = args.unit
log_path = instance / 'logs/latest.log'
report = {'scope': 'isolated synthetic dedicated server', 'status': 'running', 'checks': [], 'snapshots': {}}


def persist():
    args.result.parent.mkdir(parents=True, exist_ok=True)
    args.result.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def states():
    return {name: subprocess.check_output(['systemctl', 'show', name, '-p', 'ActiveState', '-p', 'MainPID'], text=True).strip()
            for name in args.watch_unit}


def check(condition, name, evidence=None):
    if not condition:
        raise AssertionError(name)
    report['checks'].append({'name': name, 'evidence': evidence})
    persist()
    print('PASS ' + name, flush=True)


def log():
    return log_path.read_text(encoding='utf-8', errors='replace') if log_path.exists() else ''


def wait(pattern, offset=0, timeout=75):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        match = re.search(pattern, log()[offset:])
        if match:
            return match
        time.sleep(0.2)
    raise AssertionError('Timed out waiting for ' + pattern + '\n' + log()[-4000:])


def console(text, pattern=None, timeout=75):
    offset = len(log())
    descriptor = os.open(instance / 'console.fifo', os.O_WRONLY | os.O_NONBLOCK)
    try:
        os.write(descriptor, (text + '\n').encode('utf-8'))
    finally:
        os.close(descriptor)
    if pattern:
        return wait(pattern, offset, timeout)
    time.sleep(0.2)


def idle():
    deadline = time.monotonic() + 75
    while time.monotonic() < deadline:
        offset = len(log())
        console('chunkbackup status')
        if re.search(r'\[备份\] (?:已就绪|上次备份异常)', log()[offset:]):
            return
        time.sleep(0.25)
    raise AssertionError('Synthetic backup engine did not become idle')


def start():
    ownership = (instance / 'mods').stat()
    options = {'User': pwd.getpwuid(ownership.st_uid).pw_name, 'Group': grp.getgrgid(ownership.st_gid).gr_name, 'WorkingDirectory': str(instance),
               'MemoryMax': '2048M', 'MemoryHigh': '1792M', 'CPUWeight': '10', 'IOWeight': '10', 'Nice': '10',
               'ProtectSystem': 'strict', 'ProtectHome': 'yes', 'ReadWritePaths': str(instance),
               'PrivateTmp': 'yes', 'PrivateDevices': 'yes', 'NoNewPrivileges': 'yes',
               'Restart': 'no', 'TimeoutStopSec': '120', 'UMask': '0027'}
    command = ['systemd-run', '--unit', unit, '--collect', '--service-type=exec']
    for name, value in options.items():
        command += ['--property', name + '=' + value]
    subprocess.run(command + [str(instance / 'launch.sh')], check=True)
    invocation = subprocess.check_output(['systemctl', 'show', unit, '-p', 'InvocationID', '--value'], text=True).strip()
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        boot = subprocess.check_output(['journalctl', '_SYSTEMD_INVOCATION_ID=' + invocation, '-o', 'cat', '--no-pager'], text=True)
        if 'VCB_READY' in boot and re.search(r'Done \(', boot):
            return
        time.sleep(0.5)
    raise AssertionError('Synthetic server did not start: ' + boot[-4000:])


def backup(label):
    idle()
    result = console('vcbtest asplayer BackupProbe chunkbackup create ' + label,
                     r'VCB_BACKUP_OK id=(\S+) reason=manual label=' + re.escape(label) + r' newObjects=(\d+) newBytes=(\d+)')
    report['snapshots'][label] = {'id': result[1], 'newObjects': int(result[2]), 'newBytes': int(result[3])}
    persist()
    idle()
    return result[1]


def run():
    if not (instance / 'synthetic-test-marker.txt').is_file() or not ('backup-trial' in unit or unit.startswith('vcb-test-')):
        raise ValueError('Synthetic marker and a test service name are required')
    if unit in args.watch_unit:
        raise ValueError('The test service cannot be a watched service')
    properties = dict(line.split('=', 1) for line in (instance / 'server.properties').read_text().splitlines()
                      if '=' in line and not line.startswith('#'))
    if properties.get('server-ip') != '127.0.0.1' or properties.get('online-mode') != 'false':
        raise ValueError('This helper only runs against a loopback-only, synthetic offline test instance')
    if not properties.get('level-name', '').startswith('synthetic-'):
        raise ValueError('Expected a clearly named synthetic world')
    if subprocess.check_output(['systemctl', 'show', unit, '-p', 'MainPID', '--value'], text=True).strip() != '0':
        raise ValueError('The test instance must already be stopped')
    digest = hashlib.sha256(args.artifact.read_bytes()).hexdigest()
    if digest != args.sha256:
        raise ValueError('Candidate artifact hash does not match')
    report['artifactSha256'] = digest
    report['watchedBefore'] = states()
    previous = instance / ('test-previous-mods/' + str(int(time.time())))
    previous.mkdir(parents=True)
    owner = (instance / 'mods').stat()
    for old in (instance / 'mods').glob('vanilla-chunk-backup-*.jar'):
        shutil.move(old, previous / old.name)
    for source in (args.artifact, args.probe):
        target = instance / 'mods' / source.name
        shutil.copy2(source, target)
        os.chown(target, owner.st_uid, owner.st_gid)
    try:
        start()
        check('vanilla_chunk_backup 1.1.0+26.3' in log(), 'candidate loads with the installed server mods')
        check(not re.search(r'Mixin apply failed|InjectionError|InvalidMixinException', log()), 'region capture mixin loads')
        world = instance / properties['level-name']
        world_id = (world / '.vanilla-chunk-backup-id').read_text().strip()
        repository = instance / 'backups/vanilla-chunk-backup' / world_id
        legacy = sorted(path.stem[:-4] for path in (repository / 'snapshots').glob('*.vcb.gz')
                        if re.fullmatch(r'\d{8}T\d{9}Z-[0-9a-f]{8}\.vcb\.gz', path.name))
        check(bool(legacy), 'legacy snapshots remain discoverable')
        idle()
        console('chunkbackup verify ' + legacy[-1], r'VCB_VERIFY_OK id=' + re.escape(legacy[-1]))
        check(True, 'legacy snapshot objects verify with the candidate')
        console('tick freeze', r'The game is frozen')
        console('forceload add -32 -32 48 32')
        console('player BackupProbe spawn', r'BackupProbe joined the game')
        console('tp BackupProbe 8 6 8')
        console('setblock 8 4 8 minecraft:chest')
        console('item replace block 8 4 8 container.0 with minecraft:diamond 11')
        console('setblock 24 4 8 minecraft:chest')
        console('item replace block 24 4 8 container.0 with minecraft:iron_ingot 5')
        console('setblock -8 4 8 minecraft:chest')
        console('item replace block -8 4 8 container.0 with minecraft:gold_ingot 3')
        console('setblock 9 4 8 minecraft:white_bed[part=foot,facing=east]')
        console('setblock 10 4 8 minecraft:white_bed[part=head,facing=east]')
        console('kill @e[type=minecraft:armor_stand,tag=vcb_selected]')
        console('summon minecraft:armor_stand 8 5 9 {Tags:["vcb_selected"],NoGravity:1b,Invulnerable:1b}')
        run_label = 'v11-' + str(int(time.time()))
        baseline = backup(run_label + '-first')
        repeated = backup(run_label + '-second')
        check('admin=false createAllowed=true configAllowed=false restoreAllowed=false' in log(),
              'non-OP player can back up and cannot configure or restore')
        check(baseline != repeated, 'consecutive completed player requests have no added cooldown')
        check(re.fullmatch(r'\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}_\d{3}\+0800-[0-9a-f]{8}', baseline)
              and (repository / 'snapshots' / (baseline + '.vcb.gz')).is_file(), 'new filenames contain readable date, time and timezone', baseline)
        listed = console('chunkbackup list', r'\[备份\] \d{2}-\d{2} \d{2}:\d{2}:\d{2} · 手动 · ' + re.escape(run_label + '-first'))
        check(True, 'player list displays readable times and labels', listed[0])
        idle()
        console('chunkbackup verify ' + baseline, r'VCB_VERIFY_OK id=' + re.escape(baseline))
        check(True, 'new snapshot objects pass SHA-256 verification')
        active_restore = subprocess.run([args.java, '-jar', str(args.artifact), 'restore', str(world), str(repository),
                                         baseline, 'minecraft:overworld', '0', '0', '0', '0'], capture_output=True, text=True)
        check(active_restore.returncode != 0 and '世界正被服务器使用' in active_restore.stderr,
              'offline restore refuses a running world')
        idle()
        console('item replace block 8 4 8 container.0 with minecraft:coal 2')
        console('item replace block 24 4 8 container.0 with minecraft:copper_ingot 11')
        console('item replace block -8 4 8 container.0 with minecraft:gold_ingot 15')
        console('setblock 9 4 8 minecraft:air')
        console('setblock 10 4 8 minecraft:air')
        console('kill @e[type=minecraft:armor_stand,tag=vcb_selected]')
        token = console('chunkbackup restore ' + baseline + ' minecraft:overworld 0 0 0 0', r'chunkbackup confirm ([0-9a-f]{8})')[1]
        idle()
        console('chunkbackup confirm ' + token, r'VCB_RESTORE_QUEUED')
        deadline = time.monotonic() + 75
        while subprocess.check_output(['systemctl', 'show', unit, '-p', 'MainPID', '--value'], text=True).strip() != '0':
            if time.monotonic() > deadline:
                raise AssertionError('Synthetic cold restore did not stop the test instance')
            time.sleep(0.3)
        check((instance / 'config/vanilla_chunk_backup.pending.properties').is_file(), 'cold restore queues a durable date-named snapshot plan')
        start()
        console('tick freeze')
        console('forceload add -32 -32 48 32')
        restored = console('vcbtest inspect', r'VCB_TEST_WORLD[^\n]+')[0]
        check('chest8=minecraft:diamond:11' in restored and 'poi=minecraft:home' in restored and 'selectedEntities=1' in restored,
              'cold restore recovers selected terrain, container, entity and POI', restored)
        check('chest24=minecraft:copper_ingot:11' in restored and 'chest-8=minecraft:gold_ingot:15' in restored,
              'cold restore preserves neighboring positive and negative chunks')
        check(not (instance / 'config/vanilla_chunk_backup.pending.properties').exists(), 'successful restart clears the pending plan')
        automatic_offset = len(log())
        console('chunkbackup config intervalMinutes 1', r'intervalMinutes = 1')
        automatic = wait(r'VCB_BACKUP_OK id=(\S+) reason=automatic[^\n]+', automatic_offset, 80)
        check(True, 'wall-clock automatic backup runs with a test-only one-minute interval', automatic[0])
        idle()
        console('chunkbackup config intervalMinutes 60', r'intervalMinutes = 60')
        subprocess.run(['systemctl', 'stop', unit], check=True)
        listing = subprocess.check_output([args.java, '-jar', str(args.artifact), 'list', str(world), str(repository)], text=True)
        check(legacy[-1] in listing and baseline in listing and '+0800 | ' in listing,
              'offline list reads legacy and date-named snapshots together')
        exported = instance / ('synthetic-export-v11-' + str(int(time.time())))
        subprocess.run([args.java, '-jar', str(args.artifact), 'export', str(repository), baseline, str(exported)], check=True)
        check((exported / 'level.dat').is_file(), 'date-named snapshot exports a complete world into a new directory')
        report['watchedAfter'] = states()
        check(report['watchedBefore'] == report['watchedAfter'], 'watched production services retain their original state and PID')
        report['status'] = 'passed'
        persist()
        print('SERVER_SMOKE_OK checks=' + str(len(report['checks'])), flush=True)
    finally:
        if subprocess.run(['systemctl', 'is-active', '--quiet', unit]).returncode == 0:
            subprocess.run(['systemctl', 'stop', unit], check=True)


if __name__ == '__main__':
    try:
        run()
    except Exception as failure:
        report['status'] = 'failed'
        report['error'] = str(failure)
        persist()
        traceback.print_exc()
        raise SystemExit(1)
