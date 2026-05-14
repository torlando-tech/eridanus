package tech.torlando.eridanus.rns

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import tech.torlando.eridanus.rns.py.PyRnsBackend

fun provideRnsBackend(context: Context): RnsBackend {
    if (!Python.isStarted()) {
        Python.start(AndroidPlatform(context))
    }
    return PyRnsBackend(context)
}
