import { cp, mkdir, readdir, readFile, utimes } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
for (const file of ['app.js', 'data.js', 'i18n.js']) execFileSync(process.execPath, ['--check', resolve(root, 'dist', file)], { stdio: 'inherit' });
const html = await readFile(resolve(root, 'dist/index.html'), 'utf8');
for (const asset of ['app.js', 'styles.css', 'favicon.svg']) {
  if (!html.includes(asset)) throw new Error(`Missing entry asset: ${asset}`);
  await readFile(resolve(root, 'dist', asset));
}
const destination = resolve(root, '../src/main/resources/static');
await mkdir(destination, { recursive: true });
for (const file of await readdir(resolve(root, 'dist'))) {
  const output = resolve(destination, file);
  await cp(resolve(root, 'dist', file), output, { recursive: true });
  // Maven's incremental resource copier compares timestamps. Explicitly mark
  // the fresh copy so repeated frontend builds cannot keep an older JS module.
  const now = new Date();
  await utimes(output, now, now);
}
console.log(`BUILD_OK: frontend assets copied to ${destination}`);
