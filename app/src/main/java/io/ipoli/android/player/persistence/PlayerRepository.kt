package io.ipoli.android.player.persistence

import android.arch.lifecycle.LiveData
import android.arch.persistence.room.Dao
import android.arch.persistence.room.Entity
import android.arch.persistence.room.PrimaryKey
import android.arch.persistence.room.Query
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FacebookAuthProvider
import com.google.firebase.auth.FirebaseAuthProvider
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import io.ipoli.android.achievement.Achievement
import io.ipoli.android.challenge.predefined.entity.PredefinedChallenge
import io.ipoli.android.common.ErrorLogger
import io.ipoli.android.common.datetime.*
import io.ipoli.android.common.distinct
import io.ipoli.android.common.persistence.BaseDao
import io.ipoli.android.common.persistence.BaseEntityFirestoreRepository
import io.ipoli.android.common.persistence.BaseRoomRepository
import io.ipoli.android.common.persistence.getSync
import io.ipoli.android.pet.*
import io.ipoli.android.player.Theme
import io.ipoli.android.player.data.*
import io.ipoli.android.player.persistence.model.*
import io.ipoli.android.quest.ColorPack
import io.ipoli.android.quest.IconPack
import io.ipoli.android.store.powerup.PowerUp
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.runBlocking
import org.jetbrains.annotations.NotNull
import org.threeten.bp.DayOfWeek
import java.util.*
import kotlin.coroutines.experimental.suspendCoroutine

/**
 * Created by Venelin Valkov <venelin@mypoli.fun>
 * on 8/2/17.
 */
interface PlayerRepository {

    fun listen(): Channel<Player?>
    fun find(): Player?
    fun save(entity: Player): Player

    fun hasPlayer(): Boolean

    fun findAllForSync(lastSync: Duration<Millisecond>): List<Player>

    fun findFriend(friendId: String): Player

    fun findAll(playerIds: List<String>): List<Player>

    fun isUsernameAvailable(username: String): Boolean
    fun addUsername(
        username: String
    )

    fun removeUsername(username: String)
    fun findSchemaVersion(): Int?
    fun saveStatistics(stats: Statistics): Statistics
    fun delete()
}

@Dao
abstract class PlayerDao : BaseDao<RoomPlayer>() {

    @Query("SELECT * FROM players LIMIT 1")
    abstract fun find(): RoomPlayer?

    @Query("SELECT * FROM players WHERE id = :id")
    abstract fun findById(id: String): RoomPlayer

    @Query("SELECT * FROM players WHERE id = :id")
    abstract fun listenById(id: String): LiveData<RoomPlayer>

    @Query("SELECT * FROM players LIMIT 1")
    abstract fun listen(): LiveData<RoomPlayer>

    @Query("SELECT COUNT(*) FROM players")
    abstract fun count(): Int

    @Query("SELECT * FROM players $FIND_SYNC_QUERY")
    abstract fun findAllForSync(lastSync: Long): List<RoomPlayer>

    @Query("DELETE FROM players")
    abstract fun delete()
}

@Entity(tableName = "players")
data class RoomPlayer(
    @NotNull
    @PrimaryKey(autoGenerate = false)
    val id: String,
    val username: String?,
    val displayName: String?,
    val bio: String?,
    val schemaVersion: Long,
    val level: Long,
    val coins: Long,
    val gems: Long,
    val experience: Long,
    val authProvider: MutableMap<String, Any?>?,
    val avatar: String,
    val pet: MutableMap<String, Any?>,
    val inventory: MutableMap<String, Any?>,
    val membership: String,
    val preferences: MutableMap<String, Any?>,
    val achievements: List<MutableMap<String, Any?>>,
    val statistics: MutableMap<String, Any?>,
    val createdAt: Long,
    val updatedAt: Long,
    val removedAt: Long?
)

class AndroidPlayerRepository(
    private val database: FirebaseFirestore,
    dao: PlayerDao
) : BaseRoomRepository<Player, RoomPlayer, PlayerDao>(dao), PlayerRepository {

    override fun save(entities: List<Player>): List<Player> {
        TODO("not implemented")
    }

    override fun findAllForSync(lastSync: Duration<Millisecond>) =
        dao.findAllForSync(lastSync.millisValue).map { toEntityObject(it) }

    override fun findFriend(friendId: String) =
        FirestorePlayerRepository(database).findFriend(friendId)

    override fun findAll(playerIds: List<String>) =
        FirestorePlayerRepository(database).findAll(playerIds)


    override fun save(entity: Player): Player {
        val rp = toDatabaseObject(entity)
        dao.save(rp)
        return entity.copy(id = rp.id)
    }

    override fun hasPlayer(): Boolean {
        return dao.count() > 0
    }

    override fun findSchemaVersion(): Int? {
        return find()?.schemaVersion
    }

    override fun saveStatistics(stats: Statistics): Statistics {
        save(find()!!.copy(statistics = stats))
        return stats
    }

    override fun listen() =
        dao.listen().distinct().notifySingle()

    override fun find(): Player? {
        return dao.find()?.let {
            toEntityObject(it)
        }
    }

    override fun delete() {
        dao.delete()
    }

    override fun toEntityObject(dbObject: RoomPlayer): Player {
        val authProvider = dbObject.authProvider?.let {
            val cap = DbAuthProvider(it)

            when (cap.provider) {
                FacebookAuthProvider.PROVIDER_ID -> {
                    AuthProvider.Facebook(
                        userId = cap.userId,
                        displayName = cap.displayName,
                        email = cap.email,
                        imageUrl = cap.image?.let { Uri.parse(it) }
                    )
                }

                GoogleAuthProvider.PROVIDER_ID -> {
                    AuthProvider.Google(
                        userId = cap.userId,
                        displayName = cap.displayName,
                        email = cap.email,
                        imageUrl = cap.image?.let { Uri.parse(it) }
                    )
                }

                else -> throw IllegalArgumentException("Unknown provider ${cap.provider}")
            }
        }


        val cPet = DbPet(dbObject.pet)
        val pet = Pet(
            name = cPet.name,
            avatar = PetAvatar.valueOf(cPet.avatar),
            equipment = createPetEquipment(cPet),
            moodPoints = cPet.moodPoints.toInt(),
            healthPoints = cPet.healthPoints.toInt(),
            coinBonus = cPet.coinBonus,
            experienceBonus = cPet.experienceBonus,
            itemDropBonus = cPet.itemDropBonus
        )

        val ci = DbInventory(dbObject.inventory)
        val inventory = Inventory(
            food = ci.food.entries.associate { Food.valueOf(it.key) to it.value.toInt() },
            avatars = ci.avatars.map { Avatar.valueOf(it) }.toSet(),
            powerUps = ci.powerUps.map {
                PowerUp.fromType(
                    PowerUp.Type.valueOf(it.key),
                    it.value.startOfDayUTC
                )
            },
            pets = ci.pets.map {
                val cip = DbInventoryPet(it)
                InventoryPet(
                    cip.name,
                    PetAvatar.valueOf(cip.avatar),
                    cip.items.map { PetItem.valueOf(it) }.toSet()
                )
            }.toSet(),
            themes = ci.themes.map { Theme.valueOf(it) }.toSet(),
            colorPacks = ci.colorPacks.map { ColorPack.valueOf(it) }.toSet(),
            iconPacks = ci.iconPacks.map { IconPack.valueOf(it) }.toSet(),
            challenges = ci.challenges.map { PredefinedChallenge.valueOf(it) }.toSet()
        )

        val cPref = DbPreferences(dbObject.preferences)
        val pref = Player.Preferences(
            theme = Theme.valueOf(cPref.theme),
            syncCalendars = cPref.syncCalendars.map {
                val sc = DbSyncCalendar(it)
                Player.Preferences.SyncCalendar(sc.id, sc.name)
            }.toSet(),
            productiveTimesOfDay = cPref.productiveTimesOfDay.map { TimeOfDay.valueOf(it) }.toSet(),
            workDays = cPref.workDays.map { DayOfWeek.valueOf(it) }.toSet(),
            workStartTime = Time.of(cPref.workStartMinute.toInt()),
            workEndTime = Time.of(cPref.workEndMinute.toInt()),
            sleepStartTime = Time.of(cPref.sleepStartMinute.toInt()),
            sleepEndTime = Time.of(cPref.sleepEndMinute.toInt()),
            timeFormat = Player.Preferences.TimeFormat.valueOf(cPref.timeFormat),
            temperatureUnit = Player.Preferences.TemperatureUnit.valueOf(cPref.temperatureUnit),
            planDayTime = Time.of(cPref.planDayStartMinute.toInt()),
            planDays = cPref.planDays.map { DayOfWeek.valueOf(it) }.toSet(),
            isQuickDoNotificationEnabled = cPref.isQuickDoNotificationEnabled
        )

        val ca = dbObject.achievements.map { DbUnlockedAchievement(it) }
        val achievements = ca.map {
            Player.UnlockedAchievement(
                achievement = Achievement.valueOf(it.achievement),
                unlockTime = Time.of(it.unlockMinute.toInt()),
                unlockDate = it.unlockDate.startOfDayUTC
            )
        }

        return Player(
            id = dbObject.id,
            username = dbObject.username,
            displayName = dbObject.displayName,
            bio = dbObject.bio,
            schemaVersion = dbObject.schemaVersion.toInt(),
            level = dbObject.level.toInt(),
            coins = dbObject.coins.toInt(),
            gems = dbObject.gems.toInt(),
            experience = dbObject.experience,
            authProvider = authProvider,
            avatar = Avatar.valueOf(dbObject.avatar),
            inventory = inventory,
            createdAt = dbObject.createdAt.instant,
            updatedAt = dbObject.updatedAt.instant,
            removedAt = dbObject.removedAt?.instant,
            pet = pet,
            membership = Membership.valueOf(dbObject.membership),
            preferences = pref,
            achievements = achievements,
            statistics = createStatistics(dbObject.statistics)

        )

    }

    private fun createPetEquipment(dbPet: DbPet): PetEquipment {
        val e = DbPetEquipment(dbPet.equipment)
        val toPetItem: (String?) -> PetItem? = { it?.let { PetItem.valueOf(it) } }
        return PetEquipment(toPetItem(e.hat), toPetItem(e.mask), toPetItem(e.bodyArmor))
    }

    private fun createStatistics(stats: Map<String, Any?>): Statistics {
        val dailyChallengeCompleteStreak = createStreakStatistic(
            "dailyChallengeCompleteStreak",
            stats
        )
        return Statistics(
            questCompletedCount = createCountStatistic("questCompletedCount", stats),
            questCompletedCountForToday = createCountStatistic(
                "questCompletedCountForToday",
                stats
            ),
            questCompletedStreak = createStreakStatistic("questCompletedStreak", stats),
            dailyChallengeCompleteStreak = dailyChallengeCompleteStreak,
            dailyChallengeBestStreak = createCountStatistic(
                statisticKey = "dailyChallengeBestStreak",
                stats = stats,
                defaultValue = dailyChallengeCompleteStreak.count
            ),
            petHappyStateStreak = createCountStatistic("petHappyStateStreak", stats),
            awesomenessScoreStreak = createCountStatistic("awesomenessScoreStreak", stats),
            planDayStreak = createStreakStatistic("planDayStreak", stats),
            focusHoursStreak = createCountStatistic("focusHoursStreak", stats),
            repeatingQuestCreatedCount = createCountStatistic("repeatingQuestCreatedCount", stats),
            challengeCompletedCount = createCountStatistic("challengeCompletedCount", stats),
            challengeCreatedCount = createCountStatistic("challengeCreatedCount", stats),
            gemConvertedCount = createCountStatistic("gemConvertedCount", stats),
            friendInvitedCount = createCountStatistic("friendInvitedCount", stats),
            experienceForToday = createCountStatistic("experienceForToday", stats),
            petItemEquippedCount = createCountStatistic("petItemEquippedCount", stats),
            avatarChangeCount = createCountStatistic("avatarChangeCount", stats),
            petChangeCount = createCountStatistic("petChangeCount", stats),
            petFedWithPoopCount = createCountStatistic("petFedWithPoopCount", stats),
            petFedCount = createCountStatistic("petFedCount", stats),
            feedbackSentCount = createCountStatistic("feedbackSentCount", stats),
            joinMembershipCount = createCountStatistic("joinMembershipCount", stats),
            powerUpActivatedCount = createCountStatistic("powerUpActivatedCount", stats),
            petRevivedCount = createCountStatistic("petRevivedCount", stats),
            petDiedCount = createCountStatistic("petDiedCount", stats)
        )
    }

    private fun createCountStatistic(
        statisticKey: String,
        stats: Map<String, Any?>,
        defaultValue: Long = 0
    ) =
        stats[statisticKey]?.let { it as Long } ?: defaultValue

    private fun createStreakStatistic(
        statisticKey: String,
        stats: Map<String, Any?>
    ) =
        if (stats.containsKey(statisticKey)) {
            @Suppress("UNCHECKED_CAST")
            val statisticData = stats[statisticKey]!! as Map<String, Any>
            Statistics.StreakStatistic(
                statisticData["count"]!! as Long,
                statisticData["lastDate"]?.let {
                    (it as Long).startOfDayUTC
                }
            )
        } else {
            Statistics.StreakStatistic()
        }

    override fun toDatabaseObject(entity: Player) =
        RoomPlayer(
            id = if (entity.id.isEmpty()) UUID.randomUUID().toString() else entity.id,
            username = entity.username,
            displayName = entity.displayName,
            bio = entity.bio,
            schemaVersion = entity.schemaVersion.toLong(),
            level = entity.level.toLong(),
            coins = entity.coins.toLong(),
            gems = entity.gems.toLong(),
            experience = entity.experience,
            authProvider = entity.authProvider?.let { createDbAuthProvider(it).map },
            avatar = entity.avatar.name,
            pet = createDbPet(entity.pet).map,
            inventory = createDbInventory(entity.inventory).map,
            membership = entity.membership.name,
            preferences = createDbPreferences(entity.preferences).map,
            achievements = createDbAchievements(entity.achievements),
            statistics = createDbStatistics(entity.statistics),
            updatedAt = System.currentTimeMillis(),
            createdAt = entity.createdAt.toEpochMilli(),
            removedAt = entity.removedAt?.toEpochMilli()
        )

    private fun createDbPet(pet: Pet) =
        DbPet().also {
            it.name = pet.name
            it.avatar = pet.avatar.name
            it.equipment = createDbPetEquipment(pet.equipment).map
            it.healthPoints = pet.healthPoints.toLong()
            it.moodPoints = pet.moodPoints.toLong()
            it.coinBonus = pet.coinBonus
            it.experienceBonus = pet.experienceBonus
            it.itemDropBonus = pet.itemDropBonus
        }

    private fun createDbPetEquipment(equipment: PetEquipment) =
        DbPetEquipment().also {
            it.hat = equipment.hat?.name
            it.mask = equipment.mask?.name
            it.bodyArmor = equipment.bodyArmor?.name
        }

    private fun createDbAuthProvider(authProvider: AuthProvider) =

        when (authProvider) {
            is AuthProvider.Google -> {
                DbAuthProvider().also {
                    it.userId = authProvider.userId
                    it.email = authProvider.email
                    it.displayName = authProvider.displayName
                    it.image = authProvider.imageUrl?.toString()
                    it.provider = GoogleAuthProvider.PROVIDER_ID
                }
            }

            is AuthProvider.Facebook -> {
                DbAuthProvider().also {
                    it.userId = authProvider.userId
                    it.email = authProvider.email
                    it.displayName = authProvider.displayName
                    it.image = authProvider.imageUrl?.toString()
                    it.provider = FacebookAuthProvider.PROVIDER_ID
                }
            }
        }


    private fun createDbInventory(inventory: Inventory) =
        DbInventory().also {
            it.food = inventory.food.entries
                .associate { it.key.name to it.value.toLong() }
                .toMutableMap()
            it.avatars = inventory.avatars.map { it.name }
            it.powerUps = inventory.powerUps
                .associate { it.type.name to it.expirationDate.startOfDayUTC() }
                .toMutableMap()
            it.pets = inventory.pets
                .map { createDbInventoryPet(it).map }
            it.themes = inventory.themes.map { it.name }
            it.colorPacks = inventory.colorPacks.map { it.name }
            it.iconPacks = inventory.iconPacks.map { it.name }
            it.challenges = inventory.challenges.map { it.name }
        }

    private fun createDbInventoryPet(inventoryPet: InventoryPet) =
        DbInventoryPet().also {
            it.name = inventoryPet.name
            it.avatar = inventoryPet.avatar.name
            it.items = inventoryPet.items.map { it.name }
        }

    private fun createDbPreferences(preferences: Player.Preferences) =
        DbPreferences().also {
            it.theme = preferences.theme.name
            it.syncCalendars = preferences.syncCalendars.map { c ->
                DbSyncCalendar().also {
                    it.id = c.id
                    it.name = c.name
                }.map
            }
            it.productiveTimesOfDay = preferences.productiveTimesOfDay.map { it.name }
            it.workDays = preferences.workDays.map { it.name }
            it.workStartMinute = preferences.workStartTime.toMinuteOfDay().toLong()
            it.workEndMinute = preferences.workEndTime.toMinuteOfDay().toLong()
            it.sleepStartMinute = preferences.sleepStartTime.toMinuteOfDay().toLong()
            it.sleepEndMinute = preferences.sleepEndTime.toMinuteOfDay().toLong()
            it.timeFormat = preferences.timeFormat.name
            it.temperatureUnit = preferences.temperatureUnit.name
            it.planDayStartMinute = preferences.planDayTime.toMinuteOfDay().toLong()
            it.planDays = preferences.planDays.map { it.name }
            it.isQuickDoNotificationEnabled = preferences.isQuickDoNotificationEnabled
        }

    private fun createDbAchievements(achievements: List<Player.UnlockedAchievement>) =
        achievements.map { a ->
            DbUnlockedAchievement().also {
                it.achievement = a.achievement.name
                it.unlockMinute = a.unlockTime.toMinuteOfDay().toLong()
                it.unlockDate = a.unlockDate.startOfDayUTC()
            }.map
        }

    private fun createDbStatistics(stats: Statistics) =
        mutableMapOf<String, Any?>(
            "questCompletedCount" to stats.questCompletedCount,
            "questCompletedCountForToday" to stats.questCompletedCountForToday,
            "questCompletedStreak" to stats.questCompletedStreak.db,
            "dailyChallengeCompleteStreak" to stats.dailyChallengeCompleteStreak.db,
            "dailyChallengeBestStreak" to stats.dailyChallengeBestStreak,
            "petHappyStateStreak" to stats.petHappyStateStreak,
            "awesomenessScoreStreak" to stats.awesomenessScoreStreak,
            "planDayStreak" to stats.planDayStreak.db,
            "focusHoursStreak" to stats.focusHoursStreak,
            "repeatingQuestCreatedCount" to stats.repeatingQuestCreatedCount,
            "challengeCompletedCount" to stats.challengeCompletedCount,
            "challengeCreatedCount" to stats.challengeCreatedCount,
            "gemConvertedCount" to stats.gemConvertedCount,
            "friendInvitedCount" to stats.friendInvitedCount,
            "experienceForToday" to stats.experienceForToday,
            "petItemEquippedCount" to stats.petItemEquippedCount,
            "avatarChangeCount" to stats.avatarChangeCount,
            "petChangeCount" to stats.petChangeCount,
            "petFedWithPoopCount" to stats.petFedWithPoopCount,
            "petFedCount" to stats.petFedCount,
            "feedbackSentCount" to stats.feedbackSentCount,
            "joinMembershipCount" to stats.joinMembershipCount,
            "powerUpActivatedCount" to stats.powerUpActivatedCount,
            "petRevivedCount" to stats.petRevivedCount,
            "petDiedCount" to stats.petDiedCount
        )

    private fun createDbStreakStatistic(stat: Statistics.StreakStatistic) =
        mapOf(
            "count" to stat.count,
            "lastDate" to stat.lastDate?.startOfDayUTC()
        )

    private val Statistics.StreakStatistic.db
        get() = createDbStreakStatistic(this)

    private val usernamesReference get() = database.collection("usernames")

    override fun isUsernameAvailable(username: String): Boolean = runBlocking {
        suspendCoroutine<Boolean> { continuation ->
            val usernameRef = usernamesReference.document(username.toLowerCase())
            var registration: ListenerRegistration? = null
            registration = usernameRef.addSnapshotListener(
                MetadataChanges.INCLUDE
            ) { snapshot, error ->

                if (error != null) {
                    ErrorLogger.log(error)
                    registration?.remove()
                    return@addSnapshotListener
                }

                if (!snapshot!!.metadata.isFromCache) {
                    registration?.remove()
                    continuation.resume(!snapshot.exists())
                }
            }
        }
    }

    override fun addUsername(
        username: String
    ) {
        Tasks.await(
            usernamesReference.document(username.toLowerCase()).set(
                mapOf(
                    "username" to username
                )
            )
        )
    }

    override fun removeUsername(username: String) {
        Tasks.await(usernamesReference.document(username).delete())
    }
}

class FirestorePlayerRepository(
    database: FirebaseFirestore
) : BaseEntityFirestoreRepository<Player, DbPlayer>(
    database
) {

    override val collectionReference
        get() = database.collection("players")

    override val entityReference
        get() = collectionReference.document(playerId)

    override fun toEntityObject(dataMap: MutableMap<String, Any?>): Player {
        val cp = DbPlayer(dataMap)

        val authProvider = cp.authProvider?.let {
            val cap = DbAuthProvider(it)

            when (cap.provider) {
                FacebookAuthProvider.PROVIDER_ID -> {
                    AuthProvider.Facebook(
                        userId = cap.userId,
                        displayName = cap.displayName,
                        email = cap.email,
                        imageUrl = cap.image?.let { Uri.parse(it) }
                    )
                }

                GoogleAuthProvider.PROVIDER_ID -> {
                    AuthProvider.Google(
                        userId = cap.userId,
                        displayName = cap.displayName,
                        email = cap.email,
                        imageUrl = cap.image?.let { Uri.parse(it) }
                    )
                }

                FirebaseAuthProvider.PROVIDER_ID -> null

                else -> throw IllegalArgumentException("Unknown provider ${cap.provider}")
            }
        }


        val cPet = DbPet(cp.pet)
        val pet = Pet(
            name = cPet.name,
            avatar = PetAvatar.valueOf(cPet.avatar),
            equipment = createPetEquipment(cPet),
            moodPoints = cPet.moodPoints.toInt(),
            healthPoints = cPet.healthPoints.toInt(),
            coinBonus = cPet.coinBonus,
            experienceBonus = cPet.experienceBonus,
            itemDropBonus = cPet.itemDropBonus
        )

        val ci = DbInventory(cp.inventory)
        val inventory = Inventory(
            food = ci.food.entries.associate { Food.valueOf(it.key) to it.value.toInt() },
            avatars = ci.avatars.map { Avatar.valueOf(it) }.toSet(),
            powerUps = ci.powerUps.map {
                PowerUp.fromType(
                    PowerUp.Type.valueOf(it.key),
                    it.value.startOfDayUTC
                )
            },
            pets = ci.pets.map {
                val cip = DbInventoryPet(it)
                InventoryPet(
                    cip.name,
                    PetAvatar.valueOf(cip.avatar),
                    cip.items.map { PetItem.valueOf(it) }.toSet()
                )
            }.toSet(),
            themes = ci.themes.map { Theme.valueOf(it) }.toSet(),
            colorPacks = ci.colorPacks.map { ColorPack.valueOf(it) }.toSet(),
            iconPacks = ci.iconPacks.map { IconPack.valueOf(it) }.toSet(),
            challenges = ci.challenges.map { PredefinedChallenge.valueOf(it) }.toSet()
        )

        val cPref = DbPreferences(cp.preferences)
        val pref = Player.Preferences(
            theme = Theme.valueOf(cPref.theme),
            syncCalendars = cPref.syncCalendars.map {
                val sc = DbSyncCalendar(it)
                Player.Preferences.SyncCalendar(sc.id, sc.name)
            }.toSet(),
            productiveTimesOfDay = cPref.productiveTimesOfDay.map { TimeOfDay.valueOf(it) }.toSet(),
            workDays = cPref.workDays.map { DayOfWeek.valueOf(it) }.toSet(),
            workStartTime = Time.of(cPref.workStartMinute.toInt()),
            workEndTime = Time.of(cPref.workEndMinute.toInt()),
            sleepStartTime = Time.of(cPref.sleepStartMinute.toInt()),
            sleepEndTime = Time.of(cPref.sleepEndMinute.toInt()),
            timeFormat = Player.Preferences.TimeFormat.valueOf(cPref.timeFormat),
            temperatureUnit = Player.Preferences.TemperatureUnit.valueOf(cPref.temperatureUnit),
            planDayTime = Time.of(cPref.planDayStartMinute.toInt()),
            planDays = cPref.planDays.map { DayOfWeek.valueOf(it) }.toSet(),
            isQuickDoNotificationEnabled = cPref.isQuickDoNotificationEnabled
        )

        val ca = cp.achievements.map { DbUnlockedAchievement(it) }
        val achievements = ca.map {
            Player.UnlockedAchievement(
                achievement = Achievement.valueOf(it.achievement),
                unlockTime = Time.of(it.unlockMinute.toInt()),
                unlockDate = it.unlockDate.startOfDayUTC
            )
        }

        return Player(
            id = cp.id,
            username = cp.username,
            displayName = cp.displayName,
            bio = cp.bio,
            schemaVersion = cp.schemaVersion.toInt(),
            level = cp.level.toInt(),
            coins = cp.coins.toInt(),
            gems = cp.gems.toInt(),
            experience = cp.experience,
            authProvider = authProvider,
            avatar = Avatar.valueOf(cp.avatar),
            inventory = inventory,
            createdAt = cp.createdAt.instant,
            updatedAt = cp.updatedAt.instant,
            removedAt = cp.removedAt?.instant,
            pet = pet,
            membership = Membership.valueOf(cp.membership),
            preferences = pref,
            achievements = achievements,
            statistics = createStatistics(cp.statistics)
        )
    }

    private fun createPetEquipment(dbPet: DbPet): PetEquipment {
        val e = DbPetEquipment(dbPet.equipment)
        val toPetItem: (String?) -> PetItem? = { it?.let { PetItem.valueOf(it) } }
        return PetEquipment(toPetItem(e.hat), toPetItem(e.mask), toPetItem(e.bodyArmor))
    }

    private fun createStatistics(stats: Map<String, Any?>): Statistics {
        val dailyChallengeCompleteStreak = createStreakStatistic(
            "dailyChallengeCompleteStreak",
            stats
        )
        return Statistics(
            questCompletedCount = createCountStatistic("questCompletedCount", stats),
            questCompletedCountForToday = createCountStatistic(
                "questCompletedCountForToday",
                stats
            ),
            questCompletedStreak = createStreakStatistic("questCompletedStreak", stats),
            dailyChallengeCompleteStreak = dailyChallengeCompleteStreak,
            dailyChallengeBestStreak = createCountStatistic(
                statisticKey = "dailyChallengeBestStreak",
                stats = stats,
                defaultValue = dailyChallengeCompleteStreak.count
            ),
            petHappyStateStreak = createCountStatistic("petHappyStateStreak", stats),
            awesomenessScoreStreak = createCountStatistic("awesomenessScoreStreak", stats),
            planDayStreak = createStreakStatistic("planDayStreak", stats),
            focusHoursStreak = createCountStatistic("focusHoursStreak", stats),
            repeatingQuestCreatedCount = createCountStatistic("repeatingQuestCreatedCount", stats),
            challengeCompletedCount = createCountStatistic("challengeCompletedCount", stats),
            challengeCreatedCount = createCountStatistic("challengeCreatedCount", stats),
            gemConvertedCount = createCountStatistic("gemConvertedCount", stats),
            friendInvitedCount = createCountStatistic("friendInvitedCount", stats),
            experienceForToday = createCountStatistic("experienceForToday", stats),
            petItemEquippedCount = createCountStatistic("petItemEquippedCount", stats),
            avatarChangeCount = createCountStatistic("avatarChangeCount", stats),
            petChangeCount = createCountStatistic("petChangeCount", stats),
            petFedWithPoopCount = createCountStatistic("petFedWithPoopCount", stats),
            petFedCount = createCountStatistic("petFedCount", stats),
            feedbackSentCount = createCountStatistic("feedbackSentCount", stats),
            joinMembershipCount = createCountStatistic("joinMembershipCount", stats),
            powerUpActivatedCount = createCountStatistic("powerUpActivatedCount", stats),
            petRevivedCount = createCountStatistic("petRevivedCount", stats),
            petDiedCount = createCountStatistic("petDiedCount", stats)
        )
    }

    private fun createCountStatistic(
        statisticKey: String,
        stats: Map<String, Any?>,
        defaultValue: Long = 0
    ) =
        stats[statisticKey]?.let { it as Long } ?: defaultValue

    private fun createStreakStatistic(
        statisticKey: String,
        stats: Map<String, Any?>
    ) =
        if (stats.containsKey(statisticKey)) {
            @Suppress("UNCHECKED_CAST")
            val statisticData = stats[statisticKey]!! as Map<String, Any>
            Statistics.StreakStatistic(
                statisticData["count"]!! as Long,
                statisticData["lastDate"]?.let {
                    (it as Long).startOfDayUTC
                }
            )
        } else {
            Statistics.StreakStatistic()
        }

    override fun toDatabaseObject(entity: Player) =
        DbPlayer().also {
            it.id = entity.id
            it.username = entity.username
            it.displayName = entity.displayName
            it.bio = entity.bio
            it.schemaVersion = entity.schemaVersion.toLong()
            it.level = entity.level.toLong()
            it.coins = entity.coins.toLong()
            it.gems = entity.gems.toLong()
            it.experience = entity.experience
            it.authProvider = entity.authProvider?.let { createDbAuthProvider(it).map }
            it.avatar = entity.avatar.name
            it.createdAt = entity.createdAt.toEpochMilli()
            it.updatedAt = entity.updatedAt.toEpochMilli()
            it.removedAt = entity.removedAt?.toEpochMilli()
            it.pet = createDbPet(entity.pet).map
            it.inventory = createDbInventory(entity.inventory).map
            it.membership = entity.membership.name
            it.preferences = createDbPreferences(entity.preferences).map
            it.achievements = createDbAchievements(entity.achievements)
            it.statistics = createDbStatistics(entity.statistics)
        }

    private fun createDbPet(pet: Pet) =
        DbPet().also {
            it.name = pet.name
            it.avatar = pet.avatar.name
            it.equipment = createDbPetEquipment(pet.equipment).map
            it.healthPoints = pet.healthPoints.toLong()
            it.moodPoints = pet.moodPoints.toLong()
            it.coinBonus = pet.coinBonus
            it.experienceBonus = pet.experienceBonus
            it.itemDropBonus = pet.itemDropBonus
        }

    private fun createDbPetEquipment(equipment: PetEquipment) =
        DbPetEquipment().also {
            it.hat = equipment.hat?.name
            it.mask = equipment.mask?.name
            it.bodyArmor = equipment.bodyArmor?.name
        }

    private fun createDbAuthProvider(authProvider: AuthProvider) =

        when (authProvider) {
            is AuthProvider.Google -> {
                DbAuthProvider().also {
                    it.userId = authProvider.userId
                    it.email = authProvider.email
                    it.displayName = authProvider.displayName
                    it.image = authProvider.imageUrl?.toString()
                    it.provider = GoogleAuthProvider.PROVIDER_ID
                }
            }

            is AuthProvider.Facebook -> {
                DbAuthProvider().also {
                    it.userId = authProvider.userId
                    it.email = authProvider.email
                    it.displayName = authProvider.displayName
                    it.image = authProvider.imageUrl?.toString()
                    it.provider = FacebookAuthProvider.PROVIDER_ID
                }
            }
        }


    private fun createDbInventory(inventory: Inventory) =
        DbInventory().also {
            it.food = inventory.food.entries
                .associate { it.key.name to it.value.toLong() }
                .toMutableMap()
            it.avatars = inventory.avatars.map { it.name }
            it.powerUps = inventory.powerUps
                .associate { it.type.name to it.expirationDate.startOfDayUTC() }
                .toMutableMap()
            it.pets = inventory.pets
                .map { createDbInventoryPet(it).map }
            it.themes = inventory.themes.map { it.name }
            it.colorPacks = inventory.colorPacks.map { it.name }
            it.iconPacks = inventory.iconPacks.map { it.name }
            it.challenges = inventory.challenges.map { it.name }
        }

    private fun createDbInventoryPet(inventoryPet: InventoryPet) =
        DbInventoryPet().also {
            it.name = inventoryPet.name
            it.avatar = inventoryPet.avatar.name
            it.items = inventoryPet.items.map { it.name }
        }

    private fun createDbPreferences(preferences: Player.Preferences) =
        DbPreferences().also {
            it.theme = preferences.theme.name
            it.syncCalendars = preferences.syncCalendars.map { c ->
                DbSyncCalendar().also {
                    it.id = c.id
                    it.name = c.name
                }.map
            }
            it.productiveTimesOfDay = preferences.productiveTimesOfDay.map { it.name }
            it.workDays = preferences.workDays.map { it.name }
            it.workStartMinute = preferences.workStartTime.toMinuteOfDay().toLong()
            it.workEndMinute = preferences.workEndTime.toMinuteOfDay().toLong()
            it.sleepStartMinute = preferences.sleepStartTime.toMinuteOfDay().toLong()
            it.sleepEndMinute = preferences.sleepEndTime.toMinuteOfDay().toLong()
            it.timeFormat = preferences.timeFormat.name
            it.temperatureUnit = preferences.temperatureUnit.name
            it.planDayStartMinute = preferences.planDayTime.toMinuteOfDay().toLong()
            it.planDays = preferences.planDays.map { it.name }
            it.isQuickDoNotificationEnabled = preferences.isQuickDoNotificationEnabled
        }

    private fun createDbAchievements(achievements: List<Player.UnlockedAchievement>) =
        achievements.map { a ->
            DbUnlockedAchievement().also {
                it.achievement = a.achievement.name
                it.unlockMinute = a.unlockTime.toMinuteOfDay().toLong()
                it.unlockDate = a.unlockDate.startOfDayUTC()
            }.map
        }

    private fun createDbStatistics(stats: Statistics) =
        mutableMapOf<String, Any?>(
            "questCompletedCount" to stats.questCompletedCount,
            "questCompletedCountForToday" to stats.questCompletedCountForToday,
            "questCompletedStreak" to stats.questCompletedStreak.db,
            "dailyChallengeCompleteStreak" to stats.dailyChallengeCompleteStreak.db,
            "dailyChallengeBestStreak" to stats.dailyChallengeBestStreak,
            "petHappyStateStreak" to stats.petHappyStateStreak,
            "awesomenessScoreStreak" to stats.awesomenessScoreStreak,
            "planDayStreak" to stats.planDayStreak.db,
            "focusHoursStreak" to stats.focusHoursStreak,
            "repeatingQuestCreatedCount" to stats.repeatingQuestCreatedCount,
            "challengeCompletedCount" to stats.challengeCompletedCount,
            "challengeCreatedCount" to stats.challengeCreatedCount,
            "gemConvertedCount" to stats.gemConvertedCount,
            "friendInvitedCount" to stats.friendInvitedCount,
            "experienceForToday" to stats.experienceForToday,
            "petItemEquippedCount" to stats.petItemEquippedCount,
            "avatarChangeCount" to stats.avatarChangeCount,
            "petChangeCount" to stats.petChangeCount,
            "petFedWithPoopCount" to stats.petFedWithPoopCount,
            "petFedCount" to stats.petFedCount,
            "feedbackSentCount" to stats.feedbackSentCount,
            "joinMembershipCount" to stats.joinMembershipCount,
            "powerUpActivatedCount" to stats.powerUpActivatedCount,
            "petRevivedCount" to stats.petRevivedCount,
            "petDiedCount" to stats.petDiedCount
        )

    private fun createDbStreakStatistic(stat: Statistics.StreakStatistic) =
        mapOf(
            "count" to stat.count,
            "lastDate" to stat.lastDate?.startOfDayUTC()
        )

    fun findFriend(friendId: String): Player =
        extractDocument(collectionReference.document(friendId))!!

    fun findAll(playerIds: List<String>): List<Player> {
        val playerJobs = playerIds
            .map {
                async {
                    collectionReference
                        .document(playerId)
                        .getSync()
                }
            }

        return runBlocking {
            playerJobs.map {
                val doc = it.await()
                toEntityObject(doc.data!!)
            }
        }
    }

    private val Statistics.StreakStatistic.db
        get() = createDbStreakStatistic(this)
}