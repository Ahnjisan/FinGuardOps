# Issue #382 로컬 거래 E2E 증거 (2026-10-10 UTC)

합성 고객·계좌 참조만 사용했다. 고유 Compose 프로젝트는 `finguardops-382-i382oct10a1`이다. Backend, FastAPI Rule·ML, PostgreSQL, External Risk HTTP fixture와 로컬 JWT fixture를 연결했다. 정책 발행 one-shot v1·v2·v3은 각각 종료 코드 0이었고, 활성 집합은 R001–R004 v3 네 건과 R005 v1 한 건(`1|1`, `3|4`)이었다. 완료 Snapshot이나 기존 프로젝트 DB는 재계산하지 않았다.

최종 `verify_scn003_e2e.py --tag i382oct10a1 verify` 전체 실행 종료 코드는 0이다. 같은 거래 ID로 공개 접수, 채택 API, PostgreSQL 점수·Evidence, 거래 상태·사건·Audit를 비교했다.

| 반례/결과 | 관측값 |
| --- | --- |
| 위험 수취 계좌 | 거래 `2c93fd91-6e1f-48ad-87f3-539828739463`, Rule 40 + ML 0 = 40/MEDIUM, APPROVED, 채택 ID `a0238015-fe32-41d4-99d6-3a9ac925d627` |
| 비위험·송신 계좌만 적중 | 각각 Rule 0 + ML 0 = 0/LOW, 채택 결과의 `recipientAccountMatched=false` |
| 정확히 24시간 경계 | Rule 40/MEDIUM, 포함 경계로 채택 |
| 늦은 접수 | 거래 `e9bccddb-2e48-4488-930e-d5c9c582274d`, Rule 40/MEDIUM, 적격 과거 이력 미관측 |
| 같은 키 재전송·다른 본문 | 같은 응답 201, 다른 본문 409; Provider·Rule·ML 재호출과 DB 행 증가 없음 |
| 24시간 초과·미래 시각·Provider 코드 불일치·모순 match | 각각 500 `INTERNAL_ERROR` 실패 Snapshot, 거래 RECEIVED, 채택 탐지·사건·Audit 없음 |
| unavailable·timeout | 각각 503 `DEPENDENCY_UNAVAILABLE`·`DEPENDENCY_TIMEOUT` 실패 Snapshot, 거래 RECEIVED, 채택 탐지·사건·Audit 없음 |
| 같은 수취 계좌의 동시 두 거래 | 거래 `a18a2cd3-9527-4673-a803-7b69facb7022`와 `4824a71f-d8ed-47eb-8001-74b25437c295`가 각각 Rule 50 + ML 0 = 50/HIGH, ADDITIONAL_AUTH_REQUIRED; 사건 `fe71051d-0782-4bf6-b207-88bfd78e214b`와 `b5405d6b-dc1d-4bed-bc03-88df6c4e93d9`로 분리 |

R005 단독 40/MEDIUM과 R005+ML 기여 40의 80/CRITICAL은 별도 PostgreSQL 통합 테스트에서 검증했다. 실제 연결된 로컬 ML 모델의 위 거래 기여는 0이었으므로 이 E2E가 80점의 실제 모델 예측을 증명하지는 않는다. 금융 실운영 임계값이나 실제 사기 성능으로 해석하지 않는다.

최종 실행 전에 E2E fixture의 실패 HTTP 매핑과 행동 이벤트 접수 시각 조건을 보정했다. 이전의 중단 실행은 성공으로 합산하지 않았다. 최종 실행 뒤 DB에는 합성 거래 51건, 탐지 31건, Evidence 116건, 사건 2건, Audit 66건이 있었고 PostgreSQL 익명 볼륨 `d8a09c0fe844a3f9edc98ce66ea4858d650ee16053b57793d4a672f32769c5d6`을 보존했다. #382 소유 앱 컨테이너 네 개만 정지하고 PostgreSQL은 계속 실행 중이다. 다른 프로젝트·kind 자원은 변경하지 않았다.

측정 시점의 호스트 물리 메모리는 총 15.69 GiB, E2E 시작 전 가용 1.82 GiB, 최종 점검 시 가용 1.77 GiB였으며 Docker 할당은 8,161,275,904 bytes였다. 이 수치는 구간 최솟값이 아니다. #382 소유 컨테이너 다섯 개 모두 OOMKilled=false, RestartCount=0이었다. Backend·AI·Provider 로그에서 합성 고객·계좌 참조 문자열 적중은 각각 0건이었다. 실제 Secret 값은 기록하지 않았다.

공식 Keycloak Browser Gate는 별도 clean commit 단계에서 실행한다. 이 문서는 그 결과를 미리 통과로 기록하지 않는다.
