# Bot orders endpoint

`GET /api/bot/orders` lists the orders that have not shipped yet. It is read-only and made for
omiyage-bot. It takes no JWT. The caller proves itself with an HMAC signature made with the same
secret as the order webhook (`ORDER_WEBHOOK_SECRET`, see `order-events.md`). Without a valid
signature the answer is `401`; when the secret is not set the endpoint is always `401`.

## Request

| Header | Value |
| --- | --- |
| `X-Omiyage-Timestamp` | Unix time in seconds when the request was signed, e.g. `1762142400` |
| `X-Omiyage-Signature` | `sha256=` + lowercase hex HMAC-SHA256 |

Query parameters: `page` (0-based, default 0) and `size` (default 25, at most 50, values above are lowered to 50).

Signature: `HMAC-SHA256(key = secret as UTF-8, message = "<timestamp>\n<METHOD>\n<path>\n<query>\n")`
where each part is exactly as sent: `<METHOD>` is `GET`, `<path>` the raw request path (for example
`/api/bot/orders`), `<query>` the raw query string without the `?` (empty when there is none), and
the body, always empty, follows the last newline. Parameter order and encoding in `<query>` must be
the same as in the URL that is requested.

Test vector: secret `test-secret`, timestamp `1762142400`, `GET`, path `/api/bot/orders`, query
`page=0&size=25` gives
`sha256=8d98a7a02ed36118b2847af0cda7d9240d1eedc7a90cecf3488518ed78593c84` (pinned in `HmacRequestSignatureVerifierTest`).

## Verification

1. Missing header, non-numeric timestamp or a signature that does not match: `401`. The comparison is constant time.
2. The timestamp must be from **10 minutes in the past to 1 minute in the future**, otherwise `401`.
3. There is no replay protection: the endpoint is read-only, so a replayed request inside the window only repeats a read.

The `401` body is always `{"error":"unauthorized","code":"unauthorized"}`; the reason is only logged (`bot_request_rejected`, logger `security`).

## Response

Unshipped means every status before `SENT`: `REQUESTED`, `IN_PROGRESS`, `PURCHASED`, `PREPARING_SHIPPING`.
`SENT`, `DELIVERED` and `CANCELLED` are left out. Oldest order first.

```json
{
  "content": [
    {
      "id": "1a2b3c4d-5e6f-4a1b-9c2d-3e4f5a6b7c8d",
      "reference": "1a2b3c4d",
      "status": "PURCHASED",
      "nickname": "Birthday presents",
      "itemCount": 3,
      "createdAt": "2026-11-01T10:00:00Z"
    }
  ],
  "page": 0, "size": 25, "totalElements": 1, "totalPages": 1, "hasNext": false
}
```

`nickname` is the nickname of the order, absent when it has none. The member's account name is not
included: it lives in MeishiApi and needs the member's or an admin's token, which the bot does not have.
