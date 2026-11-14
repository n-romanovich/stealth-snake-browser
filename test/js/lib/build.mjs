// Builds the injected script exactly like the Kotlin SnakeScript does.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const ASSETS = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', 'app', 'src', 'main', 'assets');
export const TOKEN = '__SS_CONFIG_JSON__';

export function readBlocklistRules() {
  return readFileSync(join(ASSETS, 'blocklist.txt'), 'utf8')
    .split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('#'));
}

export function buildScript(seed, rules = readBlocklistRules(), flags = undefined, extra = undefined) {
  const template = readFileSync(join(ASSETS, 'snake_core.js'), 'utf8');
  if (template.split(TOKEN).length !== 2) throw new Error('config token must appear exactly once');
  const payload = { seed, blocklist: rules, ...(flags ? { flags } : {}), ...(extra ?? {}) };
  return template.replace(TOKEN, () => JSON.stringify(payload));
}

export const RULES_PATH = join(ASSETS, 'blocklist.txt');
