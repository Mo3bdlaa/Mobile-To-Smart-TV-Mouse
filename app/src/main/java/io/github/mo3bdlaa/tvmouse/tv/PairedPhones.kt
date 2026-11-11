package io.github.mo3bdlaa.tvmouse.tv

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.github.mo3bdlaa.tvmouse.net.Crypto
import org.json.JSONObject
import java.net.Inet4Address
import java.util.UUID

/** Phones paired with this TV, plus this TV's own identity. Stored on the TV. */
object PairedPhones {

    data class Phone(val id: String, val name: String, val token: ByteArray)

    private const val PREFS = "receiver"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun tvId(context: Context): String {
        val prefs = prefs(context)
        return prefs.getString("tv_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("tv_id", it).apply()
        }
    }

    fun tvName(context: Context): String =
        runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    @Synchronized
    fun all(context: Context): List<Phone> {
        val json = JSONObject(prefs(context).getString("phones", "{}"))
        return json.keys().asSequence().map { id ->
            val o = json.getJSONObject(id)
            Phone(id, o.getString("name"), Crypto.unb64(o.getString("token")))
        }.toList()
    }

    fun get(context: Context, id: String): Phone? = all(context).firstOrNull { it.id == id }

    @Synchronized
    fun add(context: Context, id: String, name: String, token: ByteArray) {
        val json = JSONObject(prefs(context).getString("phones", "{}"))
        json.put(id, JSONObject().put("name", name).put("token", Crypto.b64(token)))
        prefs(context).edit().putString("phones", json.toString()).apply()
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        val json = JSONObject(prefs(context).getString("phones", "{}"))
        json.remove(id)
        prefs(context).edit().putString("phones", json.toString()).apply()
    }

    /** The TV's IPv4 address on the local network, for typing it on the phone by hand. */
    fun localAddress(context: Context): String? {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses.map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }
}
