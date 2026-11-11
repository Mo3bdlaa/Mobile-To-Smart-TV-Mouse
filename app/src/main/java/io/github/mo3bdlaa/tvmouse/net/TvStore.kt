package io.github.mo3bdlaa.tvmouse.net

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** TVs this phone has paired with, stored on the phone. */
object TvStore {

    data class Tv(val id: String, val name: String, val host: String, val port: Int, val token: ByteArray) {
        override fun equals(other: Any?) = other is Tv && other.id == id && other.host == host && other.port == port
        override fun hashCode() = id.hashCode()
    }

    private const val PREFS = "wifi"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun phoneId(context: Context): String {
        val prefs = prefs(context)
        return prefs.getString("phone_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("phone_id", it).apply()
        }
    }

    fun all(context: Context): List<Tv> {
        val array = JSONArray(prefs(context).getString("tvs", "[]"))
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Tv(o.getString("id"), o.getString("name"), o.getString("host"), o.getInt("port"), Crypto.unb64(o.getString("token")))
        }
    }

    fun get(context: Context, id: String): Tv? = all(context).firstOrNull { it.id == id }

    fun save(context: Context, tv: Tv) {
        val list = all(context).filter { it.id != tv.id } + tv
        write(context, list)
        prefs(context).edit().putString("last_tv", tv.id).apply()
    }

    fun remove(context: Context, id: String) {
        write(context, all(context).filter { it.id != id })
    }

    fun last(context: Context): Tv? = prefs(context).getString("last_tv", null)?.let { get(context, it) }

    /** Keeps the saved address up to date when the TV gets a new IP from the router. */
    fun updateAddress(context: Context, id: String, host: String, port: Int) {
        val tv = get(context, id) ?: return
        if (tv.host != host || tv.port != port) {
            write(context, all(context).map { if (it.id == id) it.copy(host = host, port = port) else it })
        }
    }

    private fun write(context: Context, list: List<Tv>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject().put("id", it.id).put("name", it.name).put("host", it.host)
                    .put("port", it.port).put("token", Crypto.b64(it.token)),
            )
        }
        prefs(context).edit().putString("tvs", array.toString()).apply()
    }
}
