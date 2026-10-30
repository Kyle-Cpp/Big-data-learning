"""Back up and migrate the pre-season player table; requires the mysql CLI.

Run with the backend stopped. This migrates identifiers only; re-import each
run's original players.tsv afterward. Aggregated legacy rows cannot be split
back into seasons from the database alone. No passwords are written or printed.
"""
import argparse
import getpass
import os
from pathlib import Path
import re
import shutil
import subprocess
from urllib.parse import urlparse


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, help='Private Spring properties file')
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', default=3306, type=int)
    parser.add_argument('--database', default='footpred')
    parser.add_argument('--user', default='root')
    args = parser.parse_args()
    cfg = {}
    if args.config:
        cfg = dict(line.split('=', 1) for line in args.config.read_text(encoding='utf-8').splitlines()
                   if '=' in line and not line.lstrip().startswith('#'))
        url = cfg.get('spring.datasource.url', '')
        if url.startswith('jdbc:mysql://'):
            url = urlparse(url[5:])
            args.host, args.port = url.hostname, url.port or 3306
            args.database = url.path.lstrip('/')
        args.user = cfg.get('spring.datasource.username', args.user)
    if not re.fullmatch(r'[A-Za-z0-9_]+', args.database):
        raise SystemExit('Invalid database identifier')
    mysql = shutil.which('mysql')
    if not mysql:
        raise SystemExit('Install the mysql CLI or add it to PATH')
    env = os.environ.copy()
    env['MYSQL_PWD'] = cfg.get('spring.datasource.password', env.get('MYSQL_PWD')) or getpass.getpass('MySQL password: ')
    command = [mysql, '--host', args.host, '--port', str(args.port), '--user', args.user,
               '--batch', '--skip-column-names', '--default-character-set=utf8mb4', args.database]

    def sql(statement):
        result = subprocess.run(command, input=statement, text=True, encoding='utf-8',
                                capture_output=True, env=env)
        if result.returncode:
            # MySQL stderr does not include the password (not a CLI argument).
            raise SystemExit(result.stderr.strip())
        return result.stdout.strip()

    columns = set(sql('SHOW COLUMNS FROM player_profile;').splitlines())
    columns = {line.split('\t')[0] for line in columns}
    indexes = {line.split('\t')[2] for line in sql('SHOW INDEX FROM player_profile;').splitlines()}
    needs_migration = not {'season', 'team_id'}.issubset(columns) or 'uk_run_player_team' in indexes
    if needs_migration:
        backup = 'player_profile_pre_season_v1'
        exists = sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"
                     " AND table_name='" + backup + "';")
        if exists == '0':
            sql('CREATE TABLE ' + backup + ' LIKE player_profile; INSERT INTO ' + backup + ' SELECT * FROM player_profile;')
            print('Legacy rows backed up in ' + backup)
        # NULL explicitly marks an unknown identifier: do not fabricate a season
        # or team from the already-merged legacy table.
        if 'season' not in columns:
            sql('ALTER TABLE player_profile ADD COLUMN season VARCHAR(16) NULL AFTER run_id;')
        if 'team_id' not in columns:
            sql('ALTER TABLE player_profile ADD COLUMN team_id INT NULL AFTER season;')
        if 'uk_run_season_team_player' not in indexes:
            sql('ALTER TABLE player_profile ADD UNIQUE KEY uk_run_season_team_player (run_id,season,team_id,player_id);')
        if 'uk_run_player_team' in indexes:
            sql('ALTER TABLE player_profile DROP INDEX uk_run_player_team;')
    unknown = sql('SELECT COUNT(*) FROM player_profile WHERE season IS NULL OR team_id IS NULL;')
    print('Schema ready. Rows awaiting original TSV re-import: ' + unknown)
    print('Re-import all affected runs via POST /api/admin/import/players/{runId}.')


if __name__ == '__main__':
    main()
