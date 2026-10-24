import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { repository, sha256, validateRequest, validateVerification, validateReleaseAssets } from './publish-release-candidate.mjs';

const request = JSON.parse(readFileSync(new URL('../.github/release-candidate.json', import.meta.url), 'utf8'));
const run = {
  id: request.verificationRun, repository: { full_name: repository }, head_sha: request.sourceCommit,
  path: '.github/workflows/verify.yml', event: 'push', head_branch: 'main', status: 'completed', conclusion: 'success',
};
const jobs = ['network-databases', ...['ubuntu-latest', 'windows-latest'].flatMap(os =>
  ['21', '25'].map(java => `build-and-test (${os}, ${java})`))]
  .map(name => ({ name, status: 'completed', conclusion: 'success' }));

test('the requested candidate and its pinned successful build are valid', () => {
  validateRequest(request);
  validateVerification(request, run, jobs);
});

test('stable tags, malformed commits and unexpected download names are rejected', () => {
  for (const change of [
    { tag: 'v1.0.0' }, { prerelease: false }, { sourceCommit: 'main' }, { verificationRun: 0 },
    { notes: '../../secret' }, { assets: [...request.assets, request.assets[0]] },
    { assets: request.assets.map((asset, index) => index ? asset : { ...asset, name: '../plugin.jar' }) },
    { assets: request.assets.map((asset, index) => index ? asset : { ...asset, sha256: 'invalid' }) },
  ]) assert.throws(() => validateRequest({ ...request, ...change }));
});

test('a different commit, repository, branch, workflow or failed run cannot publish', () => {
  for (const change of [
    { head_sha: '0'.repeat(40) }, { repository: { full_name: 'other/repo' } }, { head_branch: 'other' },
    { path: '.github/workflows/other.yml' }, { event: 'pull_request' }, { status: 'in_progress' }, { conclusion: 'failure' },
  ]) assert.throws(() => validateVerification(request, { ...run, ...change }, jobs));
});

test('missing, skipped and failed verification jobs prevent publication', () => {
  assert.throws(() => validateVerification(request, run, jobs.slice(1)));
  for (const conclusion of ['skipped', 'failure', null]) {
    assert.throws(() => validateVerification(request, run, [{ ...jobs[0], conclusion }, ...jobs.slice(1)]));
  }
});

test('every uploaded file must have its expected name and SHA-256 digest', () => {
  const assets = request.assets.map(asset => ({ name: asset.name, state: 'uploaded', digest: `sha256:${asset.sha256}` }));
  validateReleaseAssets({ assets }, request.assets);
  assert.throws(() => validateReleaseAssets({ assets: assets.slice(1) }, request.assets));
  assert.throws(() => validateReleaseAssets({ assets: [...assets, assets[0]] }, request.assets));
  assert.throws(() => validateReleaseAssets({ assets: [{ ...assets[0], digest: 'sha256:wrong' }, ...assets.slice(1)] }, request.assets));
});

test('checksum files use standard SHA-256 bytes', () => {
  assert.equal(sha256('abc'), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
});
