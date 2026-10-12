# Issue #394 최종 main 로컬 통합 검증 기록

## 범위와 식별

- 기준 원격 main: `1a59d9fe81c6c690ff50efe080f9f91d7c90e946`; 전용 브랜치 `issue-394-final-local-e2e`에서 시작했다. 최종 변경 SHA와 공식 Keycloak Gate SHA는 아래에 별도로 기록한다.
- 격리 Compose 프로젝트 및 DB: `finguardops-394-i39476817c0d2763`. 다른 프로젝트의 #382/#384 PostgreSQL 및 #373 kind 자원은 사용하거나 정리하지 않았다.
- 최종 검증 시도 ID: `c4834a0384c1`, KST 날짜 `2026-10-12`. 이 DB의 앞선 fixture 조정 시도는 통과 결과에 합산하지 않았다. 직전 API 기준값은 거래 98(채택 87, 미분류 11), 신규 사건 39였다.
- 실행 이미지는 이 기준 main의 Backend/AI Service를 각각 `:i39476817c0d2763`으로 빌드했다. External Risk는 전용 모의 Provider, AI 리포트는 도달 불가 Ollama URL에 따른 `TEMPLATE_FALLBACK`이다. 실제 Qwen 검증으로 표시하지 않는다.

## 공개 접수에서 저장까지

`python infra/local-jwt-fixture/verify_issue394_e2e.py --tag i39476817c0d2763 verify`의 종료 코드는 **0**이다. 합성 거래는 공개 `POST /api/v1/transactions`로 생성했고, 채택 조회 API와 PostgreSQL의 `financial_transaction`·`detection_result`·`detection_evidence`·`case_transaction`·`audit_log`를 교차 검사했다. Rule set SHA-256 `b06a7e6edf1baa5cd0e2636d7721a5447ef2a1d47a0debf9964e23aca96e8bcc`, 점수 정책 `rule-ml-policy-v6`, feature `fraud-feature-v5`, model `fraud-logistic-v6` SHA-256 `707284450be52621a7ca00455eab9edfa74fbbef207efbf89ffc87267cd97383`을 각 거래의 API·DB에서 확인했다. 이 실행의 ML 기여도는 모두 0이며, 모델 정확도 측정이 아니다. 로컬 전체 출력 `backend/build/issue394-anchored-run.jsonl`의 SHA-256은 `022e29026f24a2fd8887ae1ef50693dcd7b6fd9bcd7c74a29c79b582c5130410`이다(ignored 로컬 증거, PR에는 아래 요약만 포함).

| 시나리오 | 거래 ID | 채택 결과 ID | 사건 ID | Rule 점수/대응 | 첫 근거 ID / 첫 Audit ID (전체 개수) |
| --- | --- | --- | --- | --- | --- |
| SCN-001 | `12b2c1fe-87c4-4da0-977c-8a9a3aa37c2d` | `e3160ce4-5c11-4ece-8bfd-837bcda7380a` | `df9c7043-a39a-4347-991a-9ea9263cea86` | 50 / 추가 인증 | `56d43664-02b1-45e1-9045-17d46b1f3686` / `10c81b70-4c1c-4a5f-8b1b-dc05bc71b78c` (9 / 4) |
| SCN-002 | `16a901f2-dedc-4d2c-9a4d-90ef504fd7b8` | `6f80a188-d759-4ed9-9bd8-675530afc41a` | `f8ed1eb9-6b80-4d55-b29e-b20d995e4106` | 75 / 추가 인증 | `f99071b9-7ac9-4170-ac58-def1ddb45e68` / `64008ef0-b827-4609-855c-709697aea398` (9 / 4) |
| SCN-003 | `165605a7-07d7-4c52-8b9c-c7a5d76195a2` | `1a7931f2-5d56-42a9-8ada-8b043322fde5` | `768e3946-5201-4428-8dde-35d548d7b050` | 55 / 추가 인증 | `e1e0826c-4f74-488b-9465-4984333aac9f` / `d6adc6cf-860c-4485-86a1-87e88a61b3a8` (8 / 4) |
| SCN-004 | `4a7a9dd6-42d7-4cf0-8e10-fb14a2b70345` | `1a87869d-21a7-4265-a6d0-9718b9ac06b5` | `32425230-53d6-4c6d-bc70-5b337968d4c6` | 70 / 추가 인증 | `e6077917-6b8b-4b75-8634-5352e57cbc79` / `210a8544-96c4-4ca3-87f4-aa037afe37e8` (10 / 4) |
| SCN-005 | `087de080-e9bf-4473-8027-ed15323f4cf0` | `d2f68449-d8c9-4d2e-a687-08cd1b729844` | `c68715ca-3a7f-453d-9a8e-879e7ceb8180` | 80 / 보류 | `b0192fde-468e-4e84-9c8d-ccd3f2042537` / `8e8c58ac-405d-4d77-b387-2cd27ac1203e` (9 / 4) |
| SCN-006 | `b9e348c2-e49e-4dd3-8504-a9d22f1af1d0` | `d6ed52bf-4746-47eb-a9e5-051e897e7f27` | `cb490853-2baa-4313-adb5-2cccfd6af902` | 50 / 추가 인증 | `f9e2906a-fbdc-4288-b5ac-d13d37f72f2a` / `bdc16d90-776b-4243-85a0-db74932230db` (7 / 4) |
| SCN-007 | `88aae41c-0b32-4a03-8043-9708dfe369fb` | `b91d1a43-b946-48df-9292-1823781096e8` | `720c35cb-5455-4c05-a3aa-e45b061bd6a1` | 50 / 추가 인증 | `ae16855e-df9e-44df-9237-fddad5babd39` / `9ed146d4-a01f-4a02-b7cd-4c0c7d2ad908` (7 / 4) |
| SCN-008 | `08e46944-5fa7-4a12-9031-89f74950b3ad` | `3d987bc1-d443-4d9b-9b48-c1a780836971` | `ecf7bbc4-b44a-4ed8-be83-95c20843c7d1` | 50 / 추가 인증 | `7c4e0c59-131c-419b-ad20-93cf6243178c` / `138d87f2-696d-41d7-ba01-0c1ca3af40ac` (7 / 4) |

SCN-001의 과거 승인 10건은 별도 합성 DB 준비 이력이다. 각 시나리오 **대상** 거래는 공개 접수 API를 사용했다. SCN-004의 최초 수취인 판단에는 전체 이력 coverage가 필요하며, 1일 coverage만 준 초기 부분 실행은 Rule 적중에도 49점 `MEDIUM`, 사건 없음으로 끝났다. 이는 [기존 상한 계약](../03-api/scn004-005-detection.md)에 맞는 정상 반례다. SCN-005 대상은 R005 외부 위험 수취 계좌도 적중하므로 SCN-003 집계에 중복 **시나리오 포함**되지만 고유 거래·사건은 한 건이다. SCN-001/002는 생성한 event·fact ID, SCN-004/005는 coverage와 선행 거래 ID, SCN-006/007은 원천 거래·Mock link·사건 연결 ID, SCN-008은 2개 stage의 원천 거래·link·소유권 assertion ID를 Rule observation 및 사건 연결 집합과 대조했다. 저장된 cutoff와 API의 집계·자금 흐름·오픈뱅킹 Evidence도 비교했다.

## 동일 실행의 집계와 리포트

대상 8건 외에 공개 접수한 선행·정상 거래 10건은 모두 사건 없이 채택·승인됐다. 동일 멱등 키 재전송은 저장 snapshot이 불변이고 상충 재전송은 409였다. Provider 503 거래 `61c42253-482a-4ccf-82bf-1b4cad0d89af`는 `RECEIVED`, 채택 결과 없음으로 남았다. Rule HTTP 장애 거래 `b0f12c27-8291-4db8-80a0-1c62e74d4042`는 `FAILED/AI_SERVICE_UNAVAILABLE`, ML HTTP 장애 거래 `2fb5a32f-8114-4655-842f-076393786bfd`는 `FAILED/ML_SERVICE_UNAVAILABLE`로, 모두 채택·근거·사건·Audit 없이 끝났고 같은 멱등 키의 실패 재전송을 확인했다.

KST `2026-10-12` 대시보드 API와 DB의 기간별 고유 ID 집합을 독립 산출해 비교한 최종 실행 전후 차이는 거래 **+21**(채택 **+18**, 미분류 **+3**), 신규 사건 **+8**이다. 장애 주입 전 중간값은 +19/+18/+1/+8이었다. 시나리오별 고유 거래 증가는 SCN-001/002/004/005/006/007/008 각각 +1, SCN-003 +2다. `currentStatuses`는 사건 상태 변경 뒤에도 DB의 당일 사건 상태별 고유 건수와 일치했다. 기간 밖 합성 과거 승인 이력과 이전 부분 시도는 이 차이에 포함하지 않았다.

SCN-003 사건 `768e3946-5201-4428-8dde-35d548d7b050`은 거래 접수 이후 별도 `POST /cases/{id}/ai-reports`로 요청했다. 요청 ID `2c31bfad-bad4-4767-92b5-59889040094b`, 실행 ID `38d5b56b-d00c-41df-874f-b17bbef09392`, 저장 리포트 ID `4e970660-ed5b-42e4-94c1-76116a8a18da`와 채택 탐지 결과 버전을 API·DB에서 대조했다. 상태는 `FALLBACK_COMPLETED`, 출처는 `TEMPLATE_FALLBACK`이다. 사건은 `IN_REVIEW`로 변경됐고 원래 채택 탐지 snapshot은 그대로였다.

## 영향 검증과 경계

| 단계 | 결과 | 범위 |
| --- | --- | --- |
| Backend 기준 대시보드 PostgreSQL 통합 테스트 | 종료 0 | 최종 main의 집계 기준 확인 |
| Backend 5개 PostgreSQL 통합 테스트 클래스 | 종료 0 | 이력 cutoff, 역순·늦은 승인, 집계 고유성, 부분 자금 연결, 오픈뱅킹 소유 중복, KST 대시보드 |
| AI Service Rule/ML 시나리오 v4~v7 테스트 | 종료 0, 24 passed | 합성 Rule/ML 계약; 실제 탐지 성능 아님 |
| Backend Scenario/External Risk/멱등 단위 테스트 | 종료 0 | 집계 상한, 외부 장애, 멱등 정책 |
| Backend Rule 장애·ML 장애·동시 멱등 PostgreSQL 통합 메서드 3개 | 종료 0 | Rule 확정 실패의 재생, ML 실패 시 미채택, 동시 요청 단일 Provider 승자 |
| 전용 JWT/PostgreSQL 전체 시도 | 종료 0 | 위 8건의 같은 DB·같은 시도, 별도 리포트와 대시보드 |
| Provider/Rule/ML 장애 주입 | 종료 0 | 같은 시도의 503·미채택·무사건·재전송 및 최종 미분류 +3 |
| Frontend TypeScript typecheck | 종료 0 | Keycloak Browser fixture 변경의 정적 타입 검증 |
| Python verifier/Keycloak fixture 문법 | 종료 0 | `py_compile`, 실제 Compose fault overlay는 위 주입에서 검증 |

실 Keycloak 역할별 Browser Gate, Kafka 소비, kind/GHCR 인증 pull, 실제 Qwen은 위 JWT E2E 종료 코드 0의 범위가 아니다. Kafka·kind/GHCR·실제 Qwen은 각각 [Kafka runbook](local-kafka-ai-report-runbook.md), [kind runbook](local-kubernetes-critical-e2e-runbook.md), [Qwen 평가](../08-ai-team-workflow/local-ai-report-evaluation.md)의 별도 실행·환경 증거로만 다룬다. #373 GHCR private 발행과 인증 pull은 해당 runbook상 미완료 경계다. 운영용 임계값, 실제 거래 제어, 탐지 Precision/Recall은 이 Issue에서 확정하지 않았다.

## 공식 Gate·리뷰·자원 상태

이 기록은 커밋 전 전용 JWT/PostgreSQL 실행의 재현 정보다. 최종 clean commit SHA에서 수행하는 Keycloak `Prepare → Validate → Service → Run`의 종료 코드와 실행 ID, 역할별 Browser 확인 및 CI 결과는 PR과 최종 보고에 별도 연결한다. JWT fixture 결과로 공식 Browser Gate를 대신하지 않는다.
