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
import okio.ByteString
import kotlin.Any
import kotlin.AssertionError
import kotlin.Boolean
import kotlin.Deprecated
import kotlin.DeprecationLevel
import kotlin.Int
import kotlin.Nothing
import kotlin.String
import kotlin.collections.Map
import kotlin.jvm.JvmField

internal class CrumbMetadata(
  @field:WireField(
    tag = 1,
    adapter = "com.squareup.wire.ProtoAdapter#STRING",
    label = WireField.Label.REQUIRED
  )
  val extensionKey: String,
  @field:WireField(
    tag = 2,
    keyAdapter = "com.squareup.wire.ProtoAdapter#STRING",
    adapter = "com.squareup.wire.ProtoAdapter#STRING"
  )
  val producerMetadata: Map<String, String>,
  unknownFields: ByteString = ByteString.EMPTY
) : Message<CrumbMetadata, Nothing>(ADAPTER, unknownFields) {
  @Deprecated(
    message = "Shouldn't be used in Kotlin",
    level = DeprecationLevel.HIDDEN
  )
  override fun newBuilder(): Nothing {
    throw AssertionError()
  }

  override fun equals(other: Any?): Boolean {
    if (other === this) return true
    if (other !is CrumbMetadata) return false
    return unknownFields == other.unknownFields &&
      extensionKey == other.extensionKey &&
      producerMetadata == other.producerMetadata
  }

  override fun hashCode(): Int {
    var result = super.hashCode
    if (result == 0) {
      result = extensionKey.hashCode()
      result = result * 37 + producerMetadata.hashCode()
      super.hashCode = result
    }
    return result
  }

  override fun toString(): String {
    val result = mutableListOf<String>()
    result += """extensionKey=$extensionKey"""
    if (producerMetadata.isNotEmpty()) result += """producerMetadata=$producerMetadata"""
    return result.joinToString(prefix = "CrumbMetadata{", separator = ", ", postfix = "}")
  }

  fun copy(
    extensionKey: String = this.extensionKey,
    producerMetadata: Map<String, String> = this.producerMetadata,
    unknownFields: ByteString = this.unknownFields
  ): CrumbMetadata = CrumbMetadata(extensionKey, producerMetadata, unknownFields)

  companion object {
    @JvmField
    val ADAPTER: ProtoAdapter<CrumbMetadata> = object : ProtoAdapter<CrumbMetadata>(
      FieldEncoding.LENGTH_DELIMITED,
      CrumbMetadata::class
    ) {
      private val producerMetadataAdapter: ProtoAdapter<Map<String, String>> =
        ProtoAdapter.newMapAdapter(ProtoAdapter.STRING, ProtoAdapter.STRING)

      override fun encodedSize(value: CrumbMetadata): Int =
        ProtoAdapter.STRING.encodedSizeWithTag(1, value.extensionKey) +
          producerMetadataAdapter.encodedSizeWithTag(2, value.producerMetadata) +
          value.unknownFields.size

      override fun encode(writer: ProtoWriter, value: CrumbMetadata) {
        ProtoAdapter.STRING.encodeWithTag(writer, 1, value.extensionKey)
        producerMetadataAdapter.encodeWithTag(writer, 2, value.producerMetadata)
        writer.writeBytes(value.unknownFields)
      }

      override fun decode(reader: ProtoReader): CrumbMetadata {
        var extensionKey: String? = null
        val producerMetadata = mutableMapOf<String, String>()
        val unknownFields = reader.forEachTag { tag ->
          when (tag) {
            1 -> extensionKey = ProtoAdapter.STRING.decode(reader)
            2 -> producerMetadata.putAll(producerMetadataAdapter.decode(reader))
            else -> reader.readUnknownField(tag)
          }
        }
        return CrumbMetadata(
          extensionKey = extensionKey ?: throw missingRequiredFields(extensionKey, "extensionKey"),
          producerMetadata = producerMetadata,
          unknownFields = unknownFields
        )
      }

      override fun redact(value: CrumbMetadata): CrumbMetadata = value.copy(
        unknownFields = ByteString.EMPTY
      )
    }
  }
}
