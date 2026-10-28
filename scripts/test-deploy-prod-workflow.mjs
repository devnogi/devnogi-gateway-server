import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const workflow = readFileSync(new URL('../.github/workflows/push-cd-prod.yml', import.meta.url), 'utf8').replace(/\r\n/g, '\n');
const compose = readFileSync(new URL('../docker-compose-prod.yml', import.meta.url), 'utf8').replace(/\r\n/g, '\n');

test('only main push or main manual dispatch can deploy and deployments serialize', () => {
  assert.match(workflow, /^on:\n  push:\n    branches: \[main\]\n  workflow_dispatch:/m);
  assert.match(workflow, /github\.ref == 'refs\/heads\/main' && \(github\.event_name == 'push' \|\| github\.event_name == 'workflow_dispatch'\)/);
  assert.match(workflow, /group: gateway-production-deploy\n  cancel-in-progress: false/);
  assert.match(workflow, /PROD_SSH_KNOWN_HOSTS/);
  assert.doesNotMatch(workflow, /ssh-keyscan/);
  assert.doesNotMatch(workflow, /refs\/heads\/dev|pull_request:/);
});

test('deployment uses build digest and restores previous image and config on failure', () => {
  assert.match(workflow, /prod-\$\{\{ github.sha \}\}/);
  assert.match(workflow, /docker buildx imagetools inspect/);
  assert.match(workflow, /IMAGE_REF='.*@\$\{\{ steps.build.outputs.digest \}\}'/);
  assert.match(compose, /image: \$\{IMAGE_REF:\?Set the immutable image digest\}/);
  assert.match(workflow, /Previous configuration missing/);
  assert.match(workflow, /! health_ok; then[\s\S]*image: %s[\s\S]*docker-compose-prod.yml -f "\$PWD\/rollback.yml"[\s\S]*exit 1/);
  assert.doesNotMatch(workflow, /docker compose[^\n]* down/);
  assert.equal((workflow.match(/docker compose -p devnogi-gateway/g) || []).length, 2);
});
