package tech.torlando.eridanus.rns

import android.content.Context
import tech.torlando.eridanus.rns.py.PyRnsBackend

fun provideRnsBackend(@Suppress("UNUSED_PARAMETER") context: Context): RnsBackend = PyRnsBackend()
