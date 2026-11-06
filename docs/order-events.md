# Order events webhook

omiyage-api sends a signed HTTP POST for every new order and every order status change. omiyage-bot
is the receiver. This file is the contract: change it only together with the bot.

## Request

`POST <ORDER_WEBHOOK_URL>`, body `application/json`, UTF-8.

| Header | Value |
| --- | --- |
| `X-Omiyage-Timestamp` | Unix time in seconds when the event was created (same instant as `occurredAt`), e.g. `1762142400` |
| `X-Omiyage-Signature` | `sha256=` + lowercase hex HMAC-SHA256 |

Signature: `HMAC-SHA256(key = secret as UTF-8, message = "<X-Omiyage-Timestamp>." + raw body bytes)`.

Test vector: secret `test-secret`, timestamp `1762142400`, body `{"id":"1"}` gives
`sha256=cf765ab67ec2fd104f0b5a7a2c97723d624c6cd12829e6ba100e3f05076f4129` (pinned in `OrderWebhookTest`).

The timestamp is fixed when the event is created and the body is signed once. Every retry resends
the identical body, timestamp and signature; nothing is ever re-signed.

## Receiver rules

1. Recompute the signature over the raw body bytes (before any JSON parsing) and compare in constant time. Mismatch: 401.
2. Accept a timestamp from **10 minutes in the past to 1 minute in the future**, otherwise 401.
3. Drop an `eventId` already handled within those 10 minutes: 2xx, do nothing.
4. 2xx when handled. 5xx, 408 or 429 for a transient failure, so omiyage-api retries. Any other status ends delivery.

## Retries

Delays between attempts: 1 s, 5 s, 30 s, 120 s (5 attempts). Each attempt has a 2 s connect and 5 s
request timeout, and the discordName lookup before the first attempt can take up to 7 s more, so the
schedule ends at most 7 s + 156 s + 5 x 7 s = **198 s** after the timestamp.

Hard limit: every attempt ends within **5 minutes** of the signed timestamp. An attempt that could
not finish by then (because events backed up behind a slow receiver) is not sent; the event is
logged as `order_event_expired` and dropped. Since the bot remembers event ids for 10 minutes, a
retry of an event whose answer was lost is always caught as a duplicate.

Delivery is at least once while the API runs, with no ordering guarantee between events. Use
`occurredAt` to order them.

## Body

```json
{
  "eventId": "6f1c2a9e-3b0d-4c55-9a51-0f7e3c2d1b11",
  "type": "order.status_changed",
  "occurredAt": "2026-11-03T10:15:30.123456Z",
  "order": {
    "id": "1a2b3c4d-5e6f-4a1b-9c2d-3e4f5a6b7c8d",
    "reference": "1a2b3c4d",
    "userId": "0b9f8e7d-6c5b-4a39-8271-605f4e3d2c1b",
    "discordName": "bob_42",
    "nickname": "Birthday presents",
    "status": "PREPARING_SHIPPING",
    "previousStatus": "PURCHASED",
    "items": [
      { "name": "Frieren card", "quantity": 2 },
      { "name": "Ghibli museum ticket", "quantity": 1 }
    ],
    "itemsTotalEur": "27.00",
    "itemsTotalIsEstimate": true
  }
}
```

| Field | Type | Notes |
| --- | --- | --- |
| `eventId` | UUID string | Unique per event, the same on every retry. Inside the signed bytes. |
| `type` | string | `order.created` or `order.status_changed` |
| `occurredAt` | ISO-8601 UTC string | When the change happened |
| `order.id` | UUID string | Link to the order with it |
| `order.reference` | string | First 8 characters of `order.id`, the short id the frontend shows. Stable |
| `order.userId` | UUID string | Order owner |
| `order.discordName` | string or null | Owner's Discord username. Best effort, see below |
| `order.nickname` | string or null | Name the owner gave the order. User text |
| `order.status` | enum | `REQUESTED`, `IN_PROGRESS`, `PURCHASED`, `PREPARING_SHIPPING`, `SENT`, `DELIVERED`, `CANCELLED` |
| `order.previousStatus` | enum or null | Null on `order.created` |
| `order.items[].name` | string | Catalog title or custom request title. User text |
| `order.items[].quantity` | integer | 1 to 1000 |
| `order.itemsTotalEur` | decimal string, 2 places | Sum of (sold price, else the member's max price) x quantity. Shipping not included |
| `order.itemsTotalIsEstimate` | boolean | True while any item has no sold price yet |

Every field is always present; nullable ones are `null`. Receivers must ignore unknown fields, so
fields can be added later without a contract break. `nickname` and item names are user input: never
let them trigger mentions.

`discordName` lives in MeishiApi and omiyage-api can only read it with the caller's token: it is set
when the owner or an admin caused the event, and null when there is no request (none today) or the
lookup fails. A shipment co-member's order moved by the shipment owner's submit has null.

## When events fire

- `order.created`: a member or admin creates an order. A double submit that returns the existing order sends nothing.
- `order.status_changed`: admin status change (cancel included), member cancel, member submit for shipping (one event per order of the shipment), shipment marked shipped (one event per order moved to `SENT`). A change to the same status sends nothing.
- Nothing is sent for splits, merges, item moves or item edits.
- Events are sent only after the transaction commits; a rolled-back change sends nothing.

## Configuration

| Env var | Source | Default |
| --- | --- | --- |
| `ORDER_WEBHOOK_URL` | Deployment env, e.g. the bot's ClusterIP service URL | empty |
| `ORDER_WEBHOOK_SECRET` | Kubernetes Secret `omiyage-webhook`, key `secret`, namespace `default`, shared with omiyage-bot, referenced as optional | empty |

The feature is off unless both are set and the URL is an absolute http(s) URL (an invalid URL is logged once at startup), so the API boots and runs unchanged while the Secret is missing.

```sh
kubectl -n default create secret generic omiyage-webhook --from-literal=secret='<random 32+ byte value>'
```

## Why in-memory retries, not an outbox table

Events go to one internal bot, a few per day, and a lost notification costs a missing Discord
message: the order itself is in the database and visible in the app. An outbox table would make
delivery survive restarts, but needs a migration, a polling job and cleanup for that one case. The
in-memory queue is bounded (1000 pending events, extra ones are dropped and logged), and pending
retries are dropped at shutdown after a 5 s grace period with a logged count. Revisit if events
start driving anything that must not be missed, such as payments.
