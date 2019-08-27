/*
 * Copyright 2015 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.uber.crumb.internal.wire

import com.uber.crumb.internal.wire.ProtoWriter.Companion.decodeZigZag32
import com.uber.crumb.internal.wire.ProtoWriter.Companion.decodeZigZag64
import com.uber.crumb.internal.wire.ProtoWriter.Companion.encodeZigZag32
import com.uber.crumb.internal.wire.ProtoWriter.Companion.encodeZigZag64
import com.uber.crumb.internal.wire.ProtoWriter.Companion.int32Size
import com.uber.crumb.internal.wire.ProtoWriter.Companion.varint32Size
import com.uber.crumb.internal.wire.ProtoWriter.Companion.varint64Size
import com.uber.crumb.internal.wire.internal.RuntimeMessageAdapter
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString
import okio.buffer
import okio.sink
import okio.source
import okio.utf8Size
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.reflect.KClass

internal abstract class ProtoAdapter<E> constructor(
  internal val fieldEncoding: FieldEncoding,
  val type: KClass<*>?
) {
  internal var packedAdapter: ProtoAdapter<List<E>>? = null
  internal var repeatedAdapter: ProtoAdapter<List<E>>? = null

  constructor(fieldEncoding: FieldEncoding, type: Class<*>): this(fieldEncoding, type.kotlin)

  abstract fun redact(value: E): E

  abstract fun encodedSize(value: E): Int

  open fun encodedSizeWithTag(tag: Int, value: E?): Int {
    return commonEncodedSizeWithTag(tag, value)
  }

  @Throws(IOException::class)
  abstract fun encode(writer: ProtoWriter, value: E)

  @Throws(IOException::class)
  open fun encodeWithTag(writer: ProtoWriter, tag: Int, value: E?) {
    commonEncodeWithTag(writer, tag, value)
  }

  @Throws(IOException::class)
  fun encode(sink: BufferedSink, value: E) {
    commonEncode(sink, value)
  }

  fun encode(value: E): ByteArray {
    return commonEncode(value)
  }

  @Throws(IOException::class)
  fun encode(stream: OutputStream, value: E) {
    val buffer = stream.sink().buffer()
    encode(buffer, value)
    buffer.emit()
  }

  @Throws(IOException::class)
  abstract fun decode(reader: ProtoReader): E

  @Throws(IOException::class)
  fun decode(bytes: ByteArray): E {
    return commonDecode(bytes)
  }

  @Throws(IOException::class)
  fun decode(bytes: ByteString): E {
    return commonDecode(bytes)
  }

  @Throws(IOException::class)
  fun decode(source: BufferedSource): E {
    return commonDecode(source)
  }

  @Throws(IOException::class)
  fun decode(stream: InputStream): E = decode(stream.source().buffer())

  open fun toString(value: E): String {
    return commonToString(value)
  }

  internal fun withLabel(label: WireField.Label): ProtoAdapter<*> {
    return commonWithLabel(label)
  }

  fun asPacked(): ProtoAdapter<List<E>> {
    return commonAsPacked()
  }

  fun asRepeated(): ProtoAdapter<List<E>> {
    return commonAsRepeated()
  }

  class EnumConstantNotFoundException constructor(
    @JvmField val value: Int,
    type: KClass<*>?
  ) : IllegalArgumentException("Unknown enum tag $value for ${type?.java?.name}") {
    constructor(value: Int, type: Class<*>): this(value, type.kotlin)
  }

  companion object {
    @JvmStatic fun <K, V> newMapAdapter(
      keyAdapter: ProtoAdapter<K>,
      valueAdapter: ProtoAdapter<V>
    ): ProtoAdapter<Map<K, V>> {
      return commonNewMapAdapter(keyAdapter, valueAdapter)
    }

    /** Creates a new proto adapter for `type`. */
    @JvmStatic fun <M : Message<M, B>, B : Message.Builder<M, B>> newMessageAdapter(
      type: Class<M>
    ): ProtoAdapter<M> {
      return RuntimeMessageAdapter.create(type)
    }

    /** Creates a new proto adapter for `type`. */
    @JvmStatic fun <E : WireEnum> newEnumAdapter(type: Class<E>): EnumAdapter<E> {
      return RuntimeEnumAdapter(type)
    }

    /** Returns the adapter for the type of `Message`. */
    @JvmStatic fun <M : Message<*, *>> get(message: M): ProtoAdapter<M> {
      return get(message.javaClass)
    }

    /** Returns the adapter for `type`. */
    @JvmStatic fun <M> get(type: Class<M>): ProtoAdapter<M> {
      try {
        return type.getField("ADAPTER").get(null) as ProtoAdapter<M>
      } catch (e: IllegalAccessException) {
        throw IllegalArgumentException("failed to access ${type.name}#ADAPTER", e)
      } catch (e: NoSuchFieldException) {
        throw IllegalArgumentException("failed to access ${type.name}#ADAPTER", e)
      }
    }

    /**
     * Returns the adapter for a given `adapterString`. `adapterString` is specified on a proto
     * message field's [WireField] annotation in the form
     * `com.uber.crumb.internal.wire.protos.person.Person#ADAPTER`.
     */
    @JvmStatic fun get(adapterString: String): ProtoAdapter<*> {
      try {
        val hash = adapterString.indexOf('#')
        val className = adapterString.substring(0, hash)
        val fieldName = adapterString.substring(hash + 1)
        return Class.forName(className).getField(fieldName).get(null) as ProtoAdapter<Any>
      } catch (e: IllegalAccessException) {
        throw IllegalArgumentException("failed to access $adapterString", e)
      } catch (e: NoSuchFieldException) {
        throw IllegalArgumentException("failed to access $adapterString", e)
      } catch (e: ClassNotFoundException) {
        throw IllegalArgumentException("failed to access $adapterString", e)
      }
    }

    @JvmField val BOOL: ProtoAdapter<Boolean> = commonBool()
    @JvmField val INT32: ProtoAdapter<Int> = commonInt32()
    @JvmField val UINT32: ProtoAdapter<Int> = commonUint32()
    @JvmField val SINT32: ProtoAdapter<Int> = commonSint32()
    @JvmField val FIXED32: ProtoAdapter<Int> = commonFixed32()
    @JvmField val SFIXED32: ProtoAdapter<Int> = commonSfixed32()
    @JvmField val INT64: ProtoAdapter<Long> = commonInt64()
    @JvmField val UINT64: ProtoAdapter<Long> = commonUint64()
    @JvmField val SINT64: ProtoAdapter<Long> = commonSint64()
    @JvmField val FIXED64: ProtoAdapter<Long> = commonFixed64()
    @JvmField val SFIXED64: ProtoAdapter<Long> = commonSfixed64()
    @JvmField val FLOAT: ProtoAdapter<Float> = commonFloat()
    @JvmField val DOUBLE: ProtoAdapter<Double> = commonDouble()
    @JvmField val BYTES: ProtoAdapter<ByteString> = commonBytes()
    @JvmField val STRING: ProtoAdapter<String> = commonString()
  }
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <E> ProtoAdapter<E>.commonEncodedSizeWithTag(tag: Int, value: E?): Int {
  if (value == null) return 0
  var size = encodedSize(value)
  if (fieldEncoding == FieldEncoding.LENGTH_DELIMITED) {
    size += varint32Size(size)
  }
  return size + ProtoWriter.tagSize(tag)
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <E> ProtoAdapter<E>.commonEncodeWithTag(
    writer: ProtoWriter,
    tag: Int,
    value: E?
) {
  if (value == null) return
  writer.writeTag(tag, fieldEncoding)
  if (fieldEncoding == FieldEncoding.LENGTH_DELIMITED) {
    writer.writeVarint32(encodedSize(value))
  }
  encode(writer, value)
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <E> ProtoAdapter<E>.commonEncode(sink: BufferedSink, value: E) {
  encode(ProtoWriter(sink), value)
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <E> ProtoAdapter<E>.commonEncode(value: E): ByteArray {
  val buffer = Buffer()
  encode(buffer, value)
  return buffer.readByteArray()
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <E> ProtoAdapter<E>.commonDecode(bytes: ByteArray): E {
  return decode(Buffer().write(bytes))
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <E> ProtoAdapter<E>.commonDecode(bytes: ByteString): E {
  return decode(Buffer().write(bytes))
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <E> ProtoAdapter<E>.commonDecode(source: BufferedSource): E {
  return decode(ProtoReader(source))
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <E> commonToString(value: E): String = value.toString()

@Suppress("NOTHING_TO_INLINE")
internal inline fun <E> ProtoAdapter<E>.commonWithLabel(label: WireField.Label): ProtoAdapter<*> {
  if (label.isRepeated) {
    return if (label.isPacked) asPacked() else asRepeated()
  }
  return this
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <E> ProtoAdapter<E>.commonAsPacked(): ProtoAdapter<List<E>> {
  return packedAdapter ?: commonCreatePacked().also {
    packedAdapter = it
  }
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <E> ProtoAdapter<E>.commonCreatePacked(): ProtoAdapter<List<E>> {
  require(fieldEncoding != FieldEncoding.LENGTH_DELIMITED) {
    "Unable to pack a length-delimited type."
  }
  val adapter = this
  return object : ProtoAdapter<List<E>>(FieldEncoding.LENGTH_DELIMITED, List::class) {
    @Throws(IOException::class)
    override fun encodeWithTag(writer: ProtoWriter, tag: Int, value: List<E>?) {
      if (value != null && value.isNotEmpty()) {
        super.encodeWithTag(writer, tag, value)
      }
    }

    override fun encodedSize(value: List<E>): Int {
      var size = 0
      for (i in 0 until value.size) {
        size += adapter.encodedSize(value[i])
      }
      return size
    }

    override fun encodedSizeWithTag(tag: Int, value: List<E>?): Int {
      return if (value == null || value.isEmpty()) 0 else super.encodedSizeWithTag(tag, value)
    }

    @Throws(IOException::class)
    override fun encode(writer: ProtoWriter, value: List<E>) {
      for (i in 0 until value.size) {
        adapter.encode(writer, value[i])
      }
    }

    @Throws(IOException::class)
    override fun decode(reader: ProtoReader): List<E> = listOf(adapter.decode(reader))

    override fun redact(value: List<E>): List<E> = emptyList()
  }
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <E> ProtoAdapter<E>.commonAsRepeated(): ProtoAdapter<List<E>> {
  return repeatedAdapter ?: commonCreateRepeated().also {
    repeatedAdapter = it
  }
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <E> ProtoAdapter<E>.commonCreateRepeated(): ProtoAdapter<List<E>> {
  val adapter = this
  return object : ProtoAdapter<List<E>>(fieldEncoding, List::class) {
    override fun encodedSize(value: List<E>): Int {
      throw UnsupportedOperationException("Repeated values can only be sized with a tag.")
    }

    override fun encodedSizeWithTag(tag: Int, value: List<E>?): Int {
      if (value == null) return 0
      var size = 0
      for (i in 0 until value.size) {
        size += adapter.encodedSizeWithTag(tag, value[i])
      }
      return size
    }

    override fun encode(writer: ProtoWriter, value: List<E>) {
      throw UnsupportedOperationException("Repeated values can only be encoded with a tag.")
    }

    @Throws(IOException::class)
    override fun encodeWithTag(writer: ProtoWriter, tag: Int, value: List<E>?) {
      if (value == null) return
      for (i in 0 until value.size) {
        adapter.encodeWithTag(writer, tag, value[i])
      }
    }

    @Throws(IOException::class)
    override fun decode(reader: ProtoReader): List<E> = listOf(adapter.decode(reader))

    override fun redact(value: List<E>): List<E> = emptyList()
  }
}

internal class MapProtoAdapter<K, V> internal constructor(
    keyAdapter: ProtoAdapter<K>,
    valueAdapter: ProtoAdapter<V>
) : ProtoAdapter<Map<K, V>>(FieldEncoding.LENGTH_DELIMITED, Map::class) {
  private val entryAdapter = MapEntryProtoAdapter(keyAdapter, valueAdapter)

  override fun encodedSize(value: Map<K, V>): Int {
    throw UnsupportedOperationException("Repeated values can only be sized with a tag.")
  }

  override fun encodedSizeWithTag(tag: Int, value: Map<K, V>?): Int {
    if (value == null) return 0
    var size = 0
    for (entry in value.entries) {
      size += entryAdapter.encodedSizeWithTag(tag, entry)
    }
    return size
  }

  override fun encode(writer: ProtoWriter, value: Map<K, V>) {
    throw UnsupportedOperationException("Repeated values can only be encoded with a tag.")
  }

  @Throws(IOException::class)
  override fun encodeWithTag(writer: ProtoWriter, tag: Int, value: Map<K, V>?) {
    if (value == null) return
    for (entry in value.entries) {
      entryAdapter.encodeWithTag(writer, tag, entry)
    }
  }

  @Throws(IOException::class)
  override fun decode(reader: ProtoReader): Map<K, V> {
    var key: K? = null
    var value: V? = null

    val token = reader.beginMessage()
    while (true) {
      val tag = reader.nextTag()
      if (tag == -1) break
      when (tag) {
        1 -> key = entryAdapter.keyAdapter.decode(reader)
        2 -> value = entryAdapter.valueAdapter.decode(reader)
        // Ignore unknown tags in map entries.
      }
    }
    reader.endMessageAndGetUnknownFields(token)

    check(key != null) { "Map entry with null key" }
    check(value != null) { "Map entry with null value" }
    return mapOf(key to value)
  }

  override fun redact(value: Map<K, V>): Map<K, V> = emptyMap()
}

private class MapEntryProtoAdapter<K, V> internal constructor(
    internal val keyAdapter: ProtoAdapter<K>,
    internal val valueAdapter: ProtoAdapter<V>
) : ProtoAdapter<Map.Entry<K, V>>(FieldEncoding.LENGTH_DELIMITED, Map.Entry::class) {

  override fun encodedSize(value: Map.Entry<K, V>): Int {
    return keyAdapter.encodedSizeWithTag(1, value.key) +
        valueAdapter.encodedSizeWithTag(2, value.value)
  }

  @Throws(IOException::class)
  override fun encode(writer: ProtoWriter, value: Map.Entry<K, V>) {
    keyAdapter.encodeWithTag(writer, 1, value.key)
    valueAdapter.encodeWithTag(writer, 2, value.value)
  }

  override fun decode(reader: ProtoReader): Map.Entry<K, V> {
    throw UnsupportedOperationException()
  }

  override fun redact(value: Map.Entry<K, V>): Map.Entry<K, V> {
    throw UnsupportedOperationException()
  }
}

private const val FIXED_BOOL_SIZE = 1
private const val FIXED_32_SIZE = 4
private const val FIXED_64_SIZE = 8

@Suppress("NOTHING_TO_INLINE")
internal inline fun <K, V> commonNewMapAdapter(
    keyAdapter: ProtoAdapter<K>,
    valueAdapter: ProtoAdapter<V>
): ProtoAdapter<Map<K, V>> {
  return MapProtoAdapter(keyAdapter, valueAdapter)
}

internal fun commonBool(): ProtoAdapter<Boolean> = object : ProtoAdapter<Boolean>(
    FieldEncoding.VARINT,
    Boolean::class
) {
  override fun encodedSize(value: Boolean): Int = FIXED_BOOL_SIZE

  @Throws(IOException::class)
  override fun encode(writer: ProtoWriter, value: Boolean) {
    writer.writeVarint32(if (value) 1 else 0)
  }

  @Throws(IOException::class)
  override fun decode(reader: ProtoReader): Boolean = when (val value = reader.readVarint32()) {
    0 -> false
    1 -> true
    else -> throw IOException("Invalid boolean value 0x%02x".format(value))
  }

  override fun redact(value: Boolean): Boolean = throw UnsupportedOperationException()
}
internal fun commonInt32(): ProtoAdapter<Int> = object : ProtoAdapter<Int>(
    FieldEncoding.VARINT,
    Int::class
) {
  override fun encodedSize(value: Int): Int = int32Size(value)

  @Throws(IOException::class)
  override fun encode(writer: ProtoWriter, value: Int) {
    writer.writeSignedVarint32(value)
  }

  @Throws(IOException::class)
  override fun decode(reader: ProtoReader): Int = reader.readVarint32()

  override fun redact(value: Int): Int = throw UnsupportedOperationException()
}
internal fun commonUint32(): ProtoAdapter<Int> = object : ProtoAdapter<Int>(
    FieldEncoding.VARINT,
    Int::class
) {
  override fun encodedSize(value: Int): Int = varint32Size(value)

  @Throws(IOException::class)
  override fun encode(writer: ProtoWriter, value: Int) {
    writer.writeVarint32(value)
  }

  @Throws(IOException::class)
  override fun decode(reader: ProtoReader): Int = reader.readVarint32()

  override fun redact(value: Int): Int = throw UnsupportedOperationException()
}
internal fun commonSint32(): ProtoAdapter<Int> = object : ProtoAdapter<Int>(
    FieldEncoding.VARINT,
    Int::class
) {
  override fun encodedSize(value: Int): Int = varint32Size(encodeZigZag32(value))

  @Throws(IOException::class)
  override fun encode(writer: ProtoWriter, value: Int) {
    writer.writeVarint32(encodeZigZag32(value))
  }

  @Throws(IOException::class)
  override fun decode(reader: ProtoReader): Int = decodeZigZag32(reader.readVarint32())

  override fun redact(value: Int): Int = throw UnsupportedOperationException()
}
internal fun commonFixed32(): ProtoAdapter<Int> = object : ProtoAdapter<Int>(
    FieldEncoding.FIXED32,
    Int::class
) {
  override fun encodedSize(value: Int): Int = FIXED_32_SIZE

  @Throws(IOException::class)
  override fun encode(writer: ProtoWriter, value: Int) {
    writer.writeFixed32(value)
  }

  @Throws(IOException::class)
  override fun decode(reader: ProtoReader): Int = reader.readFixed32()

  override fun redact(value: Int): Int = throw UnsupportedOperationException()
}
internal fun commonSfixed32() = commonFixed32()
internal fun commonInt64(): ProtoAdapter<Long> = object : ProtoAdapter<Long>(
    FieldEncoding.VARINT,
    Long::class
) {
  override fun encodedSize(value: Long): Int = varint64Size(value)

  @Throws(IOException::class)
  override fun encode(writer: ProtoWriter, value: Long) {
    writer.writeVarint64(value)
  }

  @Throws(IOException::class)
  override fun decode(reader: ProtoReader): Long = reader.readVarint64()

  override fun redact(value: Long): Long = throw UnsupportedOperationException()
}
/**
 * Like INT64, but negative longs are interpreted as large positive values, and encoded that way
 * in JSON.
 */
internal fun commonUint64(): ProtoAdapter<Long> = object : ProtoAdapter<Long>(
    FieldEncoding.VARINT,
    Long::class
) {
  override fun encodedSize(value: Long): Int = varint64Size(value)

  @Throws(IOException::class)
  override fun encode(writer: ProtoWriter, value: Long) {
    writer.writeVarint64(value)
  }

  @Throws(IOException::class)
  override fun decode(reader: ProtoReader): Long = reader.readVarint64()

  override fun redact(value: Long): Long = throw UnsupportedOperationException()
}
internal fun commonSint64(): ProtoAdapter<Long> = object : ProtoAdapter<Long>(
    FieldEncoding.VARINT,
    Long::class
) {
  override fun encodedSize(value: Long): Int = varint64Size(encodeZigZag64(value))

  @Throws(IOException::class)
  override fun encode(writer: ProtoWriter, value: Long) {
    writer.writeVarint64(encodeZigZag64(value))
  }

  @Throws(IOException::class)
  override fun decode(reader: ProtoReader): Long = decodeZigZag64(reader.readVarint64())

  override fun redact(value: Long): Long = throw UnsupportedOperationException()
}
internal fun commonFixed64(): ProtoAdapter<Long> = object : ProtoAdapter<Long>(
    FieldEncoding.FIXED64,
    Long::class
) {
  override fun encodedSize(value: Long): Int = FIXED_64_SIZE

  @Throws(IOException::class)
  override fun encode(writer: ProtoWriter, value: Long) {
    writer.writeFixed64(value)
  }

  @Throws(IOException::class)
  override fun decode(reader: ProtoReader): Long = reader.readFixed64()

  override fun redact(value: Long): Long = throw UnsupportedOperationException()
}
internal fun commonSfixed64() = commonFixed64()
internal fun commonFloat(): ProtoAdapter<Float> = object : ProtoAdapter<Float>(
    FieldEncoding.FIXED32,
    Float::class
) {
  override fun encodedSize(value: Float): Int = FIXED_32_SIZE

  @Throws(IOException::class)
  override fun encode(writer: ProtoWriter, value: Float) {
    writer.writeFixed32(value.toBits())
  }

  @Throws(IOException::class)
  override fun decode(reader: ProtoReader): Float {
    return Float.fromBits(reader.readFixed32())
  }

  override fun redact(value: Float): Float = throw UnsupportedOperationException()
}
internal fun commonDouble(): ProtoAdapter<Double> = object : ProtoAdapter<Double>(
    FieldEncoding.FIXED64,
    Double::class
) {
  override fun encodedSize(value: Double): Int = FIXED_64_SIZE

  @Throws(IOException::class)
  override fun encode(writer: ProtoWriter, value: Double) {
    writer.writeFixed64(value.toBits())
  }

  @Throws(IOException::class)
  override fun decode(reader: ProtoReader): Double {
    return Double.fromBits(reader.readFixed64())
  }

  override fun redact(value: Double): Double = throw UnsupportedOperationException()
}
internal fun commonString(): ProtoAdapter<String> = object : ProtoAdapter<String>(
    FieldEncoding.LENGTH_DELIMITED,
    String::class
) {
  override fun encodedSize(value: String): Int = value.utf8Size().toInt()

  @Throws(IOException::class)
  override fun encode(writer: ProtoWriter, value: String) {
    writer.writeString(value)
  }

  @Throws(IOException::class)
  override fun decode(reader: ProtoReader): String = reader.readString()

  override fun redact(value: String): String = throw UnsupportedOperationException()
}
internal fun commonBytes(): ProtoAdapter<ByteString> = object : ProtoAdapter<ByteString>(
    FieldEncoding.LENGTH_DELIMITED,
    ByteString::class
) {
  override fun encodedSize(value: ByteString): Int = value.size

  @Throws(IOException::class)
  override fun encode(writer: ProtoWriter, value: ByteString) {
    writer.writeBytes(value)
  }

  @Throws(IOException::class)
  override fun decode(reader: ProtoReader): ByteString = reader.readBytes()

  override fun redact(value: ByteString): ByteString = throw UnsupportedOperationException()
}
