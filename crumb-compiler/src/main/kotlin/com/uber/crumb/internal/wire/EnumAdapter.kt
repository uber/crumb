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
package com.uber.crumb.internal.wire

import java.io.IOException
import kotlin.reflect.KClass

/**
 * An abstract [ProtoAdapter] that converts values of an enum to and from integers.
 */
internal abstract class EnumAdapter<E : WireEnum> protected constructor(
  type: KClass<E>
) : ProtoAdapter<E>(FieldEncoding.VARINT, type) {
  constructor(type: Class<E>) : this(type.kotlin)

  override fun encodedSize(value: E): Int = commonEncodedSize(value)

  @Throws(IOException::class)
  override fun encode(writer: ProtoWriter, value: E) {
    commonEncode(writer, value)
  }

  @Throws(IOException::class)
  override fun decode(reader: ProtoReader): E = commonDecode(reader, this::fromValue)

  override fun redact(value: E): E = commonRedact(value)

  /**
   * Converts an integer to an enum.
   * Returns null if there is no corresponding enum.
   */
  protected abstract fun fromValue(value: Int): E?
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <E : WireEnum> commonEncodedSize(value: E): Int {
  return ProtoWriter.varint32Size(value.value)
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <E : WireEnum> commonEncode(writer: ProtoWriter, value: E) {
  writer.writeVarint32(value.value)
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <E : WireEnum> EnumAdapter<E>.commonDecode(
  reader: ProtoReader,
  fromValue: (Int) -> E?
): E {
  val value = reader.readVarint32()
  return fromValue(value) ?: throw ProtoAdapter.EnumConstantNotFoundException(value, type)
}

@Suppress("NOTHING_TO_INLINE", "UNUSED_PARAMETER")
internal inline fun <E : WireEnum> commonRedact(value: E): E {
  throw UnsupportedOperationException()
}
