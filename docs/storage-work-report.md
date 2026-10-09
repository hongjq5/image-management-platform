# Storage and picture lifecycle work report

> Historical implementation and verification record from 2026-09-19. These results were not rerun as part of the 2026-10-09 publication sanitation.

Scope: ordinary `yu-picture-backend` copy only. No DDD, controller, configuration, dependency or live cloud changes were made by the storage lane.

## Changes

- `PictureServiceImpl`: replacement preserves the original uploader; replacement charges `newSize - currentSize` with zero count delta. Existing pictures are locked with `SELECT ... FOR UPDATE`; space usage is updated with a conditional atomic SQL statement within the same transaction as the picture write. Missing/deleted spaces and quota failures roll back the picture write. Existing over-quota spaces can reduce usage without first meeting the newly lowered limit; underflow and positive changes beyond the limit remain forbidden.
- Public deletion skips space accounting. Space deletion decrements actual persisted picture usage. New cloud objects are cleaned after transaction rollback; replaced/deleted objects are cleaned only after commit. A remaining reference in either `url` or `thumbnailUrl` prevents deletion.
- Color search and batch editing use the database-backed space permission manager. Batch requests accept at most 100 IDs and reject missing/foreign IDs before writing; each update includes both picture ID and target space ID. Picture query sorting is allowlisted and page sizes are limited to 1–100.
- `CosManager`: cleanup translates only the configured COS origin to an object key and rejects foreign/traversal paths. New keys have no leading slash. Compression and thumbnail rules preserve the `public/{userId}` / `space/{spaceId}` directory. The thumbnail threshold now matches the intended 20 KB.
- `PictureUploadTemplate` / `FilePictureUpload`: nonempty 2 MB bounded streams, actual JPEG/PNG decoding, 25-million-pixel limit, case-insensitive filename extensions, safe temporary file names and generated storage keys. Static WebP uses bounded RIFF/chunk/frame and dimension validation; successful COS decoding is still mandatory before accepting metadata. Animated WebP is rejected. Input streams and temporary files are closed/removed.
- COS processing leaves an original object; after successful conversion the unreferenced original is deleted. A failed upload/processing response triggers best-effort cleanup of the original and predicted derivative keys.
- If COS returns no derivatives, both original and thumbnail URLs reference the new canonical object. This prevents MyBatis's null-skipping update behavior retaining a replaced image's old thumbnail.
- `UrlPictureUpload`: accepts HTTP(S), standard ports, no credentials/fragments, maximum URL length 2048. Private, loopback, link-local, shared-address, multicast, reserved and IPv6 transition ranges are rejected. Apache HTTP client's DNS resolver validates the actual addresses passed to the connection, closing the resolve/check/re-resolve gap. Redirects and automatic retries are disabled. The actual GET must return 200; advertised size and actual streamed bytes are both bounded. A HEAD response is never trusted. Socket/connect timeouts and a 20-second copy deadline bound downloads.

## Verification

JDK: 17. Maven command, run from ordinary backend:

```shell
# Use JDK 17 and Maven available on PATH.
mvn -Dtest=PictureStorageTest,UploadValidationTest,UploadLifecycleTest,CosStorageTest test
```

- Initial red run at 20:37:42: 5 tests, 5 expected failures (owner changed, public deletion attempted space update, full URL passed to COS deletion, loopback URL made network request, fake PNG reached upload handling).
- First green run at 20:44:20: those same 5 tests passed.
- WebP compatibility regression at 20:45:36 rejected the fixture before the bounded WebP parser was added; parser then accepted the valid static fixture.
- Extended green run at 20:50:55: 17 tests, zero failures/errors. Includes quota delta arguments and no-write behavior on quota rejection, real Spring transaction synchronization ordering for commit/rollback cleanup, trusted team permissions, foreign batch rejection, surviving object references, streamed size cap, private/transition address rejection, COS origin and derivative keys, sort/page bounds.
- Parent reproduced three additional red cases: real-MySQL over-quota decreases rejected; a zero-dimension WebP passed local validation; the no-derivative fallback omitted the new thumbnail URL. These received focused fixes. Negative WebP fixtures also cover truncation, animation and excessive dimensions; upload lifecycle tests check generated-key cleanup on failed COS decoding and temporary-file removal.
- Final combined targeted green at 20:58:03: `-Dtest=PictureStorageTest,UploadValidationTest,UploadLifecycleTest,CosStorageTest,*SecurityTest` — 53 tests, zero failures/errors (20 storage tests plus 33 auth/security tests). The deliberate fake COS decode failure emits an expected error log while its cleanup assertions pass. `git diff --check` on storage production files passed; only the repository's LF/CRLF notices were emitted.
- Database concurrency and transaction SQL semantics require the parent-owned integration run. This lane's tests use mocked database/cloud boundaries and do not establish production MySQL/COS availability.

## Remaining operational concerns

- Cleanup is best-effort with explicit logs. A process crash or COS outage can leave an orphan; durable cleanup/outbox/reconciliation is a future operational improvement. No existing user records or cloud objects were bulk repaired/deleted.
- Existing bad historical quota totals are not automatically recomputed. Historical objects created under earlier malformed/root-level keys require an audited migration; the cleanup guard intentionally refuses arbitrary root objects.
- The storage service returns canonical URLs. Private bucket reads and response-boundary URL signing are handled by the separate access/configuration lane; application authorization cannot protect a publicly readable COS object by itself.
- JPEG/PNG are decoded locally. WebP has no bundled JDK decoder, so local validation checks container/frame bounds and COS provides complete decoding. No extra application dependency was added.
- DNS validation does not replace a production network egress firewall. No external destination or real cloud deletion was exercised by these unit tests.
