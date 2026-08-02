# Health Check API

Spring Boot 백엔드와 FastAPI AI Service 프로세스의 동작 상태를 확인한다.

## 요청

- Method: `GET`
- Path: `/api/health`
- Request Body: 없음
- Spring Boot Request Header: `X-Trace-Id` 선택
- FastAPI AI Service Request Header: 별도 요구 없음

Spring Boot의 `X-Trace-Id` 형식, 외부 값 수용과 서버 생성 규칙은 [`api-conventions.md`](api-conventions.md)의 공통 계약을 따른다.

## 성공 응답

- Status Code: `200 OK`
- Content-Type: `application/json`
- Spring Boot Response Header: `X-Trace-Id: <traceId>` 필수
- FastAPI AI Service Response Header: 이번 초기 기반에서는 별도 추적 헤더를 확정하지 않음

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `status` | string | 서비스 프로세스 상태 (`UP`) |
| `service` | string | 서비스 식별자 (`backend` 또는 `ai-service`) |

Spring Boot 백엔드 응답:

```json
{
  "status": "UP",
  "service": "backend"
}
```

FastAPI AI Service 응답:

```json
{
  "status": "UP",
  "service": "ai-service"
}
```

Health Check 성공 응답의 JSON 본문은 `status`, `service` 필드만 유지한다. Spring Boot에서 현재 요청의 `traceId`는 `X-Trace-Id` 응답 헤더로만 반환하며 본문 필드로 추가하지 않는다. FastAPI AI Service의 추적 헤더와 전역 오류 응답 계약은 이번 초기 기반 구현 범위에 포함하지 않는다.
