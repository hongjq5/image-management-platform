# COS temporary picture access

> Historical implementation and verification record from 2026-09-19. These results were not rerun as part of the 2026-10-09 publication sanitation.

The backend issues temporary COS GET URLs in outgoing picture responses while keeping canonical URLs in the database. After explicit user approval on 2026-09-19, the coordinator changed the bucket ACL to private and added the two local frontend origins to COS CORS. Live anonymous denial, signed access, and browser cropping are verified in [the consolidated report](verification.md).

## Implementation

- `PictureUrlSigner.sign(String canonicalUrl)` is a Spring component available to other backend callers, including authorized image-search requests. It creates a 15-minute GET URL with the existing COS SDK 5.6.227 client. SDK signing binds the Host header; callers must not replace the returned URL's host.
- The signer only signs URLs with the same scheme, hostname, and effective port as `cos.client.host`. It rejects user information, query strings, fragments, empty keys, traversal segments, backslashes, control characters, empty path segments, and keys longer than COS's 1024-byte limit. URI decoding happens once and preserves literal plus signs.
- Foreign, malformed, or already signed URLs are returned unchanged, without receiving a signature. This preserves existing external image links, but means the signer is not an external-image privacy filter. New uploads and picture edit/update paths must continue to own and validate canonical URL storage.
- `PictureUrlResponseAdvice` copies outgoing `BaseResponse`, `Page`, collections, `PictureVO`, and `Picture` instances and signs both `url` and `thumbnailUrl`. Existing objects, persistence entities, and cached response instances are never mutated. The advice is restricted to JSON `BaseResponse` controller responses and does not inspect arbitrary object fields or maps.
- Signed links are bearer access URLs until expiration. Business authorization must finish before a picture reaches the response advice or a caller invokes the signer. Long-lived UI sessions must refetch authorized picture data when links expire.

## Integration responsibilities

Incoming edit/update payloads must not overwrite canonical storage URLs with signed response URLs. The coordinator is handling that in the existing controller/DTO paths. The unsafe shared picture cache is also being removed by the authorization slice, which avoids raw `Page.class` deserialization losing picture types.

For Baidu image search, inject `PictureUrlSigner` and pass `signer.sign(picture.getUrl())` only after existing picture access checks. Do not persist the resulting URL.

The test bucket inspected on 2026-09-19 had private ACL and no bucket policy. Its original object had default ACL and was verified to reject anonymous GET/HEAD with 403 while valid signed requests return 200. The tested CORS rule allowed GET/HEAD from `http://localhost:5173` and `http://127.0.0.1:5173`, with allowed headers `*`, exposed ETag/Content-Length, and max age 600. Both origins and their preflights passed; an unapproved origin received no CORS permission. Newly uploaded images also passed unsigned-denial and signed-read checks. Future object ACL or policy changes require renewed verification; a private bucket ACL alone is not proof of effective denial for every object.

## Verification

Targeted tests use the real COS SDK with fake credentials and make no network calls. They cover the full object key, HTTPS, Host binding, an independently recomputed COS V5 GET signature, approximately 15-minute expiration, different signatures for main/thumbnail objects, Unicode/encoded keys, literal plus characters, foreign origins, deceptive authority strings, malformed paths, nulls, and already signed input. Response tests cover paginated VOs, lists, admin entities, preservation of page metadata, both URL fields, and non-mutation of source objects.

Run with JDK 17:

```text
mvn -q -Dtest=PictureUrlSignerTest,PictureUrlResponseAdviceTest test
```

The initial executable baseline failed five assertions as expected because URLs were unsigned and response objects were not copied. After implementation, all 23 targeted cases passed. The machine's default newer JDK caused pre-existing Lombok annotation-processing failures; the installed Microsoft JDK 17 successfully compiled the backend and ran these tests.

## Official references

- [Java SDK access control](https://cloud.tencent.com/document/product/436/50708): `getBucketAcl`, `getObjectAcl`, and `getCannedAccessControl`.
- [Java SDK presigned URLs](https://cloud.tencent.com/document/product/436/35217): GET signing, expiry, raw object keys, and Host binding.
- [COS ACL behavior](https://cloud.tencent.com/document/product/436/13327): objects without an explicit ACL inherit bucket access. Effective access also considers policies.
- [Image processing mechanism](https://cloud.tencent.com/document/product/436/54050): upload-time processing persists original and processed images as COS objects. The documented result rules expose bucket, fileid, and processing rule, not a separate ACL.

The image-processing documentation does not make a separate explicit ACL-inheritance guarantee for derived images. Default object inheritance applies to this flow without explicit ACLs; read-only `getObjectAcl(bucket, returnedKey)` checks can confirm existing original and derived objects. The presigned-URL documentation also flags default-domain browser-preview restrictions for buckets created after 2024-01-01, so actual browser response behavior must be verified separately from permissions.
