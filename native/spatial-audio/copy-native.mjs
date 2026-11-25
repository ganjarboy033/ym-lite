import { cp, mkdir, access } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const androidRoot = path.join(root, 'android', 'app', 'src', 'main');
const javaSource = path.join(root, 'native', 'android', 'src', 'main', 'java', 'dev', 'bagja', 'spatialaudio');
const javaTarget = path.join(androidRoot, 'java', 'dev', 'bagja', 'spatialaudio');
const drawableSource = path.join(root, 'native', 'android', 'src', 'main', 'res', 'drawable');
const drawableTarget = path.join(androidRoot, 'res', 'drawable');

try {
  await access(path.join(root, 'android', 'app'));
} catch {
  console.error('Folder android/app belum ada. Jalankan "npx cap add android" terlebih dahulu.');
  process.exit(1);
}

await mkdir(path.dirname(javaTarget), { recursive: true });
await cp(javaSource, javaTarget, { recursive: true, force: true });
await mkdir(drawableTarget, { recursive: true });
await cp(drawableSource, drawableTarget, { recursive: true, force: true });
console.log('Source Kotlin Spatial Audio dan ikon Quick Settings berhasil disalin.');
console.log('Langkah berikutnya: daftarkan SpatialAudioPlugin di MainActivity dan gabungkan Manifest/dependency.');
