package dev.motonav.simulator.routing

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import java.security.MessageDigest

class SharedPreferencesUsageLedgerStore(context: Context) : UsageLedgerStore {
    private val preferences = context.getSharedPreferences("routing_usage", Context.MODE_PRIVATE)

    override fun read(): UsageLedgerState? {
        val month = preferences.getString("month", null) ?: return null
        return UsageLedgerState(
            billingMonth = month,
            attempts = preferences.getInt("attempts", 0),
            lastObservedEpochMillis = preferences.getLong("last_observed", 0),
            billingDay = preferences.getString("day", "$month-01") ?: "$month-01",
            dailyAttempts = preferences.getInt("daily_attempts", 0),
        )
    }

    override fun write(state: UsageLedgerState) {
        check(preferences.edit()
            .putString("month", state.billingMonth)
            .putInt("attempts", state.attempts)
            .putLong("last_observed", state.lastObservedEpochMillis)
            .putString("day", state.billingDay)
            .putInt("daily_attempts", state.dailyAttempts)
            .commit()) { "Could not persist route usage ledger" }
    }
}

class SavedDestinationStore(context: Context) {
    private val preferences = context.getSharedPreferences("saved_destinations", Context.MODE_PRIVATE)

    fun load(): DestinationBook {
        val entries = preferences.getStringSet("entries", emptySet()).orEmpty().mapNotNull { encoded ->
            runCatching {
                val fields = encoded.split('|')
                SavedDestination(
                    String(Base64.decode(fields[0], Base64.NO_WRAP), Charsets.UTF_8),
                    GeoPoint(fields[1].toDouble(), fields[2].toDouble()),
                )
            }.getOrNull()
        }.sortedBy { it.label.lowercase() }
        return DestinationBook(entries)
    }

    fun save(book: DestinationBook) {
        val entries = book.all().mapTo(linkedSetOf()) {
            val label = Base64.encodeToString(it.label.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            "$label|${it.point.latitude}|${it.point.longitude}"
        }
        check(preferences.edit().putStringSet("entries", entries).commit()) {
            "Could not persist saved destinations"
        }
    }
}

object AndroidIdentity {
    @Suppress("DEPRECATION")
    fun from(context: Context): AndroidApplicationIdentity {
        val packageInfo = if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } else {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val signature = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.signingInfo?.apkContentsSigners?.singleOrNull()
                ?: error("Expected one app signing certificate")
        } else {
            packageInfo.signatures?.singleOrNull() ?: error("Expected one app signing certificate")
        }
        val digest = MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
            .joinToString("") { "%02X".format(it.toInt() and 0xff) }
        return AndroidApplicationIdentity(context.packageName, digest)
    }
}
