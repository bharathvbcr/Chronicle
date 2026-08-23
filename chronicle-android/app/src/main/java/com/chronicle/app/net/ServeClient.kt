package com.chronicle.app.net

import java.io.IOException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * Thin HTTP client for optional LAN Chronicle serve (recall + resume + entries).
 * Only calls the user-configured base URL — never cloud.
 *
 * Security model (CONTRACT v1.11):
 *  - LAN serve is **https by default** with a self-signed cert whose SPKI
 *    SHA-256 (`tls_fp` from the connect QR) is pinned via [CertificatePinner].
 *    Cleartext http stays possible for `--no-tls` servers.
 *  - Every request sends header [AUTH_HEADER] (`X-Chronicle-Token`) when a
 *    pairing token is present (persistent per-device token from the Mac).
 *  - URLs must resolve to private/loopback addresses **or** match a
 *    user-approved extra CIDR (e.g. Tailscale's 100.64/10) — enforced
 *    per-request so DNS rebinding cannot keep a stale pass.
 */
class ServeClient(
    private val client: OkHttpClient = defaultClient(),
    private val tokenProvider: () -> String? = { null },
    /** Extra allowed CIDRs ("100.64.0.0/10"), re-read per request. */
    private val extraCidrsProvider: () -> Set<String> = { emptySet() },
) {
    data class Health(val ok: Boolean, val raw: String = "")

    data class Citation(
        val id: String,
        val kind: String = "",
        val score: Double = 0.0,
        val snippet: String = "",
        val path: String? = null,
        /** Graph node ids linked to this citation (Phase 1+ recall). */
        val nodeIds: List<String> = emptyList(),
    )

    data class RecallResult(
        val answer: String,
        val citations: List<Citation>,
        /** Expanded seed neighborhood returned by serve. */
        val seedNodeIds: List<String> = emptyList(),
        val degraded: Boolean,
    )

    data class SearchHit(
        val id: String,
        val kind: String = "",
        val score: Double = 0.0,
        val text: String = "",
        val path: String? = null,
    )

    data class ResumeResult(
        val ok: Boolean,
        val bullets: List<String>,
        val notes: String,
        val error: String? = null,
    )

    data class EntryPushResult(
        val ok: Boolean,
        val id: String = "",
        val error: String? = null,
    )

    /** Parsed Mac connect QR: normalized base URL plus optional pairing token / TLS pin. */
    data class ConnectPayload(
        val baseUrl: String,
        val token: String? = null,
        /** Base64 SHA-256 of the serve cert SPKI — pinned for https bases. */
        val tlsFp: String? = null,
    )

    fun health(baseUrl: String): Health {
        if (baseUrl.isBlank()) return Health(false, "URL not set")
        return try {
            requireAllowedUrl(baseUrl, extraCidrsProvider())
            val req = authorizedRequestBuilder("$baseUrl/health").get().build()
            healthClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val ok = resp.isSuccessful && parseHealthOk(body)
                Health(ok, body)
            }
        } catch (e: Exception) {
            when (e) {
                // OkHttp pin failure: the Mac's cert changed (LAN IP rotation
                // regenerates it) and the stored fingerprint no longer matches.
                // A generic "unreachable" sends users debugging the wrong
                // thing — name the actual fix.
                is javax.net.ssl.SSLPeerUnverifiedException -> Health(
                    false,
                    "Certificate changed — rescan the Mac QR in Settings to update the security pin",
                )
                else -> Health(false, e.message ?: "unreachable")
            }
        }
    }

    /**
     * Graph-seeded recall. Pass [nodeIds] (e.g. `topic:health`) to bias retrieval;
     * response citations include [Citation.nodeIds] for graph highlighting.
     */
    fun recall(
        baseUrl: String,
        message: String,
        history: List<Pair<String, String>> = emptyList(),
        scope: String = "all",
        nodeIds: List<String> = emptyList(),
    ): RecallResult {
        val payload = JSONObject()
            .put("message", message)
            .put("scope", scope)
            .put(
                "history",
                JSONArray().apply {
                    history.forEach { (role, content) ->
                        put(JSONObject().put("role", role).put("content", content))
                    }
                },
            )
        if (nodeIds.isNotEmpty()) {
            payload.put(
                "node_ids",
                JSONArray().apply { nodeIds.forEach { put(it) } },
            )
        }
        val json = postJson(baseUrl, "/recall", payload, timeoutSec = 120)
        return parseRecallResult(json)
    }

    fun search(baseUrl: String, query: String, topK: Int = 8): List<SearchHit> {
        val payload = JSONObject().put("query", query).put("top_k", topK)
        val json = postJson(baseUrl, "/search", payload, timeoutSec = 60)
        val hits = mutableListOf<SearchHit>()
        val arr = json.optJSONArray("hits") ?: return hits
        for (i in 0 until arr.length()) {
            val h = arr.getJSONObject(i)
            hits.add(
                SearchHit(
                    id = h.optString("id"),
                    kind = h.optString("kind"),
                    score = h.optDouble("score", 0.0),
                    text = h.optString("text"),
                    path = h.optString("path").takeIf { it.isNotBlank() },
                ),
            )
        }
        return hits
    }

    fun resume(baseUrl: String, role: String): ResumeResult {
        val payload = JSONObject().put("role", role)
        val json = postJson(baseUrl, "/resume", payload, timeoutSec = 300)
        if (!json.optBoolean("ok", true) && json.has("error")) {
            return ResumeResult(false, emptyList(), "", json.optString("error"))
        }
        val bullets = mutableListOf<String>()
        val arr = json.optJSONArray("bullets")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                when (val item = arr.get(i)) {
                    is String -> bullets.add(item)
                    is JSONObject -> {
                        val text = item.optString("text").ifBlank {
                            item.optString("bullet").ifBlank { item.toString() }
                        }
                        bullets.add(text)
                    }
                    else -> bullets.add(item.toString())
                }
            }
        }
        return ResumeResult(
            ok = true,
            bullets = bullets,
            notes = json.optString("notes"),
        )
    }

    /**
     * Optional LAN entry create (Mac assigns a `-pc` id).
     * Use when Syncthing lag matters and a Mac-side copy is acceptable;
     * does not replace the phone's local `-an` vault write.
     */
    fun createEntry(
        baseUrl: String,
        type: String,
        text: String,
        tags: List<String> = emptyList(),
        mood: Int? = null,
        ts: String? = null,
    ): EntryPushResult {
        return try {
            val payload = JSONObject()
                .put("type", type)
                .put("text", text)
                .put("tags", JSONArray().apply { tags.forEach { put(it) } })
            if (mood != null) payload.put("mood", mood)
            if (!ts.isNullOrBlank()) payload.put("ts", ts)
            val json = postJson(baseUrl, "/entries", payload, timeoutSec = 30)
            EntryPushResult(ok = true, id = json.optString("id"))
        } catch (e: Exception) {
            EntryPushResult(ok = false, error = e.message ?: "push failed")
        }
    }

    /**
     * Idempotent outbox mirror (CONTRACT v1.11): push the phone's own entry JSON
     * to the PC vault. Server dedupes identical payloads and 409s on divergence;
     * Syncthing remains source of truth either way.
     */
    fun mirrorEntry(
        baseUrl: String,
        entryJson: JSONObject,
    ): EntryPushResult {
        return try {
            val payload = JSONObject().put("entry", entryJson)
            val json = postJson(baseUrl, "/entries/mirror", payload, timeoutSec = 30)
            EntryPushResult(ok = true, id = json.optString("id"))
        } catch (e: HttpException) {
            EntryPushResult(ok = false, id = "", error = "HTTP ${e.code}")
        } catch (e: Exception) {
            EntryPushResult(ok = false, error = e.message ?: "mirror failed")
        }
    }

    data class JournalDay(
        val date: String,
        val path: String,
        val entryIds: List<String>,
    )

    data class JournalEntryBody(
        val id: String,
        val date: String,
        val path: String,
        val body: String,
        val bodyHash: String,
        val filedContentHash: String?,
        val editable: Boolean,
    )

    data class JournalAmendResult(
        val ok: Boolean = true,
        val hash: String = "",
        val path: String = "",
    )

    /** 409 from PATCH /journal/entries — FastAPI nests under `detail`. */
    data class JournalAmendConflict(
        val detail: String,
        val onDiskHash: String,
        val filedContentHash: String?,
    ) : Exception(detail)

    fun journalDays(baseUrl: String): List<JournalDay> {
        val json = getJson(baseUrl, "/journal/days", timeoutSec = 30)
        return parseJournalDays(json)
    }

    fun journalEntry(baseUrl: String, entryId: String): JournalEntryBody {
        val json = getJson(baseUrl, "/journal/entries/$entryId", timeoutSec = 30)
        return parseJournalEntry(json)
    }

    fun journalAmend(
        baseUrl: String,
        entryId: String,
        body: String,
        baseHash: String,
    ): JournalAmendResult {
        val payload = JSONObject()
            .put("body", body)
            .put("base_hash", baseHash)
        return try {
            val json = patchJson(baseUrl, "/journal/entries/$entryId", payload, timeoutSec = 60)
            JournalAmendResult(
                ok = true,
                hash = json.optString("hash").ifBlank { json.optString("body_hash") },
                path = json.optString("path"),
            )
        } catch (e: HttpException) {
            if (e.code == 409) {
                throw parseJournalAmendConflict(e.body) ?: e
            }
            throw e
        }
    }

    /** Resync filed_content_hash to on-disk fence after external edit (Obsidian). */
    fun journalAcceptDisk(baseUrl: String, entryId: String): JournalAmendResult {
        val json = postJson(
            baseUrl,
            "/journal/entries/$entryId/accept-disk",
            JSONObject(),
            timeoutSec = 60,
        )
        return JournalAmendResult(
            ok = true,
            hash = json.optString("hash").ifBlank { json.optString("body_hash") },
            path = json.optString("path"),
        )
    }

    class HttpException(val code: Int, val body: String) : Exception("HTTP $code: ${body.take(200)}")

    class UnsafeServeUrlException(message: String) : IOException(message)

    private fun authorizedRequestBuilder(url: String): Request.Builder {
        requireAllowedUrl(url, extraCidrsProvider())
        val builder = Request.Builder().url(url)
        val token = tokenProvider()?.trim().orEmpty()
        if (token.isNotEmpty()) {
            builder.header(AUTH_HEADER, token)
        }
        return builder
    }

    private fun timedClient(timeoutSec: Long): OkHttpClient =
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    private fun getJson(baseUrl: String, path: String, timeoutSec: Long): JSONObject {
        require(baseUrl.isNotBlank()) { "Base URL not configured" }
        requireAllowedUrl(baseUrl, extraCidrsProvider())
        val timed = timedClient(timeoutSec)
        val req = authorizedRequestBuilder("$baseUrl$path").get().build()
        timed.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw HttpException(resp.code, text)
            }
            return JSONObject(text.ifBlank { "{}" })
        }
    }

    private fun patchJson(
        baseUrl: String,
        path: String,
        payload: JSONObject,
        timeoutSec: Long,
    ): JSONObject {
        require(baseUrl.isNotBlank()) { "Base URL not configured" }
        requireAllowedUrl(baseUrl, extraCidrsProvider())
        val timed = timedClient(timeoutSec)
        val body = payload.toString().toRequestBody(JSON_MEDIA)
        val req = authorizedRequestBuilder("$baseUrl$path")
            .patch(body)
            .header("Content-Type", "application/json")
            .build()
        timed.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw HttpException(resp.code, text)
            }
            return JSONObject(text.ifBlank { "{}" })
        }
    }

    private fun postJson(
        baseUrl: String,
        path: String,
        payload: JSONObject,
        timeoutSec: Long,
    ): JSONObject {
        require(baseUrl.isNotBlank()) { "Base URL not configured" }
        requireAllowedUrl(baseUrl, extraCidrsProvider())
        val timed = timedClient(timeoutSec)
        val body = payload.toString().toRequestBody(JSON_MEDIA)
        val req = authorizedRequestBuilder("$baseUrl$path")
            .post(body)
            .header("Content-Type", "application/json")
            .build()
        timed.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw HttpException(resp.code, text)
            }
            return JSONObject(text.ifBlank { "{}" })
        }
    }

    /** Short-timeout client for /health probes (default client stays for recall/Ask). */
    private val healthClient: OkHttpClient = client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    /** Per-instance gate honoring user-approved extra CIDRs. */
    class CidrAllowlistInterceptor(private val cidrs: Set<String>) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url.toString()
            requireAllowedUrl(url, cidrs)
            return chain.proceed(chain.request())
        }
    }

    companion object {
        /** Pairing token header — must match chronicle serve when LAN auth is enabled. */
        const val AUTH_HEADER = "X-Chronicle-Token"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** IPv4 dotted-quad or bracketed/unbracketed IPv6 literal (no DNS). */
        private val IPV4_LITERAL = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(PrivateLanUrlInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        /** OkHttp pin string ("sha256/<b64>") from a bare SPKI fingerprint b64. */
        fun pinFromFingerprint(tlsFp: String): String {
            val cleaned = tlsFp.trim().removePrefix("sha256/").trim()
            require(cleaned.isNotEmpty()) { "empty fingerprint" }
            return "sha256/$cleaned"
        }

        /**
         * Require JSON `ok: true` (nested `chronicle.ok` alone is not enough).
         * Rejects HTML / substring false-positives like `{"ok":false}`.
         */
        fun parseHealthOk(body: String): Boolean {
            val trimmed = body.trim()
            if (trimmed.isEmpty() || !trimmed.startsWith("{")) return false
            return try {
                JSONObject(trimmed).optBoolean("ok", false)
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Normalize a user/QR-supplied serve URL. Preserves an explicit https
         * scheme; defaults to **https** for bare hosts (v1.11 default), since
         * LAN serve is TLS-on by default. Trailing slashes are trimmed.
         */
        fun normalizeBaseUrl(raw: String): String =
            raw.trim().trimEnd('/').let {
                when {
                    it.isEmpty() -> it
                    it.contains("://") -> it
                    else -> "https://$it"
                }
            }

        /**
         * Parse Mac connect QR payload:
         *  - v2 JSON `{"v":2,"base":"https://...","token":"...","tls_fp":"..."}`
         *  - v1 JSON `{"v":1,"base":"http://...", "token":"..."}` (legacy, no pin)
         *  - a plain http(s) URL
         * Rejects public (non-RFC1918 / non-loopback) IP-literal hosts; hostname
         * privateness is enforced per-request (see [isPrivateOrLoopbackUrl]).
         */
        fun parseConnectQrPayload(raw: String): ConnectPayload? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("{")) {
                return try {
                    val obj = JSONObject(trimmed)
                    val base = obj.optString("base").trim()
                    if (base.isEmpty()) return null
                    val normalized = normalizeBaseUrl(base)
                    if (!isAllowedLanUrl(normalized)) return null
                    val token = obj.optString("token").trim().takeIf { it.isNotEmpty() }
                    val fp = obj.optString("tls_fp").trim().takeIf { it.isNotEmpty() }
                    // A pinned connection requires https; ignore fp otherwise.
                    ConnectPayload(normalized, token, fp?.takeIf { normalized.startsWith("https://") })
                } catch (_: Exception) {
                    null
                }
            }
            val lower = trimmed.lowercase()
            val candidate = when {
                lower.startsWith("http://") || lower.startsWith("https://") ->
                    normalizeBaseUrl(trimmed)
                trimmed.contains('.') || trimmed.contains(':') ->
                    normalizeBaseUrl(trimmed).takeIf { it.contains("://") }
                else -> null
            } ?: return null
            if (!isAllowedLanUrl(candidate)) return null
            return ConnectPayload(candidate)
        }

        /**
         * True for loopback or RFC1918 private IPv4 (and IPv6 ULA / link-local),
         * or any address inside [extraCidrs]. IP literals are checked without
         * DNS; hostnames are resolved via [InetAddress.getByName] and re-checked
         * on every request so DNS rebinding / prefs edits cannot keep a
         * configure-time pass. Resolving a hostname may hit DNS — do not call
         * on the main thread for hostname URLs; use [isAllowedLanUrl] for
         * configure-time validation.
         */
        fun isPrivateOrLoopbackUrl(url: String, extraCidrs: Set<String> = emptySet()): Boolean {
            return try {
                val host = URI(url).host ?: return false
                if (host.equals("localhost", ignoreCase = true)) return true
                val bare = host.removePrefix("[").removeSuffix("]")
                val addr = parseIpLiteral(bare) ?: InetAddress.getByName(bare)
                isAddressAllowed(addr, extraCidrs)
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Main-thread-safe configure-time gate. IP literals are validated strictly
         * (no DNS); hostnames (e.g. `macbook.local`) are accepted here and their
         * private/loopback enforcement is deferred to the per-request checks
         * ([PrivateLanUrlInterceptor]), which run off the main thread and can
         * resolve DNS.
         */
        fun isAllowedLanUrl(url: String, extraCidrs: Set<String> = emptySet()): Boolean {
            return try {
                val host = URI(url).host ?: return false
                if (host.equals("localhost", ignoreCase = true)) return true
                val bare = host.removePrefix("[").removeSuffix("]")
                val literal = parseIpLiteral(bare) ?: return true
                isAddressAllowed(literal, extraCidrs)
            } catch (_: Exception) {
                false
            }
        }

        fun requirePrivateOrLoopbackUrl(url: String) {
            if (!isPrivateOrLoopbackUrl(url)) {
                throw UnsafeServeUrlException("Refusing non-private Chronicle serve URL")
            }
        }

        fun requireAllowedUrl(url: String, extraCidrs: Set<String>) {
            if (!isPrivateOrLoopbackUrl(url, extraCidrs)) {
                throw UnsafeServeUrlException("Refusing non-private Chronicle serve URL")
            }
        }

        /** Parse [bare] as an IP literal without DNS, or null when it is a hostname. */
        private fun parseIpLiteral(bare: String): InetAddress? {
            if (!IPV4_LITERAL.matches(bare) && !bare.contains(':')) return null
            return try {
                // Literal parse — getByName does not query DNS for IP strings.
                InetAddress.getByName(bare)
            } catch (_: Exception) {
                null
            }
        }

        fun isPrivateOrLoopbackAddress(addr: InetAddress): Boolean =
            addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress ||
                isUniqueLocalIpv6(addr)

        /**
         * Per-request gate: private/loopback OR inside one of [extraCidrs]
         * (user-approved ranges such as Tailscale's 100.64.0.0/10).
         */
        fun isAddressAllowed(addr: InetAddress, extraCidrs: Set<String> = emptySet()): Boolean {
            if (isPrivateOrLoopbackAddress(addr)) return true
            return extraCidrs.any { cidr -> matchesCidr(addr, cidr) }
        }

        /**
         * True when [addr] is an IPv4 address inside [cidr] ("100.64.0.0/10").
         * Malformed entries never match (fail closed); IPv6 CIDRs unsupported —
         * ULA/link-local are already covered by the built-in checks.
         */
        fun matchesCidr(addr: InetAddress, cidr: String): Boolean {
            val parts = cidr.trim().split("/")
            if (parts.size != 2) return false
            val prefix = parts[1].toIntOrNull() ?: return false
            val base = try {
                InetAddress.getByName(parts[0])
            } catch (_: Exception) {
                return false
            }
            val addrBytes = addr.address
            val baseBytes = base.address
            if (addrBytes.size != 4 || baseBytes.size != 4) return false
            if (prefix !in 0..32) return false
            if (addr !is Inet4Address) return false
            val fullBytes = prefix / 8
            val remBits = prefix % 8
            for (i in 0 until fullBytes) {
                if (addrBytes[i] != baseBytes[i]) return false
            }
            if (remBits == 0) return true
            val mask = (0xFF shl (8 - remBits)) and 0xFF
            return (addrBytes[fullBytes].toInt() and mask) == (baseBytes[fullBytes].toInt() and mask)
        }

        /** fc00::/7 unique-local (ULA); [InetAddress.isSiteLocalAddress] is IPv4-oriented. */
        private fun isUniqueLocalIpv6(addr: InetAddress): Boolean {
            val bytes = addr.address
            if (bytes.size != 16) return false
            return (bytes[0].toInt() and 0xfe) == 0xfc
        }

        /** Blocks requests whose URL is not private/loopback (defense in depth vs redirects). */
        private object PrivateLanUrlInterceptor : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val url = chain.request().url.toString()
                requirePrivateOrLoopbackUrl(url)
                return chain.proceed(chain.request())
            }
        }

        /**
         * Client for a paired serve: pins [tlsFp] to [baseUrl]'s host when present
         * and swaps the strict private-IP interceptor for the CIDR-aware one when
         * user-approved ranges are configured.
         */
        fun clientFor(
            baseUrl: String?,
            tlsFp: String?,
            extraCidrs: Set<String> = emptySet(),
        ): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
            if (extraCidrs.isEmpty()) {
                builder.addInterceptor(PrivateLanUrlInterceptor)
            } else {
                builder.addInterceptor(CidrAllowlistInterceptor(extraCidrs))
            }
            if (!tlsFp.isNullOrBlank() && !baseUrl.isNullOrBlank()) {
                val host = try {
                    URI(baseUrl).host?.takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
                if (host != null) {
                    // The Mac serves a SELF-SIGNED cert: the default system
                    // TrustManager rejects it at the trust step, before
                    // CertificatePinner ever runs — every pinned connection
                    // would die with "trust anchor not found". Identity here
                    // is established by the QR-delivered SPKI pin (plus the
                    // hostname/SAN check OkHttp still performs), so trust in
                    // the CA chain is intentionally replaced by the pin.
                    val trustManager = object : X509TrustManager {
                        override fun checkClientTrusted(
                            chain: Array<X509Certificate>,
                            authType: String,
                        ) {
                        }

                        override fun checkServerTrusted(
                            chain: Array<X509Certificate>,
                            authType: String,
                        ) {
                            require(chain.isNotEmpty()) { "empty certificate chain" }
                        }

                        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    }
                    val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
                    sslContext.init(null, arrayOf<X509TrustManager>(trustManager), null)
                    builder.sslSocketFactory(sslContext.socketFactory, trustManager)
                    builder.certificatePinner(
                        okhttp3.CertificatePinner.Builder()
                            .add(host, pinFromFingerprint(tlsFp))
                            .build(),
                    )
                }
            }
            return builder.build()
        }

        fun parseStringList(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return buildList {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i).trim()
                    if (s.isNotEmpty()) add(s)
                }
            }
        }

        /** Flatten citation → node mapping for graph highlight. */
        fun citationNodeIds(citations: List<Citation>): List<String> =
            citations.flatMap { it.nodeIds }.distinct()

        /** Recall response parsing (extracted for unit tests). */
        fun parseRecallResult(json: JSONObject): RecallResult {
            val citations = mutableListOf<Citation>()
            val arr = json.optJSONArray("citations")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    citations.add(
                        Citation(
                            id = c.optString("id"),
                            kind = c.optString("kind"),
                            score = c.optDouble("score", 0.0),
                            snippet = c.optString("snippet"),
                            path = c.optString("path").takeIf { it.isNotBlank() },
                            nodeIds = parseStringList(c.optJSONArray("node_ids")),
                        ),
                    )
                }
            }
            return RecallResult(
                answer = json.optString("answer"),
                citations = citations,
                seedNodeIds = parseStringList(json.optJSONArray("seed_node_ids")),
                degraded = json.optBoolean("degraded", false),
            )
        }

        /** Journal days parsing (extracted for unit tests / SSE-triggered refreshes). */
        fun parseJournalDays(json: JSONObject): List<JournalDay> {
            val arr = json.optJSONArray("days") ?: return emptyList()
            val out = mutableListOf<JournalDay>()
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                out.add(
                    JournalDay(
                        date = d.optString("date"),
                        path = d.optString("path"),
                        entryIds = parseStringList(d.optJSONArray("entry_ids")),
                    ),
                )
            }
            return out
        }

        /** Journal entry body parsing (extracted for unit tests). */
        fun parseJournalEntry(json: JSONObject): JournalEntryBody =
            JournalEntryBody(
                id = json.optString("id"),
                date = json.optString("date"),
                path = json.optString("path"),
                body = json.optString("body"),
                bodyHash = json.optString("body_hash"),
                filedContentHash = json.optString("filed_content_hash").takeIf { it.isNotBlank() },
                editable = json.optBoolean("editable", false),
            )

        /**
         * Unwrap FastAPI 409 body: `{detail: {detail, on_disk_hash, filed_content_hash}}`
         * or a flat conflict object.
         */
        fun parseJournalAmendConflict(raw: String): JournalAmendConflict? {
            return try {
                val root = JSONObject(raw)
                val obj = when {
                    root.optJSONObject("detail")?.has("on_disk_hash") == true ->
                        root.getJSONObject("detail")
                    root.has("on_disk_hash") -> root
                    else -> return null
                }
                JournalAmendConflict(
                    detail = obj.optString("detail", "journal fence hash mismatch"),
                    onDiskHash = obj.optString("on_disk_hash"),
                    filedContentHash = obj.optString("filed_content_hash").takeIf { it.isNotBlank() },
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
