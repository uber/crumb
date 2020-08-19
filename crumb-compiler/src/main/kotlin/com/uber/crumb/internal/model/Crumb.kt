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
package com.uber.crumb.internal.model

import com.uber.crumb.internal.wire.FieldEncoding
import com.uber.crumb.internal.wire.Message
import com.uber.crumb.internal.wire.ProtoAdapter
import com.uber.crumb.internal.wire.ProtoReader
import com.uber.crumb.internal.wire.ProtoWriter
import com.uber.crumb.internal.wire.WireField
import com.uber.crumb.internal.wire.internal.missingRequiredFields
import com.uber.crumb.internal.wire.internal.redactElements
import okio.ByteString
import kotlin.Any
import kotlin.AssertionError
import kotlin.Boolean
import kotlin.Deprecated
import kotlin.DeprecationLevel
import kotlin.Int
import kotlin.Nothing
import kotlin.String
import kotlin.collections.List
import kotlin.jvm.JvmField

internal class Crumb(
  /**
   * The name of the specific data model, usually defined by source producer's canonical name
   */
  @field:WireField(
    tag = 1,
    adapter = "com.squareup.wire.ProtoAdapter#STRING",
    label = WireField.Label.REQUIRED
  )
  val name: String,
  @field:WireField(
    tag = 2,
    adapter = "com.uber.crumb.internal.model.CrumbMetadata#ADAPTER",
    label = WireField.Label.REPEATED
  )
  val extras: List<CrumbMetadata> = emptyList(),
  unknownFields: ByteString = ByteString.EMPTY
) : Message<Crumb, Nothing>(ADAPTER, unknownFields) {
  @Deprecated(
    message = "Shouldn't be used in Kotlin",
    level = DeprecationLevel.HIDDEN
  )
  override fun newBuilder(): Nothing {
    throw AssertionError()
  }

  override fun equals(other: Any?): Boolean {
    if (other === this) return true
    if (other !is Crumb) return false
    return unknownFields == other.unknownFields &&
      name == other.name &&
      extras == other.extras
  }

  override fun hashCode(): Int {
    var result = super.hashCode
    if (result == 0) {
      result = name.hashCode()
      result = result * 37 + extras.hashCode()
      super.hashCode = result
    }
    return result
  }

  override fun toString(): String {
    val result = mutableListOf<String>()
    result += """name=$name"""
    if (extras.isNotEmpty()) result += """extras=$extras"""
    return result.joinToString(prefix = "Crumb{", separator = ", ", postfix = "}")
  }

  fun copy(
    name: String = this.name,
    extras: List<CrumbMetadata> = this.extras,
    unknownFields: ByteString = this.unknownFields
  ): Crumb = Crumb(name, extras, unknownFields)

  companion object {
    @JvmField
    val ADAPTER: ProtoAdapter<Crumb> = object : ProtoAdapter<Crumb>(
      FieldEncoding.LENGTH_DELIMITED,
      Crumb::class
    ) {
      override fun encodedSize(value: Crumb): Int =
        ProtoAdapter.STRING.encodedSizeWithTag(1, value.name) +
          CrumbMetadata.ADAPTER.asRepeated().encodedSizeWithTag(2, value.extras) +
          value.unknownFields.size

      override fun encode(writer: ProtoWriter, value: Crumb) {
        ProtoAdapter.STRING.encodeWithTag(writer, 1, value.name)
        CrumbMetadata.ADAPTER.asRepeated().encodeWithTag(writer, 2, value.extras)
        writer.writeBytes(value.unknownFields)
      }

      override fun decode(reader: ProtoReader): Crumb {
        var name: String? = null
        val extras = mutableListOf<CrumbMetadata>()
        val unknownFields = reader.forEachTag { tag ->
          when (tag) {
            1 -> name = ProtoAdapter.STRING.decode(reader)
            2 -> extras.add(CrumbMetadata.ADAPTER.decode(reader))
            else -> reader.readUnknownField(tag)
          }
        }
        return Crumb(
          name = name ?: throw missingRequiredFields(name, "name"),
          extras = extras,
          unknownFields = unknownFields
        )
      }

      override fun redact(value: Crumb): Crumb = value.copy(
        extras = value.extras.redactElements(CrumbMetadata.ADAPTER),
        unknownFields = ByteString.EMPTY
      )
    }
  }
}
