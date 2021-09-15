package it.pureotigins.velocityfriends

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.Dependency
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import it.pureorigins.velocityconfiguration.json
import it.pureorigins.velocityconfiguration.readFileAs
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils.createMissingTablesAndColumns
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import java.nio.file.Path
import java.util.*
import kotlin.io.path.createDirectories

@Plugin(id = "velocity-friends", name = "Chat", version = "1.0.0", url = "https://github.com/PureOrigins/VelocityFriends",
    dependencies = [Dependency(id = "velocity-language-kotlin"), Dependency(id = "velocity-configuration")], authors = ["AgeOfWar"])
class VelocityFriends @Inject constructor(
    private val server: ProxyServer,
    private val logger: Logger,
    @DataDirectory private val dataDirectory: Path
) {
    private val commands get() = server.commandManager
    private val events get() = server.eventManager
    
    private lateinit var database: Database
    private lateinit var friends: Friends
    
    fun isBlocked(blockedUniqueId: UUID, blockerUniqueId: UUID) = friends.isBlocked(blockedUniqueId, blockerUniqueId)
    fun isBlocked(blocked: Player, blocker: Player) = friends.isBlocked(blocked, blocker)
    
    fun isFriend(playerUniqueId: UUID, otherUniqueId: UUID) = friends.isFriend(playerUniqueId, otherUniqueId)
    fun isFriend(player: Player, other: Player) = friends.isFriend(player, other)
    
    fun getFriends(playerUniqueId: UUID) = friends.getFriends(playerUniqueId)
    fun getFriends(player: Player) = friends.getFriends(player)
    
    fun getBlockedPlayers(playerUniqueId: UUID) = friends.getBlockedPlayers(playerUniqueId)
    fun getBlockedPlayers(player: Player) = friends.getBlockedPlayers(player)
    
    fun getWhoBlockedPlayer(playerUniqueId: UUID) = friends.getWhoBlockedPlayer(playerUniqueId)
    fun getWhoBlockedPlayer(player: Player) = friends.getWhoBlockedPlayer(player)
    
    fun getFriendRequests(playerUniqueId: UUID) = friends.getFriendRequests(playerUniqueId)
    fun getFriendRequests(player: Player) = friends.getFriendRequests(player)
    
    fun getWhoFriendRequest(playerUniqueId: UUID) = friends.getWhoFriendRequest(playerUniqueId)
    fun getWhoFriendRequest(player: Player) = friends.getWhoFriendRequest(player)
    
    @Subscribe
    fun onInit(event: ProxyInitializeEvent) {
        dataDirectory.createDirectories()
        val (db, friendsConfig) = json.readFileAs(dataDirectory.resolve("velocity_friends.json"), Config(Config.Database("jdbc:sqlite:${dataDirectory.toRealPath()}/velocity_friends.db")))
        database = Database.connect(db.url, user = db.username, password = db.password)
        transaction(database) {
            createMissingTablesAndColumns(FriendsTable, FriendRequestsTable, NewsTable, BlockedPlayersTable)
        }
        friends = Friends(server, logger, database, friendsConfig)
        events.register(this, friends)
        commands.register(friends)
    }
    
    @Serializable
    data class Config(
        val database: Database = Database(),
        val friends: Friends.Config = Friends.Config()
    ) {
        @Serializable
        data class Database(
            val url: String = "",
            val username: String = "",
            val password: String = ""
        )
    }
}