package dev.cmfopen.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone as P
import dev.cmfopen.protocol.Payloads
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The phone's starred (favourite) contacts on the watch's contacts list, up to 20. Sent on
 * connect and again when the address book changes. Nothing runs while the watch is disconnected,
 * and a watch app that draws its own menu owns the list until it stops.
 */
class ContactsSync(private val context: Context, private val prefs: Prefs, private val watch: WatchManager) {
    private var pending: Job? = null
    private var observing = false

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = syncSoon(5_000)
    }

    val permitted get() = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Called on connect, when the setting changes, and when a watch app releases the list. */
    fun syncSoon(delayMs: Long = 1_000) {
        if (!prefs.contactsEnabled || !permitted) return
        if (!observing) {
            context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
            observing = true
        }
        pending?.cancel()
        pending = watch.scope.launch {
            delay(delayMs) // debounce bursts of address-book changes
            if (watch.connected && !watch.apps.active) syncOnce()
        }
    }

    suspend fun syncOnce(): Int {
        val list = favourites()
        watch.withSession("contacts") { it.setContacts(list) } ?: return 0
        DebugLog.info("contacts: sent ${list.size} favourites")
        return list.size
    }

    /** Starred contacts with a phone number, primary number first, alphabetical. */
    fun favourites(): List<Payloads.Contact> {
        if (!permitted) return emptyList()
        val seen = LinkedHashMap<Long, Payloads.Contact>()
        context.contentResolver.query(
            P.CONTENT_URI,
            arrayOf(P.CONTACT_ID, P.DISPLAY_NAME, P.NUMBER, P.IS_SUPER_PRIMARY),
            "${P.STARRED} = 1",
            null,
            "${P.DISPLAY_NAME} COLLATE NOCASE ASC, ${P.IS_SUPER_PRIMARY} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (id in seen) continue
                val name = c.getString(1) ?: continue
                val number = c.getString(2)?.filter { it.isDigit() || it == '+' } ?: continue
                seen[id] = Payloads.Contact(name, number)
            }
        }
        return seen.values.take(Payloads.CONTACTS_MAX)
    }
}
