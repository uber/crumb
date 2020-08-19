/*
 * Copyright 2020. Uber Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.uber.crumb.internal.wire.internal

import java.util.Collections

internal typealias Serializable = java.io.Serializable

internal typealias ObjectStreamException = java.io.ObjectStreamException

internal typealias ProtocolException = java.net.ProtocolException

@Suppress("NOTHING_TO_INLINE") // Syntactic sugar.
internal inline fun <T> MutableList<T>.toUnmodifiableList(): List<T> =
  Collections.unmodifiableList(this)

@Suppress("NOTHING_TO_INLINE") // Syntactic sugar.
internal inline fun <K, V> MutableMap<K, V>.toUnmodifiableMap(): Map<K, V> =
  Collections.unmodifiableMap(this)

@Suppress("NOTHING_TO_INLINE") // Syntactic sugar.
internal inline fun String.format(vararg args: Any?): String = String.format(this, *args)
