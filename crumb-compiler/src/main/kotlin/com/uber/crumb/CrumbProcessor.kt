/*
 * Copyright (c) 2018. Uber Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.uber.crumb

import com.google.auto.service.AutoService
import com.google.common.annotations.VisibleForTesting
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonAdapter.Factory
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import com.uber.crumb.CrumbProcessor.Companion.OPTION_EXTRA_LOCATIONS
import com.uber.crumb.CrumbProcessor.Companion.OPTION_VERBOSE
import com.uber.crumb.annotations.CrumbConsumable
import com.uber.crumb.annotations.CrumbConsumer
import com.uber.crumb.annotations.CrumbProducer
import com.uber.crumb.annotations.CrumbQualifier
import com.uber.crumb.compiler.api.ConsumerMetadata
import com.uber.crumb.compiler.api.CrumbConsumerExtension
import com.uber.crumb.compiler.api.CrumbContext
import com.uber.crumb.compiler.api.CrumbExtension
import com.uber.crumb.compiler.api.CrumbProducerExtension
import com.uber.crumb.compiler.api.ExtensionKey
import com.uber.crumb.compiler.api.ProducerMetadata
import com.uber.crumb.packaging.CrumbLog
import com.uber.crumb.packaging.CrumbLog.Client
import com.uber.crumb.packaging.GenerationalClassUtil
import java.util.ServiceConfigurationError
import java.util.ServiceLoader
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.annotation.processing.SupportedOptions
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.TypeElement
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic
import javax.tools.Diagnostic.Kind
import javax.tools.Diagnostic.Kind.ERROR
import javax.tools.Diagnostic.Kind.WARNING
import kotlin.properties.Delegates

internal typealias MoshiTypes = com.squareup.moshi.Types

/**
 * Processes all [CrumbConsumer] and [CrumbProducer] annotated types.
 */
@AutoService(Processor::class)
@SupportedOptions(OPTION_VERBOSE, OPTION_EXTRA_LOCATIONS)
class CrumbProcessor : AbstractProcessor {

  companion object {
    /**
     * Option to disable verbose logging
     */
    const val OPTION_VERBOSE = "crumb.options.verbose"

    /**
     * A colon-delimited list of extra locations to search in consumption.
     */
    const val OPTION_EXTRA_LOCATIONS = "crumb.options.extraLocations"
  }

  private val crumbAdapter = Moshi.Builder()
      .add(CrumbAdapter.FACTORY)
      .build()
      .adapter(CrumbModel::class.java)

  // Depending on how this CrumbProcessor was constructed, we might already have a list of
  // extensions when init() is run, or, if `extensions` is null, we have a ClassLoader that will be
  // used to get the list using the ServiceLoader API.
  private var producerExtensions: Set<CrumbProducerExtension>
  private var consumerExtensions: Set<CrumbConsumerExtension>
  private var loaderForExtensions: ClassLoader? = null
  private lateinit var typeUtils: Types
  private lateinit var elementUtils: Elements

  private lateinit var supportedTypes: Set<String>

  constructor() : this(CrumbProcessor::class.java.classLoader)

  @VisibleForTesting
  internal constructor(loaderForExtensions: ClassLoader) : super() {
    this.producerExtensions = setOf()
    this.consumerExtensions = setOf()
    this.loaderForExtensions = loaderForExtensions
  }

  @VisibleForTesting
  constructor(extensions: Iterable<CrumbExtension>) : super() {
    this.loaderForExtensions = null
    producerExtensions = extensions.filterIsInstance(CrumbProducerExtension::class.java).toSet()
    consumerExtensions = extensions.filterIsInstance(CrumbConsumerExtension::class.java).toSet()
  }

  override fun getSupportedAnnotationTypes(): Set<String> {
    return supportedTypes
  }

  override fun getSupportedSourceVersion(): SourceVersion {
    return SourceVersion.latestSupported()
  }

  @Synchronized
  override fun init(processingEnv: ProcessingEnvironment) {
    super.init(processingEnv)
    typeUtils = processingEnv.typeUtils
    elementUtils = processingEnv.elementUtils
    if (processingEnv.options[OPTION_VERBOSE]?.toBoolean() == true) {
      CrumbLog.setDebugLog(true)
      CrumbLog.setClient(object : Client {
        override fun printMessage(kind: Kind, message: String, element: Element?) {
          processingEnv.messager.printMessage(kind, message, element)
        }
      })
    }
    try {
      // ServiceLoader.load returns a lazily-evaluated Iterable, so evaluate it eagerly now
      // to discover any exceptions.
      producerExtensions = ServiceLoader.load(CrumbProducerExtension::class.java,
          loaderForExtensions)
          .iterator().asSequence().toSet()
      consumerExtensions = ServiceLoader.load(CrumbConsumerExtension::class.java,
          loaderForExtensions)
          .iterator().asSequence().toSet()
      val producerAnnotatedAnnotations = producerExtensions.flatMap { it.supportedProducerAnnotations() }
          .filter { it.getAnnotation(CrumbProducer::class.java) != null }
      val consumerAnnotatedAnnotations = consumerExtensions.flatMap { it.supportedConsumerAnnotations() }
          .filter { it.getAnnotation(CrumbConsumer::class.java) != null }
      val baseCrumbAnnotations = listOf(CrumbConsumer::class, CrumbProducer::class,
          CrumbConsumable::class)
          .map { it.java }
      supportedTypes = (baseCrumbAnnotations + producerAnnotatedAnnotations + consumerAnnotatedAnnotations)
          .map { it.name }
          .toSet()
    } catch (t: Throwable) {
      val warning = StringBuilder()
      warning.append(
          "An exception occurred while looking for Crumb extensions. " + "No extensions will function.")
      if (t is ServiceConfigurationError) {
        warning.append(" This may be due to a corrupt jar file in the compiler's classpath.")
      }
      warning.append(" Exception: ")
          .append(t)
      processingEnv.messager.printMessage(Diagnostic.Kind.WARNING, warning.toString(), null)
      producerExtensions = setOf()
      consumerExtensions = setOf()
    }

  }

  override fun process(annotations: Set<TypeElement>, roundEnv: RoundEnvironment): Boolean {
    processProducers(roundEnv)
    processConsumers(roundEnv)

    // return true, we're the only ones that care about these annotations.
    return true
  }

  private fun processProducers(roundEnv: RoundEnvironment) {
    val crumbProducers = roundEnv.findElementsAnnotatedWith<CrumbProducer>()
    val crumbProducerAnnotatedProducers =
        producerExtensions.flatMap { it.supportedProducerAnnotations() }
            .filter { it.getAnnotation(CrumbProducer::class.java) != null }
            .flatMap { roundEnv.getElementsAnnotatedWith(it) }
    val producers = crumbProducers + crumbProducerAnnotatedProducers

    producers
        .cast<TypeElement>()
        .forEach { producer ->
          val context = CrumbContext(processingEnv, roundEnv)
          val qualifierAnnotations = producer.annotatedAnnotations<CrumbQualifier>()
          val producerAnnotations = producer.annotatedAnnotations<CrumbProducer>()
          val crumbAnnotations = producerAnnotations + qualifierAnnotations
          val applicableExtensions = producerExtensions
              .filter { it.isProducerApplicable(context, producer, crumbAnnotations) }

          if (applicableExtensions.isEmpty()) {
            error(producer, """
              |No extensions applicable for the given @CrumbProducer-annotated element
              |Detected producers: [${producers.joinToString { it.toString() }}]
              |Available extensions: [${producerExtensions.joinToString()}]
              """.trimMargin())
            return@forEach
          }

          val globalExtras = mutableMapOf<ExtensionKey, ProducerMetadata>()
          applicableExtensions.forEach { extension ->
            val extras = extension.produce(context, producer, crumbAnnotations)
            globalExtras[extension.key()] = extras
          }
          val adapterName = producer.classNameOf()
          val packageName = producer.packageName()
          // Write metadata to resources for consumers to pick up
          val crumbModel = CrumbModel("$packageName.$adapterName", globalExtras)
          val json = crumbAdapter.toJson(crumbModel)
          GenerationalClassUtil.writeIntermediateFile(processingEnv,
              packageName,
              adapterName + GenerationalClassUtil.ExtensionFilter.CRUMB.extension,
              json)
        }
  }

  private fun processConsumers(roundEnv: RoundEnvironment) {
    val crumbConsumers = roundEnv.findElementsAnnotatedWith<CrumbConsumer>()
    val crumbConsumerAnnotatedConsumers =
        consumerExtensions.flatMap { it.supportedConsumerAnnotations() }
            .filter { it.getAnnotation(CrumbConsumer::class.java) != null }
            .flatMap { roundEnv.getElementsAnnotatedWith(it) }
    val consumers = crumbConsumers + crumbConsumerAnnotatedConsumers
    if (consumers.isEmpty()) {
      return
    }

    // Load the producerMetadata from the classpath
    val producerMetadataBlobs = GenerationalClassUtil.loadObjects<String>(
        GenerationalClassUtil.ExtensionFilter.CRUMB,
        processingEnv)

    if (producerMetadataBlobs.isEmpty()) {
      message(WARNING, consumers.iterator().next(),
          "No @CrumbProducer metadata found on the classpath.")
      return
    }

    val producerMetadata = producerMetadataBlobs.map { crumbAdapter.fromJson(it)!! }
    val metadataByExtension = producerMetadata
        .map { it.extras }
        .flatMap { it.entries }
        .groupBy({ it.key }) { it.value }
        .mapValues { it.value.toSet() }

    // Iterate through the consumers to generate their implementations.
    consumers.cast<TypeElement>()
        .forEach { consumer ->
          val context = CrumbContext(processingEnv, roundEnv)
          val qualifierAnnotations = consumer.annotatedAnnotations<CrumbQualifier>()
          val consumerAnnotations = consumer.annotatedAnnotations<CrumbConsumer>()
          val crumbAnnotations = consumerAnnotations + qualifierAnnotations
          consumerExtensions.forEach { extension ->
            if (extension.isConsumerApplicable(context, consumer, crumbAnnotations)) {
              val metadata = metadataByExtension[extension.key()].orEmpty()
              extension.consume(context, consumer, crumbAnnotations, metadata)
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

internal class CrumbAdapter(moshi: Moshi) : JsonAdapter<CrumbModel>() {

  companion object {
    private val NAMES = arrayOf("name", "extras")
    private val OPTIONS = JsonReader.Options.of(*NAMES)

    val FACTORY = Factory { type, _, moshi ->
      when (type) {
        CrumbModel::class.java -> CrumbAdapter(moshi)
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

  override fun fromJson(reader: JsonReader): CrumbModel {
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
    return CrumbModel(name, extras)
  }

  override fun toJson(writer: com.squareup.moshi.JsonWriter, model: CrumbModel?) {
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

/** Return a list of elements annotated with [T]. */
internal inline fun <reified T : Annotation> RoundEnvironment.findElementsAnnotatedWith(): Set<Element> = getElementsAnnotatedWith(
    T::class.java)

@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
internal inline fun <T> Iterable<*>.cast() = map { it as T }

internal data class CrumbModel(
    val name: String,
    val extras: Map<ExtensionKey, ConsumerMetadata>)
