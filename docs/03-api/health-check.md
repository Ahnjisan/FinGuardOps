# Health Check API

백엔드 서비스의 동작 상태를 확인한다.

## 요청

- Method: `GET`
- Path: `/api/health`
- Request Body: 없음
- Request Header: `X-Trace-Id` 선택

`X-Trace-Id`의 형식, 외부 값 수용과 서버 생성 규칙은 [`api-conventions.md`](api-conventions.md)의 공통 계약을 따른다.

## 성공 응답

- Status Code: `200 OK`
- Content-Type: `application/json`
- Response Header: `X-Trace-Id: <traceId>` 필수

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `status` | string | 백엔드 서비스 상태 (`UP`) |
| `service` | string | 서비스 식별자 (`backend`) |

```json
{
  "status": "UP",
  "service": "backend"
}
```

Health Check 성공 응답의 JSON 본문은 기존 `status`, `service` 필드만 유지한다. 현재 요청의 `traceId`는 `X-Trace-Id` 응답 헤더로만 반환하며 본문 필드로 추가하지 않는다.
