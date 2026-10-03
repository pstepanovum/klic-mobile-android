package com.klic.mobile.app.data

import kotlinx.serialization.json.Json

/**
 * The app's shared lenient JSON codec (`ignoreUnknownKeys`): API/socket payloads and the
 * plain local caches. One instance instead of one per class — Json caches serializer
 * descriptors internally. The E2EE content/message-store codecs keep their own
 * `encodeDefaults = true` instances: those formats are persisted/on the wire and must
 * not drift with this one.
 */
val KlicJson: Json = Json { ignoreUnknownKeys = true }
