package com.deniscerri.ytdl.core

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * YTindir: one CA file for Python (yt-dlp) and aria2c that holds both the list bundled with
 * Python and every certificate Android trusts: the system store, which the OS keeps up to date,
 * plus the ones the user installed (a network, school or work certificate, or a national root
 * some sites need). Python only reads its own list, so a site Android trusts but that list doesn't
 * know failed with "CERTIFICATE_VERIFY_FAILED: unable to get local issuer certificate".
 */
object CaBundle {

    private const val TAG = "CaBundle"

    /** Writes the combined list to [target] and returns its path, or null if nothing could be read. */
    fun build(context: Context, pythonCertFile: File, target: File): String? {
        return try {
            val pem = StringBuilder()

            if (pythonCertFile.canRead()) {
                pem.append(pythonCertFile.readText().trimEnd()).append('\n')
            }

            val androidCerts = androidTrustedCertificates()
            androidCerts.forEach { cert ->
                pem.append("-----BEGIN CERTIFICATE-----\n")
                pem.append(Base64.encodeToString(cert.encoded, Base64.DEFAULT).trimEnd())
                pem.append("\n-----END CERTIFICATE-----\n")
            }

            if (pem.isBlank()) return null

            val content = pem.toString()
            // don't rewrite it on every start when nothing changed
            if (!(target.exists() && target.length() == content.length.toLong() && target.readText() == content)) {
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.writeText(content)
                if (!tmp.renameTo(target)) {
                    target.writeText(content)
                    tmp.delete()
                }
            }
            target.absolutePath
        } catch (e: Throwable) {
            Log.e(TAG, "Could not build the CA bundle", e)
            if (pythonCertFile.canRead()) pythonCertFile.absolutePath else null
        }
    }

    /** System and user installed CAs, as Android's own trust store has them (disabled ones left out). */
    private fun androidTrustedCertificates(): List<X509Certificate> {
        return try {
            val store = KeyStore.getInstance("AndroidCAStore")
            store.load(null, null)
            store.aliases().toList().mapNotNull { alias ->
                runCatching { store.getCertificate(alias) as? X509Certificate }.getOrNull()
            }.distinctBy { it.encoded.contentHashCode() }
        } catch (e: Throwable) {
            Log.e(TAG, "Could not read Android's trust store", e)
            emptyList()
        }
    }
}
