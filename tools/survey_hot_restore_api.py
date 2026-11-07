"""Read installed 26.3 class signatures; this never opens or modifies a game world."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--game-jar', type=Path, required=True)
parser.add_argument('--java', required=True)
parser.add_argument('--output', type=Path, default=Path('build/hot-restore-api-survey.json'))
args = parser.parse_args()
classes = [
    'net.minecraft.server.level.ServerChunkCache',
    'net.minecraft.server.level.ChunkMap',
    'net.minecraft.world.level.chunk.storage.SimpleRegionStorage',
    'net.minecraft.world.level.chunk.storage.IOWorker',
    'net.minecraft.world.level.chunk.storage.SectionStorage',
    'net.minecraft.world.level.entity.PersistentEntitySectionManager',
    'net.minecraft.world.level.chunk.storage.EntityStorage',
    'net.minecraft.world.level.chunk.storage.RegionFileStorage',
    'net.minecraft.world.level.TicketStorage',
]
selected = re.compile(r'getChunkNow|save\(|saveAll|saveAllChunks|flush|synchroniz|close\(|unload|hasWork|pending|loadedChunks|'
                      r'regionCache|entityManager|simpleRegionStorage|worker|\bstorage;|getTickets|isLoaded|processUnloads|extends ', re.I)
report = {'status': 'inspected', 'gameJarSha256': hashlib.sha256(args.game_jar.read_bytes()).hexdigest(),
          'inspectionOnly': True, 'hotRestoreImplemented': False, 'worldFilesOpened': False,
          'evidenceLimit': 'Method and field presence only; no runtime unload, IO drain or cache invalidation is proven.',
          'classes': {}}
for name in classes:
    result = subprocess.run([args.java, '-m', 'jdk.jdeps/com.sun.tools.javap.Main', '-p', '-classpath', str(args.game_jar), name],
                            capture_output=True, text=True, encoding='utf-8', errors='replace')
    if result.returncode:
        raise SystemExit('Class inspection failed: ' + name)
    report['classes'][name] = [line.strip() for line in result.stdout.splitlines() if selected.search(line)]
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'status': report['status'], 'classCount': len(classes), 'gameJarSha256': report['gameJarSha256'],
                  'hotRestoreImplemented': False, 'output': str(args.output)}, ensure_ascii=False))
