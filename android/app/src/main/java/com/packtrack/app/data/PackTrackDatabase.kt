package com.packtrack.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Multi-User Local-First SQLite Database stored on the Android phone
 * (/data/data/com.packtrack.app/databases/packtrack_local.db) + synced per-user
 * to Cloud Firestore (`packtrack_state/user_state_{userId}`).
 */

@Entity(tableName = "trips", indices = [Index("userId")])
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String = "default",
    val destination: String,
    val country: String,
    val startDate: String,
    val durationDays: Int,
    val avgHighF: Double,
    val avgLowF: Double,
    val rainyDaysCount: Int,
    val plugTypes: String,
    val tripMode: String = "OUTBOUND", // "OUTBOUND" | "DOORWAY" | "RETURN_SWEEP"
    val tagsCsv: String = "work,rental_car,carry_on",
    val lastInteractionId: String? = null,
    val lastAgentNote: String = ""
)

@Entity(
    tableName = "packing_items",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("tripId")]
)
data class PackingItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val name: String,
    val category: String,
    val quantity: Int = 1,
    val bagLocation: String = "Carry-On",
    val packedOutbound: Boolean = false,
    val packedReturn: Boolean = false,
    val isDoorwayCritical: Boolean = false,
    val isHighLossReturn: Boolean = false,
    val weatherReason: String = ""
)

@Entity(tableName = "template_rules", indices = [Index("userId")])
data class TemplateRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String = "default",
    val moduleKey: String,
    val name: String,
    val category: String,
    val scaleByDays: Boolean = false,
    val ratioPerDay: Double = 1.0,
    val isDoorwayCritical: Boolean = false,
    val isHighLossReturn: Boolean = true,
    val bagLocation: String = "Carry-On"
)

@Dao
interface PackTrackDao {
    @Query("SELECT * FROM trips WHERE userId = :userId ORDER BY id DESC")
    fun observeTripsForUser(userId: String): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE userId = :userId ORDER BY id DESC")
    suspend fun getTripsForUser(userId: String): List<TripEntity>

    @Query("SELECT * FROM trips WHERE id = :tripId")
    fun observeTripById(tripId: Long): Flow<TripEntity?>

    @Query("SELECT * FROM trips WHERE id = :tripId")
    suspend fun getTripById(tripId: Long): TripEntity?

    @Query("SELECT * FROM packing_items WHERE tripId = :tripId ORDER BY id ASC")
    fun observeItemsForTrip(tripId: Long): Flow<List<PackingItemEntity>>

    @Query("SELECT * FROM packing_items WHERE tripId = :tripId ORDER BY id ASC")
    suspend fun getItemsForTrip(tripId: Long): List<PackingItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrip(trip: TripEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<PackingItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSingleItem(item: PackingItemEntity): Long

    @Query("UPDATE packing_items SET packedOutbound = :packed WHERE id = :itemId")
    suspend fun setItemPackedOutbound(itemId: Long, packed: Boolean)

    @Query("UPDATE packing_items SET packedReturn = :packed WHERE id = :itemId")
    suspend fun setItemPackedReturn(itemId: Long, packed: Boolean)

    @Query("UPDATE packing_items SET quantity = :qty, weatherReason = :reason WHERE id = :itemId")
    suspend fun updateItemQuantity(itemId: Long, qty: Int, reason: String)

    @Query("UPDATE packing_items SET name = :name, quantity = :qty, category = :category, bagLocation = :bagLocation WHERE id = :itemId")
    suspend fun updateItemDetails(itemId: Long, name: String, qty: Int, category: String, bagLocation: String)

    @Query("DELETE FROM packing_items WHERE id = :itemId")
    suspend fun deleteItem(itemId: Long)

    @Query("DELETE FROM packing_items WHERE tripId = :tripId")
    suspend fun deleteAllItemsForTrip(tripId: Long)

    @Query("UPDATE trips SET tripMode = :mode WHERE id = :tripId")
    suspend fun updateTripMode(tripId: Long, mode: String)

    @Query("UPDATE trips SET startDate = :startDate WHERE id = :tripId")
    suspend fun updateTripStartDate(tripId: Long, startDate: String)

    @Query(
        "UPDATE trips SET avgHighF = :highF, avgLowF = :lowF, rainyDaysCount = :rainDays, " +
            "durationDays = :days, lastAgentNote = :note WHERE id = :tripId"
    )
    suspend fun updateTripWeatherAndDuration(
        tripId: Long,
        highF: Double,
        lowF: Double,
        rainDays: Int,
        days: Int,
        note: String
    )

    @Query(
        "UPDATE trips SET startDate = :startDate, durationDays = :days, tagsCsv = :tagsCsv, " +
            "avgHighF = :highF, avgLowF = :lowF, rainyDaysCount = :rainDays, lastAgentNote = :note WHERE id = :tripId"
    )
    suspend fun updateTripDetailsAndWeather(
        tripId: Long,
        startDate: String,
        days: Int,
        tagsCsv: String,
        highF: Double,
        lowF: Double,
        rainDays: Int,
        note: String
    )

    @Query("UPDATE trips SET lastInteractionId = :interactionId, lastAgentNote = :note WHERE id = :tripId")
    suspend fun updateTripConversation(tripId: Long, interactionId: String?, note: String)

    @Query("DELETE FROM trips WHERE id = :tripId")
    suspend fun deleteTrip(tripId: Long)

    @Query("SELECT * FROM template_rules WHERE userId = :userId ORDER BY category ASC, name ASC")
    fun observeTemplateRulesForUser(userId: String): Flow<List<TemplateRuleEntity>>

    @Query("SELECT * FROM template_rules WHERE userId = :userId")
    suspend fun getTemplateRulesForUser(userId: String): List<TemplateRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplateRule(rule: TemplateRuleEntity): Long

    @Query(
        "UPDATE template_rules SET name = :name, category = :category, moduleKey = :moduleKey, " +
            "bagLocation = :bagLocation, scaleByDays = :scaleByDays, ratioPerDay = :ratioPerDay, " +
            "isDoorwayCritical = :isDoorwayCritical WHERE id = :ruleId"
    )
    suspend fun updateTemplateRule(
        ruleId: Long,
        name: String,
        category: String,
        moduleKey: String,
        bagLocation: String,
        scaleByDays: Boolean,
        ratioPerDay: Double,
        isDoorwayCritical: Boolean
    )

    @Query("DELETE FROM template_rules WHERE id = :ruleId")
    suspend fun deleteTemplateRule(ruleId: Long)

    @Query("DELETE FROM template_rules WHERE userId = :userId")
    suspend fun deleteAllTemplateRulesForUser(userId: String)
}

@Database(
    entities = [TripEntity::class, PackingItemEntity::class, TemplateRuleEntity::class],
    version = 6,
    exportSchema = false
)
abstract class PackTrackDatabase : RoomDatabase() {
    abstract fun dao(): PackTrackDao
}
