package it.pureotigins.velocityfriends

import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.word
import com.mojang.brigadier.context.CommandContext
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.event.EventTask.async
import com.velocitypowered.api.event.ResultedEvent
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import it.pureorigins.velocityconfiguration.sendMessage
import it.pureorigins.velocityconfiguration.templateComponent
import kotlinx.serialization.Serializable
import net.kyori.adventure.text.Component
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import java.io.IOException
import java.util.*

class Friends(
    private val server: ProxyServer,
    private val logger: Logger,
    private val database: Database,
    private val config: Config
) : VelocityCommand {
    fun isBlocked(blockedUniqueId: UUID, blockerUniqueId: UUID): Boolean {
        return !transaction(database) { BlockedPlayersTable.has(blockerUniqueId, blockedUniqueId) }
    }
    
    fun isBlocked(blocked: Player, blocker: Player) = isBlocked(blocked.uniqueId, blocker.uniqueId)
    
    fun isFriend(playerUniqueId: UUID, otherUniqueId: UUID): Boolean {
        return transaction(database) { FriendsTable.has(otherUniqueId, playerUniqueId) }
    }
    
    fun isFriend(player: Player, other: Player) = isFriend(player.uniqueId, other.uniqueId)
    
    fun getFriends(playerUniqueId: UUID) = transaction(database) { FriendsTable.get(playerUniqueId) }
    fun getFriends(player: Player) = getFriends(player.uniqueId)
    
    fun getBlockedPlayers(playerUniqueId: UUID) = transaction(database) { BlockedPlayersTable.get(playerUniqueId) }
    fun getBlockedPlayers(player: Player) = getBlockedPlayers(player.uniqueId)
    
    fun getWhoBlockedPlayer(playerUniqueId: UUID) = transaction(database) { BlockedPlayersTable.inverseGet(playerUniqueId) }
    fun getWhoBlockedPlayer(player: Player) = getWhoBlockedPlayer(player.uniqueId)
    
    fun getFriendRequests(playerUniqueId: UUID) = transaction(database) { FriendRequestsTable.get(playerUniqueId) }
    fun getFriendRequests(player: Player) = getFriendRequests(player.uniqueId)
    
    fun getWhoFriendRequest(playerUniqueId: UUID) = transaction(database) { FriendRequestsTable.inverseGet(playerUniqueId) }
    fun getWhoFriendRequest(player: Player) = getWhoFriendRequest(player.uniqueId)
    
    fun add(player: Player, friendUniqueId: UUID, friendName: String) {
        if (player.uniqueId == friendUniqueId) {
            return player.sendMessage(config.add.cannotUseOnSelf?.templateComponent())
        }
        transaction(database) {
            when {
                FriendsTable.has(player.uniqueId, friendUniqueId) -> {
                    player.sendMessage(config.add.alreadyFriend?.templateComponent("player" to friendName))
                }
                FriendRequestsTable.has(player.uniqueId, friendUniqueId) -> {
                    player.sendMessage(config.add.alreadyRequested?.templateComponent("player" to friendName))
                }
                BlockedPlayersTable.has(friendUniqueId, player.uniqueId) -> {
                    player.sendMessage(config.add.blocked?.templateComponent("player" to friendName))
                }
                FriendRequestsTable.has(friendUniqueId, player.uniqueId) -> {
                    server.eventManager.fire(NewFriendEvent(player, friendUniqueId, friendName)).thenAccept {
                        val result = it.result
                        if (result.isAllowed) {
                            FriendRequestsTable.remove(friendUniqueId, player.uniqueId)
                            FriendsTable.add(friendUniqueId, player.uniqueId)
                            player.sendMessage(config.add.friendAdded?.templateComponent("player" to friendName))
                            friendUniqueId.sendMessage(config.add.friendAccepted?.templateComponent("player" to player.username))
                        } else {
                            player.sendMessage(result.cancelMessage!!)
                        }
                    }
                }
                else -> {
                    if (BlockedPlayersTable.has(player.uniqueId, friendUniqueId)) {
                        unblock(player, friendUniqueId, friendName)
                    }
                    server.eventManager.fire(NewFriendRequestEvent(player, friendUniqueId, friendName)).thenAccept {
                        val result = it.result
                        if (result.isAllowed) {
                            FriendRequestsTable.add(player.uniqueId, friendUniqueId)
                            player.sendMessage(config.add.requestSent?.templateComponent("player" to friendName))
                            server.getPlayer(friendUniqueId).ifPresent {
                                it.sendMessage(config.add.request?.templateComponent("player" to player.username))
                            }
                        } else {
                            player.sendMessage(result.cancelMessage!!)
                        }
                    }
                }
            }
        }
    }
    
    fun remove(player: Player, friendUniqueId: UUID, friendName: String) {
        if (player.uniqueId == friendUniqueId) {
            return player.sendMessage(config.remove.cannotUseOnSelf?.templateComponent())
        }
        transaction(database) {
            when {
                FriendsTable.has(player.uniqueId, friendUniqueId) -> {
                    server.eventManager.fire(FriendRemoveEvent(player, friendUniqueId, friendName)).thenAccept {
                        val result = it.result
                        if (result.isAllowed) {
                            FriendsTable.remove(player.uniqueId, friendUniqueId)
                            player.sendMessage(config.remove.friendRemoved?.templateComponent("player" to friendName))
                            friendUniqueId.sendMessage(config.remove.playerFriendRemoved?.templateComponent("player" to player.username))
                        } else {
                            player.sendMessage(result.cancelMessage!!)
                        }
                    }
                }
                FriendRequestsTable.has(player.uniqueId, friendUniqueId) -> {
                    server.eventManager.fire(FriendRequestCanceledEvent(player, friendUniqueId, friendName)).thenAccept {
                        val result = it.result
                        if (result.isAllowed) {
                            FriendRequestsTable.remove(player.uniqueId, friendUniqueId)
                            player.sendMessage(config.remove.requestCanceled?.templateComponent("player" to friendName))
                            server.getPlayer(friendUniqueId).ifPresent {
                                it.sendMessage(config.remove.playerRequestCanceled?.templateComponent("player" to player.username))
                            }
                        } else {
                            player.sendMessage(result.cancelMessage!!)
                        }
                    }
                }
                FriendRequestsTable.has(friendUniqueId, player.uniqueId) -> {
                    server.eventManager.fire(FriendRequestDeclinedEvent(player, friendUniqueId, friendName)).thenAccept {
                        val result = it.result
                        if (result.isAllowed) {
                            FriendRequestsTable.remove(friendUniqueId, player.uniqueId)
                            player.sendMessage(config.remove.requestDeclined?.templateComponent("player" to friendName))
                            friendUniqueId.sendMessage(config.remove.playerRequestDeclined?.templateComponent("player" to player.username))
                        } else {
                            player.sendMessage(result.cancelMessage!!)
                        }
                    }
                }
                else -> player.sendMessage(config.remove.notFriend?.templateComponent("player" to friendName))
            }
        }
    }
    
    fun block(player: Player, blockedUniqueId: UUID, blockedName: String) {
        if (player.uniqueId == blockedUniqueId) {
            return player.sendMessage(config.block.cannotUseOnSelf?.templateComponent())
        }
        transaction(database) {
            if (BlockedPlayersTable.has(player.uniqueId, blockedUniqueId)) {
                return@transaction player.sendMessage(config.block.alreadyBlocked?.templateComponent("player" to blockedName))
            }
            server.eventManager.fire(PlayerBlocksPlayerEvent(player, blockedUniqueId, blockedName)).thenAccept {
                val result = it.result
                if (result.isAllowed) {
                    FriendsTable.remove(player.uniqueId, blockedUniqueId)
                    FriendRequestsTable.remove(player.uniqueId, blockedUniqueId)
                    FriendRequestsTable.remove(blockedUniqueId, player.uniqueId)
                    player.sendMessage(config.block.playerBlocked?.templateComponent("player" to blockedName))
                } else {
                    player.sendMessage(result.cancelMessage!!)
                }
            }
        }
    }
    
    fun unblock(player: Player, blockedUniqueId: UUID, blockedName: String) {
        if (player.uniqueId == blockedUniqueId) {
            return player.sendMessage(config.unblock.cannotUseOnSelf?.templateComponent())
        }
        transaction(database) {
            if (!BlockedPlayersTable.has(player.uniqueId, blockedUniqueId)) {
                return@transaction player.sendMessage(config.unblock.notBlocked?.templateComponent("player" to blockedName))
            }
            server.eventManager.fire(PlayerUnblocksPlayerEvent(player, blockedUniqueId, blockedName)).thenAccept {
                val result = it.result
                if (result.isAllowed) {
                    BlockedPlayersTable.remove(player.uniqueId, blockedUniqueId)
                    player.sendMessage(config.unblock.playerUnblocked?.templateComponent("player" to blockedName))
                } else {
                    player.sendMessage(result.cancelMessage!!)
                }
            }
        }
    }
    
    private fun UUID.sendMessage(component: Component?) {
        if (component == null) return
        val player = server.getPlayer(this).orElse(null)
        if (player != null) {
            player.sendMessage(component)
        } else {
            NewsTable.add(this, component, null)
        }
    }
    
    override val command get() = literal(config.commandName) {
        requires {
            it is Player && it.hasPermission("friends.friend")
        }
        success {
            it.source.sendMessage(config.commandUsage?.templateComponent())
        }
        then(addCommand)
        then(removeCommand)
        then(blockCommand)
        then(unblockCommand)
        then(infoCommand)
    }
    
    private val addCommand get() = literal(config.add.commandName) {
        requires {
            it is Player && it.hasPermission("friends.friend.add")
        }
        success {
            it.source.sendMessage(config.add.commandUsage?.templateComponent())
        }
        then(argument("player", word()) {
            suggests { context ->
                val player = context.source as Player
                transaction(database) {
                    val friends = FriendsTable.get(player.uniqueId)
                    val requestsOut = FriendRequestsTable.get(player.uniqueId)
                    val requestsIn = FriendRequestsTable.inverseGet(player.uniqueId)
                    val blocked = BlockedPlayersTable.inverseGet(player.uniqueId)
                    server.allPlayers.forEach {
                        if (it.uniqueId !in friends && it.uniqueId !in requestsOut && it.uniqueId !in blocked && it != player) {
                            suggest(it.username)
                        }
                    }
                    requestsIn.mapNotNull { MojangApi.tryGetPlayerInfo(it) }.forEach {
                        suggest(it.name)
                    }
                }
            }
            success { context ->
                val sender = context.source as Player
                val playerName = getString(context, "player")
                val player = context.getOfflinePlayer(playerName) ?: return@success
                add(sender, player.uuid, player.name)
            }
        })
    }
    
    private val removeCommand get() = literal(config.remove.commandName) {
        requires {
            it is Player && it.hasPermission("friends.friend.remove")
        }
        success {
            it.source.sendMessage(config.remove.commandUsage?.templateComponent())
        }
        then(argument("player", word()) {
            suggests { context ->
                val player = context.source as Player
                transaction(database) {
                    val friends = FriendsTable.get(player.uniqueId)
                    val requestsOut = FriendRequestsTable.get(player.uniqueId)
                    val requestsIn = FriendRequestsTable.inverseGet(player.uniqueId)
                    friends.mapNotNull { MojangApi.tryGetPlayerInfo(it) }.forEach {
                        suggest(it.name)
                    }
                    requestsOut.mapNotNull { MojangApi.tryGetPlayerInfo(it) }.forEach {
                        suggest(it.name)
                    }
                    requestsIn.mapNotNull { MojangApi.tryGetPlayerInfo(it) }.forEach {
                        suggest(it.name)
                    }
                }
            }
            success { context ->
                val sender = context.source as Player
                val playerName = getString(context, "player")
                val player = context.getOfflinePlayer(playerName) ?: return@success
                remove(sender, player.uuid, player.name)
            }
        })
    }
    
    private val blockCommand get() = literal(config.block.commandName) {
        requires {
            it is Player && it.hasPermission("friends.friend.block")
        }
        success {
            it.source.sendMessage(config.block.commandUsage?.templateComponent())
        }
        then(argument("player", word()) {
            suggests { context ->
                val player = context.source as Player
                val blockedPlayers = transaction(database) { BlockedPlayersTable.get(player.uniqueId) }
                server.allPlayers.forEach {
                    if (it.uniqueId !in blockedPlayers && it != player) {
                        suggest(it.username)
                    }
                }
            }
            success { context ->
                val sender = context.source as Player
                val playerName = getString(context, "player")
                val player = context.getOfflinePlayer(playerName) ?: return@success
                block(sender, player.uuid, player.name)
            }
        })
    }
    
    private val unblockCommand get() = literal(config.unblock.commandName) {
        requires {
            it is Player && it.hasPermission("friends.friend.unblock")
        }
        success {
            it.source.sendMessage(config.unblock.commandUsage?.templateComponent())
        }
        then(argument("player", word()) {
            suggests { context ->
                val player = context.source as Player
                val blockedPlayers = transaction(database) {
                    BlockedPlayersTable.get(player.uniqueId)
                }
                blockedPlayers.mapNotNull { MojangApi.tryGetPlayerInfo(it) }.forEach {
                    suggest(it.name)
                }
            }
            success { context ->
                val sender = context.source as Player
                val playerName = getString(context, "player")
                val player = context.getOfflinePlayer(playerName) ?: return@success
                unblock(sender, player.uuid, player.name)
            }
        })
    }
    
    private val infoCommand get() = literal("info") {
        requires {
            it is Player && it.hasPermission("friends.friend.info")
        }
        success { context ->
            val sender = context.source as Player
            try {
                transaction(database) {
                    val friends = FriendsTable.get(sender.uniqueId).mapNotNull { MojangApi.getPlayerInfo(it) }
                    val requestsOut = FriendRequestsTable.get(sender.uniqueId).mapNotNull { MojangApi.getPlayerInfo(it) }
                    val requestsIn = FriendRequestsTable.inverseGet(sender.uniqueId).mapNotNull { MojangApi.getPlayerInfo(it) }
                    val blocked = BlockedPlayersTable.get(sender.uniqueId).mapNotNull { MojangApi.getPlayerInfo(it) }
                    sender.sendMessage(config.info.info?.templateComponent("friends" to friends, "requestsOut" to requestsOut, "requestsIn" to requestsIn, "blocked" to blocked))
                }
            } catch (e: IOException) {
                sender.sendMessage(config.mojangServerError?.templateComponent("error" to e.message, "command" to context.input))
            }
        }
    }
    
    private fun CommandContext<CommandSource>.getOfflinePlayer(username: String): PlayerInfo? {
        val onlinePlayer = server.getPlayer(username).orElse(null)
        if (onlinePlayer != null) return PlayerInfo(onlinePlayer.username, onlinePlayer.uniqueId)
        return try {
            MojangApi.getPlayerInfo(username).also {
                if (it == null) source.sendMessage(config.unblock.playerNotFound?.templateComponent("player" to username))
            }
        } catch (e: IOException) {
            source.sendMessage(config.mojangServerError?.templateComponent("error" to e.message, "command" to input))
            null
        }
    }
    
    @Subscribe
    fun onPlayerJoin(event: ServerConnectedEvent) = async {
        val player = event.player
        val (news, requests) = transaction(database) {
            NewsTable.get(player.uniqueId) to FriendRequestsTable.inverseGet(player.uniqueId)
        }
        news.forEach {
            player.sendMessage(it)
        }
        val playerRequests = requests.mapNotNull {
           MojangApi.tryGetPlayerInfo(it)?.name.also { name ->
               if (name == null) logger.warn("An error occurred while obtaining name of uuid '$it'")
           }
        }
        if (playerRequests.isNotEmpty()) {
            player.sendMessage(config.joinRequests?.templateComponent("players" to playerRequests))
        }
    }
    
    @Serializable
    data class Config(
        val joinRequests: String? = "[<#list players[0..*5] as player>{\"text\": \"\${player}\", \"color\": \"aqua\", \"clickEvent\": {\"action\": \"suggest_command\", \"value\": \"/msg \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"send message\"}}, {\"text\": \" \", \"color\": \"dark_aqua\"}, {\"text\":\"[+]\", \"color\": \"green\", \"clickEvent\": {\"action\": \"run_command\", \"value\": \"/friends add \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"add\"}}, {\"text\": \" \", \"color\": \"dark_aqua\"}, {\"text\":\"[-]\", \"color\": \"red\", \"clickEvent\": {\"action\": \"run_command\", \"value\": \"/friends remove \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"remove\"}}, {\"text\": \" \", \"color\": \"dark_aqua\"}, {\"text\":\"[×]\", \"color\": \"dark_red\", \"clickEvent\": {\"action\": \"run_command\", \"value\": \"/friends block \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"block\"}}<#sep>, {\"text\":\", \", \"color\":\"dark_aqua\"}, </#list>, {\"text\": \" <#if (players?size > 5)>and other \${players?size - 5}</#if> <#if players?size == 1>wants<#else>want</#if> to be your friend.\", \"color\": \"dark_aqua\"}]",
        val mojangServerError: String? = "[{\"text\": \"An error occurred while contacting Mojang servers. \", \"color\": \"dark_gray\"}, {\"text\": \"[RETRY]\", \"color\": \"gray\", \"clickEvent\": {\"action\": \"run_command\", \"value\": \"/\${command}\"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"retry\"}}]",
        val commandName: String = "friends",
        val commandUsage: String? = "[{\"text\": \"Usage: \", \"color\": \"dark_gray\"}, {\"text\": \"/friends <add | remove | block | unblock | info>\", \"color\": \"gray\"}]",
        val add: Add = Add(),
        val remove: Remove = Remove(),
        val block: Block = Block(),
        val unblock: Unblock = Unblock(),
        val info: Info = Info()
    ) {
        @Serializable
        data class Add(
            val commandName: String = "add",
            val commandUsage: String? = "[{\"text\": \"Usage: \", \"color\": \"dark_gray\"}, {\"text\": \"/friends add <player>\", \"color\": \"gray\"}]",
            val cannotUseOnSelf: String? = "{\"text\": \"Cannot use this command on yourself.\", \"color\": \"dark_gray\"}",
            val playerNotFound: String? = "{\"text\": \"Player not found.\", \"color\": \"dark_gray\"}",
            val alreadyFriend: String? = "[{\"text\": \"\${player}\", \"color\": \"gold\", \"clickEvent\": {\"action\": \"suggest_command\", \"value\": \"/msg \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"send message\"}}, {\"text\": \" is already your friend.\", \"color\": \"dark_gray\"}]",
            val alreadyRequested: String? = "[{\"text\": \"You have already sent a friend request to \", \"color\": \"dark_gray\"}, {\"text\": \"\${player}\", \"color\": \"gold\", \"clickEvent\": {\"action\": \"suggest_command\", \"value\": \"/msg \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"send message\"}}, {\"text\": \".\", \"color\": \"dark_gray\"}]",
            val blocked: String? = "[{\"text\": \"You cannot add \", \"color\": \"dark_gray\"}, {\"text\": \"\${player}\", \"color\": \"gold\", \"clickEvent\": {\"action\": \"suggest_command\", \"value\": \"/msg \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"send message\"}}, {\"text\": \" because he blocked you.\", \"color\": \"dark_gray\"}]",
            val friendAdded: String? = "[{\"text\": \"You have accepted the friend request of \", \"color\": \"gray\"}, {\"text\": \"\${player}\", \"color\": \"gold\", \"clickEvent\": {\"action\": \"suggest_command\", \"value\": \"/msg \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"send message\"}}, {\"text\": \". \", \"color\": \"gray\"}, {\"text\":\"[-]\", \"color\": \"red\", \"clickEvent\": {\"action\": \"run_command\", \"value\": \"/friends remove \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"undo\"}}]",
            val friendAccepted: String? = "[{\"text\": \"\${player}\", \"color\": \"aqua\", \"clickEvent\": {\"action\": \"suggest_command\", \"value\": \"/msg \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"send message\"}}, {\"text\": \" accepted your friend request.\", \"color\": \"dark_aqua\"}]",
            val requestSent: String? = "[{\"text\": \"Friend request sent to \", \"color\": \"gray\"}, {\"text\": \"\${player}\", \"color\": \"gold\", \"clickEvent\": {\"action\": \"suggest_command\", \"value\": \"/msg \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"send message\"}}, {\"text\": \". \", \"color\": \"gray\"}, {\"text\":\"[-]\", \"color\": \"red\", \"clickEvent\": {\"action\": \"run_command\", \"value\": \"/friends remove \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"undo\"}}]",
            val request: String? = "[{\"text\": \"\${player}\", \"color\": \"aqua\", \"clickEvent\": {\"action\": \"suggest_command\", \"value\": \"/msg \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"send message\"}}, {\"text\": \" wants to be your friend. \", \"color\": \"dark_aqua\"}, {\"text\":\"[+]\", \"color\": \"green\", \"clickEvent\": {\"action\": \"run_command\", \"value\": \"/friends add \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"add\"}}, {\"text\": \" \", \"color\": \"dark_aqua\"}, {\"text\":\"[-]\", \"color\": \"red\", \"clickEvent\": {\"action\": \"run_command\", \"value\": \"/friends remove \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"remove\"}}, {\"text\": \" \", \"color\": \"dark_aqua\"}, {\"text\":\"[×]\", \"color\": \"dark_red\", \"clickEvent\": {\"action\": \"run_command\", \"value\": \"/friends block \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"block\"}}]",
        )
        
        @Serializable
        data class Remove(
            val commandName: String = "remove",
            val commandUsage: String? = "[{\"text\": \"Usage: \", \"color\": \"dark_gray\"}, {\"text\": \"/friends remove <player>\", \"color\": \"gray\"}]",
            val cannotUseOnSelf: String? = "{\"text\": \"Cannot use this command on yourself.\", \"color\": \"dark_gray\"}",
            val playerNotFound: String? = "{\"text\": \"Player not found.\", \"color\": \"dark_gray\"}",
            val notFriend: String? = "[{\"text\": \"\${player}\", \"color\": \"gold\", \"clickEvent\": {\"action\": \"suggest_command\", \"value\": \"/msg \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"send message\"}}, {\"text\": \" is not your friend.\", \"color\": \"dark_gray\"}]",
            val friendRemoved: String? = "[{\"text\": \"\${player}\", \"color\": \"gold\", \"clickEvent\": {\"action\": \"suggest_command\", \"value\": \"/msg \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"send message\"}}, {\"text\": \" is no more your friend.\", \"color\": \"gray\"}]",
            val playerFriendRemoved: String? = null,
            val requestCanceled: String? = "{\"text\": \"Friend request canceled.\", \"color\": \"gray\"}",
            val playerRequestCanceled: String? = "[{\"text\": \"\${player}\", \"color\": \"aqua\", \"clickEvent\": {\"action\": \"suggest_command\", \"value\": \"/msg \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"send message\"}}, {\"text\": \" canceled his friend request.\", \"color\": \"dark_aqua\"}]",
            val requestDeclined: String? = "[{\"text\": \"\${player}\", \"color\": \"aqua\", \"clickEvent\": {\"action\": \"suggest_command\", \"value\": \"/msg \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"send message\"}}, {\"text\": \" friend request declined.\", \"color\": \"gray\"}]",
            val playerRequestDeclined: String? = "[{\"text\": \"\${player}\", \"color\": \"aqua\", \"clickEvent\": {\"action\": \"suggest_command\", \"value\": \"/msg \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"send message\"}}, {\"text\": \" denied your friend request.\", \"color\": \"dark_aqua\"}]"
        )
    
        @Serializable
        data class Block(
            val commandName: String = "block",
            val commandUsage: String? = "[{\"text\": \"Usage: \", \"color\": \"dark_gray\"}, {\"text\": \"/friends block <player>\", \"color\": \"gray\"}]",
            val cannotUseOnSelf: String? = "{\"text\": \"Cannot use this command on yourself.\", \"color\": \"dark_gray\"}",
            val playerNotFound: String? = "{\"text\": \"Player not found.\", \"color\": \"dark_gray\"}",
            val alreadyBlocked: String? = "[{\"text\": \"\${player}\", \"color\": \"gold\", \"clickEvent\": {\"action\": \"suggest_command\", \"value\": \"/msg \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"send message\"}}, {\"text\": \" is already blocked.\", \"color\": \"dark_gray\"}]",
            val playerBlocked: String? = "[{\"text\": \"You blocked \", \"color\": \"gray\"}, {\"text\": \"\${player}\", \"color\": \"gold\", \"clickEvent\": {\"action\": \"suggest_command\", \"value\": \"/msg \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"send message\"}}, {\"text\": \". \", \"color\": \"gray\"}, {\"text\":\"[-]\", \"color\": \"red\", \"clickEvent\": {\"action\": \"run_command\", \"value\": \"/friends unblock \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"undo\"}}]"
        )
    
        @Serializable
        data class Unblock(
            val commandName: String = "unblock",
            val commandUsage: String? = "[{\"text\": \"Usage: \", \"color\": \"dark_gray\"}, {\"text\": \"/friends unblock <player>\", \"color\": \"gray\"}]",
            val cannotUseOnSelf: String? = "{\"text\": \"Cannot use this command on yourself.\", \"color\": \"dark_gray\"}",
            val playerNotFound: String? = "{\"text\": \"Player not found.\", \"color\": \"dark_gray\"}",
            val notBlocked: String? = "[{\"text\": \"\${player}\", \"color\": \"gold\", \"clickEvent\": {\"action\": \"suggest_command\", \"value\": \"/msg \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"send message\"}}, {\"text\": \" is not blocked.\", \"color\": \"dark_gray\"}]",
            val playerUnblocked: String? = "[{\"text\": \"\${player}\", \"color\": \"gold\", \"clickEvent\": {\"action\": \"suggest_command\", \"value\": \"/msg \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"send message\"}}, {\"text\": \" is no more blocked. \", \"color\": \"gray\"}, {\"text\":\"[-]\", \"color\": \"red\", \"clickEvent\": {\"action\": \"run_command\", \"value\": \"/friends block \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"undo\"}}]"
        )
    
        @Serializable
        data class Info(
            val commandName: String = "info",
            val info: String? = "[{\"text\": \"\${friends?size} Friend<#if friends?size != 1>s</#if><#if (friends?size > 0)>: </#if>\", \"color\": \"gray\"}<#list friends as friend>, {\"text\": \"\${friend}\", \"color\": \"gold\", \"clickEvent\": {\"action\": \"suggest_command\", \"value\": \"/msg \${friend} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"send message\"}}, {\"text\": \" \", \"color\": \"gray\"}, {\"text\":\"[-]\", \"color\": \"red\", \"clickEvent\": {\"action\": \"run_command\", \"value\": \"/friends remove \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"remove\"}}<#sep>, {\"text\": \", \", \"color\": \"gray\"}</#list><#if (requestsIn?size > 0)>, {\"text\":\"\\n\"}, {\"text\": \"\${requestsIn?size} Requests: \", \"color\": \"gray\"}, <#list requestsIn as request>{\"text\": \"\${request}\", \"color\": \"gold\", \"clickEvent\": {\"action\": \"suggest_command\", \"value\": \"/msg \${request} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"send message\"}}, {\"text\": \" \", \"color\": \"gray\"}, {\"text\":\"[+]\", \"color\": \"green\", \"clickEvent\": {\"action\": \"run_command\", \"value\": \"/friends add \${request} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"add\"}}, {\"text\": \" \", \"color\": \"dark_aqua\"}, {\"text\":\"[-]\", \"color\": \"red\", \"clickEvent\": {\"action\": \"run_command\", \"value\": \"/friends remove \${request} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"remove\"}}, {\"text\": \" \", \"color\": \"dark_aqua\"}, {\"text\":\"[×]\", \"color\": \"dark_red\", \"clickEvent\": {\"action\": \"run_command\", \"value\": \"/friends block \${request} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"block\"}}<#sep>, {\"text\": \", \", \"color\": \"gray\"},</#list></#if><#if (requestsOut?size > 0)>, {\"text\":\"\\n\"}, {\"text\": \"\${requestsOut?size} Sent requests: \", \"color\": \"gray\"}, <#list requestsOut as request>{\"text\": \"\${request}\", \"color\": \"gold\", \"clickEvent\": {\"action\": \"suggest_command\", \"value\": \"/msg \${request} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"send message\"}}, {\"text\": \" \", \"color\":\"gray\"}, {\"text\":\"[-]\", \"color\": \"red\", \"clickEvent\": {\"action\": \"run_command\", \"value\": \"/friends remove \${request} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"undo\"}}<#sep>, {\"text\": \", \", \"color\": \"gray\"},</#list></#if><#if (blocked?size > 0)>, {\"text\":\"\\n\"}, {\"text\": \"\${blocked?size} Blocked users: \", \"color\": \"gray\"}, <#list blocked as player>{\"text\": \"\${player}\", \"color\": \"gold\", \"clickEvent\": {\"action\": \"suggest_command\", \"value\": \"/msg \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"send message\"}}, {\"text\", \" \", \"color\": \"gray\"}, {\"text\":\"[-]\", \"color\": \"red\", \"clickEvent\": {\"action\": \"run_command\", \"value\": \"/friends unblock \${player} \"}, \"hoverEvent\": {\"action\": \"show_text\", \"value\": \"unblock\"}}<#sep>, {\"text\": \", \", \"color\": \"gray\"},</#list></#if>]"
        )
    }
}

data class NewFriendEvent(
    val player: Player,
    val otherPlayerUniqueId: UUID,
    val otherPlayerName: String
) : ResultedEvent<Result> {
    private var result: Result = Result.allowed()
    
    override fun getResult() = result
    override fun setResult(result: Result) { this.result = result }
}

data class FriendRemoveEvent(
    val player: Player,
    val otherPlayerUniqueId: UUID,
    val otherPlayerName: String
) : ResultedEvent<Result> {
    private var result: Result = Result.allowed()
    
    override fun getResult() = result
    override fun setResult(result: Result) { this.result = result }
}

data class NewFriendRequestEvent(
    val player: Player,
    val otherPlayerUniqueId: UUID,
    val otherPlayerName: String
) : ResultedEvent<Result> {
    private var result: Result = Result.allowed()
    
    override fun getResult() = result
    override fun setResult(result: Result) { this.result = result }
}

data class FriendRequestDeclinedEvent(
    val player: Player,
    val otherPlayerUniqueId: UUID,
    val otherPlayerName: String
) : ResultedEvent<Result> {
    private var result: Result = Result.allowed()
    
    override fun getResult() = result
    override fun setResult(result: Result) { this.result = result }
}

data class FriendRequestCanceledEvent(
    val player: Player,
    val otherPlayerUniqueId: UUID,
    val otherPlayerName: String
) : ResultedEvent<Result> {
    private var result: Result = Result.allowed()
    
    override fun getResult() = result
    override fun setResult(result: Result) { this.result = result }
}

data class PlayerBlocksPlayerEvent(
    val player: Player,
    val otherPlayerUniqueId: UUID,
    val otherPlayerName: String
) : ResultedEvent<Result> {
    private var result: Result = Result.allowed()
    
    override fun getResult() = result
    override fun setResult(result: Result) { this.result = result }
}

data class PlayerUnblocksPlayerEvent(
    val player: Player,
    val otherPlayerUniqueId: UUID,
    val otherPlayerName: String
) : ResultedEvent<Result> {
    private var result: Result = Result.allowed()
    
    override fun getResult() = result
    override fun setResult(result: Result) { this.result = result }
}

class Result private constructor(val cancelMessage: Component? = null) : ResultedEvent.Result {
    companion object {
        private val ALLOWED = Result()
        
        fun allowed() = ALLOWED
        fun denied(message: Component) = Result(message)
    }
    
    override fun isAllowed() = cancelMessage == null
}