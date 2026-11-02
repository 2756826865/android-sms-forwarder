package android.content
class Context {
    val applicationContext get() = this
    var lookupError: Exception? = null
    var contactError: Exception? = null
    var transactionError: Exception? = null
    var present = true
    var writes = 0
    val failures = mutableMapOf<String, Any>()
    fun getSharedPreferences(name: String, mode: Int) = Preferences(failures)
    companion object { const val MODE_PRIVATE = 0 }
}
class Preferences(private val values: MutableMap<String, Any>) {
    fun edit() = this
    fun putLong(key: String, value: Long) = apply { values[key] = value }
    fun putInt(key: String, value: Int) = apply { values[key] = value }
    fun putString(key: String, value: String) = apply { values[key] = value }
    fun apply() {}
}
