# Frontend Architecture and Backend Contract Design

## Goal

Refactor only the Vue frontend so that each module has one clear responsibility, pages contain presentation logic rather than transport details, and every HTTP/WebSocket call matches the existing Java backend contract.

## Constraints

- Preserve the current page layout, visual design, routes, and user-visible features.
- Do not modify Java sources, SQL files, backend configuration, or backend behavior.
- Do not add runtime dependencies or replace the current Vue reactive state with a new state-management library.
- Keep all browser requests authenticated through the existing session token mechanism.
- Do not generate demo devices, measurements, alarms, camera streams, or fallback values.
- Keep the local development contract: the browser uses same-origin `/api` and `/ws/realtime`; Vite forwards them to `BACKEND_URL`.
- Keep formal deployment explicit: the production web server must proxy `/api` and `/ws/realtime` to the Java backend because the backend has no HTTP CORS configuration.

## Existing Contract Baseline

The current frontend already matches these backend routes and payloads:

| Domain | Frontend operation | Backend contract |
| --- | --- | --- |
| Authentication | Sign in | `POST /api/login` with `{ username, password }`; envelope data contains `{ token, username }` |
| Devices | List/create | `GET /api/device`; `POST /api/device` |
| Devices | Update/delete | `PUT /api/device/{id}`; `DELETE /api/device/{id}` |
| Measurements | Latest/history | `GET /api/measurement/latest`; `GET /api/measurement/history` |
| Measurements | Export | `GET /api/measurement/export` returning CSV |
| Runtime sampling | Latest scheduler state | `GET /api/ezviz/measurements/latest?serial=` returning a raw object |
| Alarms | List/handle | `GET /api/alarm`; `POST /api/alarm/{id}/handle` with `{ action }` |
| Alarms | Snapshot | `GET /api/alarm/{id}/snapshot` returning JPEG |
| Video | Live address/capture | `GET /api/ezviz/play-url`; `GET /api/ezviz/capture` |
| Realtime | Measurement/alarm events | `WS /ws/realtime` |

The refactor must preserve compatibility with both backend response forms already in use: the common `{ code, msg, data }` envelope and raw EZVIZ JSON responses.

## Problems to Fix

1. `services/platform.ts` currently owns endpoint construction, DTO mapping, device validation, polling, WebSocket lifecycle, reactive state, and business actions. This makes interface changes hard to audit.
2. Video and history pages call the HTTP transport directly, so endpoint definitions are scattered through components.
3. Alarm display metadata is duplicated across pages and can drift.
4. Display date formatting is reused to serialize backend `LocalDateTime` query parameters, coupling UI presentation to the interface contract.
5. Alarm screenshots use an `<img>` URL and therefore cannot send the Bearer token. They should use the authenticated blob client and a managed object URL.
6. Camera online state currently requires both a recent `lastSuccess` and an empty runtime error. The scheduler can report a successful current inference together with a measurement-persistence error, so online state must depend on recency alone while the error remains visible separately.
7. HTTP, session, exact write payloads, WebSocket lifecycle, and video calls do not have focused boundary tests.
8. Unused demonstration/export modules and a personal-name fallback remain in the frontend source.

## Target Structure

```text
frontend/src/
  services/
    api/
      client.ts             authenticated fetch, envelopes, blobs, errors
      contract.ts           safe primitive/date parsers and backend time serialization
      auth.ts               login endpoint
      devices.ts            device DTO mapping and CRUD endpoints
      measurements.ts       latest/history/export/export and runtime-status endpoints
      alarms.ts             alarm DTO mapping, list/handle/snapshot endpoints
      video.ts              play-address and capture endpoints
    platform/
      store.ts              reactive state and public page-facing actions
      realtime.ts           WebSocket creation and lifecycle
    session.ts              persisted browser session state
  composables/
    useHistoryQuery.ts      query sequencing, filters, statistics, export orchestration
    useLiveVideo.ts         HLS/native playback and resource cleanup
    useObjectUrl.ts         authenticated image object-URL lifecycle
  domain/
    alarmMeta.ts            one alarm label/color/icon mapping
  pages/                    page composition and page-only interaction
  components/               reusable visual components
  types.ts                  shared view models
```

Names may be adjusted during implementation when an existing import boundary makes a smaller structure clearer, but responsibilities and dependency direction must stay the same.

## Dependency Direction

```text
pages/components -> composables/platform store -> domain API modules -> API client
                                      |                    |
                                      v                    v
                                  view models        backend DTO parsing
```

- Pages and visual components must not build backend paths or call `fetch`.
- Domain API modules must construct exact methods, paths, query parameters, and request bodies.
- The platform store may coordinate domains, but it must not parse backend field names.
- The transport client must not know device, measurement, alarm, or video semantics.
- DTO parsers accept `unknown`, validate required fields, and return typed frontend view models.

## Interface Details

### HTTP transport

The client keeps the 20-second timeout, caller cancellation, `no-store` cache mode, Bearer header, JSON content type for JSON bodies, 401 session-expiry event, backend message extraction, response-envelope unwrapping, and blob content checks. Request paths must begin with `/api/`.

### Devices

Reads map snake_case database rows to the existing `Device` view model. Writes must send exactly these camelCase fields: `serial`, `name`, `location`, `channelNo`, `hd`, `deviceOtherParams`, `inferIntervalSec`, and `status`. IDs stay strings in frontend state, and unsafe JSON numbers remain a visible contract error.

### Measurements and history

Backend query times are Beijing wall-clock `LocalDateTime` strings such as `2026-09-09T10:00:00`. A dedicated serializer must produce that value without calling a display formatter. All-device history and export continue to issue one backend request per device with bounded concurrency because the backend requires `deviceId` or `serial`.

`GET /api/measurement/latest` currently returns HTTP 500 when a device has no measurement. The frontend must keep this as a per-device warning without fabricating zero values or failing the whole platform refresh. It cannot reliably classify that response as “empty” because the same status is used for genuine database faults.

### Runtime status and online semantics

The runtime response is parsed separately from stored measurements. A device is online when `lastSuccess` is within the existing freshness window. A runtime error remains in `runtimeError` and is shown independently; it does not override a recent successful sampling timestamp.

### Alarms

Alarm rows do not contain water-level, velocity, or discharge values. The frontend keeps `measurement: null` and displays those values as unavailable. Snapshot paths from the database are never exposed. When a snapshot exists, the alarm feature calls `/api/alarm/{id}/snapshot` through the authenticated blob client and revokes the resulting object URL when replaced or unmounted.

### Video

The video API validates the raw play-address response before the playback composable receives it. The composable owns abort controllers, HLS/native player choice, URL expiry refresh, signed-URL redaction, capture cooldown, and teardown. Changing devices or unmounting cancels stale work.

### WebSocket

The realtime module derives a same-origin `ws:`/`wss:` URL for `/ws/realtime`, exposes connection-state changes, and sends raw event text to the platform store. The store parses measurement and alarm events, rejects malformed data, and keeps polling as the source of truth.

## Error Handling

- Global device-list failure marks the platform connection as failed and preserves the last known state with a clear stale-data warning.
- Per-device latest/runtime failures are attached to that device and do not prevent other devices from loading.
- Alarm-list failure is a platform warning and does not erase usable measurements.
- Mutations update frontend state only after the backend accepts the operation.
- A mutation that succeeds but whose refresh fails tells the user to refresh before retrying, preventing duplicate writes.
- Stale responses after logout, device changes, or newer history queries must not overwrite current state.

## Testing Strategy

Focused Vitest suites will test observable boundaries:

- API client: Bearer header, JSON envelope/raw response, backend errors, 401 event, timeout/caller abort, and blob rejection.
- Domain APIs: exact method, path, query string, and JSON body for every backend endpoint; snake_case mapping and malformed responses.
- Session: trimmed username, unchanged password, required token fields, persistence, logout, and unauthorized expiry.
- Platform: partial refresh failures, precise online semantics, mutation-after-success behavior, generation invalidation, and realtime events.
- History: latest-query-wins behavior, backend time parameters, per-device export merge, and cleanup.
- Video/alarm images: exact query parameters, invalid/mixed-content URLs, stale cancellation, image content validation, signed-URL redaction, and object-URL revocation.

Final verification requires the full frontend test suite, TypeScript production build, and a browser smoke test against the unchanged backend at the configured proxy target. If camera credentials or stored measurements are absent, interface success is verified independently from camera/data availability.

## Migration and Acceptance

The refactor will proceed in small test-first slices. Compatibility re-export files may be kept temporarily while imports move, then removed once all production references use the new modules. No visual redesign is part of acceptance.

The work is accepted when:

1. No page or visual component contains a literal `/api/` or `/ws/` endpoint.
2. Every backend call is represented by a typed domain API function and exact contract test.
3. `platform.ts` is replaced by focused store/realtime modules with the same page-facing behavior.
4. Duplicate alarm metadata and dead demonstration/export code are removed.
5. All tests and the production build pass.
6. The unchanged Java backend receives the expected requests in a live smoke test.
