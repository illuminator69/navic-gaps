package paige.navic.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow
import paige.navic.data.database.entities.SyncActionEntity

@Dao
interface SyncActionDao {
	@Insert
	suspend fun enqueue(action: SyncActionEntity)

	@Transaction
	@Query("SELECT * FROM SyncActionEntity ORDER BY id ASC")
	suspend fun getPendingActions(): List<SyncActionEntity>

	/**
	 * The queue as it stands, oldest first, for the pending-actions browser (I-020). Re-emitted
	 * as rows are sent or removed. A read: it adds no column and changes no table.
	 */
	@Query("SELECT * FROM SyncActionEntity ORDER BY id ASC")
	fun observePendingActions(): Flow<List<SyncActionEntity>>

	@Query("DELETE FROM SyncActionEntity WHERE id = :id")
	suspend fun removeAction(id: Int)

	@Query("DELETE FROM SyncActionEntity")
	suspend fun clearAllActions()
}
