"""Language switch browser regression checks; uses real UI with isolated browser storage."""
import argparse
import json
import re
from pathlib import Path
from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'artifacts/i18n-20261028'
PAGES = {
    'dashboard': 'Overview', 'matches': 'Match predictions', 'players': 'Player xG',
    'simulation': 'Season simulation', 'history': 'Historical analysis',
    'evaluation': 'Model evaluation', 'models': 'Model registry', 'jobs': 'Job management',
}


def run(url, baseline=False):
    OUT.mkdir(parents=True, exist_ok=True)
    checks, errors, requests = [], [], []
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True, executable_path='C:/Program Files/Google/Chrome/Application/chrome.exe')
        context = browser.new_context(viewport={'width': 1440, 'height': 1024}, locale='en-US', reduced_motion='reduce')
        page = context.new_page()
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.on('request', lambda request: requests.append(request.url) if '/api/' in request.url else None)
        page.goto(url + '/?mode=demo', wait_until='networkidle')
        expect(page.locator('main h1')).to_contain_text('数据总览')
        if baseline:
            assert page.locator('[data-language]').count() == 0
            page.screenshot(path=str(OUT / 'baseline.png'))
            browser.close()
            report = {'result': 'BASELINE_PASS', 'default': 'zh-CN', 'languageSwitchPresent': False}
            (OUT / 'baseline-report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf8')
            return report
        checks.append('fresh-visit-defaults-to-Chinese-even-with-English-browser-locale')
        page.locator('[data-language="en"]').click()
        expect(page.locator('main h1')).to_contain_text('Overview')
        expect(page.locator('html')).to_have_attribute('lang', 'en')
        assert page.evaluate('localStorage.getItem("footpred-language")') == 'en'
        checks.append('English-switch-updates-page-and-document-language')
        for key, title in PAGES.items():
            page.locator(f'.nav [data-page="{key}"]').click()
            expect(page.locator('main h1')).to_contain_text(title)
            page.wait_for_timeout(80)
            untranslated = page.locator('main').evaluate("""el => {
                const walker=document.createTreeWalker(el,NodeFilter.SHOW_TEXT), result=[];
                while(walker.nextNode()){
                    const n=walker.currentNode;
                    if(!n.parentElement.closest('pre,code,[data-i18n-skip]') && /[\u3400-\u9fff]/u.test(n.textContent))result.push(n.textContent.trim());
                }
                return result;
            }""")
            assert not untranslated, f'{key}: untranslated UI text: {untranslated}'
        checks.append('all-eight-pages-have-English-copy-without-Chinese-UI-fragments')
        page.reload(wait_until='networkidle')
        expect(page.locator('main h1')).to_contain_text('Job management')
        checks.append('English-choice-survives-reload-and-navigation')
        page.locator('.nav [data-page="matches"]').click()
        page.locator('[data-filter="league"]').select_option('Premier League')
        page.locator('#table-search').fill('Arsenal')
        expect(page.locator('main')).to_contain_text('Arsenal')
        page.locator('[data-language="zh"]').click()
        expect(page.locator('main h1')).to_contain_text('比赛预测')
        expect(page.locator('[data-filter="league"]')).to_have_value('Premier League')
        expect(page.locator('#table-search')).to_have_value('Arsenal')
        expect(page.locator('html')).to_have_attribute('lang', 'zh-CN')
        page.locator('[data-language="en"]').click()
        expect(page.locator('[data-filter="league"]')).to_have_value('Premier League')
        checks.append('switching-both-ways-preserves-filters-search-and-API-values')
        page.locator('[data-action="reset-filters"]').click()
        page.locator('[data-source="dixon-coles"]').click()
        expect(page.locator('main')).to_contain_text('Dixon-Coles · Fixtures')
        checks.append('source-tabs-and-dynamic-counts-are-translated')
        page.locator('[data-action="settings"]').first.click()
        dialog = page.get_by_role('dialog')
        expect(dialog).to_contain_text('Connection settings')
        expect(dialog).to_contain_text('Result file locations')
        expect(dialog.locator('#path-players')).to_have_attribute('placeholder', 'Result file path')
        dialog.locator('#path-players').fill('/exports/球员.tsv')
        expect(dialog.locator('#path-players')).to_have_value('/exports/球员.tsv')
        page.keyboard.press('Escape')
        checks.append('settings-labels-placeholders-and-accessibility-are-English-path-inputs-unchanged')
        page.locator('.nav [data-page="models"]').click()
        page.locator('[data-action="model-details"]').first.click()
        expect(page.get_by_role('dialog')).to_contain_text('Feature schema')
        assert '示例模型' in page.locator('pre').inner_text(), 'Raw JSON should retain original API content'
        page.keyboard.press('Escape')
        checks.append('model-dialog-is-English-raw-JSON-is-preserved')
        page.locator('[data-action="help"]').click()
        expect(page.get_by_role('dialog')).to_contain_text('Using FootPred')
        expect(page.get_by_role('dialog')).to_contain_text('Compare attacking performance')
        page.keyboard.press('Escape')
        page.locator('.nav [data-page="jobs"]').click()
        page.locator('[data-action="new-job"]').click()
        expect(page.locator('#toast')).to_contain_text('Sample mode does not submit jobs.')
        checks.append('help-and-operation-notifications-are-translated')
        page.locator('.nav [data-page="matches"]').click()
        page.locator('[data-source="random-forest"]').click()
        page.locator('.nav [data-page="dashboard"]').click()
        page.wait_for_timeout(4300)
        page.screenshot(path=str(OUT / 'english-desktop.png'))
        page.set_viewport_size({'width': 390, 'height': 844})
        for key in PAGES:
            page.goto(url + '/?mode=demo#/' + key, wait_until='networkidle')
            expect(page.locator('[data-language="zh"]')).to_be_visible()
            expect(page.locator('[data-language="en"]')).to_be_visible()
            assert page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1'), key
        checks.append('English-language-controls-and-all-pages-fit-mobile-390px')
        page.goto(url + '/?mode=demo#/dashboard', wait_until='networkidle')
        page.screenshot(path=str(OUT / 'english-mobile.png'), full_page=True)
        page.locator('[data-language="zh"]').click()
        expect(page.locator('main h1')).to_contain_text('数据总览')
        page.reload(wait_until='networkidle')
        expect(page.locator('main h1')).to_contain_text('数据总览')
        checks.append('switching-back-to-Chinese-is-reversible-and-persistent')
        assert not requests, requests
        checks.append('language-switch-does-not-trigger-backend-requests-in-demo')
        page.set_viewport_size({'width': 1440, 'height': 1024})
        page.locator('[data-language="en"]').click()
        context.route('**/api/**', lambda route: route.fulfill(status=502, content_type='application/json', body=json.dumps({
            'error':'backend-unavailable','message':'后端尚未启动。可切换到示例数据，或启动 Spring Boot 后刷新。'
        }, ensure_ascii=False)))
        page.locator('[data-mode="live"]').click()
        expect(page.locator('main')).to_contain_text('The backend is not running.')
        expect(page.locator('main')).to_contain_text('Data is not connected')
        checks.append('live-connection-errors-are-translated-without-demo-fallback')
        submitted = []
        def fake_jobs(route):
            path = route.request.url.split('?')[0]
            if path.endswith('/api/catalog/jobs'):
                payload = [{'id':'history-p65','description':'球员生涯曲线','retrains':False,'arguments':[
                    {'name':'analysis','required':True,'defaultValue':'p65','description':'必填，固定为 p65'},
                    {'name':'input','required':True,'description':'输入位置','defaultValue':'/exports/输入.tsv'}]}]
            elif route.request.method == 'POST':
                submitted.append(route.request.post_data_json)
                route.fulfill(status=429, content_type='application/json', body=json.dumps({
                    'error':'concurrency-limit','message':'已达并发上限 1（单节点 2 核机器不建议并行），请等待当前作业完成'}, ensure_ascii=False))
                return
            else:
                payload = {'count':0,'items':[]}
            route.fulfill(status=200, content_type='application/json', body=json.dumps(payload, ensure_ascii=False))
        context.unroute('**/api/**')
        context.route('**/api/**', fake_jobs)
        page.locator('.nav [data-page="jobs"]').click()
        page.locator('[data-action="new-job"]').click()
        expect(page.get_by_role('dialog')).to_contain_text('Player career curve')
        expect(page.get_by_role('dialog')).to_contain_text('Required; must be p65')
        expect(page.locator('[data-argument="input"]')).to_have_value('/exports/输入.tsv')
        page.locator('#job-form button[type="submit"]').click()
        expect(page.locator('#job-error')).to_contain_text('Concurrent job limit reached:')
        assert submitted == [{'command':'history-p65','arguments':{'analysis':'p65','input':'/exports/输入.tsv'}}]
        page.keyboard.press('Escape')
        checks.append('English-live-job-form-and-business-errors-preserve-original-submission-values')
        assert not errors, errors
        checks.append('no-browser-runtime-errors')
        browser.close()
    report = {'result': 'PASS', 'checks': checks, 'errors': errors}
    (OUT / 'language-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--url', default='http://127.0.0.1:5173')
    parser.add_argument('--baseline', action='store_true')
    args = parser.parse_args()
    print(json.dumps(run(args.url.rstrip('/'), args.baseline), ensure_ascii=False, indent=2))
