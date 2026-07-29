package com.watchtastic.platform

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Small, synchronous settings store.
 *
 * DataStore would be the reflex choice, but every read here happens during composition
 * or service start-up where a suspending read means a frame of empty UI. The payload is
 * a handful of scalars, so `SharedPreferences` mirrored into [StateFlow]s is both
 * simpler and better behaved on a watch.
 */
class Prefs(context: Context) {

    private companion object {
        const val FILE = "watchtastic_prefs"
        const val KEY_ADDRESS = "radio_address"
        const val KEY_NAME = "radio_name"
        const val KEY_AUTO_CONNECT = "auto_connect"
        const val KEY_SHARE_LOCATION = "share_location"
        const val KEY_HAPTICS = "haptics"
        const val KEY_NOTIFY_CHANNELS = "notify_channels"
        const val KEY_IMPERIAL = "imperial_units"
        const val KEY_CANNED = "canned_messages"
        const val KEY_MUTED_CHANNELS = "muted_channels"
        const val KEY_NOTIFY_NEW_NODES = "notify_new_nodes"

        val DEFAULT_CANNED = listOf(
            "On my way",
            "Roger that",
            "Standing by",
            "Need assistance",
            "All clear",
            "Yes",
            "No",
            "Where are you?",
        )
    }

    private val sp: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _radioAddress = MutableStateFlow(sp.getString(KEY_ADDRESS, null))
    val radioAddress: StateFlow<String?> = _radioAddress.asStateFlow()

    private val _radioName = MutableStateFlow(sp.getString(KEY_NAME, null))
    val radioName: StateFlow<String?> = _radioName.asStateFlow()

    private val _autoConnect = MutableStateFlow(sp.getBoolean(KEY_AUTO_CONNECT, true))
    val autoConnect: StateFlow<Boolean> = _autoConnect.asStateFlow()

    private val _shareLocation = MutableStateFlow(sp.getBoolean(KEY_SHARE_LOCATION, false))
    val shareLocation: StateFlow<Boolean> = _shareLocation.asStateFlow()

    private val _haptics = MutableStateFlow(sp.getBoolean(KEY_HAPTICS, true))
    val haptics: StateFlow<Boolean> = _haptics.asStateFlow()

    private val _notifyChannels = MutableStateFlow(sp.getBoolean(KEY_NOTIFY_CHANNELS, true))
    val notifyChannels: StateFlow<Boolean> = _notifyChannels.asStateFlow()

    private val _imperialUnits = MutableStateFlow(sp.getBoolean(KEY_IMPERIAL, false))
    val imperialUnits: StateFlow<Boolean> = _imperialUnits.asStateFlow()

    private val _notifyNewNodes = MutableStateFlow(sp.getBoolean(KEY_NOTIFY_NEW_NODES, true))
    val notifyNewNodes: StateFlow<Boolean> = _notifyNewNodes.asStateFlow()

    fun setNotifyNewNodes(value: Boolean) = putBool(KEY_NOTIFY_NEW_NODES, value, _notifyNewNodes)

    private val _canned = MutableStateFlow(readCanned())
    val cannedMessages: StateFlow<List<String>> = _canned.asStateFlow()

    /**
     * Channels muted **on this watch**.
     *
     * Deliberately local rather than written to the radio. Muting a channel device-side
     * means a `set_channel` admin write, and `ChannelSettings` is replace-not-merge — so
     * sending one back without the PSK (which this app never stores) would silently wipe
     * the channel's encryption key. Local muting is also the behaviour a wearer actually
     * wants: quiet on the wrist, unchanged for every other client on the radio.
     */
    private val _mutedChannels = MutableStateFlow(readMutedChannels())
    val mutedChannels: StateFlow<Set<Int>> = _mutedChannels.asStateFlow()

    fun setChannelMuted(index: Int, muted: Boolean) {
        val next = if (muted) _mutedChannels.value + index else _mutedChannels.value - index
        sp.edit { putString(KEY_MUTED_CHANNELS, next.joinToString(",")) }
        _mutedChannels.value = next
    }

    private fun readMutedChannels(): Set<Int> =
        sp.getString(KEY_MUTED_CHANNELS, null)
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.toSet()
            .orEmpty()

    fun rememberRadio(address: String, name: String) {
        sp.edit {
            putString(KEY_ADDRESS, address)
            putString(KEY_NAME, name)
        }
        _radioAddress.value = address
        _radioName.value = name
    }

    fun forgetRadio() {
        sp.edit {
            remove(KEY_ADDRESS)
            remove(KEY_NAME)
        }
        _radioAddress.value = null
        _radioName.value = null
    }

    fun setAutoConnect(value: Boolean) = putBool(KEY_AUTO_CONNECT, value, _autoConnect)

    fun setShareLocation(value: Boolean) = putBool(KEY_SHARE_LOCATION, value, _shareLocation)

    fun setHaptics(value: Boolean) = putBool(KEY_HAPTICS, value, _haptics)

    fun setNotifyChannels(value: Boolean) = putBool(KEY_NOTIFY_CHANNELS, value, _notifyChannels)

    fun setImperialUnits(value: Boolean) = putBool(KEY_IMPERIAL, value, _imperialUnits)

    fun setCannedMessages(messages: List<String>) {
        // Newlines are the record separator, so they can't appear inside an entry.
        val cleaned = messages.map { it.replace('\n', ' ').trim() }.filter { it.isNotEmpty() }
        sp.edit { putString(KEY_CANNED, cleaned.joinToString("\n")) }
        _canned.value = cleaned
    }

    private fun readCanned(): List<String> {
        val raw = sp.getString(KEY_CANNED, null) ?: return DEFAULT_CANNED
        return raw.split('\n').filter { it.isNotBlank() }.ifEmpty { DEFAULT_CANNED }
    }

    private fun putBool(key: String, value: Boolean, flow: MutableStateFlow<Boolean>) {
        sp.edit { putBoolean(key, value) }
        flow.value = value
    }
}
