/*
 * Copyright (c) 2019, Dimitri <lialios.dimitri@gmail.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package net.runelite.client.plugins.revenantscout

import com.google.inject.Provides
import net.runelite.api.Client
import net.runelite.api.GameState
import net.runelite.api.Player
import net.runelite.api.SkullIcon
import net.runelite.api.events.GameStateChanged
import net.runelite.api.events.GameTick
import net.runelite.client.config.*
import net.runelite.client.eventbus.EventBus
import net.runelite.client.events.ConfigChanged
import net.runelite.client.game.ItemManager
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.PluginType
import net.runelite.client.ui.overlay.Overlay
import net.runelite.client.ui.overlay.OverlayManager
import net.runelite.client.ui.overlay.OverlayPosition
import net.runelite.client.ui.overlay.components.ImageComponent
import net.runelite.client.ui.overlay.components.PanelComponent
import net.runelite.client.util.ImageUtil
import net.runelite.http.api.RuneLiteAPI
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.awt.Dimension
import java.awt.Graphics2D
import java.awt.Rectangle
import java.io.IOException
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.properties.Delegates

@PluginDescriptor(
        name = "Revenant Scout",
        description = "Sends game data to a remote host when inside the Revenant Caves",
        tags = ["revenant", "scout", "discord"],
        type = PluginType.EXTERNAL,
        enabledByDefault = false
)
class RevenantScoutPlugin : Plugin() {
    //dependency injection
    @Inject private lateinit var client: Client

    @Inject private lateinit var eventBus: EventBus

    @Inject private lateinit var itemManager: ItemManager

    @Inject private lateinit var discordOverlay: DiscordOverlay

    @Inject private lateinit var overlayManager: OverlayManager

    @Inject private lateinit var config: RevenantScoutConfig

    @Provides private fun getConfig(configManager: ConfigManager): RevenantScoutConfig =
            configManager.getConfig(RevenantScoutConfig::class.java)

    //properties for config
    private var showDiscordOverlay by Delegates.notNull<Boolean>()

    private var remoteHost by Delegates.notNull<String>()

    private var transmitFreq by Delegates.notNull<Int>()

    private var skulledPlayersOnly by Delegates.notNull<Boolean>()

    private var wealthIncreaseWarning by Delegates.notNull<Int>()

    //properties for plugin logic
    @Volatile private var sentData: PlayerDataContainer? = null

    @Volatile private var inProgressPOST: Boolean = false

    private var lastPOST = Instant.MIN

    private val isInRegion: () -> Boolean = {
        val localPlayer = client.localPlayer
        if (localPlayer != null)
            setOf(12703, 12959, 12702, 12958, 12701, 12957).contains(localPlayer.worldLocation.regionID)
        else false
    }

    override fun startUp() {
        //subscribe to necessary events
        eventBus.subscribe(GameStateChanged::class.java, this, ::onGameStateChanged)
        eventBus.subscribe(ConfigChanged::class.java, this, ::onConfigChanged)
        eventBus.subscribe(GameTick::class.java, this, ::onGameTick)

        //init config properties
        showDiscordOverlay = config.showDiscordOverlay()
        remoteHost = config.remoteHost()
        transmitFreq = config.transmitFreq()
        skulledPlayersOnly = config.skulledPlayersOnly()
        wealthIncreaseWarning = config.wealthIncreaseWarning()
        //clear what was sent previously
        sentData = null

        //add overlay if needed
        if (showDiscordOverlay && isInRegion())
            overlayManager.add(discordOverlay)
    }

    override fun shutDown() {
        //unsubscribe from all events
        eventBus.unregister(this)

        //clear what was sent previously
        sentData = null

        //remove the overlay
        overlayManager.remove(discordOverlay)
    }

    private fun onGameStateChanged(event: GameStateChanged) =
            if (event.gameState == GameState.LOGGING_IN || event.gameState == GameState.HOPPING)
                sentData = null
            else Unit

    private fun onConfigChanged(event: ConfigChanged) {
        if (event.group == "revenantscout") {
            val newVal = event.newValue ?: return Unit
            when (event.key) {
                "overlay" -> Unit

                "connection" -> Unit

                "filters" -> Unit

                "showDiscordOverlay" -> showDiscordOverlay = newVal.toBoolean()

                "remoteHost" -> remoteHost = newVal

                "transmitFreq" -> transmitFreq = newVal.toInt()

                "skulledPlayersOnly" -> skulledPlayersOnly = newVal.toBoolean()

                "wealthIncreaseWarning" -> wealthIncreaseWarning = newVal.toInt()

                else -> throw UnsupportedOperationException("unimplemented key: ${event.key}")
            }
        }
    }

    private fun onGameTick(@Suppress("UNUSED_PARAMETER") event: GameTick) {
        //process the overlay
        if (showDiscordOverlay && isInRegion())
            overlayManager.add(discordOverlay)
        else
            overlayManager.remove(discordOverlay)

        //return if no scouting needs to be done
        if (!isInRegion() || inProgressPOST || Duration.between(lastPOST, Instant.now()).seconds < transmitFreq)
            return

        //avoid a future NPE by making a copy
        var copyOfSentData = sentData ?: PlayerDataContainer.create(client.world)
        //if true then game state or plugin state changed since the last game tick event
        if (copyOfSentData.count() > 0 && copyOfSentData[0].world != client.world)
            copyOfSentData = PlayerDataContainer.create(client.world)

        //prepare the payload and avoid resending data
        val dataToSend =
                PlayerDataContainer.create(client.world).let { theContainer: PlayerDataContainer ->
                    val findWealth: (Player) -> Long = {
                        var amount: Long = 0
                        val equipmentIDs = it.playerAppearance?.equipmentIds ?: intArrayOf()
                        for (id in equipmentIDs)
                            amount += if (id > 512) itemManager.getItemPrice(id - 512) else 0
                        amount
                    }
                    client.players.filter {
                        val playerData =
                                PlayerData(
                                        it.name ?: "null",
                                        it.combatLevel,
                                        findWealth(it),
                                        it.skullIcon == SkullIcon.SKULL,
                                        client.world
                                )
                        when {
                            //exclude the local player
                            it == client.localPlayer -> false

                            //exclude players that are not skulled when considering only skulled players
                            skulledPlayersOnly && !playerData.skull -> false

                            //exclude players whose information has already been sent
                            copyOfSentData.contains(playerData) -> false

                            //include players for which there exists no past record
                            copyOfSentData.count { x: PlayerData ->
                                x.name == playerData.name
                            } == 0 -> true

                            //include players for which a past record exists but a resubmission is needed
                            copyOfSentData.count { x: PlayerData ->
                                (x.name == playerData.name)
                                        && ((playerData.wealth - x.wealth >= wealthIncreaseWarning) || (!x.skull && playerData.skull))
                            } != 0 -> true

                            //exclude players otherwise
                            else -> false
                        }
                    }.forEach {
                        val playerData =
                                PlayerData(
                                        it.name ?: "null",
                                        it.combatLevel,
                                        findWealth(it),
                                        it.skullIcon == SkullIcon.SKULL,
                                        client.world
                                )
                        copyOfSentData.plusAssign(playerData)
                        theContainer.plusAssign(playerData)
                    }
                    theContainer
                }

        //update the object containing sent player data
        sentData = copyOfSentData

        //do not send empty iterables
        if (dataToSend.count() == 0)
            return

        //prepare a POST request and enqueue it
        inProgressPOST = true
        val requestBody =
                RuneLiteAPI.GSON.toJson(dataToSend).toRequestBody("application/json; charset=utf-8".toMediaType())
        //the second half of this elvis operator will never evaluate (see config class companion object)
        val httpUrl
                = remoteHost.toHttpUrlOrNull() ?: HttpUrl.Builder().host("https://localhost/").build()
        RuneLiteAPI.CLIENT.newCall(
                Request.Builder().method("POST", requestBody).url(httpUrl).build()
        ).enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        //reconstruct the sent player cache as if the previous additions to it never happened
                        if (client.world == dataToSend[0].world)
                            sentData =
                                    PlayerDataContainer.create(client.world).let { newContainer: PlayerDataContainer ->
                                        copyOfSentData.filter { !dataToSend.contains(it) }.forEach { newContainer.plusAssign(it) }
                                        newContainer
                                    }
                        inProgressPOST = false
                    }
                    override fun onResponse(call: Call, response: Response) {
                        //reconstruct the sent player cache as if the previous additions to it never happened
                        if (response.code != 200 && client.world == dataToSend[0].world)
                            sentData =
                                    PlayerDataContainer.create(client.world).let { newContainer: PlayerDataContainer ->
                                        copyOfSentData.filter { !dataToSend.contains(it) }.forEach { newContainer.plusAssign(it) }
                                        newContainer
                                    }
                        response.close()
                        inProgressPOST = false
                        discordOverlay.pulseRequired = true
                    }
                }
        )
        lastPOST = Instant.now()
    }
}

@ConfigGroup("revenantscout")
interface RevenantScoutConfig : Config {
    //define three sections
    @ConfigSection(position = 0, keyName = "overlay", name = "Overlay", description = "")
    @JvmDefault fun overlay() = false

    @ConfigSection(position = 1, keyName = "connection", name = "Connection", description = "")
    @JvmDefault fun connection() = false

    @ConfigSection(position = 2, keyName = "filters", name = "Filters", description = "")
    @JvmDefault fun filters() = false

    //overlay section
    @ConfigItem(position = 0, keyName = "showDiscordOverlay", name = "Show Discord Icon",
            description = "Display an indicator when actively scouting",
            section = "overlay")
    @JvmDefault fun showDiscordOverlay() = true

    //connection section
    companion object { @JvmStatic fun parse(s: String): Boolean = s.toHttpUrlOrNull() != null }
    @ConfigItem(position = 0, keyName = "remoteHost", name = "Remote Host",
            description = "JSON data will be sent to this address via HTTP",
            section = "connection",
            parse = true,
            clazz = RevenantScoutConfig::class,
            method = "parse")
    @JvmDefault fun remoteHost() = "https://localhost/"

    @ConfigItem(position = 1, keyName = "transmitFreq", name = "Update Frequency",
            description = "The minimum amount of seconds to wait before sending the server more data",
            section = "connection")
    @JvmDefault @Range(min = 2, max = 6) fun transmitFreq() = 4

    //filters section
    @ConfigItem(position = 0, keyName = "skulledPlayersOnly", name = "Skulls Only",
            description = "Only send information for players that are skulled",
            section = "filters")
    @JvmDefault fun skulledPlayersOnly() = false

    @ConfigItem(position = 1, keyName = "wealthIncreaseWarning", name = "Sudden High Roller",
            description = "Resend information pertaining to a player if the value of their gear increases by this amount or greater",
            section = "filters")
    @JvmDefault fun wealthIncreaseWarning() = 500000

    //logo message at bottom
    @ConfigTitleSection(position = Int.MAX_VALUE, keyName = "bottomMessage",
            name = """<html><font size="6" color="#D500FF">Wilderness Guardians</font></html>""",
            description = "")
    @JvmDefault fun bottomMessage() = Title()
}

@Singleton
class DiscordOverlay : Overlay() {
    private val panelComponent = PanelComponent()
    private val bufferedImages =
            arrayOf(ImageUtil.getResourceStreamFromClass(RevenantScoutPlugin::class.java, "discord_icon.png"),
                    ImageUtil.getResourceStreamFromClass(RevenantScoutPlugin::class.java, "discord_icon_pulse.png"))
    private var pulseStart = Instant.MAX
    private var pulseDuration = Duration.ZERO
    @Volatile var pulseRequired = false

    init {
        super.setPosition(OverlayPosition.ABOVE_CHATBOX_RIGHT)
        panelComponent.setBackgroundColor(null)
        //left.up.right.down from image
        panelComponent.setBorder(Rectangle(5, 5, 4, 4))
    }

    override fun render(graphics: Graphics2D): Dimension {
        //select the correct image based on whether or not a pulse is needed
        var selectedImage = bufferedImages[0]
        pulseDuration = Duration.between(pulseStart, Instant.now())
        if (pulseRequired)
            when (pulseDuration.isNegative) {
                true -> {
                    pulseStart = Instant.now()
                    selectedImage = bufferedImages[1]
                }
                false -> when (pulseDuration < Duration.ofMillis(400)) {
                             true -> selectedImage = bufferedImages[1]
                             false -> {
                                 pulseRequired = false
                                 pulseStart = Instant.MAX
                                 pulseDuration = Duration.ZERO
                             }
                         }
            }

        //return the updated panel component
        panelComponent.children.clear()
        panelComponent.children.add(ImageComponent(selectedImage))
        return panelComponent.render(graphics)
    }
}

private data class PlayerData(val name: String, val level: Int, val wealth: Long, val skull: Boolean, val world: Int)

private class PlayerDataContainer private constructor(private val players: MutableList<String>,
                                                      private val levels: MutableList<Int>,
                                                      private val wealths: MutableList<Long>,
                                                      private val skulls: MutableList<Boolean>,
                                                      private val world: Int) : Iterable<PlayerData> {
    companion object {
        fun create(world: Int): PlayerDataContainer =
                PlayerDataContainer(mutableListOf(), mutableListOf(), mutableListOf(), mutableListOf(), world)
    }

    override fun iterator(): Iterator<PlayerData> =
            object : Iterator<PlayerData> {
                val playersIterator = players.iterator()
                val levelsIterator = levels.iterator()
                val wealthsIterator = wealths.iterator()
                val skullsIterator = skulls.iterator()
                //only need to check the first list
                override fun hasNext(): Boolean = playersIterator.hasNext()
                override fun next(): PlayerData =
                        PlayerData(
                                playersIterator.next(),
                                levelsIterator.next(),
                                wealthsIterator.next(),
                                skullsIterator.next(),
                                world
                        )
            }

    operator fun get(index: Int): PlayerData = this.elementAt(index)

    operator fun plusAssign(playerData: PlayerData) {
        if (playerData.world == this.world) {
            players.add(playerData.name)
            levels.add(playerData.level)
            wealths.add(playerData.wealth)
            skulls.add(playerData.skull)
        } else badOperandFailure("world mismatch: ${playerData.world} != ${this.world}")
    }

    private fun badOperandFailure(msg: String): Nothing = throw IllegalArgumentException(msg)
}