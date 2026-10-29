/**
 * FootPred browser API adapter. All live payloads retain the backend field names.
 * fetchPageData returns { mode, updatedAt, warnings, matches?, players?, ... }.
 * mode=demo is an explicit opt-in; network failures never become sample results.
 */
export const LEAGUES = [
  { label: 'Premier League', name: '英超', kebab: 'premier-league', code: 'ENG' },
  { label: 'La Liga', name: '西甲', kebab: 'la-liga', code: 'ESP' },
  { label: 'Bundesliga', name: '德甲', kebab: 'bundesliga', code: 'GER' },
  { label: 'Serie A', name: '意甲', kebab: 'serie-a', code: 'ITA' },
  { label: 'Ligue 1', name: '法甲', kebab: 'ligue-1', code: 'FRA' },
];

export const HISTORY_ANALYSES = [
  { id: 'P6.1', token: 'champions', name: '冠军演变', kind: 'CHAMPION_EVOLUTION', valueLabel: '进球', secondaryLabel: '失球' },
  { id: 'P6.2', token: 'home-advantage', name: '主场优势', kind: 'HOME_ADVANTAGE', valueLabel: '主场胜率', secondaryLabel: '客场胜率' },
  { id: 'P6.3', token: 'goal-minutes', name: '进球时间', kind: 'GOAL_MINUTES', valueLabel: '进球占比', secondaryLabel: '辅助值' },
  { id: 'P6.4', token: 'h2h', name: '历史交锋', kind: 'HEAD_TO_HEAD', valueLabel: '球队 A 胜率', secondaryLabel: '球队 B 胜率' },
  { id: 'P6.5', token: 'p65', name: '球员生涯', kind: 'CAREER_CURVE', valueLabel: '进球', secondaryLabel: '点球 / xG' },
  { id: 'P6.6', token: 'p66', name: '联赛风格', kind: 'LEAGUE_STYLE', valueLabel: '指标值', secondaryLabel: '辅助值' },
  { id: 'P6.7', token: 'p67', name: '比分分布', kind: 'SCORE_DIFFERENCE', valueLabel: '比分占比', secondaryLabel: '辅助值' },
  { id: 'P6.8', token: 'p68', name: '国籍多样性', kind: 'NATIONALITY', valueLabel: '外籍占比', secondaryLabel: '香农指数 / 出场人数' },
];

export class ApiError extends Error {
  constructor(message, { status = 0, code = 'network-error', detail = null, url = '' } = {}) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.detail = detail;
    this.url = url;
  }
}

function hasValue(value) {
  return value !== null && value !== undefined && String(value).trim() !== '';
}

export function normalizeFilters(filters = {}) {
  const result = {};
  for (const [key, value] of Object.entries(filters)) {
    if (!hasValue(value)) continue;
    const clean = typeof value === 'string' ? value.trim() : value;
    if (['all', '全部', '全部联赛', '全部赛季', '全部位置'].includes(String(clean).toLowerCase())) continue;
    result[key] = clean;
  }
  if (result.league) {
    const match = LEAGUES.find(item => [item.label, item.name, item.kebab, item.code].some(alias => alias.toLowerCase() === String(result.league).toLowerCase()));
    if (match) result.league = match.label;
  }
  if (result.season) {
    const value = String(result.season).replace('/', '-');
    const compact = value.match(/^(\d{2})(\d{2})$/);
    const full = value.match(/^(\d{4})-(\d{4})$/);
    result.season = compact ? `${Number(compact[1]) >= 93 ? '19' : '20'}${compact[1]}-${compact[2]}` : full ? `${full[1]}-${full[2].slice(-2)}` : value;
  }
  if (result.analysis) {
    const match = HISTORY_ANALYSES.find(item => [item.id, item.token, item.kind].some(alias => alias.toLowerCase() === String(result.analysis).toLowerCase()));
    if (match) result.analysis = match.id;
  }
  if (result.limit) {
    const value = Number(result.limit);
    result.limit = Number.isFinite(value) ? Math.max(1, Math.min(500, Math.floor(value))) : 100;
  }
  return result;
}

/** baseURL is an origin or deployment prefix, e.g. http://localhost:8080. */
export function buildApiURL(path, { baseURL = '', query = {} } = {}) {
  if (!String(path).startsWith('/api/')) throw new ApiError('API 路径必须以 /api/ 开头', { code: 'invalid-path' });
  const base = String(baseURL || '').trim().replace(/\/+$/, '');
  if (base && !/^https?:\/\//i.test(base) && !base.startsWith('/')) {
    throw new ApiError('后端地址应为 HTTP(S) 地址或相对路径', { code: 'invalid-base-url' });
  }
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (hasValue(value)) params.set(key, String(value));
  }
  const suffix = params.toString();
  return `${base}${path}${suffix ? `${path.includes('?') ? '&' : '?'}${suffix}` : ''}`;
}

/**
 * Requests JSON with a 15-second default timeout. Pass an external AbortSignal
 * to cancel page transitions; body objects are JSON encoded automatically.
 */
export async function apiRequest(path, options = {}) {
  const { baseURL = '', query = {}, body, signal, timeoutMs = 15000, method = 'GET', headers = {}, fetchImpl = globalThis.fetch } = options;
  const url = buildApiURL(path, { baseURL, query });
  const controller = new AbortController();
  let timedOut = false;
  const abort = () => controller.abort(signal?.reason);
  if (signal?.aborted) abort();
  else signal?.addEventListener('abort', abort, { once: true });
  const timer = Number(timeoutMs) > 0 ? setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, Number(timeoutMs)) : null;
  try {
    const response = await fetchImpl(url, {
      method,
      headers: { Accept: 'application/json', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...headers },
      ...(body === undefined ? {} : { body: typeof body === 'string' ? body : JSON.stringify(body) }),
      signal: controller.signal,
    });
    const raw = await response.text();
    const isHTML = /^\s*(?:<!doctype\s+html|<html|<head|<body)/i.test(raw) || (response.headers?.get('content-type') || '').includes('text/html');
    let payload = null;
    if (raw.trim() && !isHTML) {
      try { payload = JSON.parse(raw); }
      catch {
        throw new ApiError(`接口未返回有效 JSON（HTTP ${response.status}）`, { status: response.status, code: 'invalid-json', url });
      }
    }
    if (!response.ok) {
      throw new ApiError(payload?.message || payload?.error || `请求失败（HTTP ${response.status}）`, {
        status: response.status, code: payload?.error || 'http-error', detail: payload, url,
      });
    }
    if (isHTML) throw new ApiError('API 返回了网页。请检查后端地址或预览服务的 /api 代理配置。', { status: response.status, code: 'html-response', url });
    return payload;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    if (controller.signal.aborted) {
      throw new ApiError(timedOut ? '请求超时，请检查后端连接' : '请求已取消', { code: timedOut ? 'timeout' : 'aborted', url });
    }
    throw new ApiError('连接后端失败，请检查服务地址及网络', { code: 'network-error', detail: error?.message || String(error), url });
  } finally {
    if (timer !== null) clearTimeout(timer);
    signal?.removeEventListener('abort', abort);
  }
}

const teams = [
  ['Arsenal', 'Liverpool', 'Manchester City', 'Chelsea', 'Newcastle', 'Aston Villa', 'Brighton', 'Tottenham'],
  ['Barcelona', 'Real Madrid', 'Atletico Madrid', 'Athletic Club', 'Villarreal', 'Real Betis', 'Real Sociedad', 'Valencia'],
  ['Bayern Munich', 'Bayer Leverkusen', 'Dortmund', 'RB Leipzig', 'Frankfurt', 'Stuttgart', 'Freiburg', 'Mainz'],
  ['Inter', 'Napoli', 'Atalanta', 'Juventus', 'Roma', 'AC Milan', 'Bologna', 'Lazio'],
  ['Paris Saint-Germain', 'Marseille', 'Monaco', 'Lille', 'Lyon', 'Nice', 'Strasbourg', 'Lens'],
];

const epochDay = date => Math.floor(Date.parse(`${date}T00:00:00Z`) / 86400000);
const probabilitySets = [[0.57, 0.24, 0.19], [0.48, 0.27, 0.25], [0.36, 0.30, 0.34], [0.29, 0.26, 0.45]];
const sampleIncorrectIndices = [[2], [1, 2], [], [2], [1, 2]];
const sampleMatches = LEAGUES.flatMap((league, li) => Array.from({ length: 4 }, (_, i) => {
  const p = probabilitySets[(i + li) % probabilitySets.length];
  const predicted = p[0] >= Math.max(p[1], p[2]) ? 'H' : p[2] > p[1] ? 'A' : 'D';
  const actual = sampleIncorrectIndices[li].includes(i) ? 'D' : predicted;
  return {
    matchId: `sample-${li + 1}-${i + 1}`, source: 'random-forest', epochDay: epochDay(`2026-02-${String(14 + i).padStart(2, '0')}`),
    season: '2025-26', league: league.label, homeTeam: teams[li][i * 2], awayTeam: teams[li][i * 2 + 1],
    actual, predicted, probabilityHome: p[0], probabilityDraw: p[1], probabilityAway: p[2],
    lambdaHome: null, lambdaAway: null, topScores: null, correct: actual === predicted,
  };
}));
const sampleFixtures = LEAGUES.map((league, li) => ({
  matchId: `sample-fixture-${li + 1}`, source: 'dixon-coles', epochDay: 0, season: null, league: null,
  homeTeam: teams[li][0], awayTeam: teams[li][2], actual: null, predicted: 'H', correct: null,
  probabilityHome: 0.49 + li * 0.025, probabilityDraw: 0.26 - li * 0.008, probabilityAway: 0.25 - li * 0.017,
  lambdaHome: 1.84 + li * 0.08, lambdaAway: 1.17 - li * 0.04,
  topScores: ['2-1:0.103', '1-1:0.096', '1-0:0.083', '2-0:0.079', '2-2:0.061'],
}));

const playerSeeds = [
  ['Erling Haaland', 'Manchester City', 0, 'FW', 0.82, 21],
  ['Mohamed Salah', 'Liverpool', 0, 'FW', 0.74, 18],
  ['Bukayo Saka', 'Arsenal', 0, 'FW', 0.52, 11],
  ['Cole Palmer', 'Chelsea', 0, 'MF', 0.48, 10],
  ['Kylian Mbappé', 'Real Madrid', 1, 'FW', 0.79, 20],
  ['Robert Lewandowski', 'Barcelona', 1, 'FW', 0.71, 17],
  ['Lamine Yamal', 'Barcelona', 1, 'FW', 0.39, 8],
  ['Jude Bellingham', 'Real Madrid', 1, 'MF', 0.44, 9],
  ['Harry Kane', 'Bayern Munich', 2, 'FW', 0.86, 23],
  ['Jamal Musiala', 'Bayern Munich', 2, 'MF', 0.47, 10],
  ['Serhou Guirassy', 'Dortmund', 2, 'FW', 0.66, 15],
  ['Florian Wirtz', 'Bayer Leverkusen', 2, 'MF', 0.42, 8],
  ['Lautaro Martínez', 'Inter', 3, 'FW', 0.68, 16],
  ['Marcus Thuram', 'Inter', 3, 'FW', 0.61, 14],
  ['Ademola Lookman', 'Atalanta', 3, 'FW', 0.54, 12],
  ['Khvicha Kvaratskhelia', 'Napoli', 3, 'FW', 0.46, 9],
  ['Ousmane Dembélé', 'Paris Saint-Germain', 4, 'FW', 0.72, 19],
  ['Bradley Barcola', 'Paris Saint-Germain', 4, 'FW', 0.58, 13],
  ['Jonathan David', 'Lille', 4, 'FW', 0.64, 15],
  ['Rayan Cherki', 'Lyon', 4, 'MF', 0.36, 7],
];
const samplePlayers = playerSeeds.map(([playerName, teamName, li, position, rate, goals], i) => ({
  teamId: 100 + i, matchId: `sample-player-match-${i}`, playerId: 1000 + i, playerName, teamName,
  competition: LEAGUES[li].label, position, appearances: 22 + i % 5, starts: 19 + i % 5,
  minutes: 1780 + i * 21, shots: 51 + Math.round(rate * 65), goals,
  xg: Number((rate * 23.2).toFixed(2)), xgPer90: Number((rate + 0.035).toFixed(3)), shrunkXgPer90: rate,
  expectedMinutes: 78 + i % 10, expectedGoals: Number((rate * (78 + i % 10) / 90).toFixed(3)),
  goalProbability: Number((1 - Math.exp(-rate * (78 + i % 10) / 90)).toFixed(4)),
})).sort((a, b) => b.shrunkXgPer90 - a.shrunkXgPer90);

const championProbabilities = [0.438, 0.302, 0.142, 0.068, 0.03, 0.014, 0.005, 0.001];
const sampleSimulation = LEAGUES.flatMap((league, li) => teams[li].map((team, i) => ({
  league: league.label, team, averagePoints: Number((86.4 - i * 5.15 + li * 0.7).toFixed(1)),
  pointsStandardDeviation: Number((4.7 + i * 0.13).toFixed(2)), averageRank: Number((1.9 + i * 1.28).toFixed(2)),
  championshipProbability: championProbabilities[i], topPlacesProbability: [0.99, 0.97, 0.89, 0.75, 0.27, 0.09, 0.03, 0.01][i],
  relegationProbability: [0, 0, 0, 0.001, 0.003, 0.008, 0.02, 0.042][i],
  actualRank: i + 1, actualPoints: 88 - i * 6 + li,
  rankHistogram: `${i + 1}:0.45|${i + 2}:0.32|${Math.max(1, i)}:0.23`,
})));

const history = {};
const historyPayload = (analysis, items) => ({ analysis, order: HISTORY_ANALYSES.find(a => a.id === analysis).token, count: items.length, items });
history['P6.1'] = historyPayload('P6.1', LEAGUES.flatMap((league, li) => ['2021-22', '2022-23', '2023-24', '2024-25', '2025-26'].map((season, i) => ({
  kind: 'CHAMPION_EVOLUTION', league: league.label, season, team: teams[li][i % 3], player: null, dimension: null,
  value: 83 + i * 3 + li, secondaryValue: 24 + i * 2, count: li === 2 || li === 4 ? 34 : 38,
}))));
history['P6.2'] = historyPayload('P6.2', LEAGUES.flatMap((league, li) => ['2021-22', '2022-23', '2023-24', '2024-25', '2025-26'].map((season, i) => ({
  kind: 'HOME_ADVANTAGE', league: league.label, season, team: null, player: null, dimension: null,
  value: Number((0.47 - i * 0.008 + li * 0.004).toFixed(3)), secondaryValue: Number((0.29 + i * 0.007 - li * 0.003).toFixed(3)), count: 380,
}))));
history['P6.3'] = historyPayload('P6.3', LEAGUES.flatMap(league => ['0–15', '16–30', '31–45', '46–60', '61–75', '76–90+'].map((dimension, i) => ({
  kind: 'GOAL_MINUTES', league: league.label, season: '2025-26', dimension, team: null, player: null,
  value: [0.12, 0.145, 0.17, 0.163, 0.177, 0.225][i], secondaryValue: 0, count: [120, 145, 170, 163, 177, 225][i],
}))));
history['P6.4'] = historyPayload('P6.4', LEAGUES.flatMap((league, li) => [0, 2, 4].map(i => ({
  kind: 'HEAD_TO_HEAD', league: league.label, season: null, team: `${teams[li][i]} vs ${teams[li][i + 1]}`,
  player: null, dimension: null, value: 0.44 + i * 0.025, secondaryValue: 0.31 - i * 0.015, count: 20 + i,
}))));
history['P6.5'] = historyPayload('P6.5', ['2017-18', '2018-19', '2019-20', '2020-21', '2021-22', '2022-23', '2023-24', '2024-25', '2025-26'].map((season, i) => ({
  kind: 'CAREER_CURVE', league: null, season, player: 'Lionel Messi', team: null, dimension: 'intl',
  value: [4, 6, 3, 5, 9, 8, 11, 6, 7][i], secondaryValue: [1, 2, 0, 1, 2, 3, 2, 1, 2][i], count: 10 + i % 4,
})));
history['P6.6'] = historyPayload('P6.6', LEAGUES.flatMap((league, li) => ['2023-24', '2024-25', '2025-26'].flatMap((season, si) => [
  ['goalsPerGame', 2.7 + li * 0.1 + si * 0.05], ['shotsPerGame', 25.4 + li * 0.7],
  ['shotsOnTargetPerGame', 9.2 + li * 0.3], ['foulsPerGame', 20.2 + li * 1.3],
  ['cornersPerGame', 9.1 + li * 0.2], ['yellowsPerGame', 3.6 + li * 0.15],
].map(([dimension, value]) => ({ kind: 'LEAGUE_STYLE', league: league.label, season, team: null, player: null, dimension, value: Number(value.toFixed(2)), secondaryValue: 0, count: 380 })))));
history['P6.7'] = historyPayload('P6.7', Array.from({ length: 11 }, (_, i) => ({
  kind: 'SCORE_DIFFERENCE', league: 'league', season: '2020s', team: null, player: null, dimension: String(i - 5),
  value: [0.004, 0.009, 0.026, 0.077, 0.18, 0.253, 0.23, 0.13, 0.058, 0.024, 0.009][i],
  secondaryValue: 0, count: [40, 90, 260, 770, 1800, 2530, 2300, 1300, 580, 240, 90][i],
})));
history['P6.8'] = historyPayload('P6.8', LEAGUES.flatMap((league, li) => ['2023-24', '2024-25', '2025-26'].map((season, si) => ({
  kind: 'NATIONALITY', league: league.label, season, team: null, player: null, dimension: 'summary',
  value: Number((0.58 + li * 0.035 + si * 0.012).toFixed(3)), secondaryValue: 3.15 + li * 0.07, count: 62 + li * 3 + si,
}))));

const evaluation = {
  split: 'test', matches: 2280, accuracy: 0.6474, macroF1: 0.6178, logLoss: 0.8916, rps: 0.1728, drawMargin: 0.11,
  confusion: { H: '791,147,91', D: '193,252,132', A: '134,107,433' },
  metrics: { count: '2280', accuracy: '0.6474', macro_f1: '0.6178', log_loss: '0.8916', rps: '0.1728', draw_margin: '0.11', precision_H: '0.7075', precision_D: '0.4980', precision_A: '0.6601', recall_H: '0.7687', recall_D: '0.4367', recall_A: '0.6424' },
};

const models = {
  randomForests: [
    { key: 'club', label: '俱乐部随机森林', available: true, location: '示例模型 / club-forest', modelVersion: 'rf-v2.1', featureSchemaVersion: 'club-73-v2', featureCount: 73, expectedFeatureCount: 73, treeCount: 200, featureCountMatches: true, configuredDrawMargin: 0.11, drawMarginSource: 'config (not stored in the model file)', parameters: { maxDepth: 12, minSamplesLeaf: 8 } },
    { key: 'international', label: '国家队随机森林', available: true, location: '示例模型 / international-forest', modelVersion: 'rf-v2.0', featureSchemaVersion: 'intl-56-v2', featureCount: 56, expectedFeatureCount: 56, treeCount: 200, featureCountMatches: true, configuredDrawMargin: 0.15, drawMarginSource: 'config (not stored in the model file)', parameters: { maxDepth: 10, minSamplesLeaf: 6 } },
  ],
  playerXg: { available: true, location: '示例模型 / player-xg.json', version: 'lr-xg-v1.2', featureCount: 16, expectedFeatureCount: 16, coefficientCount: 16, coefficientsMatchFeatures: true, intercept: -1.92, featureNames: ['distance', 'angle', 'header', 'leftFoot', 'rightFoot', 'openPlay', 'setPiece', 'penalty', 'oneOnOne', 'underPressure', 'firstTime', 'assisted', 'cutback', 'cross', 'throughBall', 'rebound'] },
};

const catalogSeeds = [
  ['prepare', 'SCORE_PREDICTION', '准备训练数据', ['input', 'workdir']],
  ['train', 'SCORE_PREDICTION', '训练 Dixon-Coles 模型', ['workdir']],
  ['evaluate', 'SCORE_PREDICTION', '评估比分预测', ['workdir']],
  ['baseline', 'SCORE_PREDICTION', '基准模型对比', ['workdir']],
  ['predict', 'SCORE_PREDICTION', '预测比赛比分', ['model', 'fixtures']],
  ['simulate', 'SEASON_SIMULATION', '蒙特卡洛赛季模拟', ['input']],
  ['history-p65', 'HISTORY', '球员生涯曲线', ['analysis', 'input', 'workdir']],
  ['history-p66', 'HISTORY', '联赛风格演变', ['analysis', 'input', 'workdir']],
  ['history-p67', 'HISTORY', '比分差分布', ['analysis', 'input', 'workdir']],
  ['history-p68', 'HISTORY', '国籍多样性分析', ['analysis', 'input', 'workdir']],
  ['club-predict', 'MATCH_OUTCOME', '随机森林胜平负预测', ['feature-input', 'model-path', 'prediction-output']],
  ['club-evaluate', 'MATCH_OUTCOME', '胜平负模型评估', ['prediction-input', 'evaluation-output']],
  ['club-clean', 'MATCH_OUTCOME', '清洗五大联赛数据', ['raw-input', 'clean-output']],
  ['club-demo', 'MATCH_OUTCOME', '执行胜平负预测流水线', ['raw-input', 'existing-model', 'work-root']],
  ['score', 'PLAYER_PREDICTION', '球员 xG 预测', ['shots-input', 'appearances-input', 'existing-model-dir', 'work-root']],
  ['champions', 'HISTORY', '冠军演变分析', ['clean-five-league-matches', 'output']],
  ['home-advantage', 'HISTORY', '主场优势分析', ['clean-five-league-matches', 'output']],
  ['h2h', 'HISTORY', '历史交锋分析', ['clean-five-league-matches', 'output']],
  ['goal-minutes', 'HISTORY', '进球时间分析', ['statsbomb-raw-root', 'work-root']],
];
const catalog = catalogSeeds.map(([id, module, description, args], i) => ({
  id, module, description, origin: i < 10 ? 'PARTNER' : 'HANDOFF', jar: i < 10 ? 'partner' : i < 14 ? 'module2' : i === 14 ? 'module3' : 'module5',
  argStyle: i < 10 ? 'NAMED' : 'POSITIONAL', retrains: id === 'train', expectedSeconds: id === 'train' ? 600 : 30 + i * 5,
  arguments: args.map(name => ({ name, required: true, defaultValue: null, description: name })),
}));

const jobs = { count: 4, items: [
  { id: 1042, runId: 'sample-sim-1042', module: 'SEASON_SIMULATION', command: 'simulate', origin: 'PARTNER', status: 'RUNNING', modelVersion: 'dc-v1.4', yarnApplicationId: 'application_sample_1042', startedAt: '2026-02-18T09:42:00', finishedAt: null },
  { id: 1041, runId: 'sample-rf-1041', module: 'MATCH_OUTCOME', command: 'club-predict', origin: 'HANDOFF', status: 'SUCCEEDED', modelVersion: 'rf-v2.1', yarnApplicationId: 'application_sample_1041', startedAt: '2026-02-18T09:38:00', finishedAt: '2026-02-18T09:38:31' },
  { id: 1040, runId: 'sample-xg-1040', module: 'PLAYER_PREDICTION', command: 'score', origin: 'HANDOFF', status: 'SUCCEEDED', modelVersion: 'lr-xg-v1.2', yarnApplicationId: 'application_sample_1040', startedAt: '2026-02-18T09:35:00', finishedAt: '2026-02-18T09:36:28' },
  { id: 1039, runId: 'sample-hist-1039', module: 'HISTORY', command: 'home-advantage', origin: 'HANDOFF', status: 'SUCCEEDED', modelVersion: null, yarnApplicationId: 'application_sample_1039', startedAt: '2026-02-18T09:31:00', finishedAt: '2026-02-18T09:31:24' },
] };

/** Synthetic examples for interface preview; these are not model results. */
export const demoData = {
  mode: 'demo', sample: true,
  matches: { source: 'random-forest', count: sampleMatches.length, accuracy: sampleMatches.filter(row => row.correct).length / sampleMatches.length, items: sampleMatches },
  fixtures: { source: 'dixon-coles', count: sampleFixtures.length, accuracy: null, items: sampleFixtures },
  players: { count: samplePlayers.length, items: samplePlayers },
  simulation: { count: sampleSimulation.length, items: sampleSimulation },
  realTable: { count: sampleSimulation.length, items: sampleSimulation.map(row => ({ league: row.league, team: row.team, rank: row.actualRank, points: row.actualPoints, played: 38, goalsFor: 82 - row.actualRank * 3, goalsAgainst: 22 + row.actualRank * 2, goalDifference: 60 - row.actualRank * 5 })) },
  evaluation, models, history, jobs, catalog,
};

function clone(value) { return JSON.parse(JSON.stringify(value)); }
function listWithItems(payload, items) { return { ...payload, count: items.length, items }; }
function pick(object, keys) { return Object.fromEntries(keys.filter(key => hasValue(object[key])).map(key => [key, object[key]])); }

function demoPage(page, filters) {
  const byLeague = row => !filters.league || row.league === filters.league;
  const bySeason = row => !filters.season || row.season === filters.season;
  const matches = () => {
    const source = filters.source === 'dixon-coles' ? demoData.fixtures : demoData.matches;
    const items = source.items.filter(row => source.source === 'dixon-coles' || (byLeague(row) && bySeason(row)));
    const scored = items.filter(row => row.correct !== null);
    return { ...listWithItems(source, items), accuracy: scored.length ? scored.filter(row => row.correct).length / scored.length : null };
  };
  const players = () => listWithItems(demoData.players, demoData.players.items.filter(row => (!filters.competition || row.competition.toLowerCase() === String(filters.competition).toLowerCase()) && (!filters.league || row.competition === filters.league) && (!filters.position || row.position.toLowerCase().includes(String(filters.position).toLowerCase()))).slice(0, filters.limit || 100));
  const simulation = () => listWithItems(demoData.simulation, demoData.simulation.items.filter(byLeague));
  const data = {};
  if (['dashboard', 'matches'].includes(page)) data.matches = matches();
  if (['dashboard', 'players'].includes(page)) data.players = players();
  if (['dashboard', 'simulation'].includes(page)) data.simulation = simulation();
  if (page === 'simulation') data.realTable = listWithItems(demoData.realTable, demoData.realTable.items.filter(byLeague));
  if (['dashboard', 'evaluation'].includes(page)) data.evaluation = { ...demoData.evaluation, split: filters.split || 'test' };
  if (['dashboard', 'models'].includes(page)) data.models = demoData.models;
  if (['dashboard', 'jobs'].includes(page)) {
    data.jobs = listWithItems(demoData.jobs, demoData.jobs.items.filter(row => !filters.runId || row.runId === filters.runId));
    data.catalog = demoData.catalog;
  }
  if (page === 'history') {
    const source = demoData.history[filters.analysis || 'P6.2'];
    if (!source) throw new ApiError('未知历史分析类型', { code: 'invalid-analysis' });
    data.history = listWithItems(source, source.items.filter(row => byLeague(row) && bySeason(row)));
  }
  return clone(data);
}

const PAGE_NAMES = new Set(['dashboard', 'matches', 'players', 'simulation', 'history', 'evaluation', 'models', 'jobs']);

/**
 * settings: { mode:'demo'|'live', baseURL:'', paths:{matches,players,simulation,
 * history,evaluation}, signal?, timeoutMs? }. Each path may be a plain string;
 * matches also accepts { 'random-forest':path, 'dixon-coles':path }, history
 * accepts { 'P6.1':path }, and evaluation accepts { test:path, validation:path }.
 * filters: league, season, source, competition, position, limit, analysis, split.
 * An optional jobs 404 is represented as available:false, never sample jobs.
 * The dashboard also tolerates unavailable job status without losing core data;
 * the dedicated jobs page reports other request failures to its caller.
 */
export async function fetchPageData(page, settings = {}, filters = {}) {
  if (!PAGE_NAMES.has(page)) throw new ApiError(`未知页面：${page}`, { code: 'invalid-page' });
  const normalized = normalizeFilters(filters);
  const mode = settings.mode === 'demo' ? 'demo' : 'live';
  const metadata = { mode, updatedAt: new Date().toISOString(), warnings: [] };
  if (mode === 'demo') return { ...metadata, ...demoPage(page, normalized) };
  const paths = settings.paths || {};
  const request = (path, query = {}) => apiRequest(path, {
    baseURL: settings.baseURL || '', signal: settings.signal, timeoutMs: settings.timeoutMs,
    fetchImpl: settings.fetchImpl, query,
  });
  const locationFor = key => {
    const config = paths[key];
    if (!config || typeof config !== 'object') return config;
    const selector = key === 'matches' ? normalized.source || 'random-forest'
      : key === 'evaluation' ? normalized.split || 'test'
        : normalized.analysis || 'P6.2';
    return config[selector];
  };
  const requestJobs = async () => {
    try { return await request('/api/jobs', pick(normalized, ['limit', 'runId'])); }
    catch (error) {
      if (error.status === 404) {
        metadata.warnings.push('作业服务未启用，请在后端启用 MySQL 作业记录后连接。');
        return { count: 0, items: [], available: false, note: '作业服务未启用（HTTP 404）' };
      }
      if (page !== 'dashboard' || error.code === 'aborted') throw error;
      const note = `作业状态暂不可用：${error.message}`;
      metadata.warnings.push(note);
      return { count: 0, items: [], available: false, note };
    }
  };
  const loaders = {
    matches: () => request('/api/results/matches', { ...pick(normalized, normalized.source === 'dixon-coles' ? ['source'] : ['league', 'season', 'source']), location: locationFor('matches') }),
    players: () => request('/api/results/players', { ...pick(normalized, ['position', 'limit']), competition: normalized.competition || normalized.league, location: locationFor('players') }),
    simulation: () => request('/api/results/simulation', { ...pick(normalized, ['league']), location: locationFor('simulation') }),
    realTable: () => request('/api/results/real-table', pick(normalized, ['league'])),
    history: () => request('/api/results/history', { ...pick(normalized, ['league', 'season']), analysis: normalized.analysis || 'P6.2', location: locationFor('history') }),
    evaluation: () => request('/api/results/evaluation', { split: normalized.split || 'test', location: locationFor('evaluation') }),
    models: () => request('/api/models'),
    jobs: requestJobs,
    catalog: () => request('/api/catalog/jobs'),
  };
  const sections = {
    dashboard: ['matches', 'players', 'simulation', 'evaluation', 'models', 'jobs', 'catalog'],
    matches: ['matches'], players: ['players'], simulation: ['simulation', 'realTable'],
    history: ['history'], evaluation: ['evaluation'], models: ['models'], jobs: ['jobs', 'catalog'],
  }[page];
  if (paths.realTable && sections.includes('realTable')) metadata.warnings.push('真实积分榜的读取位置由后端配置决定。');
  const values = await Promise.all(sections.map(key => loaders[key]()));
  return { ...metadata, ...Object.fromEntries(sections.map((key, i) => [key, values[i]])) };
}

export const submitJob = (request, settings = {}) => apiRequest('/api/jobs', { ...settings, method: 'POST', body: request });
export const getJob = (id, settings = {}) => apiRequest(`/api/jobs/${encodeURIComponent(id)}`, settings);
export const getJobLog = (id, settings = {}) => apiRequest(`/api/jobs/${encodeURIComponent(id)}/log`, settings);
export const killJob = (id, settings = {}) => apiRequest(`/api/jobs/${encodeURIComponent(id)}/kill`, { ...settings, method: 'POST' });
