package com.watchtastic.mesh

import android.util.Log
import com.watchtastic.mesh.model.ChatMessage
import com.watchtastic.mesh.model.ConversationKey
import com.watchtastic.mesh.model.MeshChannel
import com.watchtastic.mesh.model.MeshNode
import com.watchtastic.mesh.model.MsgStatus
import com.watchtastic.mesh.model.NodeMetrics
import com.watchtastic.mesh.model.NodePosition
import com.watchtastic.mesh.model.TraceHop
import com.watchtastic.mesh.model.TraceRoute
import com.watchtastic.mesh.model.Waypoint
import org.meshtastic.proto.AdminProtos
import org.meshtastic.proto.ChannelProtos
import org.meshtastic.proto.ConfigProtos
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.Portnums
import org.meshtastic.proto.TelemetryProtos

/** Callbacks for the things the store alone can't decide. */
interface PacketEvents {
    /** A Routing reply arrived for one of our outbound packets. */
    fun onDeliveryReceipt(requestId: Int, error: MeshProtos.Routing.Error)

    fun onTraceRoute(result: TraceRoute)

    fun onAdminReply(message: AdminProtos.AdminMessage)

    /** Firmware wants to tell the wearer something (key mismatch, low entropy, …). */
    fun onClientNotification(text: String)

    /** The radio rebooted underneath us, so our cached config is suspect. */
    fun onRadioRebooted()

    fun onConfigComplete(nonce: Int)

    fun onConfigProgress()
}

/**
 * Translates `FromRadio` frames into store mutations.
 *
 * Kept separate from the connection code so the decode rules — which are where the
 * protocol's sharp edges live — can be read, and changed, on their own.
 */
class PacketRouter(
    private val store: MeshStore,
    private val events: PacketEvents,
) {
    private companion object {
        const val TAG = "PacketRouter"

        /** Meshtastic transmits coordinates as integers scaled by 1e7. */
        const val COORD_SCALE = 1e-7
    }

    fun handle(frame: MeshProtos.FromRadio) {
        when (frame.payloadVariantCase) {
            MeshProtos.FromRadio.PayloadVariantCase.MY_INFO -> handleMyInfo(frame.myInfo)
            MeshProtos.FromRadio.PayloadVariantCase.METADATA -> handleMetadata(frame.metadata)
            MeshProtos.FromRadio.PayloadVariantCase.NODE_INFO -> handleNodeInfo(frame.nodeInfo)
            MeshProtos.FromRadio.PayloadVariantCase.CONFIG -> handleConfig(frame.config)
            MeshProtos.FromRadio.PayloadVariantCase.CHANNEL -> handleChannel(frame.channel)
            MeshProtos.FromRadio.PayloadVariantCase.PACKET -> handlePacket(frame.packet)
            MeshProtos.FromRadio.PayloadVariantCase.QUEUESTATUS -> handleQueueStatus(frame.queueStatus)
            MeshProtos.FromRadio.PayloadVariantCase.CONFIG_COMPLETE_ID ->
                events.onConfigComplete(frame.configCompleteId)

            MeshProtos.FromRadio.PayloadVariantCase.REBOOTED -> events.onRadioRebooted()
            MeshProtos.FromRadio.PayloadVariantCase.CLIENTNOTIFICATION ->
                events.onClientNotification(frame.clientNotification.message)

            MeshProtos.FromRadio.PayloadVariantCase.MODULECONFIG -> events.onConfigProgress()

            // Log records and file/xmodem traffic aren't surfaced on a watch.
            else -> Unit
        }
    }

    private fun handleMyInfo(info: MeshProtos.MyNodeInfo) {
        store.setMyNodeNum(info.myNodeNum)
        store.setRadioInfo {
            it.copy(rebootCount = info.rebootCount, nodeDbCount = info.nodedbCount)
        }
        events.onConfigProgress()
    }

    private fun handleMetadata(meta: MeshProtos.DeviceMetadata) {
        store.setRadioInfo {
            it.copy(
                firmwareVersion = meta.firmwareVersion,
                hwModel = meta.hwModel.name,
                role = meta.role.name,
                hasBluetooth = meta.hasBluetooth,
                hasWifi = meta.hasWifi,
                hasPKC = meta.hasPKC,
                canShutdown = meta.canShutdown,
            )
        }
        events.onConfigProgress()
    }

    private fun handleNodeInfo(info: MeshProtos.NodeInfo) {
        val self = store.myNodeNum.value
        store.upsertNode(
            MeshNode(
                num = info.num,
                userId = info.user.id.ifBlank { MeshConstants.nodeIdOf(info.num) },
                longName = info.user.longName,
                shortName = info.user.shortName,
                hwModel = info.user.hwModel.name,
                role = info.user.role.name,
                lastHeardSeconds = info.lastHeard,
                snr = info.snr,
                hopsAway = if (info.hasHopsAway()) info.hopsAway else null,
                channelIndex = info.channel,
                position = info.position.toModel(),
                metrics = info.deviceMetrics.toModel(),
                isFavorite = info.isFavorite,
                isIgnored = info.isIgnored,
                isMuted = info.isMuted,
                viaMqtt = info.viaMqtt,
                hasPublicKey = !info.user.publicKey.isEmpty(),
                isUnmessagable = info.user.hasIsUnmessagable() && info.user.isUnmessagable,
                isSelf = info.num == self,
            ),
        )
        events.onConfigProgress()
    }

    private fun handleConfig(config: ConfigProtos.Config) {
        when (config.payloadVariantCase) {
            ConfigProtos.Config.PayloadVariantCase.LORA -> {
                val lora = config.lora
                // Keep the whole message, not just the fields we display — edits are
                // built from this so untouched settings survive the round trip.
                store.setLoraConfig(lora)
                store.setRadioInfo {
                    it.copy(
                        region = lora.region.name,
                        modemPreset = lora.modemPreset.name,
                        hopLimit = lora.hopLimit,
                        txEnabled = lora.txEnabled,
                    )
                }
            }

            ConfigProtos.Config.PayloadVariantCase.DEVICE -> {
                store.setDeviceConfig(config.device)
                store.setRadioInfo { it.copy(role = config.device.role.name) }
            }

            else -> Unit
        }
        events.onConfigProgress()
    }

    private fun handleChannel(channel: ChannelProtos.Channel) {
        store.setChannel(
            MeshChannel(
                index = channel.index,
                name = channel.settings.name,
                role = channel.role.name,
                hasKey = !channel.settings.psk.isEmpty(),
                uplinkEnabled = channel.settings.uplinkEnabled,
                downlinkEnabled = channel.settings.downlinkEnabled,
            ),
        )
        events.onConfigProgress()
    }

    private fun handleQueueStatus(status: MeshProtos.QueueStatus) {
        if (status.meshPacketId == 0) return
        if (status.res == 0) {
            store.updateMessageStatus(status.meshPacketId, MsgStatus.Sent)
        } else {
            store.updateMessageStatus(
                status.meshPacketId,
                MsgStatus.Failed,
                "Radio queue rejected it (${status.res})",
            )
        }
    }

    private fun handlePacket(packet: MeshProtos.MeshPacket) {
        // An `encrypted` payload means the radio had no key for that channel; there is
        // nothing useful we can do with it, but the sender is still worth remembering.
        if (!packet.hasDecoded()) {
            if (packet.from != 0) touchNode(packet)
            return
        }
        val data = packet.decoded
        touchNode(packet)

        when (data.portnum) {
            Portnums.PortNum.TEXT_MESSAGE_APP -> handleText(packet, data)
            Portnums.PortNum.POSITION_APP -> handlePosition(packet, data)
            Portnums.PortNum.NODEINFO_APP -> handleUser(packet, data)
            Portnums.PortNum.TELEMETRY_APP -> handleTelemetry(packet, data)
            Portnums.PortNum.ROUTING_APP -> handleRouting(data)
            Portnums.PortNum.TRACEROUTE_APP -> handleTraceRoute(packet, data)
            Portnums.PortNum.WAYPOINT_APP -> handleWaypoint(packet, data)
            Portnums.PortNum.ADMIN_APP -> runCatching {
                events.onAdminReply(AdminProtos.AdminMessage.parseFrom(data.payload))
            }.onFailure { Log.w(TAG, "bad admin reply", it) }

            // These all present as text to the wearer, just from a different module.
            Portnums.PortNum.ALERT_APP,
            Portnums.PortNum.DETECTION_SENSOR_APP,
            Portnums.PortNum.RANGE_TEST_APP,
            -> handleText(packet, data)

            else -> Unit
        }
    }

    /** Any traffic from a node updates its liveness, SNR and hop count. */
    private fun touchNode(packet: MeshProtos.MeshPacket) {
        if (packet.from == 0) return
        val heard = if (packet.rxTime != 0) packet.rxTime else nowSeconds()
        val hops = if (packet.hopStart > 0 && packet.hopLimit <= packet.hopStart) {
            packet.hopStart - packet.hopLimit
        } else {
            null
        }
        store.mutateNode(packet.from) {
            it.copy(
                lastHeardSeconds = maxOf(it.lastHeardSeconds, heard),
                snr = if (packet.rxSnr != 0f) packet.rxSnr else it.snr,
                rssi = if (packet.rxRssi != 0) packet.rxRssi else it.rssi,
                hopsAway = hops ?: it.hopsAway,
                viaMqtt = packet.viaMqtt || it.viaMqtt,
            )
        }
    }

    private fun handleText(packet: MeshProtos.MeshPacket, data: MeshProtos.Data) {
        val self = store.myNodeNum.value
        val conversation = when {
            packet.to == MeshConstants.BROADCAST_ADDR -> ConversationKey.channel(packet.channel)
            packet.to == self -> ConversationKey.direct(packet.from)
            // Overheard traffic addressed to a third party: not ours to display.
            else -> return
        }
        val text = data.payload.toStringUtf8()
        if (text.isEmpty()) return

        store.addMessage(
            ChatMessage(
                id = packet.id,
                conversation = conversation,
                fromNum = packet.from,
                toNum = packet.to,
                channel = packet.channel,
                text = text,
                timeMs = if (packet.rxTime != 0) packet.rxTime * 1000L else System.currentTimeMillis(),
                outgoing = false,
                status = MsgStatus.Received,
                replyId = data.replyId,
                // `emoji` is a flag, not a codepoint: it marks the payload as a tapback.
                isReaction = data.emoji != 0,
                snr = packet.rxSnr,
                rssi = packet.rxRssi,
                pkiEncrypted = packet.pkiEncrypted,
            ),
        )
    }

    private fun handlePosition(packet: MeshProtos.MeshPacket, data: MeshProtos.Data) {
        runCatching {
            val position = MeshProtos.Position.parseFrom(data.payload)
            position.toModel()?.let {
                store.applyPosition(
                    packet.from,
                    it,
                    if (packet.rxTime != 0) packet.rxTime else nowSeconds(),
                )
            }
        }.onFailure { Log.w(TAG, "bad position payload", it) }
    }

    private fun handleUser(packet: MeshProtos.MeshPacket, data: MeshProtos.Data) {
        runCatching {
            val user = MeshProtos.User.parseFrom(data.payload)
            store.mutateNode(packet.from) {
                it.copy(
                    userId = user.id.ifBlank { it.userId },
                    longName = user.longName.ifBlank { it.longName },
                    shortName = user.shortName.ifBlank { it.shortName },
                    hwModel = user.hwModel.name,
                    role = user.role.name,
                    hasPublicKey = !user.publicKey.isEmpty(),
                    isUnmessagable = user.hasIsUnmessagable() && user.isUnmessagable,
                )
            }
        }.onFailure { Log.w(TAG, "bad nodeinfo payload", it) }
    }

    private fun handleTelemetry(packet: MeshProtos.MeshPacket, data: MeshProtos.Data) {
        runCatching {
            val telemetry = TelemetryProtos.Telemetry.parseFrom(data.payload)
            val heard = if (packet.rxTime != 0) packet.rxTime else nowSeconds()
            when (telemetry.variantCase) {
                TelemetryProtos.Telemetry.VariantCase.DEVICE_METRICS ->
                    store.applyMetrics(packet.from, telemetry.deviceMetrics.toModel(), heard)

                TelemetryProtos.Telemetry.VariantCase.ENVIRONMENT_METRICS -> {
                    val env = telemetry.environmentMetrics
                    store.applyMetrics(
                        packet.from,
                        NodeMetrics(
                            temperature = if (env.hasTemperature()) env.temperature else null,
                            relativeHumidity = if (env.hasRelativeHumidity()) {
                                env.relativeHumidity
                            } else {
                                null
                            },
                            barometricPressure = if (env.hasBarometricPressure()) {
                                env.barometricPressure
                            } else {
                                null
                            },
                        ),
                        heard,
                    )
                }

                TelemetryProtos.Telemetry.VariantCase.LOCAL_STATS -> {
                    val stats = telemetry.localStats
                    store.applyMetrics(
                        packet.from,
                        NodeMetrics(
                            channelUtilization = stats.channelUtilization,
                            airUtilTx = stats.airUtilTx,
                            uptimeSeconds = stats.uptimeSeconds,
                        ),
                        heard,
                    )
                }

                else -> Unit
            }
        }.onFailure { Log.w(TAG, "bad telemetry payload", it) }
    }

    private fun handleRouting(data: MeshProtos.Data) {
        if (data.requestId == 0) return
        runCatching {
            val routing = MeshProtos.Routing.parseFrom(data.payload)
            events.onDeliveryReceipt(data.requestId, routing.errorReason)
        }.onFailure { Log.w(TAG, "bad routing payload", it) }
    }

    private fun handleTraceRoute(packet: MeshProtos.MeshPacket, data: MeshProtos.Data) {
        runCatching {
            val discovery = MeshProtos.RouteDiscovery.parseFrom(data.payload)
            events.onTraceRoute(
                TraceRoute(
                    targetNum = packet.from,
                    towards = discovery.routeList.mapIndexed { i, num ->
                        TraceHop(num, discovery.snrTowardsList.getOrNull(i)?.let { it / 4f })
                    },
                    back = discovery.routeBackList.mapIndexed { i, num ->
                        TraceHop(num, discovery.snrBackList.getOrNull(i)?.let { it / 4f })
                    },
                    completedAtMs = System.currentTimeMillis(),
                ),
            )
        }.onFailure { Log.w(TAG, "bad traceroute payload", it) }
    }

    private fun handleWaypoint(packet: MeshProtos.MeshPacket, data: MeshProtos.Data) {
        runCatching {
            val wp = MeshProtos.Waypoint.parseFrom(data.payload)
            if (!wp.hasLatitudeI() || !wp.hasLongitudeI()) return
            store.upsertWaypoint(
                Waypoint(
                    id = wp.id,
                    name = wp.name,
                    description = wp.description,
                    latitude = wp.latitudeI * COORD_SCALE,
                    longitude = wp.longitudeI * COORD_SCALE,
                    expireSeconds = wp.expire,
                    lockedTo = wp.lockedTo,
                    createdByNum = packet.from,
                ),
            )
        }.onFailure { Log.w(TAG, "bad waypoint payload", it) }
    }

    // ------------------------------------------------------------- converters

    private fun MeshProtos.Position.toModel(): NodePosition? {
        if (!hasLatitudeI() || !hasLongitudeI()) return null
        if (latitudeI == 0 && longitudeI == 0) return null
        return NodePosition(
            latitude = latitudeI * COORD_SCALE,
            longitude = longitudeI * COORD_SCALE,
            altitudeMeters = if (hasAltitude()) altitude else null,
            timeSeconds = if (time != 0) time else timestamp,
            satsInView = satsInView,
            groundSpeed = if (hasGroundSpeed()) groundSpeed else null,
        )
    }

    private fun TelemetryProtos.DeviceMetrics.toModel(): NodeMetrics = NodeMetrics(
        batteryLevel = if (hasBatteryLevel()) batteryLevel else null,
        voltage = if (hasVoltage()) voltage else null,
        channelUtilization = if (hasChannelUtilization()) channelUtilization else null,
        airUtilTx = if (hasAirUtilTx()) airUtilTx else null,
        uptimeSeconds = if (hasUptimeSeconds()) uptimeSeconds else null,
    )

    private fun nowSeconds(): Int = (System.currentTimeMillis() / 1000L).toInt()
}
