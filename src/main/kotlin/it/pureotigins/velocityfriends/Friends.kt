package it.pureotigins.velocityfriends

import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.word
import com.velocitypowered.api.event.EventTask.async
import com.velocitypowered.api.event.ResultedEvent
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import it.pureorigins.velocityconfiguration.templateComponent
import kotlinx.serialization.Serializable
import net.kyori.adventure.text.Component
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import java.io.IOException
import java.time.Duration
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
            return player.sendMessage(config.cannotUseOnSelf.templateComponent())
        }
        transaction(database) {
            if (FriendsTable.has(player.uniqueId, friendUniqueId)) {
                return@transaction player.sendMessage(config.alreadyFriend.templateComponent("player" to friendName))
            }
            if (FriendRequestsTable.has(player.uniqueId, friendUniqueId)) {
                return@transaction player.sendMessage(config.alreadyRequested.templateComponent("player" to friendName))
            }
            if (FriendRequestsTable.has(friendUniqueId, player.uniqueId)) {
                server.eventManager.fire(NewFriendEvent(player, friendUniqueId, friendName)).thenAccept {
                    val result = it.result
                    if (result.isAllowed) {
                        FriendRequestsTable.remove(friendUniqueId, player.uniqueId)
                        FriendsTable.add(friendUniqueId, player.uniqueId)
                        player.sendMessage(config.friendAdded.templateComponent("player" to friendName))
                        friendUniqueId.sendMessage(config.friendAccepted.templateComponent("player" to player.username))
                    } else {
                        player.sendMessage(result.cancelMessage!!)
                    }
                }
            } else {
                server.eventManager.fire(NewFriendRequestEvent(player, friendUniqueId, friendName)).thenAccept {
                    val result = it.result
                    if (result.isAllowed) {
                        FriendRequestsTable.add(player.uniqueId, friendUniqueId)
                        player.sendMessage(config.requestSent.templateComponent("player" to friendName))
                        server.getPlayer(friendUniqueId).ifPresent {
                            it.sendMessage(config.request.templateComponent("player" to player.username))
                        }
                    } else {
                        player.sendMessage(result.cancelMessage!!)
                    }
                }
            }
        }
    }
    
    fun remove(player: Player, friendUniqueId: UUID, friendName: String) {
        if (player.uniqueId == friendUniqueId) {
            return player.sendMessage(config.cannotUseOnSelf.templateComponent())
        }
        transaction(database) {
            when {
                FriendsTable.has(player.uniqueId, friendUniqueId) -> {
                    server.eventManager.fire(FriendRemoveEvent(player, friendUniqueId, friendName)).thenAccept {
                        val result = it.result
                        if (result.isAllowed) {
                            FriendsTable.remove(player.uniqueId, friendUniqueId)
                            player.sendMessage(config.friendRemoved.templateComponent("player" to friendName))
                            if (config.playerFriendRemoved != null) {
                                friendUniqueId.sendMessage(config.playerFriendRemoved.templateComponent("player" to player.username))
                            }
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
                            player.sendMessage(config.requestCanceled.templateComponent("player" to friendName))
                            if (config.playerRequestCanceled != null) {
                                server.getPlayer(friendUniqueId).ifPresent {
                                    it.sendMessage(config.playerRequestCanceled.templateComponent("player" to player.username))
                                }
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
                            player.sendMessage(config.requestDeclined.templateComponent("player" to friendName))
                            if (config.playerRequestDeclined != null) {
                                friendUniqueId.sendMessage(config.playerRequestDeclined.templateComponent("player" to player.username))
                            }
                        } else {
                            player.sendMessage(result.cancelMessage!!)
                        }
                    }
                }
                else -> player.sendMessage(config.notFriend.templateComponent("player" to friendName))
            }
        }
    }
    
    fun block(player: Player, blockedUniqueId: UUID, blockedName: String) {
        if (player.uniqueId == blockedUniqueId) {
            return player.sendMessage(config.cannotUseOnSelf.templateComponent())
        }
        transaction(database) {
            if (BlockedPlayersTable.has(player.uniqueId, blockedUniqueId)) {
                return@transaction player.sendMessage(config.alreadyBlocked.templateComponent("player" to blockedName))
            }
            server.eventManager.fire(PlayerBlocksPlayerEvent(player, blockedUniqueId, blockedName)).thenAccept {
                val result = it.result
                if (result.isAllowed) {
                    FriendsTable.remove(player.uniqueId, blockedUniqueId)
                    FriendRequestsTable.remove(player.uniqueId, blockedUniqueId)
                    FriendRequestsTable.remove(blockedUniqueId, player.uniqueId)
                    player.sendMessage(config.playerBlocked.templateComponent("player" to blockedName))
                } else {
                    player.sendMessage(result.cancelMessage!!)
                }
            }
        }
    }
    
    fun unblock(player: Player, blockedUniqueId: UUID, blockedName: String) {
        if (player.uniqueId == blockedUniqueId) {
            return player.sendMessage(config.cannotUseOnSelf.templateComponent())
        }
        transaction(database) {
            if (!BlockedPlayersTable.has(player.uniqueId, blockedUniqueId)) {
                return@transaction player.sendMessage(config.playerNotBlocked.templateComponent("player" to blockedName))
            }
            server.eventManager.fire(PlayerUnblocksPlayerEvent(player, blockedUniqueId, blockedName)).thenAccept {
                val result = it.result
                if (result.isAllowed) {
                    BlockedPlayersTable.remove(player.uniqueId, blockedUniqueId)
                    player.sendMessage(config.playerUnblocked.templateComponent("player" to blockedName))
                } else {
                    player.sendMessage(result.cancelMessage!!)
                }
            }
        }
    }
    
    private fun UUID.sendMessage(component: Component) {
        val player = server.getPlayer(this).orElse(null)
        if (player != null) {
            player.sendMessage(component)
        } else {
            val expirationTime = if (config.newsExpirationTimeDays != null) Duration.ofDays(config.newsExpirationTimeDays) else null
            NewsTable.add(this, component, expirationTime)
        }
    }
    
    override val command get() = literal(config.commandName) {
        requires {
            it is Player && it.hasPermission("friends.friend")
        }
        success {
            it.source.sendMessage(config.commandUsage.templateComponent())
        }
        then(addCommand)
        then(removeCommand)
        then(blockCommand)
        then(unblockCommand)
        then(infoCommand)
    }
    
    private val addCommand get() = literal(config.addCommandName) {
        requires {
            it is Player && it.hasPermission("friends.friend.add")
        }
        success {
            it.source.sendMessage(config.addCommandUsage.templateComponent())
        }
        then(argument("player", word()) {
            suggests { context ->
                val player = context.source as Player
                val (friends, requestsOut, requestsIn) = transaction(database) {
                    Triple(
                        FriendsTable.get(player.uniqueId),
                        FriendRequestsTable.get(player.uniqueId),
                        FriendRequestsTable.inverseGet(player.uniqueId)
                    )
                }
                server.allPlayers.forEach {
                    if (it.uniqueId !in friends && it.uniqueId !in requestsOut && it != player) {
                        suggest(it.username)
                    }
                }
                requestsIn.mapNotNull { MojangApi.tryGetPlayerInfo(it) }.forEach {
                    suggest(it.name)
                }
            }
            success { context ->
                val sender = context.source as Player
                val playerName = getString(context, "player")
                val player = try {
                    getOfflinePlayer(playerName) ?: return@success sender.sendMessage(config.playerNotFound.templateComponent("player" to playerName))
                } catch (e: IOException) {
                    return@success sender.sendMessage(config.mojangServerError.templateComponent("error" to e.message, "command" to context.input))
                }
                add(sender, player.uuid, player.name)
            }
        })
    }
    
    private val removeCommand get() = literal(config.removeCommandName) {
        requires {
            it is Player && it.hasPermission("friends.friend.remove")
        }
        success {
            it.source.sendMessage(config.removeCommandUsage.templateComponent())
        }
        then(argument("player", word()) {
            suggests { context ->
                val player = context.source as Player
                val (friends, requests) = transaction(database) {
                    FriendsTable.get(player.uniqueId) to FriendRequestsTable.get(player.uniqueId)
                }
                friends.mapNotNull { MojangApi.tryGetPlayerInfo(it) }.forEach {
                    suggest(it.name)
                }
                requests.mapNotNull { MojangApi.tryGetPlayerInfo(it) }.forEach {
                    suggest(it.name)
                }
            }
            success { context ->
                val sender = context.source as Player
                val playerName = getString(context, "player")
                val player = try {
                    getOfflinePlayer(playerName) ?: return@success sender.sendMessage(config.playerNotFound.templateComponent("player" to playerName))
                } catch (e: IOException) {
                    return@success sender.sendMessage(config.mojangServerError.templateComponent("error" to e.message, "command" to context.input))
                }
                remove(sender, player.uuid, player.name)
            }
        })
    }
    
    private val blockCommand get() = literal(config.blockCommandName) {
        requires {
            it is Player && it.hasPermission("friends.friend.block")
        }
        success {
            it.source.sendMessage(config.blockCommandUsage.templateComponent())
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
                val player = try {
                    getOfflinePlayer(playerName) ?: return@success sender.sendMessage(config.playerNotFound.templateComponent("player" to playerName))
                } catch (e: IOException) {
                    return@success sender.sendMessage(config.mojangServerError.templateComponent("error" to e.message, "command" to context.input))
                }
                block(sender, player.uuid, player.name)
            }
        })
    }
    
    private val unblockCommand get() = literal(config.unblockCommandName) {
        requires {
            it is Player && it.hasPermission("friends.friend.unblock")
        }
        success {
            it.source.sendMessage(config.unblockCommandUsage.templateComponent())
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
                val player = try {
                    getOfflinePlayer(playerName) ?: return@success sender.sendMessage(config.playerNotFound.templateComponent("player" to playerName))
                } catch (e: IOException) {
                    return@success sender.sendMessage(config.mojangServerError.templateComponent("error" to e.message, "command" to context.input))
                }
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
                    sender.sendMessage(config.info.templateComponent("friends" to friends, "requestsOut" to requestsOut, "requestsIn" to requestsIn, "blocked" to blocked))
                }
            } catch (e: IOException) {
                sender.sendMessage(config.mojangServerError.templateComponent("error" to e.message, "command" to context.input))
            }
        }
    }
    
    private fun getOfflinePlayer(username: String): PlayerInfo? {
        val onlinePlayer = server.getPlayer(username).orElse(null)
        if (onlinePlayer != null) return PlayerInfo(onlinePlayer.username, onlinePlayer.uniqueId)
        return MojangApi.getPlayerInfo(username)
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
            player.sendMessage(config.multipleRequests.templateComponent("players" to playerRequests))
        }
    }
    
    @Serializable
    data class Config(
        val newsExpirationTimeDays: Long? = null,
        val friendAdded: String = "Now you and \${player} are friends.",
        val friendAccepted: String = "You accepted \${player} request.",
        val requestSent: String = "Friend request sent to \${player}.",
        val request: String = "\${player} wants to be your friend.",
        val multipleRequests: String = "You have \${players?size} new friend requests.",
        val requestCanceled: String = "Friend request canceled.",
        val playerRequestCanceled: String? = null,
        val requestDeclined: String = "Request denied.",
        val playerRequestDeclined: String? = null,
        val friendRemoved: String = "Friend removed.",
        val playerFriendRemoved: String? = null,
        val alreadyFriend: String = "\${player} is already your friend.",
        val alreadyRequested: String = "You have already sent a request to \${player}.",
        val notFriend: String = "\${player} is not your friend.",
        val playerNotFound: String = "Player not found.",
        val cannotUseOnSelf: String = "You cannot use this command on yourself!",
        val mojangServerError: String = "An error occurred while contacting Mojang servers, try later.",
        val playerBlocked: String = "You blocked \${player}",
        val playerUnblocked: String = "\${player} is no more blocked.",
        val alreadyBlocked: String = "\${player} is already blocked.",
        val playerNotBlocked: String = "\${player} is not blocked.",
        val info: String = "Friends: <#list friends as friend>\${friend}<#sep>, </#list>\nBlocked players: <#list blocked as player>\${player}<#sep>, </#info>",
        val commandName: String = "friends",
        val commandUsage: String = "Usage: /friends <add | accept | remove>",
        val addCommandName: String = "add",
        val addCommandUsage: String = "/friends add <player>",
        val removeCommandName: String = "remove",
        val removeCommandUsage: String = "/friends remove <player>",
        val blockCommandName: String = "block",
        val blockCommandUsage: String = "/block <player>",
        val unblockCommandName: String = "unblock",
        val unblockCommandUsage: String = "/unblock <player>",
        val infoCommandsName: String = "info"
    )
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