package org.fossify.messages.extensions
import android.content.Context
class Message(val senderPhoneNumber: String = "123", val threadId: Long = 4)
class Contact(val name: String = "name", val photoUri: String? = null)
class Dao(private val c: Context) {
    fun getMessageWithId(id: Long): Message? {
        c.lookupError?.let { throw it }
        return if(c.present) Message() else null
    }
    fun updateSenderDisplay(id: Long, address: String, name: String, photo: String) { c.writes++ }
}
val Context.messagesDB get() = Dao(this)
val Context.conversationsDB get() = Dao(this)
fun Context.getNameAndPhotoFromPhoneNumber(address: String): Contact {
    contactError?.let { throw it }
    return Contact()
}
class Database(private val c: Context) {
    fun runInTransaction(action: () -> Unit) {
        c.transactionError?.let { throw it }
        action()
    }
}
fun Context.getMessagesDB() = Database(this)
