"""Verify the packaged frontend against actual Spring HTTP responses and TSV fixtures.

Start the JAR with footpred.local.root=. and evaluation/simulation/real-table
pointing to src/test/resources/fixtures, using the command in VERIFICATION.txt.
No API requests are intercepted; no jobs are submitted.
"""
import argparse
import json
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import urlopen
from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[2]


def run(base):
    fixture = 'src/test/resources/fixtures/'
    paths = {
        'matches': {'random-forest': fixture + 'rf4-predictions-sample.tsv', 'dixon-coles': fixture + 'predict-part-m-sample.tsv'},
        'players': fixture + 'p4-4-player-predictions-sample.tsv',
        'simulation': fixture + 'simulation-head.tsv',
        'history': {'P6.2': fixture + 'p6-2-home-advantage-sample.tsv'},
        'evaluation': {'test': fixture + 'evaluation-test.tsv'},
    }

    def get(path, query=None):
        with urlopen(base + path + ('?' + urlencode(query) if query else ''), timeout=10) as response:
            return json.load(response)

    assets = {}
    for asset in ['/', '/app.js', '/data.js', '/styles.css', '/favicon.svg']:
        with urlopen(base + asset, timeout=10) as response:
            assert response.status == 200
            data = response.read()
            expected = ROOT / 'frontend/dist' / ('index.html' if asset == '/' else asset[1:])
            assert data == expected.read_bytes(), f'Stale packaged asset: {asset}'
            assets[asset] = {'status': response.status, 'bytes': len(data), 'sourceBytesMatch': True}
    matches = get('/api/results/matches', {'location': paths['matches']['random-forest']})
    players = get('/api/results/players', {'location': paths['players']})
    simulation = get('/api/results/simulation', {'location': paths['simulation']})
    evaluation = get('/api/results/evaluation', {'location': paths['evaluation']['test']})
    history = get('/api/results/history', {'analysis': 'P6.2', 'location': paths['history']['P6.2']})
    assert matches['count'] == 6
    assert players['count'] == 6
    assert any(row['team'] == 'Bayern Munich' for row in simulation['items'])
    assert evaluation['matches'] == 1752 and evaluation['accuracy'] == 0.51655251
    assert history['count'] > 0
    errors = []
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True, executable_path='C:/Program Files/Google/Chrome/Application/chrome.exe')
        context = browser.new_context(viewport={'width': 1440, 'height': 1024})
        settings = json.dumps({'mode': 'live', 'baseURL': '', 'paths': paths})
        context.add_init_script(f'localStorage.setItem("footpred-settings", {json.dumps(settings)});')
        page = context.new_page()
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.goto(base + '/?mode=live#/matches', wait_until='networkidle')
        expect(page.locator('main')).to_contain_text('Arsenal')
        page.locator('[data-source="dixon-coles"]').click()
        expect(page.locator('main')).to_contain_text('Dixon-Coles')
        expect(page.locator('main .full-table tbody tr')).not_to_have_count(0)
        page.goto(base + '/?mode=live#/players', wait_until='networkidle')
        expect(page.locator('main')).to_contain_text('Lionel Messi')
        positions = page.locator('[data-filter="position"] option').all_text_contents()
        assert 'Center Forward' in positions, positions
        page.locator('[data-filter="position"]').select_option('Center Forward')
        expect(page.locator('main')).to_contain_text('Robert Lewandowski')
        page.goto(base + '/?mode=live#/simulation', wait_until='networkidle')
        expect(page.locator('main')).to_contain_text('Bayern Munich')
        page.goto(base + '/?mode=live#/evaluation', wait_until='networkidle')
        expect(page.locator('main')).to_contain_text('51.7%')
        expect(page.locator('main')).to_contain_text('1,752')
        page.goto(base + '/?mode=live#/history', wait_until='networkidle')
        expect(page.locator('main .full-table tbody tr')).not_to_have_count(0)
        browser.close()
    assert not errors, errors
    report = {'result': 'PASS', 'base': base, 'assets': assets, 'matches': matches['count'],
              'players': players['count'], 'simulation': simulation['count'], 'history': history['count'],
              'evaluationMatches': evaluation['matches'], 'accuracy': evaluation['accuracy'],
              'browser': 'real Spring API; matches/DC/players/position/simulation/evaluation/history verified; zero runtime errors'}
    output = ROOT / 'artifacts/frontend/spring-report.json'
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--url', default='http://127.0.0.1:18080')
    print(json.dumps(run(parser.parse_args().url.rstrip('/')), ensure_ascii=False, indent=2))
