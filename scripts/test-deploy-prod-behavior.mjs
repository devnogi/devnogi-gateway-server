import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';

const workflow = readFileSync(new URL('../.github/workflows/push-cd-prod.yml', import.meta.url), 'utf8').replace(/\r\n/g, '\n');
const start = workflow.indexOf(" << 'EOF'\n");
const end = workflow.indexOf('\n          EOF', start);
assert.ok(start >= 0 && end > start);
const script = workflow.slice(start + " << 'EOF'\n".length, end).split('\n').map(line => line.slice(12)).join('\n')
  .replaceAll('/opt/devnogi-gateway', '${FIX_ROOT}/devnogi-gateway')
  .replaceAll('${{ secrets.DOCKER_USERNAME }}', 'fixture')
  .replaceAll('${{ secrets.DOCKER_REPO }}', 'gateway')
  .replaceAll('${{ steps.build.outputs.digest }}', 'sha256:abc')
  .replaceAll('${{ secrets.PROD_APP_PORT || 8090 }}', '8090')
  .replace('[ "$count" -lt 60 ]', '[ "$count" -lt 2 ]');
const bash = process.env.DEPLOY_TEST_BASH || 'bash';
const mock = `
docker() {
  printf '%s\\n' "$*" >> "$FIX_ROOT/events"
  case "$*" in
    "inspect devnogi-gateway-app-prod"*) [ "$CASE" != first ] || return 1; echo old-id ;;
    "image inspect"*) echo old@sha256:abc ;;
    "pull "*) [ "$CASE" != pull-fail ] ;;
    *" up "*) case "$*" in *rollback.yml*) [ "$CASE" != rollback-fail ] ;; *) [ "$CASE" != start-fail ] ;; esac ;;
  esac
}
curl() { case "$CASE" in smoke-fail|rollback-fail) return 1 ;; esac; echo '{"status":"UP"}'; }
sleep() { :; }
`;
function run(scenario) {
  const root = mkdtempSync(join(tmpdir(), 'gateway-deploy-fixture-'));
  const posix = root.replaceAll('\\', '/').replace(/^([A-Za-z]):/, (_, drive) => '/' + drive.toLowerCase());
  try {
    const base = join(root, 'devnogi-gateway');
    const candidate = join(base, '.candidate');
    mkdirSync(candidate, { recursive: true });
    writeFileSync(join(candidate, '.env.prod'), 'SERVER_PORT=8090\n');
    writeFileSync(join(candidate, '.docker-token'), 'fixture');
    writeFileSync(join(candidate, 'docker-compose-prod.yml'), 'fixture');
    if (scenario !== 'missing-old-config') {
      writeFileSync(join(base, '.env.prod'), 'SERVER_PORT=8090\n');
      writeFileSync(join(base, 'docker-compose-prod.yml'), 'fixture');
    }
    const result = spawnSync(bash, ['-c', mock + script], { encoding: 'utf8', env: { ...process.env, FIX_ROOT: posix, CASE: scenario } });
    assert.equal(result.error, undefined, result.error?.message);
    const events = (() => { try { return readFileSync(join(root, 'events'), 'utf8'); } catch { return ''; } })();
    return { ...result, events };
  } finally { rmSync(root, { recursive: true, force: true }); }
}

test('first and subsequent deployment retain the existing compose project', () => {
  for (const scenario of ['first', 'success']) {
    const result = run(scenario); assert.equal(result.status, 0, result.stderr);
    assert.match(result.events, /compose -p devnogi-gateway .* up /);
  }
});
test('pull failure and missing prior configuration stop before replacement', () => {
  for (const scenario of ['pull-fail', 'missing-old-config']) {
    const result = run(scenario); assert.notEqual(result.status, 0, scenario); assert.doesNotMatch(result.events, / up /);
  }
});
test('candidate start and smoke failures restore previous config under the same project', () => {
  for (const scenario of ['start-fail', 'smoke-fail']) {
    const result = run(scenario); assert.notEqual(result.status, 0, scenario);
    assert.match(result.events, /compose -p devnogi-gateway .*rollback.yml.* up /);
  }
});
test('failed rollback is reported', () => assert.match(run('rollback-fail').stderr, /Rollback failed/));
