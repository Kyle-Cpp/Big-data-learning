import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const source = await readFile(new URL('../dist/data.js', import.meta.url), 'utf8');
const { apiRequest, buildApiURL, normalizeFilters, fetchPageData, demoData, LEAGUES, HISTORY_ANALYSES, submitJob, killJob } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

test('URL encoding preserves configured HDFS locations and omits empty filters', () => {
  const url = buildApiURL('/api/results/matches', { baseURL: 'http://localhost:8080/', query: { league: 'Premier League', location: 'hdfs:///footpred/output A', season: undefined, empty: '' } });
  const parsed = new URL(url);
  assert.equal(parsed.origin, 'http://localhost:8080');
  assert.equal(parsed.searchParams.get('location'), 'hdfs:///footpred/output A');
  assert.equal(parsed.searchParams.get('league'), 'Premier League');
  assert.equal(parsed.searchParams.has('season'), false);
  assert.equal(buildApiURL('/api/models'), '/api/models');
  assert.throws(() => buildApiURL('/invented/route'), /API/);
});

test('filters normalize league and both backend season encodings', () => {
  assert.deepEqual(normalizeFilters({ league: '英超', season: '2526', source: ' random-forest ', position: 'all', analysis: 'p65', limit: '900', empty: '' }), { league: 'Premier League', season: '2025-26', source: 'random-forest', analysis: 'P6.5', limit: 500 });
  assert.equal(normalizeFilters({ season: '1993-1994' }).season, '1993-94');
  assert.equal(normalizeFilters({ season: 'WC-2022' }).season, 'WC-2022');
  assert.equal(normalizeFilters({ league: '全部联赛' }).league, undefined);
});

test('API parses JSON and serializes submission payloads', async () => {
  let captured;
  const fetchImpl = async (url, options) => {
    captured = { url, options };
    return new Response(JSON.stringify({ id: 15 }), { status: 202, headers: { 'Content-Type': 'application/json' } });
  };
  assert.deepEqual(await submitJob({ command: 'simulate', arguments: { input: '/sample.csv' } }, { fetchImpl }), { id: 15 });
  assert.equal(captured.url, '/api/jobs');
  assert.equal(captured.options.method, 'POST');
  assert.equal(JSON.parse(captured.options.body).command, 'simulate');
  await killJob(15, { fetchImpl });
  assert.equal(captured.url, '/api/jobs/15/kill');
  assert.equal(captured.options.method, 'POST');
});

test('HTTP errors retain status and backend error details', async () => {
  await assert.rejects(apiRequest('/api/jobs', { fetchImpl: async () => new Response(JSON.stringify({ error: 'job-disabled', message: '作业未启用' }), { status: 503 }) }), error => error.status === 503 && error.code === 'job-disabled' && error.message === '作业未启用');
  await assert.rejects(apiRequest('/api/jobs', { fetchImpl: async () => new Response('', { status: 404 }) }), error => error.status === 404);
});

test('HTML preview fallback and malformed JSON are explicit failures', async () => {
  await assert.rejects(apiRequest('/api/models', { fetchImpl: async () => new Response('<!doctype html><html></html>', { headers: { 'content-type': 'text/html' } }) }), error => error.code === 'html-response');
  await assert.rejects(apiRequest('/api/models', { fetchImpl: async () => new Response('invalid') }), error => error.code === 'invalid-json');
});

test('timeout and external abort report distinct request states', async () => {
  const fetchImpl = async (url, { signal }) => new Promise((resolve, reject) => {
    if (signal.aborted) { reject(new DOMException('aborted', 'AbortError')); return; }
    signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true });
  });
  await assert.rejects(apiRequest('/api/models', { timeoutMs: 5, fetchImpl }), error => error.code === 'timeout');
  const controller = new AbortController();
  controller.abort();
  await assert.rejects(apiRequest('/api/models', { signal: controller.signal, fetchImpl }), error => error.code === 'aborted');
});

test('synthetic dataset includes five leagues, eight analyses and consistent probabilities', () => {
  assert.equal(demoData.sample, true);
  assert.ok(demoData.matches.items.length >= 12);
  assert.ok(demoData.players.items.length >= 12);
  assert.equal(demoData.catalog.length, 19);
  assert.equal(HISTORY_ANALYSES.length, 8);
  for (const match of [...demoData.matches.items, ...demoData.fixtures.items]) {
    assert.ok(Math.abs(match.probabilityHome + match.probabilityDraw + match.probabilityAway - 1) < 0.000001);
  }
  for (const league of LEAGUES) {
    const rows = demoData.simulation.items.filter(row => row.league === league.label);
    assert.ok(rows.length >= 6);
    assert.ok(Math.abs(rows.reduce((sum, row) => sum + row.championshipProbability, 0) - 1) < 0.000001);
  }
  for (const analysis of HISTORY_ANALYSES) assert.ok(demoData.history[analysis.id].items.length > 0);
  const matrixCount = Object.values(demoData.evaluation.confusion).flatMap(row => row.split(',')).reduce((sum, value) => sum + Number(value), 0);
  assert.equal(matrixCount, demoData.evaluation.matches);
  const matrix = Object.values(demoData.evaluation.confusion).map(row => row.split(',').map(Number));
  const accuracy = matrix.reduce((sum, row, i) => sum + row[i], 0) / matrixCount;
  const macroF1 = matrix.reduce((sum, row, i) => sum + 2 * row[i] / (row.reduce((a, b) => a + b, 0) + matrix.reduce((total, other) => total + other[i], 0)), 0) / 3;
  assert.ok(Math.abs(accuracy - demoData.evaluation.accuracy) < 0.00005);
  assert.ok(Math.abs(macroF1 - demoData.evaluation.macroF1) < 0.00005);
});

test('sample league accuracies differ and all result labels agree with correct flags', async () => {
  const expected = [0.75, 0.5, 1, 0.75, 0.5];
  for (const [index, league] of LEAGUES.entries()) {
    const data = await fetchPageData('matches', { mode: 'demo' }, { league: league.label });
    assert.equal(data.matches.accuracy, expected[index]);
    for (const row of data.matches.items) assert.equal(row.correct, row.actual === row.predicted);
  }
  assert.equal(demoData.matches.accuracy, 0.7);
});

test('sample filtering is isolated and forward fixtures never invent known outcomes', async () => {
  const data = await fetchPageData('matches', { mode: 'demo' }, { league: 'la-liga', source: 'dixon-coles' });
  assert.equal(data.mode, 'demo');
  assert.equal(data.matches.count, 5);
  assert.equal(data.matches.items[0].actual, null);
  assert.equal(data.matches.accuracy, null);
  assert.equal(data.matches.items[0].league, null);
  assert.equal(data.matches.items[0].epochDay, 0);
  data.matches.items[0].homeTeam = 'changed';
  assert.notEqual(demoData.fixtures.items[0].homeTeam, 'changed');
  const players = await fetchPageData('players', { mode: 'demo' }, { league: '英超', position: 'MF', limit: 1 });
  assert.equal(players.players.count, 1);
  assert.equal(players.players.items[0].competition, 'Premier League');
});

test('live requests follow controller parameter names and do not silently use examples', async () => {
  const urls = [];
  const fetchImpl = async url => {
    urls.push(url);
    return new Response(JSON.stringify({ count: 0, items: [] }));
  };
  await fetchPageData('players', { baseURL: 'http://localhost:8080', paths: { players: 'hdfs:///players' }, fetchImpl }, { league: '英超', position: 'FW' });
  const url = new URL(urls[0]);
  assert.equal(url.pathname, '/api/results/players');
  assert.equal(url.searchParams.get('competition'), 'Premier League');
  assert.equal(url.searchParams.get('location'), 'hdfs:///players');
  await assert.rejects(fetchPageData('matches', { mode: 'live', fetchImpl: async () => { throw new Error('offline'); } }), error => error.code === 'network-error');
});

test('optional jobs 404 is represented as unavailable while the catalog stays live', async () => {
  const data = await fetchPageData('jobs', { mode: 'live', fetchImpl: async url => url === '/api/jobs' ? new Response('', { status: 404 }) : new Response(JSON.stringify([{ id: 'simulate' }])) });
  assert.equal(data.mode, 'live');
  assert.equal(data.jobs.available, false);
  assert.equal(data.jobs.count, 0);
  assert.equal(data.catalog[0].id, 'simulate');
  assert.equal(data.warnings.length, 1);
});

test('Dixon-Coles omits league and season because the backend rows lack those fields', async () => {
  let requested;
  await fetchPageData('matches', { fetchImpl: async url => { requested = url; return new Response('{"count":0,"items":[]}'); } }, { source: 'dixon-coles', league: '英超', season: '2526' });
  const url = new URL(requested, 'http://localhost');
  assert.equal(url.searchParams.get('source'), 'dixon-coles');
  assert.equal(url.searchParams.has('league'), false);
  assert.equal(url.searchParams.has('season'), false);
});

test('dashboard preserves live results when optional jobs fail, while jobs page exposes the failure', async () => {
  const fetchImpl = async url => {
    if (url === '/api/jobs') return new Response('{"message":"database unavailable"}', { status: 503 });
    if (url === '/api/models') return new Response('{"randomForests":[],"playerXg":{"available":false}}');
    if (url === '/api/catalog/jobs') return new Response('[{"id":"simulate"}]');
    return new Response('{"count":1,"items":[{"matchId":"live-result"}]}');
  };
  const data = await fetchPageData('dashboard', { mode: 'live', fetchImpl });
  assert.equal(data.mode, 'live');
  assert.equal(data.matches.items[0].matchId, 'live-result');
  assert.equal(data.jobs.available, false);
  assert.equal(data.jobs.items.length, 0);
  assert.match(data.warnings[0], /database unavailable/);
  assert.deepEqual(data.models.randomForests, []);
  await assert.rejects(fetchPageData('jobs', { mode: 'live', fetchImpl }), error => error.status === 503);
});

test('dashboard still exposes core result failures rather than silently rendering samples', async () => {
  await assert.rejects(fetchPageData('dashboard', {
    mode: 'live', fetchImpl: async url => url.startsWith('/api/results/matches') ? new Response('', { status: 500 }) : new Response('{"count":0,"items":[]}'),
  }), error => error.status === 500);
});

test('live file locations select match source, evaluation split and history analysis independently', async () => {
  const requested = [];
  const settings = {
    mode: 'live', paths: {
      matches: { 'random-forest': 'hdfs:///rf/results', 'dixon-coles': 'hdfs:///dc/results' },
      evaluation: { test: 'hdfs:///evaluation/test.tsv', validation: 'hdfs:///evaluation/validation.tsv' },
      history: { 'P6.1': 'hdfs:///history/champions', 'P6.2': 'hdfs:///history/home' },
    },
    fetchImpl: async url => { requested.push(new URL(url, 'http://localhost')); return new Response('{"count":0,"items":[]}'); },
  };
  await fetchPageData('matches', settings, { source: 'dixon-coles' });
  await fetchPageData('matches', settings);
  await fetchPageData('evaluation', settings, { split: 'validation' });
  await fetchPageData('evaluation', settings);
  await fetchPageData('history', settings, { analysis: 'champions' });
  await fetchPageData('history', { ...settings, paths: { history: 'hdfs:///plain/history' } });
  assert.deepEqual(requested.map(url => url.searchParams.get('location')), [
    'hdfs:///dc/results', 'hdfs:///rf/results', 'hdfs:///evaluation/validation.tsv',
    'hdfs:///evaluation/test.tsv', 'hdfs:///history/champions', 'hdfs:///plain/history',
  ]);
  assert.equal(requested[2].searchParams.get('split'), 'validation');
  assert.equal(requested[4].searchParams.get('analysis'), 'P6.1');
});
