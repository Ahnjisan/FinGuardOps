# Management endpoint 운영 경계

## 1. 목적과 범위

이 문서는 Spring Boot Backend의 Actuator web endpoint와 production runtime
Prometheus registry의 현재 운영 경계를 정의한다. 금융거래 API 계약을 변경하지 않는다.
Issue #196의 로컬 Compose scrape는 구현되었지만 production scrape·집계·알림이
구성되었다는 의미가 아니다.

## 2. profile별 상태

| 상태 | registry·export | web exposure | listener |
| --- | --- | --- | --- |
| 기본 profile | Prometheus export 명시적 비활성, `PrometheusMeterRegistry` 자동구성 안 됨 | `health` | 기존 애플리케이션 listener 정책 유지 |
| `prometheus` profile | production `PrometheusMeterRegistry`와 export 활성 | 정확히 `health,prometheus` | 별도 management listener, 기본 `127.0.0.1:8081` |

기본 profile에서는 기존 `/api/health`와 `/actuator/health`를 유지하고
`/actuator/prometheus`를 노출하지 않는다. 이 경로 요청은 endpoint가 존재하거나
인증으로 차단된 응답이 아니라 미등록 리소스의 `404 Not Found`로 처리된다. 응답은
공통 오류 계약의 `RESOURCE_NOT_FOUND`, `요청한 리소스를 찾을 수 없습니다.`, 빈
`fieldErrors`를 사용하며 요청 path·query와 내부 예외 정보는 포함하지 않는다.
`prometheus` profile에서는 다음 GET endpoint만 web으로 노출한다.

| Endpoint | 기본 profile | `prometheus` profile | 용도 |
| --- | --- | --- | --- |
| `/actuator/health` | 노출 | 노출 | Backend health 확인 |
| `/actuator/prometheus` | 미노출, 요청 시 `404 Not Found` | 노출, `200 OK` | Prometheus 호환 scrape payload |

`env`, `beans`, `configprops`, `mappings`, `metrics`, `loggers`, `heapdump`,
`threaddump`를 포함한 그 밖의 Actuator endpoint는 web에 노출하지 않는다.

## 3. port·address 환경 변수

| 환경 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `MANAGEMENT_SERVER_PORT` | `8081` | `prometheus` profile의 별도 management port |
| `MANAGEMENT_SERVER_ADDRESS` | `127.0.0.1` | `prometheus` profile의 management bind address |

두 환경 변수는 profile 활성 시에만 management listener 설정을 재정의한다. 기본
profile은 별도 management port·address를 지정하지 않으므로 기존 애플리케이션 listener
정책을 바꾸지 않는다. 환경 변수 원문은 응답이나 로그에 출력하지 않는다.

## 4. 실행과 확인 예시

기존 Spring datasource 환경 변수를 먼저 구성한 뒤 Backend 디렉터리에서 실행한다.

```powershell
$env:SPRING_PROFILES_ACTIVE = "prometheus"
$env:MANAGEMENT_SERVER_PORT = "8081"
$env:MANAGEMENT_SERVER_ADDRESS = "127.0.0.1"
.\gradlew.bat bootRun
```

별도 터미널에서 health와 scrape 응답을 확인한다.

```powershell
curl.exe --fail-with-body http://127.0.0.1:8081/actuator/health
curl.exe --fail-with-body http://127.0.0.1:8081/actuator/prometheus
```

기본 profile 확인 시 `SPRING_PROFILES_ACTIVE=prometheus`를 사용하지 않는다.
`/actuator/health`는 애플리케이션 listener에서 정상이어야 하고
`/actuator/prometheus` 요청은 `404 Not Found`여야 한다. `prometheus` profile에서
`/actuator/prometheus` 요청은 `200 OK`와 Prometheus 호환 payload를 반환해야 한다.

## 5. 보안과 네트워크 경계

이번 구현에는 Spring Security, 인증·인가, credential과 인증 프록시가 없다. 따라서
`prometheus` profile의 기본 address는 원격 접속이 불가능한 loopback
`127.0.0.1`이다. loopback 기본값은 인증을 대신하지 않는다.

`MANAGEMENT_SERVER_ADDRESS`를 외부 인터페이스로 변경할 때는 배포 운영자가 다음을
모두 책임져야 한다.

- public network가 아닌 private network에 배치
- 방화벽과 보안그룹으로 승인된 수집 주체만 허용
- 인증을 수행하는 reverse proxy 또는 동등한 보호 경계 적용
- endpoint 접근 로그와 네트워크 정책 검증

이 보호 경계가 준비되지 않은 상태에서 public address로 bind하면 안 된다.

## 6. 로컬 Docker Compose 경계

[`Prometheus 로컬 scrape runbook`](../09-deployment/prometheus-local-scrape-runbook.md)은
Backend와 Prometheus를 internal observability network에 연결한다. Backend는 internal
application·observability network에만 연결하고 application `8080`과 management `8081`을
host에 publish하지 않는다. 이 환경에서만 `MANAGEMENT_SERVER_ADDRESS=0.0.0.0`을
override한다. Prometheus는 internal network에서
`http://backend:8081/actuator/prometheus`를 scrape하며, Prometheus만 별도 UI bridge에
연결해 UI를 host `127.0.0.1:9090`에 bind한다.

PostgreSQL과 AI Service는 application network DNS를 사용한다. External Risk fixture는
Backend의 network namespace를 공유하여 `127.0.0.1:8001`에만 bind하고 Backend도 이
loopback 주소로 호출한다. fixture port는 host에 publish하지 않는다. 이 구조는 기존
non-production plain HTTP loopback 제한을 보존하지만 production Provider 정책이나
인증·TLS를 대체하지 않는다.

Docker의 network 분리와 host loopback은 접근면을 제한하지만 인증·TLS를 제공하지
않는다. Backend 재생성은 loopback sidecar의 network namespace도 안전 순서에 따라
재생성해야 하며 상세 절차는 runbook을 따른다. 이 Compose 설정을 production 보안 또는
배포 구성으로 재사용하면 안 된다.

## 7. 현재 미구현 범위

- production Prometheus 서버와 scrape target 설정
- recording rule과 alert rule
- Grafana dashboard
- Spring Security·인증·credential
- production Docker·Kubernetes·AWS 배포 설정
- OpenTelemetry
- 신규 Meter·tag·SLA·SLO·임계값
- 자동 retry·fallback·cache와 recovery scheduler·batch
