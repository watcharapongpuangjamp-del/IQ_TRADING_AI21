package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.model.DailyPnLSummary
import com.example.model.ExecutionMode
import com.example.model.RiskPolicy
import com.example.model.StrategyConfig
import com.example.model.TradeRecord
import com.example.model.TradeStatus
import com.example.model.UserProfile
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TradingCloudRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun todayDateKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    suspend fun ensureUserBootstrap(userId: String, displayName: String, email: String?): Result<Unit> {
        val userPath = "users/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch" }

            val userRef = db.collection("users").document(uid)
            val userSnap = userRef.get().await()
            if (!userSnap.exists()) {
                val profile = UserProfile(
                    userId = uid,
                    displayName = displayName.ifBlank { "Trader" },
                    email = email,
                    executionMode = ExecutionMode.SIMULATION.name,
                    simulationBalance = 10000.0
                )
                userRef.set(profile.toCreateMap(uid)).await()
            }

            val policyRef = userRef.collection("riskPolicies").document("default")
            val policySnap = policyRef.get().await()
            if (!policySnap.exists()) {
                val defaultPolicy = RiskPolicy(userId = uid)
                policyRef.set(defaultPolicy.toCreateMap(uid)).await()
            }

            val strategiesSnap = userRef.collection("strategies").limit(1).get().await()
            if (strategiesSnap.isEmpty) {
                val starter = StrategyConfig(
                    userId = uid,
                    name = "EMA-RSI Momentum",
                    instrument = "EUR/USD",
                    timeframe = "1m",
                    tradeAmount = 25.0,
                    expirySeconds = 60,
                    active = true
                )
                userRef.collection("strategies").document("default_ema_rsi")
                    .set(starter.toCreateMap(uid))
                    .await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, userPath)
            Result.failure(e)
        }
    }

    fun observeUserProfile(userId: String): Flow<UserProfile?> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Cross-user observation refused" }
        val path = "users/$uid"
        emitAll(
            db.collection("users").document(uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObject(
                        UserProfile::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    logStreamErrorIfActive(error, uid, OperationType.GET, path)
                    throw error
                }
        )
    }

    fun observeStrategies(userId: String): Flow<List<StrategyConfig>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Cross-user observation refused" }
        val path = "users/$uid/strategies"
        emitAll(
            db.collection("users").document(uid).collection("strategies")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        StrategyConfig::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    logStreamErrorIfActive(error, uid, OperationType.LIST, path)
                    throw error
                }
        )
    }

    fun observeRiskPolicy(userId: String): Flow<RiskPolicy?> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Cross-user observation refused" }
        val path = "users/$uid/riskPolicies/default"
        emitAll(
            db.collection("users").document(uid).collection("riskPolicies").document("default")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObject(
                        RiskPolicy::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    logStreamErrorIfActive(error, uid, OperationType.GET, path)
                    throw error
                }
        )
    }

    fun observeTrades(userId: String): Flow<List<TradeRecord>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Cross-user observation refused" }
        val path = "users/$uid/trades"
        emitAll(
            db.collection("users").document(uid).collection("trades")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(100)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        TradeRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    logStreamErrorIfActive(error, uid, OperationType.LIST, path)
                    throw error
                }
        )
    }

    fun observeDailySummaries(userId: String): Flow<List<DailyPnLSummary>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Cross-user observation refused" }
        val path = "users/$uid/dailySummaries"
        emitAll(
            db.collection("users").document(uid).collection("dailySummaries")
                .orderBy("dateKey", Query.Direction.DESCENDING)
                .limit(14)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        DailyPnLSummary::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    logStreamErrorIfActive(error, uid, OperationType.LIST, path)
                    throw error
                }
        )
    }

    suspend fun saveStrategy(strategy: StrategyConfig): Result<String> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauth"}/strategies"
        return try {
            val uid = requireUserId()
            val strategiesRef = db.collection("users").document(uid).collection("strategies")
            val docId = strategy.id.takeIf { it.isNotBlank() && it.matches(VALID_ID_REGEX) }
                ?: "strat_${System.currentTimeMillis()}"
            val docRef = strategiesRef.document(docId)
            docPath = docRef.path

            val exists = docRef.get().await().exists()
            if (exists) {
                docRef.update(strategy.toUpdateMap()).await()
            } else {
                docRef.set(strategy.toCreateMap(uid)).await()
            }
            Result.success(docId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun saveRiskPolicy(policy: RiskPolicy): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauth"}/riskPolicies/default"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid)
                .collection("riskPolicies").document("default")
            docPath = docRef.path
            val exists = docRef.get().await().exists()
            if (exists) {
                docRef.update(policy.toUpdateMap()).await()
            } else {
                docRef.set(policy.toCreateMap(uid)).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun createTrade(trade: TradeRecord): Result<String> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauth"}/trades"
        return try {
            val uid = requireUserId()
            val docId = trade.id.takeIf { it.isNotBlank() && it.matches(VALID_ID_REGEX) }
                ?: "trade_${System.currentTimeMillis()}"
            val docRef = db.collection("users").document(uid).collection("trades").document(docId)
            docPath = docRef.path
            docRef.set(trade.toCreateMap(uid)).await()
            Result.success(docId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun settleTrade(
        tradeId: String,
        exitPrice: Double,
        status: TradeStatus,
        pnl: Double
    ): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauth"}/trades/$tradeId"
        return try {
            val uid = requireUserId()
            require(tradeId.isNotBlank() && tradeId.matches(VALID_ID_REGEX)) { "Invalid tradeId" }
            val docRef = db.collection("users").document(uid).collection("trades").document(tradeId)
            docPath = docRef.path
            docRef.update(
                mapOf(
                    "exitPrice" to exitPrice.coerceAtLeast(0.0),
                    "status" to status.name,
                    "pnl" to pnl.coerceIn(-100_000.0, 200_000.0),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun upsertDailySummary(summary: DailyPnLSummary): Result<Unit> {
        val dateKey = summary.dateKey.ifBlank { todayDateKey() }
        var docPath = "users/${auth.currentUser?.uid ?: "unauth"}/dailySummaries/$dateKey"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid)
                .collection("dailySummaries").document(dateKey)
            docPath = docRef.path
            val exists = docRef.get().await().exists()
            if (exists) {
                docRef.update(summary.toUpdateMap()).await()
            } else {
                docRef.set(summary.toCreateMap(uid, dateKey)).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateUserBalanceAndMode(
        simulationBalance: Double,
        executionMode: ExecutionMode,
        markBackupTimestamp: Boolean = false
    ): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauth"}"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid)
            docPath = docRef.path
            val payload = buildMap<String, Any> {
                put("simulationBalance", simulationBalance.coerceIn(0.0, 10_000_000.0))
                put("executionMode", executionMode.name)
                if (markBackupTimestamp) {
                    put("lastBackupAt", FieldValue.serverTimestamp())
                }
                put("updatedAt", FieldValue.serverTimestamp())
            }
            docRef.update(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    private fun logStreamErrorIfActive(
        error: Throwable,
        uid: String,
        operationType: OperationType,
        path: String
    ) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, operationType, path)
        }
    }

    private companion object {
        val VALID_ID_REGEX = Regex("^[a-zA-Z0-9_\\-]+$")
    }
}
