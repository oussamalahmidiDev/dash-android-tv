package tv.afterglow

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Entity(primaryKeys = ["id", "part"])
internal data class Record(val id: String, val part: Int, val contents: ByteArray)

@Dao
internal interface Records {
    @Query("SELECT * FROM Record WHERE id = :id ORDER BY part")
    suspend fun read(id: String): List<Record>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(rows: List<Record>)

    @Query("DELETE FROM Record WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM Record WHERE id LIKE :pattern ORDER BY id, part")
    suspend fun list(pattern: String): List<Record>
}

@Database(entities = [Record::class], version = 1, exportSchema = false)
internal abstract class AfterglowDatabase : RoomDatabase() {
    abstract fun records(): Records
}

internal class Store(context: Context) {
    private val database = Room.databaseBuilder(context, AfterglowDatabase::class.java, "afterglow.db")
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .build()
    private val records = database.records()
    private val key: SecretKey by lazy {
        val alias = "afterglow-local-v1"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
            }.generateKey()
    }

    private fun encrypt(id: String, value: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(id.toByteArray())
        return cipher.iv + cipher.doFinal(value)
    }

    private fun decrypt(id: String, value: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, value.copyOfRange(0, 12)))
        cipher.updateAAD(id.toByteArray())
        return cipher.doFinal(value, 12, value.size - 12)
    }

    private fun join(parts: List<Record>): String = ByteArrayOutputStream().apply {
        parts.forEach { write(decrypt("${it.id}/${it.part}", it.contents)) }
    }.toByteArray().decodeToString()

    suspend fun read(id: String): String? = records.read(id).takeIf { it.isNotEmpty() }?.let(::join)

    suspend fun list(prefix: String): List<String> = records.list("$prefix%")
        .groupBy(Record::id).values.map(::join)

    suspend fun change(vararg values: Pair<String, String?>) {
        val inserts = values.flatMap { (id, value) ->
            val bytes = value?.encodeToByteArray() ?: return@flatMap emptyList()
            val size = 128 * 1024
            (0 until bytes.size step size).mapIndexed { index, offset ->
                Record(id, index, encrypt("$id/$index", bytes.copyOfRange(offset, minOf(offset + size, bytes.size))))
            }
        }
        database.withTransaction {
            values.forEach { records.delete(it.first) }
            if (inserts.isNotEmpty()) records.put(inserts)
        }
    }

    suspend inline fun <reified T> load(id: String): T? = read(id)?.let { json.decodeFromString<T>(it) }
}
