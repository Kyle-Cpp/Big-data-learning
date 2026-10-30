import test from 'node:test';
import assert from 'node:assert/strict';
import { LANGUAGE_KEY, readLanguage, setLanguage, getLanguage, getLocale, translateText } from '../dist/i18n.js';

test('new or invalid preferences default to Chinese without consulting browser locale', () => {
  assert.equal(readLanguage({getItem: () => null}), 'zh');
  assert.equal(readLanguage({getItem: () => 'fr'}), 'zh');
  assert.equal(readLanguage({getItem: () => 'en'}), 'en');
  assert.equal(readLanguage({getItem: () => { throw new Error('Storage blocked'); }}), 'zh');
});

test('language choice persists and still changes in memory when storage is unavailable', () => {
  const entries = new Map();
  const storage = {setItem: (key, value) => entries.set(key, value), getItem: key => entries.get(key)};
  setLanguage('en', storage);
  assert.equal(entries.get(LANGUAGE_KEY), 'en');
  assert.equal(getLanguage(), 'en');
  assert.equal(getLocale(), 'en-GB');
  assert.equal(readLanguage(storage), 'en');
  setLanguage('zh', {setItem: () => { throw new Error('Storage blocked'); }});
  assert.equal(getLanguage(), 'zh');
  assert.equal(getLocale(), 'zh-CN');
});

test('all primary views and critical notices have explicit English translations', () => {
  assert.deepEqual(['数据总览','比赛预测','球员 xG','赛季模拟','历史分析','模型评估','模型中心','作业管理'].map(text => translateText(text,'en')),
    ['Overview','Match predictions','Player xG','Season simulation','Historical analysis','Model evaluation','Model registry','Job management']);
  assert.equal(translateText('数据连接设置','en'), 'Connection settings');
  assert.equal(translateText('暂无比赛预测','en'), 'No match predictions yet');
  assert.match(translateText('后端尚未启动。可切换到示例数据，或启动 Spring Boot 后刷新。','en'), /backend is not running/);
});

test('interpolated pagination, accessibility and export notices preserve identifiers and counts', () => {
  assert.equal(translateText('共 1,752 条 · 第 2 / 176 页','en'), '1,752 records · Page 2 / 176');
  assert.equal(translateText('第 2 页','en'), 'Page 2');
  assert.equal(translateText('已导出 20 条示例记录。','en'), 'Exported 20 sample records.');
  assert.equal(translateText('查看作业 1042 详情','en'), 'View job 1042 details');
  assert.equal(translateText('查看 Arsenal 对阵详情','en'), 'View fixture details: Arsenal');
});

test('Chinese copy is reversible and API paths, values and football names remain unchanged', () => {
  const copy = '比赛预测 · 随机森林';
  assert.equal(translateText(copy,'zh'), copy);
  assert.equal(translateText(copy,'en'), 'Match predictions · Random forest');
  for (const value of ['/api/results/matches','random-forest','Premier League','application_1792822091296_0042','2025-26','Arsenal vs Chelsea','73']) {
    assert.equal(translateText(value,'en'), value);
  }
});
