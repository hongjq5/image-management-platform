# Authentication and controller hardening

> Historical implementation and verification record from 2026-09-19. These results were not rerun as part of the 2026-10-09 publication sanitation.

## Changes

- `StpInterfaceImpl` reads identifier fields only, ignoring submitted nested user/space/role objects. It reloads the Spring-session user from the database, requires the Sa-Token login ID to match, derives picture/member space IDs from stored records, and rejects conflicting aliases or spaces. Empty contexts grant nothing except `picture:upload` for the two exact new-public-upload POST routes. JSON with charset, multipart parameters, and explicit null IDs remain supported.
- Picture detail and both list routes now enforce approved public visibility or stored space permissions. Owners/admins can still inspect their unapproved public pictures. Approved public detail remains available anonymously. The legacy cache route delegates to the authorized database list, eliminating both stale permissions and shared private results. Public paging rejects nonpositive page/current values.
- Reverse-image search checks visibility and accepts only approved public pictures, including for space owners. Private/team media is never sent to the third-party search service. Approved public search uses `PictureUrlSigner.sign` so private COS access remains compatible.
- Space detail requires picture-view permission; ordinary space lists are scoped to the logged-in owner, with joined teams available through the existing member-list route. Space sort fields and directions are allowlisted. Empty-space deletion holds the space row lock, checks both counters and actual pictures, and deletes member rows within the same transaction. Nonempty spaces return an error asking the owner to remove pictures first. The storage lane confirmed its quota update uses the same space row and checks `isDelete=0`.
- Member list requires an explicit positive `spaceId`; a member alias accepted by the authorization interceptor cannot become an unbounded controller query. Creator membership cannot be deleted or demoted from admin. Member creation accepts team spaces only and locks the space row in a transaction to serialize against space deletion.
- Login verifies `PasswordUtils` hashes, upgrades valid legacy MD5 hashes through a compare-and-set update, and rotates both sessions. New registrations hash passwords using PBKDF2 through the same utility. Logout revokes Sa-Token and invalidates Spring Session, including when the Spring login attribute has expired. User sort input is allowlisted.
- Admin metadata updates now write the review fields to the update object. Both picture edit DTOs were inspected: neither accepts URL or thumbnail URL, so signed response URLs cannot be written back by metadata edits.

## Intentionally disabled routes

The routes are retained and return business error `40300` with a clear message:

- `POST /picture/out_painting/create_task`
- `GET /picture/out_painting/get_task`
- `POST /user/exchange/vip` (authenticated calls reach the disabled service; no classpath file reads or writes remain)
- `POST /user/add` (admin-only demo provisioning previously assigned a publicly known default password; normal registration remains enabled)

No database, COS, Redis, or external image-search requests were made by these unit tests. No files in the DDD project, POM, YAML, upload/storage implementation, or WebSocket modules were edited by this lane.

## Regression evidence

Before fixes, the first 13 regression tests all failed with assertions and zero test errors. They reproduced forged nested roles, JSON charset bypass, empty-context grants, excessive public-upload permissions, picture/member/space ID mismatches, cached private listing, unapproved detail disclosure, negative page size, retained Sa-Token login after logout, and arbitrary SQL sort expressions.

Additional failing runs reproduced unrestricted space metadata/list access, space SQL sort expressions, nonempty-space deletion, unbounded member listing, lack of password migration, and predictable-password admin provisioning. A compatibility test caught explicit JSON null IDs after the initial resolver change and was fixed before the final run.

Final command, using JDK 17 and the installed Maven runtime:

```text
mvn -Dtest=StpInterfaceSecurityTest,PictureControllerSecurityTest,UserSessionSecurityTest,SpaceControllerSecurityTest,SpaceUserControllerSecurityTest,UserControllerSecurityTest -DtrimStackTrace=true test -q
```

Initial full result: **29 tests, 0 failures, 0 errors; exit 0.** The subsequent creator-membership regression run reproduced all three remaining defects (deletion, demotion, and private-space membership creation) before the guards were added. The storage lane then ran the combined targeted suites with `*SecurityTest`: **53 tests total, 0 failures, 0 errors, including all 33 security tests; exit 0.**

| Suite | Tests |
| --- | ---: |
| StpInterfaceSecurityTest | 10 |
| PictureControllerSecurityTest | 7 |
| UserSessionSecurityTest | 6 |
| SpaceControllerSecurityTest | 4 |
| SpaceUserControllerSecurityTest | 5 |
| UserControllerSecurityTest | 1 |

`git diff --check` passed for the owned source/test paths. Maven invocations were coordinated after one shared-target compilation race; no `clean` was run by this lane. The parent owns the final clean verification and HTTP smoke run.

## Verification limits

- Lock/rollback behavior for space deletion needs the parent's real MySQL integration run; the unit test verifies rejection of a nonempty space, not concurrent transactions.
- The actual third-party reverse-image service and signed COS URL retrieval were not invoked by this lane.
- Creator ownership transfer is intentionally unsupported; the creator's admin membership is protected instead.
