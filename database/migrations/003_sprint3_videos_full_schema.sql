-- database/migrations/003_sprint3_videos_full_schema.sql
-- ChildFocus Sprint 3 — Align videos table with schema.sql
-- Run this if your DB was created before Sprint 2/3 schema updates.
-- Safe to run: ALTER TABLE ADD COLUMN is a no-op if column already exists (SQLite 3.37+)

ALTER TABLE videos ADD COLUMN video_title          TEXT;
ALTER TABLE videos ADD COLUMN thumbnail_url        TEXT;
ALTER TABLE videos ADD COLUMN thumbnail_intensity  REAL;
ALTER TABLE videos ADD COLUMN heuristic_score      REAL;
ALTER TABLE videos ADD COLUMN nb_score             REAL;
ALTER TABLE videos ADD COLUMN preliminary_label    TEXT;
ALTER TABLE videos ADD COLUMN classified_by        TEXT;
ALTER TABLE videos ADD COLUMN video_duration_sec   REAL;
ALTER TABLE videos ADD COLUMN runtime_seconds      REAL;
