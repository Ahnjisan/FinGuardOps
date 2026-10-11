# SCN-004~008 작업 기록

- 기준: PR #385 병합 `main` (`16a1fd2`), Issue #386 → #387 → #388. 선행 PR 미병합 시 다음 PR은 선행 최종 HEAD 기반 stacked PR이다.
- 생산 호출: 거래 intake → External Risk → Rule/ML 분석·채택 → 위험 대응·사건 → 완료 Snapshot. SCN-001·002 v4는 고정 10 Rule/12 ML feature이며 004~008 신호를 포함하지 않는다.
- 재사용: `approved_at`, `created_at`, `occurred_at`의 as-of 검증, Mock 출처, Rule Evidence 검증, 사건 잠금·연결 구조. 집계 키와 선행 자금 귀속·외부 계좌 소유는 별도 증명한다.
- #386: 출금 고객+계좌 10분, 수취 계좌 24시간 조회와 인덱스, Rule v5/ML v4 로컬 계약, Evidence/조회/화면, 대표 사건과 과거 거래 연결 감사 경로 구현. 최종 Backend 단위·PostgreSQL 통합 합계 1,664건(실패 0, 기존 skip 1), AI pytest 521건, Frontend Vitest 2,879건과 빌드, 004·005 거래 통합 경로 및 CI를 통과했다.
- #389는 원격에서 사용자에 의해 병합됐고, clean commit `bffb697`의 공식 Browser Gate는 재검증 `Prepare → Validate → Service → Run` 종료 코드 모두 0, Browser 40건 통과였다. Issue #386은 열린 상태다.
- #387은 새 `main` `db74a9b`에서 시작했다. V35/V36, 인증된 로컬 Mock 자금 연결과 계좌 흐름 완전성, cutoff 조회, Rule v6/ML v5, Evidence/대표 사건/화면 경로를 구현했다. Backend 단위 1,344건·PostgreSQL 통합 326건(기존 skip 1), AI 525건, Frontend 2,880건과 lint/typecheck/build를 통과했다. 이후 PostgreSQL 자금 연결 반례 2건을 추가해 대상 재검증을 통과했고, 최종 전체 수량은 clean commit에서 재확인한다. CI·공식 Browser Gate도 최종 clean commit에서 확인한다.
- 계약 확장 지점: Rule v4는 R001~R010/4 그룹, ML v3는 12 feature로 고정된다. 신규 신호에는 미래 cutoff에서 선택되는 새 Rule 집합·ML feature/model과 Backend 독립 재계산, DB 제약·조회 projection·Frontend parser의 동시 버전 확장이 필요하다. 기존 v4 결과를 덮어쓰면 안 된다.
- 보존: 기존 PostgreSQL·kind·Docker 프로젝트와 사용자 변경을 정리하지 않는다. 성공 로그는 종료 코드와 건수만 기록한다.
