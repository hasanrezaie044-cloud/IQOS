package com.example.iqoscontroller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A single device the user has connected to before. Real BLE hardware only allows one active
 * connection at a time, but the app can still remember every IQOS device the user owns and let
 * them switch which one is "active" for connecting / display purposes - exactly like the official
 * app's device list.
 */
data class SavedDevice(
    val address: String,
    var name: String,
    var model: String,
    var lastConnectedAt: Long
)

class DeviceRegistry(context: Context) {

    private val prefs = context.getSharedPreferences("iqos_devices", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LIST = "device_list_json"
        private const val KEY_ACTIVE = "active_device_address"
    }

    fun getAll(): List<SavedDevice> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SavedDevice(
                    address = o.getString("address"),
                    name = o.optString("name", "IQOS ILUMA i PRIME"),
                    model = o.optString("model", ""),
                    lastConnectedAt = o.optLong("lastConnectedAt", 0L)
                )
            }.sortedByDescending { it.lastConnectedAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveAll(list: List<SavedDevice>) {
        val arr = JSONArray()
        list.forEach { d ->
            val o = JSONObject()
            o.put("address", d.address)
            o.put("name", d.name)
            o.put("model", d.model)
            o.put("lastConnectedAt", d.lastConnectedAt)
            arr.put(o)
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    /** Called whenever a device successfully connects - adds it if new, refreshes it if known. */
    fun upsert(address: String, discoveredName: String?, model: String) {
        if (address.isBlank()) return
        val list = getAll().toMutableList()
        val idx = list.indexOfFirst { it.address == address }
        val now = System.currentTimeMillis()
        if (idx >= 0) {
            val existing = list[idx]
            list[idx] = existing.copy(model = model, lastConnectedAt = now)
        } else {
            list.add(
                SavedDevice(
                    address = address,
                    name = discoveredName?.takeIf { it.isNotBlank() } ?: "IQOS ILUMA i PRIME",
                    model = model,
                    lastConnectedAt = now
                )
            )
        }
        saveAll(list)
        setActiveAddress(address)
    }

    fun rename(address: String, newName: String) {
        val list = getAll().toMutableList()
        val idx = list.indexOfFirst { it.address == address }
        if (idx >= 0) {
            list[idx] = list[idx].copy(name = newName)
            saveAll(list)
        }
    }

    fun remove(address: String) {
        saveAll(getAll().filterNot { it.address == address })
        if (getActiveAddress() == address) {
            setActiveAddress(getAll().firstOrNull()?.address)
        }
    }

    fun getActiveAddress(): String? = prefs.getString(KEY_ACTIVE, null)

    fun setActiveAddress(address: String?) {
        prefs.edit().putString(KEY_ACTIVE, address).apply()
    }

    fun getActive(): SavedDevice? {
        val address = getActiveAddress() ?: return null
        return getAll().firstOrNull { it.address == address }
    }
}
