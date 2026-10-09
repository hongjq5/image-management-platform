# WebSocket collaboration lifecycle fixes

> Historical implementation and verification record from 2026-09-19. These results were not rerun as part of the 2026-10-09 publication sanitation.

Scope: the ordinary backend handshake/handler, response model, and focused unit tests. WebSocket configuration, DDD backend, frontend, and Disruptor producer/consumer signatures are unchanged by this slice.

## Result

- Handshakes require a positive numeric picture ID, an authenticated HTTP session, an existing picture in an existing TEAM space, and current edit permission. Public images, private spaces, unknown space types, missing login, and malformed/overflow IDs are rejected.
- The handshake records the HTTP session ID. Incoming commands and queued commands independently reload Spring Session, confirm the same logged-in user, reload the user/picture/space, and query current permissions. Logout, expiry, deleted records, revoked membership, and unavailable authorization storage fail closed.
- Each picture has an atomic room transition. The editor is a WebSocket connection, so another tab logged in as the same user cannot edit, release, or accidentally clear the owner's lock. Connection close/removal and queued actions share the same serialization; removed connections cannot reclaim locks.
- A new edit claim revalidates the current owner, removing an idle expired/logged-out owner before granting a valid claimant. Transport failure and close release owned locks.
- JSON and supported message/action values are checked before publishing to Disruptor. Invalid messages receive an ERROR reply without reaching the queue.
- Recipient sends are synchronized with parser-error replies. Failed transports are removed before closing; one recipient failure, including a synchronous close callback, does not interrupt delivery to healthy recipients. Outgoing user data and stringified Long IDs remain compatible.
- Room messages now add the optional boolean `isEditor`, calculated separately for each receiving connection. `ENTER_EDIT` is true only for the lock owner, including when several tabs share one user account. Late joiners receive the current owner through an initial `ENTER_EDIT` snapshot; an unlocked room starts with `INFO` and `isEditor: false`. `EXIT_EDIT` resets ownership to false. `EDIT_ACTION` still reaches same-account sibling tabs and excludes only the sending socket. Non-room ERROR replies omit the field.

## TDD evidence

Using JDK 17 and the workspace Maven installation:

```text
mvn -Dtest=WsHandshakeInterceptorTest,PictureEditHandlerTest -DtrimStackTrace=true test -q
```

1. Initial RED: 31 tests ran, 26 expected assertion failures, zero test errors. Failures reproduced public-image admission, malformed IDs/login exceptions, cross-tab lock misuse, stale authority, malformed queue messages, concurrent sends, and failed broadcast recipients.
2. Initial GREEN: all 31 cases passed after implementation.
3. Additional RED: three new edge cases failed: idle logged-out owner, failed ERROR reply lock cleanup, and synchronous transport-close callback during broadcast.
4. Lifecycle GREEN: 34 tests passed (19 handler cases, 15 handshake cases), zero failures/errors/skips.
5. Protocol RED: five new cases failed for same-user connection ownership, late-join state, initial unlocked state, exit ownership reset, and simultaneous claims.
6. Final GREEN: 39 tests passed (24 handler cases, 15 handshake cases), zero failures/errors/skips. Intentional rejected requests and broken transports emit expected log messages.

## Limits

- Tests use real handler/handshake code with mocked external persistence and transports; no live Redis/MySQL/Tomcat browser integration was performed in this slice.
- Room state remains local to one backend process. Multiple backend instances still need shared ownership/broadcast coordination before distributed deployment.
- Authority is refreshed on inbound commands and when another connection requests an idle owner's lock; this is not a scheduled revocation service for completely idle rooms. Recipient-only connections are not proactively disconnected merely because a membership row changes.
- Outgoing messages preserve user identity and add recipient-specific `isEditor`; frontend editing controls must use this boolean instead of comparing user IDs. No session identifier is exposed.
- Per-picture state updates include authorization and delivery work to preserve event/lifecycle ordering. The shared Disruptor consumer is single-threaded, so a slow persistence lookup or socket can also delay other rooms; this change does not introduce an asynchronous delivery queue or a distributed lock.
