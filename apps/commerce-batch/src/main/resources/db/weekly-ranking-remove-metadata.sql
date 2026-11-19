-- 기존 주간 랭킹 스키마 전환용. 주간 랭킹 배치를 중지한 상태에서 한 번 실행
ALTER TABLE weekly_ranking_input DROP FOREIGN KEY weekly_ranking_input_ibfk_1;
ALTER TABLE weekly_ranking_result DROP FOREIGN KEY weekly_ranking_result_ibfk_1;

DROP TABLE weekly_ranking_publication;
DROP TABLE weekly_ranking_snapshot;
