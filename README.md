HANI TV compatibility patch

Files:
- api_hani_tv_compatible.php: revised API, syntax-checked with PHP 8.4.
- MainActivity.kt: previously patched Android source.
- EmbeddedPlayerActivity.kt: previously patched Android player source.
- AndroidManifest.xml and build.gradle.kts.txt: supplied project configuration for reference.

Before deployment:
1. Change the exposed database password in Hostinger and create a NEW independent admin key (at least 32 characters, random).
2. Put the new values into the configuration section at the top of api_hani_tv_compatible.php. Do not use the exposed values from the chat.
3. Back up the current API and database before replacing anything.
4. Confirm the existing `sources` table has the columns used by this API (channel, serverName, url, referer, displayName, userAgent, cookie, headers, authorization, clearKey, drmScheme, licenseUrl, sort_order). The API adds qualityUrls if absent.
5. Test GET and POST with a staging copy or a backup first.

Compatibility notes:
- The API keeps the current GET response shape and current POST payload field names, including qualityUrls, DRM, headers, channels, and settings.
- GET remains public to preserve compatibility with the current app. Consequently, playback configuration returned by GET can be read by anyone who knows the endpoint. The admin key protects POST writes, not public playback data. To make GET private, an authenticated-user/session design and corresponding Android changes are required; putting a fixed read key in the APK is not real protection.
- A MySQL named lock serializes writes, but the current Android payload has no revision number. A stale client can still overwrite a newer complete list if it submits after it. Proper stale-write prevention requires adding a revision/version handshake to both API and Android.
- PHP syntax check passed in this environment. This is not a live Hostinger/database test and not an APK build test.

