-- FootPred 元数据表（由 deploy/sql/schema.sql 派生）
-- 库由 JDBC URL 指定，这里只建表

CREATE TABLE IF NOT EXISTS job_instance (
  id                  BIGINT       NOT NULL AUTO_INCREMENT,
  run_id              VARCHAR(64)  NOT NULL COMMENT '归档目录名，如 train-20261012-210109',
  module              VARCHAR(32)  NOT NULL COMMENT 'SCORE_PREDICTION/MATCH_OUTCOME/PLAYER_PREDICTION/SEASON_SIMULATION/HISTORY',
  command             VARCHAR(48)  NOT NULL COMMENT 'catalog 里的命令 id，如 club-predict',
  origin              VARCHAR(16)  NOT NULL COMMENT 'PARTNER 或 HANDOFF',
  model_version       VARCHAR(64)      NULL COMMENT '本次使用的模型版本',
  input_uri           VARCHAR(512)     NULL COMMENT '主输入路径',
  output_uri          VARCHAR(512)     NULL COMMENT '主输出路径',
  yarn_application_id VARCHAR(64)      NULL COMMENT '最后一次 attempt 的 application id',
  status              VARCHAR(24)  NOT NULL DEFAULT 'PENDING'
                      COMMENT 'PENDING/SUBMITTING/RUNNING/SUCCEEDED/FAILED/KILLED',
  args_json           TEXT             NULL COMMENT '完整参数快照，便于复现',
  exit_code           INT              NULL,
  error_message       VARCHAR(1024)    NULL,
  started_at          DATETIME         NULL,
  finished_at         DATETIME         NULL,
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                      ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  -- run_id 在交接包里是唯一的归档名，但搭档包同名的 train/eval 可能撞车，
  -- 因此唯一键加上 command。
  UNIQUE KEY uk_run_command (run_id, command),
  KEY idx_status (status),
  KEY idx_module_created (module, created_at),
  KEY idx_app (yarn_application_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一次作业提交的元数据';

CREATE TABLE IF NOT EXISTS job_attempt (
  id                  BIGINT      NOT NULL AUTO_INCREMENT,
  job_instance_id     BIGINT      NOT NULL,
  attempt_no          INT         NOT NULL COMMENT '第几轮，从 1 开始',
  yarn_application_id VARCHAR(64)     NULL,
  status              VARCHAR(24) NOT NULL DEFAULT 'RUNNING',
  progress            INT         NOT NULL DEFAULT 0 COMMENT '0-100，来自 YARN REST',
  map_progress        INT         NOT NULL DEFAULT 0,
  reduce_progress     INT         NOT NULL DEFAULT 0,
  started_at          DATETIME        NULL,
  finished_at         DATETIME        NULL,
  PRIMARY KEY (id),
  KEY idx_job (job_instance_id),
  CONSTRAINT fk_attempt_job FOREIGN KEY (job_instance_id)
    REFERENCES job_instance (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='迭代训练的逐轮明细';

CREATE TABLE IF NOT EXISTS match_prediction (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  run_id          VARCHAR(64)  NOT NULL,
  source          VARCHAR(24)  NOT NULL COMMENT 'random-forest / dixon-coles',
  match_id        VARCHAR(96)  NOT NULL,
  season          VARCHAR(16)      NULL COMMENT '统一为 2015-16 形式',
  league          VARCHAR(48)      NULL COMMENT '统一为 Premier League 形式',
  epoch_day       INT              NULL,
  home_team       VARCHAR(96)  NOT NULL,
  away_team       VARCHAR(96)  NOT NULL,
  actual          CHAR(1)          NULL COMMENT 'H/D/A',
  predicted       CHAR(1)          NULL COMMENT 'H/D/A',
  p_home          DECIMAL(9,8)     NULL,
  p_draw          DECIMAL(9,8)     NULL,
  p_away          DECIMAL(9,8)     NULL,
  lambda_home     DECIMAL(9,6)     NULL,
  lambda_away     DECIMAL(9,6)     NULL,
  top_scores      VARCHAR(512)     NULL,
  is_correct      TINYINT(1)       NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_run_match (run_id, source, match_id),
  KEY idx_season_league (season, league),
  KEY idx_teams (home_team, away_team)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='比赛预测结果';

CREATE TABLE IF NOT EXISTS evaluation_metric (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  run_id      VARCHAR(64)  NOT NULL,
  module      VARCHAR(32)  NOT NULL,
  split       VARCHAR(24)  NOT NULL COMMENT 'validation/test/backtest/all',
  metric_key  VARCHAR(64)  NOT NULL,
  metric_value VARCHAR(128) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_run_split_key (run_id, module, split, metric_key),
  KEY idx_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评估指标，key-value 形式保留全部字段';

CREATE TABLE IF NOT EXISTS player_profile (
  id               BIGINT      NOT NULL AUTO_INCREMENT,
  run_id           VARCHAR(64) NOT NULL,
  season           VARCHAR(16) NOT NULL,
  team_id          INT         NOT NULL,
  player_id        INT         NOT NULL,
  player_name      VARCHAR(128)    NULL,
  team_name        VARCHAR(96)     NULL,
  competition      VARCHAR(64)     NULL,
  position         VARCHAR(48)     NULL,
  appearances      INT             NULL,
  starts           INT             NULL,
  minutes          DECIMAL(10,2)   NULL,
  shots            INT             NULL,
  goals            INT             NULL,
  xg               DECIMAL(10,4)   NULL,
  xg_per90         DECIMAL(10,6)   NULL,
  shrunk_xg_per90  DECIMAL(10,6)   NULL,
  expected_minutes DECIMAL(10,4)   NULL,
  lambda           DECIMAL(10,6)   NULL,
  goal_probability DECIMAL(10,6)   NULL,
  PRIMARY KEY (id),
  -- One reducer profile per season, team and player. Never merge seasons.
  UNIQUE KEY uk_run_season_team_player (run_id, season, team_id, player_id),
  KEY idx_rank (run_id, shrunk_xg_per90)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='球员 xG 画像';

CREATE TABLE IF NOT EXISTS season_simulation (
  id              BIGINT      NOT NULL AUTO_INCREMENT,
  run_id          VARCHAR(64) NOT NULL,
  league          VARCHAR(48) NOT NULL,
  team            VARCHAR(96) NOT NULL,
  avg_points      DECIMAL(6,2)    NULL,
  sd_points       DECIMAL(6,2)    NULL,
  avg_rank        DECIMAL(6,2)    NULL,
  p_champion      DECIMAL(7,6)    NULL,
  p_top           DECIMAL(7,6)    NULL,
  p_relegated     DECIMAL(7,6)    NULL,
  real_rank       INT             NULL,
  real_points     INT             NULL,
  rank_hist       VARCHAR(1024)   NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_run_league_team (run_id, league, team),
  KEY idx_champ (run_id, p_champion)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='蒙特卡洛赛季模拟结果';

CREATE TABLE IF NOT EXISTS history_point (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  run_id        VARCHAR(64)  NOT NULL,
  analysis      VARCHAR(16)  NOT NULL COMMENT 'P6.1..P6.8',
  league        VARCHAR(48)      NULL,
  season        VARCHAR(24)      NULL,
  team          VARCHAR(128)     NULL,
  player        VARCHAR(128)     NULL,
  dimension     VARCHAR(64)      NULL,
  value_num     DECIMAL(14,6)     NULL,
  value_second  DECIMAL(14,6)     NULL,
  cnt           BIGINT           NULL,
  PRIMARY KEY (id),
  KEY idx_run_analysis (run_id, analysis),
  KEY idx_leagueseason (league, season)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='八项历史分析的统一长表';
