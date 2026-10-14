package fr.groggy.racecontrol.tv.ui.channel.playback

import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Rewrites the client-identity fields inside the F1/buyDRM `LicenseRequest`
 * protobuf so that F1's licence server treats the request as coming from the
 * official F1 TV app running on a hardware-secure (L1) device, and grants a
 * HW_SECURE_DECODE licence instead of SW_SECURE_DECODE (L3).
 *
 * The real request layout (verified by decoding a captured request from this
 * app on an x86_64 emulator) is a custom Widevine-style wrapper:
 *
 *   top:  field 1 (varint), field 2 (LEN, the big wrapper), field 3 (challenge),
 *         field 9 (LEN)
 *   field 2 -> field 1: the `app_info` message, made of:
 *     - field 2: the key / license data (repeated key blobs)
 *     - field 3 (repeated): key/value pairs, each `{1: key, 2: value}`:
 *         application_name, origin, package_certificate_hash_bytes,
 *         company_name, model_name, architecture_name, device_name,
 *         product_name, build_info, widevine_cdm_version,
 *         oem_crypto_security_patch_level, oem_crypto_build_information
 *     - field 6: the `device_info` block — 12 varint fields only, no strings.
 *
 * There is NO `security_level` string in the request; the L3 nature is
 * advertised by the `oem_crypto_build_information` JSON (`"soc_vendor":
 * "L3_33100"`, `"form_factor":"L3"`) and the device block varints. So to claim
 * L1 we:
 *   1) rewrite the `application_name` KV value to the official package,
 *   2) rewrite the `package_certificate_hash_bytes` KV value to the official
 *      release-certificate SHA-256 (Base64-encoded, 44 bytes),
 *   3) in the device block, set the `security_level` varint (field 5) to the
 *      HW_SECURE_DECODE numeric level and append an explicit `HW_SECURE_DECODE`
 *      string field,
 *   4) rewrite the `oem_crypto_build_information` JSON with a full realistic
 *      L1 JSON (Qualcomm/QSEE), and
 *   5) rewrite the remaining device-identity KVs (company_name, model_name,
 *      device_name, product_name, build_info, architecture_name,
 *      oem_crypto_security_patch_level) to a real Google TV Streamer (arm64,
 *      Android 14) identity, so the outer request is internally consistent
 *      with the HW_SECURE_DECODE claim. The CDM version KVs are left at the
 *      pristine 19.0.1@AV1A.240428.001 to stay aligned with the unforgeable
 *      CDM-signed blob (build 33100).
 *
 * The request is not cryptographically bound to the app (no request MAC; the
 * Widevine device_id is per-CDM, not per-app), so the server cannot detect the
 * substitution.
 *
 * This is a dependency-free, minimal protobuf wire-format rewrite. It walks the
 * message tree, rewriting in place (preserving every other field byte-for-byte)
 * and only mutates sub-messages whose size actually changes.
 */
object WidevineIdentityRewriter {

    private const val TAG = "WidevineIdentityRewriter"

    private const val WIRE_TYPE_VARINT = 0
    private const val WIRE_TYPE_LEN = 2
    private const val WIRE_TYPE_64 = 1
    private const val WIRE_TYPE_32 = 5

    // Identity of the official F1 TV Android app, verified against the working
    // app's captured Widevine request and its release signing certificate.
    const val OFFICIAL_PACKAGE_NAME = "com.formulaone.production"
    const val OFFICIAL_CERT_SHA256_HEX =
        "b8448baab9e0335773e002cb06e4d7fd50efde53acc0a91753ef05f1f531c346"
    val OFFICIAL_CERT_SHA256: ByteArray = decodeHex(OFFICIAL_CERT_SHA256_HEX)

    // The CDM Base64-encodes the package certificate SHA-256 (44 bytes) in the
    // `package_certificate_hash_bytes` KV. We must match that encoding.
    private const val OFFICIAL_CERT_SHA256_B64 = "uESLqrngM1dz4ALLBuTX/VDv3lOswKkXU+8F8fUxw0Y="

    // Base64 of THIS app's debug certificate SHA-256, as it appears in a
    // captured request (used as a fast-path match; the key-name match is the
    // robust path).
    private const val CURRENT_CERT_SHA256_B64 = "bu9VD8NtGpFtE03emuyECzreLlE3vfDk42pqGalzUyA="

    // Package name of this app as it would otherwise appear in the request.
    const val CURRENT_PACKAGE_NAME = "com.st14n.f1.debug"

    // Widevine device-block `security_level`. The licence server grants the
    // minimum of the content-policy ceiling and the device capability
    // advertised in the (unsigned) request's device block. To obtain an
    // HW_SECURE_DECODE (L1) grant on an L3-only CDM (x86_64 emulator) we
    // advertise HW_SECURE_DECODE.
    const val HW_SECURE_DECODE = "HW_SECURE_DECODE"

    // Widevine security-level numeric values. L1 = HW_SECURE_DECODE = 2.
    private const val SEC_LEVEL_HW_SECURE_DECODE = 2
    private const val SEC_LEVEL_SW_SECURE_DECODE = 0

    // A real L1-capable Android TV identity (Google TV Streamer, arm64,
    // Android 14) used to make the device KVs consistent with a HW_SECURE_DECODE
    // claim. Google TV streamers are an officially supported platform for the
    // F1 app, and the CDM version is deliberately kept at the pristine
    // 19.0.1@AV1A.240428.001 so it stays aligned with the unforgeable CDM-signed
    // blob's build number (33100).
    private const val SPOOF_COMPANY_NAME = "Google"
    private const val SPOOF_MODEL_NAME = "Google TV Streamer"
    private const val SPOOF_DEVICE_NAME = "gtvs"
    private const val SPOOF_PRODUCT_NAME = "gtvs"
    private const val SPOOF_BUILD_INFO = "Google/gtvs/gtvs:14/UP1A.240505.003/11211861:user/release-keys"
    private const val SPOOF_ARCHITECTURE_NAME = "aarch64"
    private const val SPOOF_OEM_SECURITY_PATCH_LEVEL = "20240505"

    // A realistic L1 oem_crypto_build_information (v18) JSON (Qualcomm, as on
    // the Google TV Streamer). Replaces the emulator's L3 JSON end-to-end so no
    // "X86 64 bit"/"none" TEE markers remain.
    private const val SPOOF_OEM_JSON =
        "{\"soc_vendor\":\"L1_33100\",\"soc_model\":\"SM6450\",\"ta_ver\":\"18.1.0+Mar 1 2024_10:22:11_\"," +
        "\"uses_opk\":true,\"tee_os\":\"QSEE\",\"tee_os_ver\":\"1.0\"," +
        "\"form_factor\":\"L1\",\"implementer\":\"Qualcomm\",\"fused\":true}"

    private val PACKAGE_NAME_REGEX = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")

    private const val KEY_PACKAGE_NAME = "application_name"
    private const val KEY_PACKAGE_CERT_HASH = "package_certificate_hash_bytes"
    private const val KEY_OEM_CRYPTO_BUILD_INFO = "oem_crypto_build_information"
    private const val KEY_OEM_SECURITY_PATCH_LEVEL = "oem_crypto_security_patch_level"
    private const val KEY_COMPANY_NAME = "company_name"
    private const val KEY_MODEL_NAME = "model_name"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_PRODUCT_NAME = "product_name"
    private const val KEY_BUILD_INFO = "build_info"
    private const val KEY_ARCHITECTURE_NAME = "architecture_name"

    /**
     * Returns [request] with its identity and device-capability fields rewritten
     * to the official F1 app + HW_SECURE_DECODE, or the original array when the
     * request is not recognised as a Widevine request carrying our identity.
     */
    fun rewrite(request: ByteArray, currentPackageName: String = CURRENT_PACKAGE_NAME): ByteArray {
        if (request.isEmpty()) return request
        val top = try {
            parseFields(request)
        } catch (e: Exception) {
            Log.w(TAG, "rewrite: failed to parse Widevine request, leaving untouched", e)
            return request
        }

        var pkgReplaced = false
        var certReplaced = false
        var oemJsonReplaced = false
        var deviceReplaced = false

        val ctx = RewriteContext(currentPackageName)
        val rebuilt = top.map { field ->
            if (field.wireType == WIRE_TYPE_LEN && !field.value.isEmpty()) {
                val rewritten = processLen(field.value, 0, ctx)
                if (rewritten != null && !rewritten.contentEquals(field.value)) {
                    return@map ProtoField(field.fieldNumber, field.wireType, rewritten)
                }
            }
            field
        }

        pkgReplaced = ctx.pkgReplaced
        certReplaced = ctx.certReplaced
        oemJsonReplaced = ctx.oemJsonReplaced
        deviceReplaced = ctx.deviceReplaced

        if (!ctx.anyChanged) {
            Log.i(
                TAG,
                "rewrite: nothing recognised (pkg=$currentPackageName), request left unchanged"
            )
            return request
        }
        Log.i(
            TAG,
            "rewrite: applied app_info=${ctx.pkgReplaced || ctx.certReplaced} " +
                "device_info=$deviceReplaced oem_json=$oemJsonReplaced " +
                "device_kvs=${ctx.deviceKVsReplaced} " +
                "(target=$OFFICIAL_PACKAGE_NAME / $HW_SECURE_DECODE)"
        )
        return serializeFields(rebuilt)
    }

    /**
     * Recursively processes a length-delimited message. If it is the `app_info`
     * message (contains our package name as a KV value) it rewrites the identity
     * KVs; if a sub-field is the device block it rewrites the security level.
     * Returns the rewritten content or null when nothing inside changed.
     */
    private fun processLen(content: ByteArray, depth: Int, ctx: RewriteContext): ByteArray? {
        if (depth > 8) return null
        val sub = try {
            parseFields(content)
        } catch (e: Exception) {
            return null
        }
        if (sub.isEmpty()) return null

        var changed = false
        val out = ArrayList<ProtoField>(sub.size)
        for (f in sub) {
            when {
                f.wireType == WIRE_TYPE_LEN && !f.value.isEmpty() -> {
                    // app_info identity key/value pairs.
                    if (isKeyPair(f.value)) {
                        val before = ctx.copy()
                        val rewrittenKv = rewriteKeyPair(f.value, ctx)
                        if (rewrittenKv != null) {
                            out.add(ProtoField(f.fieldNumber, f.wireType, rewrittenKv))
                            changed = true
                            if (ctx.pkgReplaced && !before.pkgReplaced) Log.i(
                                TAG, "rewrite: application_name KV -> $OFFICIAL_PACKAGE_NAME")
                            if (ctx.certReplaced && !before.certReplaced) Log.i(
                                TAG, "rewrite: package_certificate_hash_bytes KV -> official cert (Base64)")
                            if (ctx.oemJsonReplaced && !before.oemJsonReplaced) Log.i(
                                TAG, "rewrite: oem_crypto_build_information JSON -> L1")
                            if (ctx.deviceKVsReplaced && !before.deviceKVsReplaced) Log.i(
                                TAG, "rewrite: device KVs -> Google TV Streamer L1 identity")
                            continue
                        }
                    }

                    // device block: all-varint, no strings.
                    if (isDeviceBlock(f.value)) {
                        val before = ctx.copy()
                        val rewrittenDev = rewriteDeviceBlock(f.value, ctx)
                        if (rewrittenDev != null && !rewrittenDev.contentEquals(f.value)) {
                            out.add(ProtoField(f.fieldNumber, f.wireType, rewrittenDev))
                            changed = true
                            if (ctx.deviceReplaced && !before.deviceReplaced) {
                                Log.i(
                                    TAG,
                                    "rewrite: device block (field ${f.fieldNumber}) -> " +
                                        "$HW_SECURE_DECODE (security_level varint + explicit field)"
                                )
                            }
                            continue
                        }
                    }

                    // recurse into nested messages.
                    val inner = processLen(f.value, depth + 1, ctx)
                    if (inner != null && !inner.contentEquals(f.value)) {
                        out.add(ProtoField(f.fieldNumber, f.wireType, inner))
                        changed = true
                    } else {
                        out.add(f)
                    }
                }
                else -> out.add(f)
            }
        }
        return if (changed) serializeFields(out) else null
    }

    // ------------------------------------------------------------------
    // app_info key/value rewrite
    // ------------------------------------------------------------------

    /** True for a `{1: key, 2: value}` KV sub-message. */
    private fun isKeyPair(content: ByteArray): Boolean {
        val sub = try {
            parseFields(content)
        } catch (e: Exception) {
            return false
        }
        if (sub.size != 2) return false
        if (sub[0].wireType != WIRE_TYPE_LEN || sub[1].wireType != WIRE_TYPE_LEN) return false
        // key must be a plausible identifier string.
        val key = sub[0].value.toString(Charsets.UTF_8)
        return key.isNotEmpty() && key.length <= 64 &&
            sub[0].value.all { (it.toInt() and 0xFF).let { c -> c in 0x20..0x7e } }
    }

    /**
     * Rewrites an app-identity KV pair in place. Returns the new KV content, or
     * null when this pair is not one of the identity fields (so it is left
     * untouched).
     */
    private fun rewriteKeyPair(kv: ByteArray, ctx: RewriteContext): ByteArray? {
        val sub = parseFields(kv)
        val key = sub[0].value.toString(Charsets.UTF_8)
        val curValue = sub[1].value
        val pkgBytes = ctx.currentPackageName.toByteArray(Charsets.UTF_8)

        val newValue: ByteArray? = when {
            // package / application name -> official F1 app.
            (key == KEY_PACKAGE_NAME && curValue.contentEquals(pkgBytes)) ||
                (key != KEY_PACKAGE_NAME && looksLikePackageName(
                    curValue.toString(Charsets.UTF_8)
                ) && curValue.contentEquals(pkgBytes)) -> {
                ctx.pkgReplaced = true
                OFFICIAL_PACKAGE_NAME.toByteArray(Charsets.UTF_8)
            }
            // package certificate SHA-256 (Base64) -> official cert hash.
            (key == KEY_PACKAGE_CERT_HASH && !curValue.contentEquals(officialCertB64Bytes())) ||
                (curValue.contentEquals(currentCertB64Bytes()) &&
                    !curValue.contentEquals(officialCertB64Bytes())) -> {
                ctx.certReplaced = true
                officialCertB64Bytes()
            }
            // oem crypto build information JSON -> claim L1.
            key == KEY_OEM_CRYPTO_BUILD_INFO -> rewriteOemJson(curValue)?.also {
                ctx.oemJsonReplaced = true
            } ?: curValue
            // Remaining device-identity KVs -> real Google TV Streamer (L1)
            // identity so the outer request no longer contradicts the
            // HW_SECURE_DECODE claim.
            key == KEY_OEM_SECURITY_PATCH_LEVEL -> spoofValue(
                SPOOF_OEM_SECURITY_PATCH_LEVEL, curValue, ctx
            )
            key == KEY_COMPANY_NAME -> spoofValue(SPOOF_COMPANY_NAME, curValue, ctx)
            key == KEY_MODEL_NAME -> spoofValue(SPOOF_MODEL_NAME, curValue, ctx)
            key == KEY_DEVICE_NAME -> spoofValue(SPOOF_DEVICE_NAME, curValue, ctx)
            key == KEY_PRODUCT_NAME -> spoofValue(SPOOF_PRODUCT_NAME, curValue, ctx)
            key == KEY_BUILD_INFO -> spoofValue(SPOOF_BUILD_INFO, curValue, ctx)
            key == KEY_ARCHITECTURE_NAME -> spoofValue(SPOOF_ARCHITECTURE_NAME, curValue, ctx)
            else -> null
        }

        if (newValue == null) return null
        if (newValue.contentEquals(curValue)) return null
        return serializeFields(
            listOf(sub[0], ProtoField(sub[1].fieldNumber, sub[1].wireType, newValue))
        )
    }

    /**
     * Replaces the oem_crypto JSON with a full realistic L1 JSON. A full
     * replacement (rather than patching the L3 markers) is used so that no
     * emulator-specific fields ("X86 64 bit" soc_model, "none" tee_os) survive.
     * Returns null when the content is already the target JSON.
     */
    private fun rewriteOemJson(content: ByteArray): ByteArray? {
        val text = content.toString(Charsets.UTF_8)
        if (text == SPOOF_OEM_JSON) return null
        return SPOOF_OEM_JSON.toByteArray(Charsets.UTF_8)
    }

    /**
     * Produces a spoof value, recording that a device KV was replaced. Returns
     * null (leaving the KV untouched) when the current value already equals the
     * spoof value — e.g. company_name is already "Google" on this app.
     */
    private fun spoofValue(value: String, currentValue: ByteArray, ctx: RewriteContext): ByteArray? {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.contentEquals(currentValue)) return null
        ctx.deviceKVsReplaced = true
        return bytes
    }

    private fun looksLikePackageName(value: String): Boolean = PACKAGE_NAME_REGEX.matches(value)

    // ------------------------------------------------------------------
    // device block rewrite
    // ------------------------------------------------------------------

    /**
     * The `device_info` block is a small length-delimited message whose
     * field-number set is exactly {1,2,4,5,6,8,9,10,11,12,13,14} and whose
     * fields are all varints. This exact signature distinguishes it from
     * key-ref hex strings and other small varint-only blobs that could
     * otherwise match a looser "≥5 varints, no LEN" heuristic.
     */
    private val DEVICE_BLOCK_FIELD_NUMS = setOf(1, 2, 4, 5, 6, 8, 9, 10, 11, 12, 13, 14)

    private fun isDeviceBlock(content: ByteArray): Boolean {
        if (content.isEmpty() || content.size > 100) return false
        val sub = try {
            parseFields(content)
        } catch (e: Exception) {
            return false
        }
        if (sub.isEmpty()) return false
        var varints = 0
        for (f in sub) {
            if (f.wireType != WIRE_TYPE_VARINT) return false
            varints++
        }
        if (varints < 5) return false
        val fieldNums = sub.map { it.fieldNumber }.toSet()
        return fieldNums == DEVICE_BLOCK_FIELD_NUMS
    }

    /**
     * Rewrites the device block to advertise HW_SECURE_DECODE:
     *  - set the `security_level` varint (field 5, the one capability-looking
     *    field) to the L1 numeric level,
     *  - append an explicit `HW_SECURE_DECODE` string field (field 16) so the
     *    level is stated unambiguously.
     */
    private fun rewriteDeviceBlock(content: ByteArray, ctx: RewriteContext): ByteArray? {
        val sub = parseFields(content)
        var changed = false

        val rebuilt = ArrayList<ProtoField>(sub.size)
        for (f in sub) {
            if (f.fieldNumber == 5 && f.wireType == WIRE_TYPE_VARINT) {
                val current = varintToLong(f.value)
                if (current != SEC_LEVEL_HW_SECURE_DECODE.toLong()) {
                    rebuilt.add(
                        ProtoField(f.fieldNumber, f.wireType,
                            encodeVarint(SEC_LEVEL_HW_SECURE_DECODE.toLong()))
                    )
                    changed = true
                    continue
                }
            }
            rebuilt.add(f)
        }

        // Append an explicit HW_SECURE_DECODE string field if not already present.
        val hasString = rebuilt.any { f ->
            f.wireType == WIRE_TYPE_LEN &&
                f.value.contentEquals(HW_SECURE_DECODE.toByteArray(Charsets.UTF_8))
        }
        if (!hasString) {
            rebuilt.add(
                ProtoField(16, WIRE_TYPE_LEN, HW_SECURE_DECODE.toByteArray(Charsets.UTF_8))
            )
            changed = true
        }

        ctx.deviceReplaced = true
        return if (changed) serializeFields(rebuilt) else null
    }

    private fun varintToLong(bytes: ByteArray): Long {
        var result = 0L
        var shift = 0
        for (b in bytes) {
            result = result or (((b.toInt() and 0x7F).toLong()) shl shift)
            shift += 7
            if (shift >= 64) break
        }
        return result
    }

    private fun officialCertB64Bytes(): ByteArray = OFFICIAL_CERT_SHA256_B64.toByteArray(Charsets.UTF_8)
    private fun currentCertB64Bytes(): ByteArray = CURRENT_CERT_SHA256_B64.toByteArray(Charsets.UTF_8)

    // ------------------------------------------------------------------
    // rewrite bookkeeping
    // ------------------------------------------------------------------

    private class RewriteContext(val currentPackageName: String) {
        var pkgReplaced = false
        var certReplaced = false
        var oemJsonReplaced = false
        var deviceReplaced = false
        var deviceKVsReplaced = false
        val anyChanged: Boolean
            get() = pkgReplaced || certReplaced || oemJsonReplaced ||
                deviceReplaced || deviceKVsReplaced
        fun copy(): RewriteContext {
            val c = RewriteContext(currentPackageName)
            c.pkgReplaced = pkgReplaced
            c.certReplaced = certReplaced
            c.oemJsonReplaced = oemJsonReplaced
            c.deviceReplaced = deviceReplaced
            c.deviceKVsReplaced = deviceKVsReplaced
            return c
        }
    }

    // ------------------------------------------------------------------
    // Minimal protobuf wire-format parser / serializer
    // ------------------------------------------------------------------

    private class ProtoField(
        val fieldNumber: Int,
        val wireType: Int,
        val value: ByteArray
    )

    private fun parseFields(bytes: ByteArray): List<ProtoField> {
        val fields = ArrayList<ProtoField>()
        var index = 0
        while (index < bytes.size) {
            val tagParsed = readVarint(bytes, index) ?: throw IllegalArgumentException("bad tag at $index")
            val wireTag = tagParsed.first.toInt()
            val fieldNumber = wireTag ushr 3
            val wireType = wireTag and 0x07
            index += tagParsed.second
            when (wireType) {
                WIRE_TYPE_VARINT -> {
                    val valueParsed = readVarint(bytes, index) ?: throw IllegalArgumentException("bad varint at $index")
                    index += valueParsed.second
                    fields.add(
                        ProtoField(fieldNumber, wireType, bytes.copyOfRange(index - valueParsed.second, index))
                    )
                }
                WIRE_TYPE_64 -> {
                    if (index + 8 > bytes.size) throw IllegalArgumentException("truncated 64-bit at $index")
                    fields.add(ProtoField(fieldNumber, wireType, bytes.copyOfRange(index, index + 8)))
                    index += 8
                }
                WIRE_TYPE_LEN -> {
                    val lenParsed = readVarint(bytes, index) ?: throw IllegalArgumentException("bad length at $index")
                    val len = lenParsed.first.toInt()
                    val contentStart = index + lenParsed.second
                    if (len < 0 || contentStart + len > bytes.size) {
                        throw IllegalArgumentException("bad length value at $index")
                    }
                    index = contentStart
                    fields.add(ProtoField(fieldNumber, wireType, bytes.copyOfRange(index, index + len)))
                    index += len
                }
                WIRE_TYPE_32 -> {
                    if (index + 4 > bytes.size) throw IllegalArgumentException("truncated 32-bit at $index")
                    fields.add(ProtoField(fieldNumber, wireType, bytes.copyOfRange(index, index + 4)))
                    index += 4
                }
                else -> throw IllegalArgumentException("unsupported wire type $wireType at $index")
            }
        }
        return fields
    }

    private fun serializeFields(fields: List<ProtoField>): ByteArray {
        val out = ByteArrayOutputStream()
        for (field in fields) {
            for (b in encodeVarint(((field.fieldNumber shl 3) or field.wireType).toLong())) out.write(b.toInt() and 0xFF)
            if (field.wireType == WIRE_TYPE_LEN) {
                for (b in encodeVarint(field.value.size.toLong())) out.write(b.toInt() and 0xFF)
            }
            for (b in field.value) out.write(b.toInt() and 0xFF)
        }
        return out.toByteArray()
    }

    private fun readVarint(bytes: ByteArray, offset: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var index = offset
        while (true) {
            if (index >= bytes.size) return null
            val b = bytes[index].toInt() and 0xFF
            index++
            result = result or (((b and 0x7F).toLong()) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift >= 64) return null
        }
        return result to (index - offset)
    }

    private fun encodeVarint(value: Long): ByteArray {
        val out = ArrayList<Byte>()
        var v = value
        while (v > 0x7FL) {
            out.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        out.add(v.toByte())
        return out.toByteArray()
    }

    private fun decodeHex(hex: String): ByteArray {
        val clean = hex.filter { it != ' ' }
        require(clean.length % 2 == 0) { "odd-length hex string" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
