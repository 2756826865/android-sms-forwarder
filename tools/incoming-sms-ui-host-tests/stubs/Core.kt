package androidx.core.content
import android.content.Context
object ContextCompat { fun checkSelfPermission(c: Context, p: String) = if(c.permission) 0 else -1 }
