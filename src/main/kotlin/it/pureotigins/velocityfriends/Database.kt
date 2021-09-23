package it.pureotigins.velocityfriends

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.*
import java.time.Instant
import java.time.temporal.TemporalAmount
import java.util.*

object FriendsTable : Table("friends") {
    val playerUniqueId = char("player_uuid", length = 36)
    val friendUniqueId = char("friend_uuid", length = 36)
    
    override val primaryKey = PrimaryKey(playerUniqueId, friendUniqueId)
    
    fun add(playerUniqueId: UUID, friendUniqueId: UUID) = insertIgnore {
        it[this.playerUniqueId] = playerUniqueId.toString()
        it[this.friendUniqueId] = friendUniqueId.toString()
    }.insertedCount > 0
    
    fun has(playerUniqueId: UUID, friendUniqueId: UUID) = select {
        ((FriendsTable.playerUniqueId eq playerUniqueId.toString()) and (FriendsTable.friendUniqueId eq friendUniqueId.toString())) or
            ((FriendsTable.playerUniqueId eq friendUniqueId.toString()) and (FriendsTable.friendUniqueId eq playerUniqueId.toString()))
    }.count() > 0
    
    fun remove(playerUniqueId: UUID, friendUniqueId: UUID) = deleteWhere {
        ((FriendsTable.playerUniqueId eq playerUniqueId.toString()) and (FriendsTable.friendUniqueId eq friendUniqueId.toString())) or
                ((FriendsTable.playerUniqueId eq friendUniqueId.toString()) and (FriendsTable.friendUniqueId eq playerUniqueId.toString()))
    } > 0
    
    fun get(playerUniqueId: UUID): Set<UUID> = select {
        (FriendsTable.playerUniqueId eq playerUniqueId.toString()) or (FriendsTable.friendUniqueId eq playerUniqueId.toString())
    }.mapTo(hashSetOf()) { UUID.fromString(if (it[this.playerUniqueId] == playerUniqueId.toString()) it[this.friendUniqueId] else it[this.playerUniqueId]) }
}

object FriendRequestsTable : Table("friend_request") {
    val playerUniqueId = char("player_uuid", length = 36)
    val friendUniqueId = char("friend_uuid", length = 36)
    
    override val primaryKey = PrimaryKey(playerUniqueId, friendUniqueId)
    
    fun add(playerUniqueId: UUID, friendUniqueId: UUID) = insertIgnore {
        it[this.playerUniqueId] = playerUniqueId.toString()
        it[this.friendUniqueId] = friendUniqueId.toString()
    }.insertedCount > 0
    
    fun has(playerUniqueId: UUID, friendUniqueId: UUID) =
        select { (FriendRequestsTable.playerUniqueId eq playerUniqueId.toString()) and (FriendRequestsTable.friendUniqueId eq friendUniqueId.toString()) }.count() > 0
    
    fun remove(playerUniqueId: UUID, friendUniqueId: UUID) = deleteWhere {
        (FriendRequestsTable.playerUniqueId eq playerUniqueId.toString()) and (FriendRequestsTable.friendUniqueId eq friendUniqueId.toString())
    } > 0
    
    fun get(playerUniqueId: UUID): Set<UUID> =
        select { FriendRequestsTable.playerUniqueId eq playerUniqueId.toString() }.mapTo(hashSetOf()) { UUID.fromString(it[this.friendUniqueId]) }
    
    fun inverseGet(playerUniqueId: UUID): Set<UUID> =
        select { FriendRequestsTable.friendUniqueId eq playerUniqueId.toString() }.mapTo(hashSetOf()) { UUID.fromString(it[this.playerUniqueId]) }
}

object NewsTable : LongIdTable("news") {
    val playerUniqueId = char("player_uuid", length = 36)
    val text = text("text")
    val date = long("date")
    val expirationDate = long("expiration_date").nullable()
    
    fun add(playerUniqueId: UUID, text: Component, expirationTime: TemporalAmount? = null) = insertIgnore {
        it[NewsTable.playerUniqueId] = playerUniqueId.toString()
        it[NewsTable.text] = GsonComponentSerializer.gson().serialize(text)
        val now = Instant.now()
        it[date] = now.toEpochMilli()
        it[expirationDate] = now?.plus(expirationTime)?.toEpochMilli()
    }
    
    fun get(playerUniqueId: UUID) =
        select { (NewsTable.playerUniqueId eq playerUniqueId.toString()) and (expirationDate greater Instant.now().toEpochMilli()) }.orderBy(date).map { GsonComponentSerializer.gson().deserialize(it[text]) }
    
    fun remove(playerUniqueId: UUID) = deleteWhere { NewsTable.playerUniqueId eq playerUniqueId.toString() }
    
    fun getAndRemove(playerUniqueId: UUID) = get(playerUniqueId).also { remove(playerUniqueId) }
}

object BlockedPlayersTable : Table("blocked_players") {
    val playerUniqueId = char("player_uuid", length = 36)
    val blockedUniqueId = char("blocked_uuid", length = 36)
    
    override val primaryKey = PrimaryKey(playerUniqueId, blockedUniqueId)
    
    fun add(playerUniqueId: UUID, blockedUniqueId: UUID) = insertIgnore {
        it[this.playerUniqueId] = playerUniqueId.toString()
        it[this.blockedUniqueId] = blockedUniqueId.toString()
    }.insertedCount > 0
    
    fun has(playerUniqueId: UUID, blockedUniqueId: UUID) =
        select { (BlockedPlayersTable.playerUniqueId eq playerUniqueId.toString()) and (BlockedPlayersTable.blockedUniqueId eq blockedUniqueId.toString()) }.count() > 0
    
    fun remove(playerUniqueId: UUID, blockedUniqueId: UUID) = deleteWhere {
        (BlockedPlayersTable.playerUniqueId eq playerUniqueId.toString()) and (BlockedPlayersTable.blockedUniqueId eq blockedUniqueId.toString())
    } > 0
    
    fun get(playerUniqueId: UUID): Set<UUID> =
        select { BlockedPlayersTable.playerUniqueId eq playerUniqueId.toString() }.mapTo(hashSetOf()) { UUID.fromString(it[this.blockedUniqueId]) }
    
    fun inverseGet(playerUniqueId: UUID): Set<UUID> =
        select { BlockedPlayersTable.blockedUniqueId eq playerUniqueId.toString() }.mapTo(hashSetOf()) { UUID.fromString(it[this.playerUniqueId]) }
    
}
