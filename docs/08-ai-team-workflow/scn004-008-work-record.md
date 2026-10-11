# SCN-004~008 작업 기록

- 기준: PR #385 병합 `main` (`16a1fd2`), Issue #386 → #387 → #388. 선행 PR 미병합 시 다음 PR은 선행 최종 HEAD 기반 stacked PR이다.
- 생산 호출: 거래 intake → External Risk → Rule/ML 분석·채택 → 위험 대응·사건 → 완료 Snapshot. SCN-001·002 v4는 고정 10 Rule/12 ML feature이며 004~008 신호를 포함하지 않는다.
- 재사용: `approved_at`, `created_at`, `occurred_at`의 as-of 검증, Mock 출처, Rule Evidence 검증, 사건 잠금·연결 구조. 집계 키와 선행 자금 귀속·외부 계좌 소유는 별도 증명한다.
- #386 현재: 출금 고객+계좌 10분, 수취 계좌 24시간 조회와 인덱스, PostgreSQL 반례 테스트 추가. 대상 통합 테스트 3/3 통과. Rule/ML·Evidence·대표 사건·화면·거래 E2E·CI·Gate는 미완료.
- 계약 확장 지점: Rule v4는 R001~R010/4 그룹, ML v3는 12 feature로 고정된다. 신규 신호에는 미래 cutoff에서 선택되는 새 Rule 집합·ML feature/model과 Backend 독립 재계산, DB 제약·조회 projection·Frontend parser의 동시 버전 확장이 필요하다. 기존 v4 결과를 덮어쓰면 안 된다.
- 보존: 기존 PostgreSQL·kind·Docker 프로젝트와 사용자 변경을 정리하지 않는다. 성공 로그는 종료 코드와 건수만 기록한다.
