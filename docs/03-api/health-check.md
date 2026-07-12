# Health Check API

백엔드 서비스의 동작 상태를 확인한다.

## 요청

- Method: `GET`
- Path: `/api/health`
- Request Body: 없음

## 성공 응답

- Status Code: `200 OK`
- Content-Type: `application/json`

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
