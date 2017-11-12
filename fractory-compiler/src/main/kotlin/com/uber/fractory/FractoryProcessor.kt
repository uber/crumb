/*
 * Copyright (c) 2017. Uber Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.uber.fractory

import com.google.auto.common.AnnotationMirrors
import com.google.auto.service.AutoService
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonAdapter.Factory
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import com.uber.fractory.annotations.FractoryConsumable
import com.uber.fractory.annotations.FractoryConsumer
import com.uber.fractory.annotations.FractoryProducer
import com.uber.fractory.annotations.FractoryQualifier
import com.uber.fractory.extensions.FractoryConsumerExtension
import com.uber.fractory.extensions.FractoryProducerExtension
import com.uber.fractory.extensions.GsonSupport
import com.uber.fractory.extensions.MoshiSupport
import com.uber.fractory.packaging.GenerationalClassUtil
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.TypeElement
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic
import javax.tools.Diagnostic.Kind.ERROR
import javax.tools.Diagnostic.Kind.WARNING
import kotlin.properties.Delegates

typealias ExtensionKey = String
typealias ConsumerMetadata = Map<String, String>
typealias ProducerMetadata = Map<String, String>
typealias MoshiTypes = com.squareup.moshi.Types

/**
 * Generates a Fractory that adapts all [FractoryConsumer] and [FractoryProducer] annotated types.
 */
@AutoService(Processor::class)
class FractoryProcessor : AbstractProcessor() {

  private val fractoryAdapter = Moshi.Builder()
      .add(FractoryAdapter.FACTORY)
      .build()
      .adapter(FractoryModel::class.java)

  private val producerExtensions = listOf<FractoryProducerExtension>(GsonSupport(), MoshiSupport())
  private val consumerExtensions = listOf<FractoryConsumerExtension>(GsonSupport(), MoshiSupport())

  private lateinit var typeUtils: Types
  private lateinit var elementUtils: Elements

  override fun getSupportedAnnotationTypes(): Set<String> {
    return (listOf(FractoryConsumer::class, FractoryProducer::class, FractoryConsumable::class)
        + producerExtensions.flatMap { it.supportedProducerAnnotations() }.map { it::class }
        + consumerExtensions.flatMap { it.supportedConsumerAnnotations() }.map { it::class })
        .map { it.java.name }
        .toSet()
  }

  override fun getSupportedSourceVersion(): SourceVersion {
    return SourceVersion.latestSupported()
  }

  @Synchronized override fun init(processingEnv: ProcessingEnvironment) {
    super.init(processingEnv)
    typeUtils = processingEnv.typeUtils
    elementUtils = processingEnv.elementUtils
  }

  override fun process(annotations: Set<TypeElement>, roundEnv: RoundEnvironment): Boolean {
    processProducers(roundEnv)
    processConsumers(roundEnv)

    // return true, we're the only ones that care about these annotations.
    return true
  }

  private fun processProducers(roundEnv: RoundEnvironment) {
    val adaptorFactories = roundEnv.findElementsAnnotatedWith<FractoryProducer>()
    adaptorFactories
        .cast<TypeElement>()
        .forEach { producer ->
          val context = FractoryContext(processingEnv, roundEnv)
          val qualifierAnnotations = AnnotationMirrors.getAnnotatedAnnotations(producer,
              FractoryQualifier::class.java)
          val applicableExtensions = producerExtensions
              .filter {
                it.isProducerApplicable(context, producer, qualifierAnnotations)
              }

          if (applicableExtensions.isEmpty()) {
            error(producer, """
              |No extensions applicable for the given @FractoryProducer-annotated element
              |Detected producers: [${adaptorFactories.joinToString { it.toString() }}]
              |Available extensions: [${producerExtensions.joinToString()}]
              """.trimMargin())
            return@forEach
          }

          val globalExtras = mutableMapOf<ExtensionKey, ProducerMetadata>()
          applicableExtensions.forEach { extension ->
            val extras = extension.produce(context, producer, qualifierAnnotations)
            globalExtras.put(extension.key(), extras)
          }
          val adapterName = producer.classNameOf()
          val packageName = producer.packageName()
          // Write metadata to resources for consumers to pick up
          val fractoryModel = FractoryModel("$packageName.$adapterName", globalExtras)
          val json = fractoryAdapter.toJson(fractoryModel)
          GenerationalClassUtil.writeIntermediateFile(processingEnv,
              packageName,
              adapterName + GenerationalClassUtil.ExtensionFilter.FRACTORY.extension,
              json)
        }
  }

  private fun processConsumers(roundEnv: RoundEnvironment) {
    val consumers = roundEnv.findElementsAnnotatedWith<FractoryConsumer>()
    if (consumers.isEmpty()) {
      return
    }

    // Load the producerMetadata from the classpath
    val producerMetadataBlobs = GenerationalClassUtil.loadObjects<String>(
        GenerationalClassUtil.ExtensionFilter.FRACTORY,
        processingEnv)

    if (producerMetadataBlobs.isEmpty()) {
      message(WARNING, consumers.iterator().next(),
          "No @FractoryProducer metadata found on the classpath.")
      return
    }

    val producerMetadata = producerMetadataBlobs.map { fractoryAdapter.fromJson(it)!! }
    val extrasByExtension = mutableMapOf<ExtensionKey, MutableSet<ConsumerMetadata>>()
    producerMetadata.map { it.extras }
        .flatMap { it.entries }
        .forEach {
          extrasByExtension.getOrPut(it.key, { mutableSetOf() }).add(it.value)
        }

    // Iterate through the consumers to generate their implementations.
    consumers.cast<TypeElement>()
        .forEach { consumer ->
          val context = FractoryContext(processingEnv, roundEnv)
          val qualifierAnnotations = AnnotationMirrors.getAnnotatedAnnotations(consumer,
              FractoryQualifier::class.java)
          consumerExtensions.forEach { extension ->
            if (extension.isConsumerApplicable(context, consumer, qualifierAnnotations)) {
              val extras = extrasByExtension[extension.key()] ?: setOf<ConsumerMetadata>()
              extension.consume(context, consumer, extras)
            }
          }
        }
  }

  private fun error(message: String, vararg args: Any) {
    error(null, message, *args)
  }

  private fun error(element: Element?, message: String, vararg args: Any) {
    message(ERROR, element, message, *args)
  }

  private fun message(kind: Diagnostic.Kind, element: Element?, message: String, vararg args: Any) {
    var localMessage = message
    if (args.isNotEmpty()) {
      localMessage = String.format(message, *args)
    }

    if (element == null) {
      processingEnv.messager.printMessage(kind, localMessage)
    } else {
      processingEnv.messager.printMessage(kind, localMessage, element)
    }
  }
}

internal class FractoryAdapter(moshi: Moshi) : JsonAdapter<FractoryModel>() {

  companion object {
    private val NAMES = arrayOf("name", "extras")
    private val OPTIONS = JsonReader.Options.of(*NAMES)

    val FACTORY = Factory { type, _, moshi ->
      when (type) {
        FractoryModel::class.java -> FractoryAdapter(moshi)
        else -> null
      }
    }
  }

  private val extrasAdapter = moshi.adapter<Map<ExtensionKey, ConsumerMetadata>>(
      MoshiTypes.newParameterizedType(
          Map::class.java,
          String::class.java,
          MoshiTypes.newParameterizedType(Map::class.java,
              String::class.java,
              String::class.java)))

  override fun fromJson(reader: JsonReader): FractoryModel {
    var name by Delegates.notNull<ExtensionKey>()
    var extras by Delegates.notNull<Map<ExtensionKey, ConsumerMetadata>>()
    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.selectName(OPTIONS)) {
        0 -> name = reader.nextString()
        1 -> extras = extrasAdapter.fromJson(reader)!!
        else -> throw IllegalArgumentException("Unrecognized name: ${reader.nextName()}")
      }
    }
    reader.endObject()
    return FractoryModel(name, extras)
  }

  override fun toJson(writer: com.squareup.moshi.JsonWriter, model: FractoryModel?) {
    model?.run {
      writer.beginObject()
          .name("name")
          .value(name)
          .name("extras")
      extrasAdapter.toJson(writer, extras)
      writer.endObject()
    }
  }
}

class FractoryContext(val processingEnv: ProcessingEnvironment,
    val roundEnv: RoundEnvironment)

/** Return a list of elements annotated with [T]. */
internal inline fun <reified T : Annotation> RoundEnvironment.findElementsAnnotatedWith(): Set<Element>
    = getElementsAnnotatedWith(T::class.java)

@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
internal inline fun <T> Iterable<*>.cast() = map { it as T }

internal data class FractoryModel(
    val name: String,
    val extras: Map<ExtensionKey, ConsumerMetadata>)

fun String.asPackageAndName(): Pair<String, String> {
  val lastIndex = lastIndexOf(".")
  val modelPackage = substring(0, lastIndex)
  val modelSimpleName = substring(lastIndex + 1)
  return Pair(modelPackage, modelSimpleName)
}
