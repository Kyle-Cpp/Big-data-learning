#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Convert the five big-league season CSVs (football-datasets) into the
martj42/international_results results.csv format expected by the FootPred
prepare/train pipeline, so the existing MatchParser can be reused unchanged.

Source layout: <root>/<league-dir>/season-*.csv with columns
    Date,HomeTeam,AwayTeam,FTHG,FTAG,FTR,... (22 cols)
Output row format (9 cols, no quoting):
    date,home,away,FTHG,FTAG,tournament,city,country,neutral

Usage:
  py scripts/convert_leagues.py \
      --root ../../dataset/football-datasets/datasets \
      --out ../../dataset/football-datasets/results_leagues.csv
"""
import argparse
import os
import sys

LEAGUES = {
    "premier-league": ("Premier League", "", "England"),
    "la-liga":        ("La Liga",        "", "Spain"),
    "serie-a":        ("Serie A",        "", "Italy"),
    "bundesliga":     ("Bundesliga",     "", "Germany"),
    "ligue-1":        ("Ligue 1",        "", "France"),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True,
                    help="directory containing the five league folder")
    ap.add_argument("--out", required=True, help="output results.csv path")
    args = ap.parse_args()

    rows = {}
    dedup = 0
    bad = 0
    per_league = {}
    for league_dir, (tournament, city, country) in sorted(LEAGUES.items()):
        league_path = os.path.join(args.root, league_dir)
        if not os.path.isdir(league_path):
            sys.exit("missing league dir: " + league_path)
        n_league = 0
        for name in sorted(os.listdir(league_path)):
            if not (name.startswith("season-") and name.endswith(".csv")):
                continue
            with open(os.path.join(league_path, name), encoding="utf-8") as f:
                lines = f.read().splitlines()
            for line in lines[1:]:
                cols = line.split(",")
                if len(cols) < 5:
                    bad += 1
                    continue
                date, home, away, fthg, ftag = cols[:5]
                ok = (len(date) == 10 and date[4] == "-" and date[7] == "-"
                      and home and away)
                try:
                    hg, ag = int(fthg), int(ftag)
                except ValueError:
                    ok = False
                if not ok or hg < 0 or ag < 0:
                    bad += 1
                    continue
                key = (date, home, away)
                if key in rows:
                    dedup += 1
                    continue
                rows[key] = "%s,%s,%s,%d,%d,%s,%s,%s,FALSE" % (
                    date, home, away, hg, ag, tournament, city, country)
                n_league += 1
        per_league[league_dir] = n_league

    with open(args.out, "w", encoding="utf-8", newline="") as f:
        f.write("date,home_team,away_team,home_score,away_score,"
                "tournament,city,country,neutral\n")
        for line in sorted(rows.values()):
            f.write(line + "\n")

    print("wrote %d matches -> %s" % (len(rows), args.out))
    for league, n in sorted(per_league.items()):
        print("  %-15s %6d" % (league, n))
    print("  skipped bad rows: %d, duplicates: %d" % (bad, dedup))


if __name__ == "__main__":
    main()
