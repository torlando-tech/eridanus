// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.rns

import android.content.Context
import tech.torlando.eridanus.rns.kt.KtRnsBackend

fun provideRnsBackend(@Suppress("UNUSED_PARAMETER") context: Context): RnsBackend = KtRnsBackend()
