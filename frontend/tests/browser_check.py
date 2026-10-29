"""Browser smoke checks for the dependency-free FootPred frontend.

Start `npm run dev` in frontend first, then run this script with Python.
The real-mode check intercepts API reads; it never submits or kills a job.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
from pathlib import Path
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

from playwright.sync_api import Browser, Page, expect, sync_playwright


PAGES = (
    "dashboard", "matches", "players", "simulation", "history",
    "evaluation", "models", "jobs",
)
ROOT = Path(__file__).resolve().parents[2]
ARTIFACTS = ROOT / "artifacts" / "frontend"
CANARY_HOME = "QA Live Home 2026"
CANARY_AWAY = "QA Live Away 2026"


def address(base: str, mode: str, page: str = "dashboard") -> str:
    parts = urlsplit(base)
    query = dict(parse_qsl(parts.query))
    query["mode"] = mode
    return urlunsplit((parts.scheme, parts.netloc, parts.path or "/",
                       urlencode(query), f"/{page}"))


def first_visible(page: Page, selectors: tuple[str, ...]):
    for selector in selectors:
        for element in page.locator(selector).all():
            if element.is_visible():
                return element
    raise AssertionError(f"No visible control found: {selectors}")


def ready(page: Page) -> None:
    expect(page.locator("main, #main").first).to_be_visible()
    expect(page.locator("[data-page='dashboard'], a[href='#/dashboard']").first).to_be_attached()
    page.wait_for_timeout(180)


def navigate(page: Page, page_id: str) -> None:
    control = page.locator(f"[data-page='{page_id}'], a[href='#/{page_id}']").first
    expect(control).to_be_visible()
    control.click()
    ready(page)


def close_dialog(page: Page) -> None:
    dialog = page.get_by_role("dialog")
    expect(dialog).to_be_visible()
    page.keyboard.press("Escape")
    if dialog.count() and dialog.is_visible():
        first_visible(page, (
            "[data-action='close-dialog']", "[data-action='close-modal']",
            "[role='dialog'] button[aria-label*='关闭']",
            "[role='dialog'] button:has-text('关闭')",
            "[role='dialog'] button:has-text('取消')",
        )).click()
    expect(page.get_by_role("dialog")).to_have_count(0)


def launch(playwright) -> Browser:
    chrome = Path("C:/Program Files/Google/Chrome/Application/chrome.exe")
    if chrome.exists():
        return playwright.chromium.launch(headless=True, executable_path=str(chrome))
    return playwright.chromium.launch(headless=True, channel="chrome")


def run(base: str) -> dict:
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    report: dict = {"url": base, "checks": [], "screenshots": {}}
    errors: list[str] = []
    with sync_playwright() as playwright:
        browser = launch(playwright)
        context = browser.new_context(viewport={"width": 1440, "height": 1024},
                                      device_scale_factor=1, reduced_motion="reduce",
                                      locale="zh-CN")
        page = context.new_page()
        page.on("pageerror", lambda error: errors.append(str(error)))
        demo_reads: list[str] = []
        page.on("request", lambda request: demo_reads.append(request.url)
                if urlsplit(request.url).path.startswith("/api/") else None)
        page.goto(address(base, "demo"), wait_until="networkidle")
        ready(page)
        for page_id in PAGES:
            navigate(page, page_id)
            assert len(page.locator("main, #main").first.inner_text().strip()) > 20, page_id
        report["checks"].append("all-eight-navigation-pages")

        navigate(page, "matches")
        search = first_visible(page, ("main input[type='search']", "#main input[type='search']",
                                      "input[placeholder*='搜索']"))
        search.fill("QA_NO_MATCH_7edc8a8")
        page.wait_for_timeout(250)
        expect(page.locator("main, #main").first).to_contain_text(re.compile("暂无|没有|未找到|0 场|0 条"))
        reset = first_visible(page, (
            "[data-action='reset-filters']", "[data-action='reset']",
            "main button:has-text('重置')", "#main button:has-text('重置')",
            "main button:has-text('清空')", "#main button:has-text('清空')",
        ))
        reset.click()
        expect(search).to_have_value("")
        report["checks"].append("match-search-and-reset")

        first_visible(page, (
            "[data-source='dixon-coles']", "[role='tab']:has-text('Dixon')",
            "button:has-text('Dixon-Coles')",
        )).click()
        page.wait_for_timeout(150)
        expect(page.locator("main, #main").first).to_contain_text(re.compile("Dixon|比分|预期进球|xG"))
        first_visible(page, (
            "[data-source='random-forest']", "[role='tab']:has-text('随机森林')",
            "button:has-text('随机森林')",
        )).click()
        report["checks"].append("match-source-tabs")

        with page.expect_download() as download_info:
            first_visible(page, ("[data-action='export']",)).click()
        download = download_info.value
        assert download.suggested_filename.startswith("footpred-matches-demo-")
        assert download.suggested_filename.endswith(".csv")
        with Path(download.path()).open(encoding="utf-8-sig", newline="") as stream:
            exported = list(csv.DictReader(stream))
        assert exported and "probabilityHome" in exported[0] and "homeTeam" in exported[0]
        report["checks"].append("demo-csv-download-has-match-data")

        navigate(page, "jobs")
        first_visible(page, ("[data-action='new-job']",)).click()
        expect(page.locator("#toast")).to_contain_text("示例模式不提交作业")
        expect(page.get_by_role("dialog")).to_have_count(0)
        report["checks"].append("demo-new-job-does-not-submit")

        navigate(page, "models")
        first_visible(page, (
            "[data-action='model-details']", "[data-action='model-detail']",
            "main button:has-text('查看详情')", "main button:has-text('模型详情')",
            "#main button:has-text('查看详情')", "#main button:has-text('模型详情')",
        )).click()
        expect(page.get_by_role("dialog")).to_be_visible()
        expect(page.get_by_role("dialog")).to_contain_text(re.compile("特征|Schema|schema|版本|参数"))
        close_dialog(page)
        report["checks"].append("model-details-dialog")

        first_visible(page, ("[data-action='settings']",)).click()
        expect(page.get_by_role("dialog")).to_be_visible()
        expect(page.get_by_role("dialog")).to_contain_text(re.compile("球员|历史|路径"))
        assert page.get_by_role("dialog").locator("input, select").count() >= 2
        close_dialog(page)
        report["checks"].append("settings-dialog")
        assert demo_reads == [], f"Demo mode issued backend reads: {demo_reads}"
        report["checks"].append("demo-does-not-read-backend")

        navigate(page, "dashboard")
        expect(page.locator("#toast")).not_to_have_class(re.compile("toast-visible"), timeout=6000)
        page.wait_for_timeout(220)
        desktop = ARTIFACTS / "desktop.png"
        page.screenshot(path=str(desktop), full_page=False)
        report["screenshots"]["desktop"] = str(desktop)

        page.set_viewport_size({"width": 390, "height": 844})
        first_visible(page, ("[data-action='menu']",)).click()
        expect(page.locator(".sidebar")).to_have_class(re.compile("open"))
        navigate(page, "jobs")
        expect(page.locator(".sidebar")).not_to_have_class(re.compile("open"))
        report["checks"].append("mobile-navigation-menu")
        for page_id in PAGES:
            page.goto(address(base, "demo", page_id), wait_until="networkidle")
            ready(page)
            dimensions = page.evaluate("""() => ({
                viewport: innerWidth,
                document: document.documentElement.scrollWidth,
                body: document.body.scrollWidth
            })""")
            assert max(dimensions["document"], dimensions["body"]) <= dimensions["viewport"] + 1, (
                f"Horizontal overflow on {page_id}: {dimensions}")
        report["checks"].append("all-pages-mobile-390x844-no-document-overflow")
        page.goto(address(base, "demo"), wait_until="networkidle")
        ready(page)
        mobile = ARTIFACTS / "mobile.png"
        page.screenshot(path=str(mobile), full_page=True)
        report["screenshots"]["mobile"] = str(mobile)
        context.close()

        live_context = browser.new_context(viewport={"width": 1440, "height": 1024},
                                           reduced_motion="reduce", locale="zh-CN")
        live_reads: list[str] = []
        live_requests: list[dict] = []
        job_state = {"enabled": False, "reject_submit": False, "detail_reads": 0, "log_reads": 0}
        submissions: list[dict] = []
        fake_job = {"id": 42, "command": "club-predict", "runId": "qa", "status": "SUCCEEDED",
                    "module": "MATCH_OUTCOME", "origin": "HANDOFF", "attemptCount": 1,
                    "attempts": [], "startedAt": "2026-10-26T12:00:00", "finishedAt": "2026-10-26T12:00:01"}
        catalog = [
            {"id": "club-predict", "description": "QA Club Predict", "retrains": False,
             "arguments": [
                 {"name": "input", "required": True, "description": "QA input path", "defaultValue": None},
                 {"name": "model", "required": True, "description": "QA model path", "defaultValue": "qa-model"},
                 {"name": "output", "required": True, "description": "QA output path", "defaultValue": None},
                 {"name": "competition", "required": False, "description": "QA bounded choice",
                  "defaultValue": "Premier League", "choices": ["Premier League", "La Liga"]}]},
            {"id": "history-p65", "description": "QA History", "retrains": False,
             "arguments": [{"name": "source", "required": True, "defaultValue": "qa-history"}]},
            {"id": "club-train", "description": "QA Training", "retrains": True, "arguments": []},
        ]

        def fake_api(route) -> None:
            request_url = urlsplit(route.request.url)
            path = request_url.path
            request_query = dict(parse_qsl(request_url.query))
            live_reads.append(path)
            live_requests.append({"path": path, "query": request_query, "method": route.request.method})
            payload: object = {"count": 0, "items": []}
            status = 200
            if path == "/api/results/matches":
                payload = {"source": "random-forest", "count": 1, "accuracy": None,
                           "items": [{"matchId": "qa-live-2026", "source": "random-forest",
                                      "epochDay": 20751, "season": "2025-26", "league": "Premier League",
                                      "homeTeam": CANARY_HOME, "awayTeam": CANARY_AWAY,
                                      "probabilityHome": .55, "probabilityDraw": .25,
                                      "probabilityAway": .20, "predicted": "H", "actual": None,
                                      "correct": None, "lambdaHome": None, "lambdaAway": None,
                                      "topScores": None}]}
            elif path == "/api/catalog/jobs":
                payload = catalog if job_state["enabled"] else []
            elif path == "/api/catalog/leagues":
                payload = [{"label": "Premier League", "kebab": "premier-league"}]
            elif path == "/api/catalog/seasons":
                payload = [{"label": "2025-26", "compact": "2526"}]
            elif path == "/api/models":
                payload = {"randomForests": [], "playerXg": {"available": False}}
            elif path == "/api/results/players":
                players = [
                    {"playerId": 5503, "playerName": "QA Messi", "teamName": "Barcelona",
                     "competition": "La Liga", "position": "Right Center Forward"},
                    {"playerId": 6612, "playerName": "QA Lewandowski", "teamName": "Barcelona",
                     "competition": "La Liga", "position": "Center Forward"},
                    {"playerId": 5211, "playerName": "QA Mbappe", "teamName": "Real Madrid",
                     "competition": "La Liga", "position": "Left Center Forward"},
                ] if request_query.get("location") else []
                position = request_query.get("position", "").lower()
                players = [{**player, "minutes": 1980, "goals": 19, "xg": 17.84,
                            "shrunkXgPer90": .7421, "goalProbability": .4761}
                           for player in players if position in player["position"].lower()]
                payload = {"count": len(players), "items": players}
            elif path == "/api/results/evaluation":
                payload = {"split": "test", "matches": None, "accuracy": None,
                           "macroF1": None, "logLoss": None, "rps": None,
                           "drawMargin": None, "metrics": {}, "confusion": {}}
            elif path.startswith("/api/jobs"):
                if not job_state["enabled"]:
                    status = 404
                    payload = {"error": "Not Found", "message": "QA job storage disabled"}
                elif path == "/api/jobs" and route.request.method == "POST":
                    submissions.append(route.request.post_data_json)
                    if job_state["reject_submit"]:
                        status = 429
                        payload = {"error": "concurrency-limit", "message": "QA concurrency capacity reached"}
                    else:
                        status = 202
                        payload = {**fake_job, "poll": "/api/jobs/42"}
                elif path == "/api/jobs/42":
                    job_state["detail_reads"] += 1
                    payload = fake_job
                elif path == "/api/jobs/42/log":
                    job_state["log_reads"] += 1
                    payload = {"id": 42, "runId": "qa", "status": "SUCCEEDED",
                               "source": "stored", "log": "QA TERMINAL LOG: completed successfully"}
                elif path == "/api/jobs/summary":
                    payload = {"SUCCEEDED": len(submissions), "activeNow": 0}
                else:
                    payload = {"count": 1 if submissions else 0, "items": [fake_job] if submissions else []}
            route.fulfill(status=status, content_type="application/json; charset=utf-8",
                          body=json.dumps(payload, ensure_ascii=False))

        live_context.route("**/api/**", fake_api)
        live_page = live_context.new_page()
        live_page.on("pageerror", lambda error: errors.append(str(error)))
        live_page.goto(address(base, "live", "matches"), wait_until="networkidle")
        ready(live_page)
        expect(live_page.locator("main, #main").first).to_contain_text(CANARY_HOME)
        expect(live_page.locator("main, #main").first).to_contain_text(CANARY_AWAY)
        assert "/api/results/matches" in live_reads
        report["checks"].append("live-renders-api-response-without-demo-fallback")

        def last_query(path: str) -> dict:
            return next(request["query"] for request in reversed(live_requests)
                        if request["path"] == path)

        def save_settings() -> None:
            live_page.get_by_role("button", name=re.compile("保存并连接")).click()
            expect(live_page.get_by_role("dialog")).to_have_count(0)
            live_page.wait_for_load_state("networkidle")
            ready(live_page)

        first_visible(live_page, ("[data-action='settings']",)).click()
        live_page.locator("#path-matches").fill("qa/random-forest.tsv")
        live_page.locator("#path-evaluation").fill("qa/evaluation-test.tsv")
        live_page.locator("#path-players").fill("qa/players.tsv")
        save_settings()
        assert last_query("/api/results/matches")["location"] == "qa/random-forest.tsv"
        first_visible(live_page, ("[data-source='dixon-coles']",)).click()
        ready(live_page)
        assert "location" not in last_query("/api/results/matches")
        first_visible(live_page, ("[data-action='settings']",)).click()
        expect(live_page.locator("#path-matches")).to_have_value("")
        live_page.locator("#path-matches").fill("qa/dixon-coles.tsv")
        expect(live_page.locator("#path-evaluation")).to_have_value("qa/evaluation-test.tsv")
        save_settings()
        assert last_query("/api/results/matches")["location"] == "qa/dixon-coles.tsv"
        first_visible(live_page, ("[data-source='random-forest']",)).click()
        ready(live_page)
        assert last_query("/api/results/matches")["location"] == "qa/random-forest.tsv"
        first_visible(live_page, ("[data-action='settings']",)).click()
        expect(live_page.locator("#path-matches")).to_have_value("qa/random-forest.tsv")
        close_dialog(live_page)
        report["checks"].append("settings-preserve-separate-match-source-paths")

        navigate(live_page, "evaluation")
        assert last_query("/api/results/evaluation")["location"] == "qa/evaluation-test.tsv"
        live_page.locator("[data-filter='split']").select_option("validation")
        ready(live_page)
        assert "location" not in last_query("/api/results/evaluation")
        first_visible(live_page, ("[data-action='settings']",)).click()
        expect(live_page.locator("#path-evaluation")).to_have_value("")
        live_page.locator("#path-evaluation").fill("qa/evaluation-validation.tsv")
        save_settings()
        assert last_query("/api/results/evaluation") == {"split": "validation", "location": "qa/evaluation-validation.tsv"}
        live_page.locator("[data-filter='split']").select_option("test")
        ready(live_page)
        assert last_query("/api/results/evaluation") == {"split": "test", "location": "qa/evaluation-test.tsv"}
        live_page.reload(wait_until="networkidle")
        ready(live_page)
        saved_paths = live_page.evaluate("JSON.parse(localStorage.getItem('footpred-settings')).paths")
        assert saved_paths["matches"] == {"random-forest": "qa/random-forest.tsv", "dixon-coles": "qa/dixon-coles.tsv"}
        assert saved_paths["evaluation"] == {"test": "qa/evaluation-test.tsv", "validation": "qa/evaluation-validation.tsv"}
        assert last_query("/api/results/evaluation")["location"] == "qa/evaluation-test.tsv"
        report["checks"].append("settings-preserve-evaluation-split-paths-after-reload")

        navigate(live_page, "players")
        positions = live_page.locator("[data-filter='position'] option").evaluate_all("items=>items.map(item=>item.value)")
        assert {"Center Forward", "Right Center Forward", "Left Center Forward"}.issubset(positions), positions
        live_page.locator("[data-filter='position']").select_option("Right Center Forward")
        ready(live_page)
        assert last_query("/api/results/players")["position"] == "Right Center Forward"
        assert last_query("/api/results/players")["location"] == "qa/players.tsv"
        expect(live_page.locator("main, #main").first).to_contain_text("QA Messi")
        expect(live_page.locator("main, #main").first).not_to_contain_text("QA Lewandowski")
        live_page.locator("[data-filter='position']").select_option("")
        ready(live_page)
        expect(live_page.locator("main, #main").first).to_contain_text("QA Lewandowski")
        report["checks"].append("live-player-position-values-match-backend-output")

        navigate(live_page, "jobs")
        expect(live_page.locator("main, #main").first).to_contain_text(
            re.compile("未启用|未开启|未连接|尚未|不可用|404|数据库|MySQL|disabled", re.I))
        report["checks"].append("disabled-job-service-state")

        job_state["enabled"] = True
        first_visible(live_page, ("[data-action='refresh']",)).click()
        expect(live_page.locator("main, #main").first).to_contain_text("暂无作业记录")
        first_visible(live_page, ("[data-action='new-job']",)).click()
        expect(live_page.locator("#job-form")).to_be_visible()
        expect(live_page.locator("#job-command option")).to_have_count(2)
        assert "club-train" not in live_page.locator("#job-command").inner_text()
        expect(live_page.locator("[data-argument='model']")).to_have_value("qa-model")
        expect(live_page.locator("[data-argument='input']")).to_have_attribute("required", "")
        live_page.locator("#job-command").select_option("history-p65")
        expect(live_page.locator("[data-argument='source']")).to_have_value("qa-history")
        expect(live_page.locator("[data-argument='input']")).to_have_count(0)
        live_page.locator("#job-command").select_option("club-predict")
        live_page.locator("#job-run").fill("qa")
        live_page.locator("[data-argument='input']").fill("qa-input")
        live_page.locator("[data-argument='output']").fill("qa-output")
        live_page.locator("[data-argument='competition']").select_option("La Liga")
        report["checks"].append("live-job-dynamic-catalog-arguments-and-training-filter")

        live_page.locator("#job-form [type='submit']").click()
        expect(live_page.get_by_role("dialog")).to_have_count(0)
        expect(live_page.locator("main, #main").first).to_contain_text("club-predict")
        assert submissions == [{"command": "club-predict", "runId": "qa", "arguments": {
            "input": "qa-input", "model": "qa-model", "output": "qa-output", "competition": "La Liga"}}], submissions
        report["checks"].append("live-job-202-submit-exact-json-body")

        first_visible(live_page, ("[data-action='job-details'][data-id='42']",)).click()
        expect(live_page.get_by_role("dialog")).to_contain_text("QA TERMINAL LOG: completed successfully")
        expect(live_page.get_by_role("dialog")).to_contain_text("qa")
        expect(live_page.locator("[data-action='kill-job']")).to_have_count(0)
        initial_reads = (job_state["detail_reads"], job_state["log_reads"])
        assert initial_reads == (1, 1), initial_reads
        live_page.wait_for_timeout(5600)
        assert initial_reads == (job_state["detail_reads"], job_state["log_reads"]), job_state
        close_dialog(live_page)
        report["checks"].append("terminal-live-job-renders-log-and-stops-polling")

        job_state["reject_submit"] = True
        first_visible(live_page, ("[data-action='new-job']",)).click()
        live_page.locator("#job-run").fill("qa-rejected")
        live_page.locator("[data-argument='input']").fill("qa-input")
        live_page.locator("[data-argument='output']").fill("qa-output")
        live_page.locator("#job-form [type='submit']").click()
        expect(live_page.locator("#job-error")).to_contain_text("QA concurrency capacity reached")
        expect(live_page.locator("#job-form [type='submit']")).to_be_enabled()
        close_dialog(live_page)
        report["checks"].append("live-job-429-keeps-form-and-shows-backend-message")
        live_context.close()
        browser.close()
    assert not errors, f"Browser runtime errors: {errors}"
    report["checks"].append("no-browser-runtime-errors")
    report["result"] = "PASS"
    report_path = ARTIFACTS / "browser-report.json"
    report["report"] = str(report_path)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:5173")
    arguments = parser.parse_args()
    print(json.dumps(run(arguments.url), ensure_ascii=False, indent=2))
