# REST error envelope

Central-server REST errors use one envelope:

```json
{
  "success": false,
  "status": 403,
  "requestId": "req-...",
  "error": {
    "code": "DASHBOARD_ADMIN_REQUIRED",
    "message": "human readable message",
    "failedCondition": "dashboard_admin_authenticated",
    "blockedAction": "AI_NETWORK_ADMIN_ACCESS",
    "actionGuide": "next action"
  }
}
```

Required fields:

- `success`: always `false`.
- `status`: same HTTP status as the response.
- `requestId`: request correlation id when available.
- `error.code`: stable client branch code from `ApiErrorCodes`.
- `error.message`: safe client-visible message.

Optional fields are omitted when they do not apply:

- `details`: structured values. Keys must be declared in `ApiErrorDetailsSchemas`.
- `currentState` / `requiredState`: state-transition failures only.
- `failedCondition`: the violated machine-readable condition.
- `blockedAction`: the action blocked by that condition.
- `actionGuide`: concrete next action when the client/user can act.

## Current code registry

The executable registry is `central-server/src/main/kotlin/com/discordassistant/central/global/error/ApiErrorContract.kt`.
Client-visible server code must reference `ApiErrorCodes` instead of new string literals.

| Code | Meaning |
|---|---|
| `NOT_FOUND` | Requested resource does not exist. |
| `INVALID_REQUEST` | Request input or JSON body is invalid. |
| `CONFLICT` | Current state conflicts with requested action. |
| `FORBIDDEN` | Authenticated caller lacks permission. |
| `INVALID_STATE_TRANSITION` | Domain state machine rejected the transition. |
| `PRECONDITION_FAILED` | Required condition was not met. |
| `DASHBOARD_ADMIN_REQUIRED` | Dashboard/admin token or allow-listed OAuth user is required. |
| `INVALID_SERVER_STATE` | Server state cannot safely process the request. |
| `INTERNAL_SERVER_ERROR` | Unexpected server failure without internal detail leakage. |
| `ERROR` | Fallback for unknown framework status code names. |
| `UNAUTHORIZED` | Framework 401 reshaped into the common envelope. |
| `SERVICE_UNAVAILABLE` | Framework/upstream unavailable state. |

## Current details schemas

| Code | Required details | Optional details |
|---|---|---|
| `PRECONDITION_FAILED` | none | `allowedValues`, `actualValue` |
