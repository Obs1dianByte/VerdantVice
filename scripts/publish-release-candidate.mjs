import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export const repository = 'Obs1dianByte/VerdantVice';
export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

export function validateRequest(request) {
  const version = /^v(\d+\.\d+\.\d+)-rc\.[1-9]\d*$/.exec(request.tag);
  assert(version && request.prerelease === true, 'Only explicitly marked release candidates may be published');
  assert.equal(request.title, `VerdantVice ${version[1]} RC${request.tag.split('-rc.')[1]}`);
  assert.match(request.sourceCommit, /^[a-f0-9]{40}$/);
  assert(Number.isSafeInteger(request.verificationRun) && request.verificationRun > 0);
  assert.equal(request.artifact, 'VerdantVice');
  assert.equal(request.notes, `.github/release-notes/${request.tag}.md`);
  assert.deepEqual(request.assets.map(asset => asset.name).sort(), [
    `VerdantVice-${version[1]}.jar`,
    'VerdantVice-resourcepack-1.21.4.zip',
    'VerdantVice-resourcepack-26.2.zip',
  ].sort());
  for (const asset of request.assets) assert.match(asset.sha256, /^[a-f0-9]{64}$/);
  return request;
}

export function validateVerification(request, run, jobs) {
  assert.equal(run.id, request.verificationRun);
  assert.equal(run.repository.full_name, repository);
  assert.equal(run.head_sha, request.sourceCommit);
  assert.equal(run.path, '.github/workflows/verify.yml');
  assert.equal(run.event, 'push');
  assert.equal(run.head_branch, 'main');
  assert.equal(run.status, 'completed');
  assert.equal(run.conclusion, 'success');
  const required = ['network-databases', ...['ubuntu-latest', 'windows-latest'].flatMap(os =>
    ['21', '25'].map(java => `build-and-test (${os}, ${java})`))];
  for (const name of required) assert(jobs.some(job => job.name === name), `Missing verification job: ${name}`);
  assert(jobs.every(job => job.status === 'completed' && job.conclusion === 'success'), 'Every verification job must pass');
}

export function validateReleaseAssets(release, expected) {
  assert.deepEqual(release.assets.map(asset => asset.name).sort(), expected.map(asset => asset.name).sort());
  for (const asset of expected) {
    const uploaded = release.assets.find(entry => entry.name === asset.name);
    assert.equal(uploaded.state, 'uploaded');
    assert.equal(uploaded.digest, `sha256:${asset.sha256}`, `Uploaded checksum mismatch: ${asset.name}`);
  }
}

function gh(args) {
  return execFileSync('gh', args, { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
}

function api(path) {
  return JSON.parse(gh(['api', `repos/${repository}/${path}`]));
}

function findRelease(tag) {
  const release = api('releases?per_page=100').find(entry => entry.tag_name === tag);
  assert(release, `Release not found after creation: ${tag}`);
  return release;
}

function filesIn(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name);
    assert(!entry.isSymbolicLink(), 'Release artifacts must not contain symlinks');
    return entry.isDirectory() ? filesIn(path) : [path];
  });
}

export function publish() {
  assert.equal(process.env.GITHUB_REPOSITORY, repository);
  assert(process.env.GH_TOKEN, 'Use the workflow-scoped GitHub token');
  const request = validateRequest(JSON.parse(readFileSync('.github/release-candidate.json', 'utf8')));
  const notes = readFileSync(request.notes, 'utf8');
  validateVerification(request, api(`actions/runs/${request.verificationRun}`),
    api(`actions/runs/${request.verificationRun}/jobs?per_page=100`).jobs);

  const directory = mkdtempSync(join(process.env.RUNNER_TEMP || tmpdir(), 'verdantvice-release-'));
  const downloaded = join(directory, 'downloaded');
  const staged = join(directory, 'assets');
  mkdirSync(staged);
  gh(['run', 'download', String(request.verificationRun), '--repo', repository,
    '--name', request.artifact, '--dir', downloaded]);
  const files = filesIn(downloaded);
  assert.equal(files.length, request.assets.length, 'Unexpected files in the verified distribution');
  for (const asset of request.assets) {
    const matches = files.filter(path => basename(path) === asset.name);
    assert.equal(matches.length, 1, `Expected one file: ${asset.name}`);
    assert.equal(sha256(readFileSync(matches[0])), asset.sha256, `CI artifact checksum mismatch: ${asset.name}`);
    copyFileSync(matches[0], join(staged, asset.name));
  }
  const sums = request.assets.map(asset => `${asset.sha256}  ${asset.name}\n`).join('');
  writeFileSync(join(staged, 'SHA256SUMS'), sums);
  const expected = [...request.assets, { name: 'SHA256SUMS', sha256: sha256(sums) }];
  let release = api('releases?per_page=100').find(entry => entry.tag_name === request.tag);
  if (release) {
    assert.equal(release.target_commitish, request.sourceCommit);
    assert.equal(release.prerelease, true);
    assert.equal(release.name, request.title);
    assert.equal(release.body.trim(), notes.trim(), 'Existing release notes differ; review them manually');
    if (!release.draft) {
      validateReleaseAssets(release, expected);
      console.log(release.html_url);
      return;
    }
    for (const asset of release.assets) {
      assert(expected.some(entry => entry.name === asset.name && asset.digest === `sha256:${entry.sha256}`),
        'Existing draft contains an unexpected asset; no files will be overwritten');
    }
  } else {
    gh(['release', 'create', request.tag, '--repo', repository, '--target', request.sourceCommit,
      '--title', request.title, '--notes-file', request.notes, '--draft', '--prerelease', '--latest=false']);
    release = findRelease(request.tag);
  }
  const missing = expected.filter(asset => !release.assets.some(entry => entry.name === asset.name));
  if (missing.length) gh(['release', 'upload', request.tag, ...missing.map(asset => join(staged, asset.name)), '--repo', repository]);
  release = findRelease(request.tag);
  assert.equal(release.draft, true);
  validateReleaseAssets(release, expected);
  gh(['release', 'edit', request.tag, '--repo', repository, '--draft=false', '--prerelease', '--latest=false']);
  console.log(api(`releases/tags/${request.tag}`).html_url);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) publish();
