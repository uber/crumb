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
package com.uber.crumb

import com.google.auto.service.AutoService
import com.google.common.annotations.VisibleForTesting
import com.uber.crumb.annotations.CrumbConsumable
import com.uber.crumb.annotations.CrumbConsumer
import com.uber.crumb.annotations.CrumbProducer
import com.uber.crumb.annotations.CrumbQualifier
import com.uber.crumb.compiler.api.CrumbConsumerExtension
import com.uber.crumb.compiler.api.CrumbContext
import com.uber.crumb.compiler.api.CrumbExtension
import com.uber.crumb.compiler.api.CrumbExtension.IncrementalExtensionType.AGGREGATING
import com.uber.crumb.compiler.api.CrumbExtension.IncrementalExtensionType.ISOLATING
import com.uber.crumb.compiler.api.CrumbExtension.IncrementalExtensionType.UNKNOWN
import com.uber.crumb.compiler.api.CrumbProducerExtension
import com.uber.crumb.compiler.api.ExtensionKey
import com.uber.crumb.compiler.api.ProducerMetadata
import com.uber.crumb.core.CrumbLog
import com.uber.crumb.core.CrumbLog.Client.MessagerClient
import com.uber.crumb.core.CrumbManager
import com.uber.crumb.core.CrumbOutputLanguage
import com.uber.crumb.internal.model.Crumb
import com.uber.crumb.internal.model.CrumbMetadata
import net.ltgt.gradle.incap.IncrementalAnnotationProcessor
import net.ltgt.gradle.incap.IncrementalAnnotationProcessorType
import net.ltgt.gradle.incap.IncrementalAnnotationProcessorType.DYNAMIC
import okio.GzipSink
import okio.GzipSource
import okio.buffer
import java.util.ServiceConfigurationError
import java.util.ServiceLoader
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

/**
 * Processes all [CrumbConsumer] and [CrumbProducer] annotated types.
 */
@AutoService(Processor::class)
@IncrementalAnnotationProcessor(DYNAMIC)
class CrumbProcessor : AbstractProcessor {

  companion object {

    const val CRUMB_INDEX_SUFFIX = "CrumbIndex"

    /**
     * Option to disable verbose logging
     */
    const val OPTION_VERBOSE = "crumb.options.verbose"

    private const val CRUMB_INDICES_PACKAGE = "com.uber.crumb.indices"
  }

  // Depending on how this CrumbProcessor was constructed, we might already have a list of
  // extensions when init() is run, or, if `extensions` is null, we have a ClassLoader that will be
  // used to get the list using the ServiceLoader API.
  private var producerExtensions: Set<CrumbProducerExtension>
  private var consumerExtensions: Set<CrumbConsumerExtension>
  private var loaderForExtensions: ClassLoader? = null
  private lateinit var typeUtils: Types
  private lateinit var elementUtils: Elements
  private lateinit var crumbLog: CrumbLog
  private lateinit var crumbManager: CrumbManager

  private lateinit var supportedTypes: Set<String>

  @Suppress("unused")
  constructor() : this(CrumbProcessor::class.java.classLoader)

  @Suppress("unused")
  @VisibleForTesting
  internal constructor(loaderForExtensions: ClassLoader) : super() {
    this.producerExtensions = setOf()
    this.consumerExtensions = setOf()
    this.loaderForExtensions = loaderForExtensions
  }

  @VisibleForTesting
  constructor(extensions: Iterable<CrumbExtension>) : super() {
    this.loaderForExtensions = null
    producerExtensions = extensions.filterIsInstance<CrumbProducerExtension>().toSet()
    consumerExtensions = extensions.filterIsInstance<CrumbConsumerExtension>().toSet()
  }

  override fun getSupportedAnnotationTypes(): Set<String> {
    return supportedTypes
  }

  override fun getSupportedSourceVersion(): SourceVersion {
    return SourceVersion.latestSupported()
  }

  override fun getSupportedOptions(): Set<String> {
    val producerIncrementalType = producerExtensions.asSequence()
      .map { it.producerIncrementalType(processingEnv) }
      .min()
      ?: ISOLATING
    val consumerIncrementalType = consumerExtensions.asSequence()
      .map { it.consumerIncrementalType(processingEnv) }
      .min()
      ?: ISOLATING
    return arrayOf(OPTION_VERBOSE, producerIncrementalType.toOption(), consumerIncrementalType.toOption())
      .filterNotNullTo(mutableSetOf())
  }

  private fun CrumbExtension.IncrementalExtensionType.toOption(): String? {
    return when (this) {
      ISOLATING -> IncrementalAnnotationProcessorType.ISOLATING.processorOption
      AGGREGATING -> IncrementalAnnotationProcessorType.AGGREGATING.processorOption
      UNKNOWN -> null
    }
  }

  @Synchronized
  override fun init(processingEnv: ProcessingEnvironment) {
    super.init(processingEnv)
    typeUtils = processingEnv.typeUtils
    elementUtils = processingEnv.elementUtils
    crumbLog = if (processingEnv.options[OPTION_VERBOSE]?.toBoolean() == true) {
      CrumbLog("CrumbProcessor", true, MessagerClient(processingEnv.messager))
    } else {
      CrumbLog("CrumbProcessor")
    }
    crumbManager = CrumbManager(processingEnv, crumbLog)
    try {
      if (loaderForExtensions != null) {
        // ServiceLoader.load returns a lazily-evaluated Iterable, so evaluate it eagerly now
        // to discover any exceptions.
        producerExtensions = ServiceLoader.load(
          CrumbProducerExtension::class.java,
          loaderForExtensions
        )
          .iterator().asSequence().toSet()
        consumerExtensions = ServiceLoader.load(
          CrumbConsumerExtension::class.java,
          loaderForExtensions
        )
          .iterator().asSequence().toSet()
      }
    } catch (t: Throwable) {
      val warning = buildString {
        append(
          "An exception occurred while looking for Crumb extensions. " + "No extensions will function."
        )
        if (t is ServiceConfigurationError) {
          append(" This may be due to a corrupt jar file in the compiler's classpath.")
        }
        append(" Exception: ")
          .append(t)
      }
      processingEnv.messager.printMessage(WARNING, warning, null)
      producerExtensions = setOf()
      consumerExtensions = setOf()
    }
    producerExtensions.plus(consumerExtensions).forEach { it.init(processingEnv) }
    val producerAnnotatedAnnotations = producerExtensions.flatMap { it.supportedProducerAnnotations() }
      .filter { it.getAnnotation(CrumbProducer::class.java) != null }
    val consumerAnnotatedAnnotations = consumerExtensions.flatMap { it.supportedConsumerAnnotations() }
      .filter { it.getAnnotation(CrumbConsumer::class.java) != null }
    val baseCrumbAnnotations = listOf(
      CrumbConsumer::class, CrumbProducer::class,
      CrumbConsumable::class
    )
      .map { it.java }
    supportedTypes = (baseCrumbAnnotations + producerAnnotatedAnnotations + consumerAnnotatedAnnotations)
      .map { it.name }
      .toSet()
  }

  override fun process(annotations: Set<TypeElement>, roundEnv: RoundEnvironment): Boolean {
    val localModels = processProducers(roundEnv)
    processConsumers(roundEnv, localModels)

    return false
  }

  private fun processProducers(roundEnv: RoundEnvironment): Set<Crumb> {
    val context = CrumbContext(processingEnv, roundEnv)
    val producers = producerExtensions.flatMap { it.supportedProducerAnnotations() }
      .filter { it.getAnnotation(CrumbProducer::class.java) != null }
      .flatMap { roundEnv.getElementsAnnotatedWith(it) }
      .cast<TypeElement>()
      .map {
        val qualifierAnnotations = it.annotatedAnnotations<CrumbQualifier>()
        val producerAnnotations = it.annotatedAnnotations<CrumbProducer>()
        val crumbAnnotations = producerAnnotations + qualifierAnnotations
        return@map it to crumbAnnotations
      }
      .distinctBy { it.first } // Cover for types with multiple producer annotations
      .filter { (type, annotations) ->
        producerExtensions.any {
          it.isProducerApplicable(context, type, annotations)
        }
      }

    return producers
      .mapNotNullTo(mutableSetOf()) { (producer, crumbAnnotations) ->
        val applicableExtensions = producerExtensions
          .filter { it.isProducerApplicable(context, producer, crumbAnnotations) }

        if (applicableExtensions.isEmpty()) {
          error(
            producer,
            """
              |No extensions applicable for the given @CrumbProducer-annotated element
              |Detected producers: [${producers.joinToString { it.toString() }}]
              |Available extensions: [${producerExtensions.joinToString()}]
              """.trimMargin()
          )
          return@mapNotNullTo null
        }

        val globalExtras = mutableMapOf<ExtensionKey, ProducerMetadata>()
        applicableExtensions.forEach { extension ->
          val extras = extension.produce(context, producer, crumbAnnotations)
          globalExtras[extension.key] = extras
        }
        val adapterName = producer.classNameOf()
        val packageName = producer.packageName()
        val sink = crumbManager.store(
          packageName = CRUMB_INDICES_PACKAGE,
          fileName = "$adapterName$CRUMB_INDEX_SUFFIX",
          outputLanguage = CrumbOutputLanguage.languageForType(producer),
          originatingElements = setOf(producer) + globalExtras.values.flatMap { it.second }
        )
        val crumbModel = Crumb("$packageName.$adapterName", globalExtras.map { (extensionKey, producerMetadata) -> CrumbMetadata(extensionKey, producerMetadata.first) })
        GzipSink(sink).buffer().use {
          Crumb.ADAPTER.encode(it, crumbModel)
        }
        return@mapNotNullTo crumbModel
      }
  }

  private fun processConsumers(roundEnv: RoundEnvironment, localModels: Set<Crumb>) {
    val context = CrumbContext(processingEnv, roundEnv)
    val consumers = consumerExtensions.flatMap { it.supportedConsumerAnnotations() }
      .filter { it.getAnnotation(CrumbConsumer::class.java) != null }
      .flatMap { roundEnv.getElementsAnnotatedWith(it) }
      .cast<TypeElement>()
      .map {
        val qualifierAnnotations = it.annotatedAnnotations<CrumbQualifier>()
        val consumerAnnotations = it.annotatedAnnotations<CrumbConsumer>()
        val crumbAnnotations = consumerAnnotations + qualifierAnnotations
        return@map it to crumbAnnotations
      }
      .distinctBy { it.first } // Cover for types with multiple consumer annotations
      .filter { (type, annotations) ->
        consumerExtensions.any {
          it.isConsumerApplicable(context, type, annotations)
        }
      }
    if (consumers.isEmpty()) {
      return
    }

    // Load the producerMetadata from the classpath
    val producerMetadataBlobs = crumbManager.load(CRUMB_INDICES_PACKAGE)

    if (producerMetadataBlobs.isEmpty()) {
      message(
        WARNING, consumers.map { it.first }.iterator().next(),
        "No @CrumbProducer metadata found on the classpath."
      )
      return
    }

    val producerMetadata = localModels + producerMetadataBlobs.map { blob ->
      GzipSource(blob).buffer().use {
        Crumb.ADAPTER.decode(it)
      }
    }
    val metadataByExtension = producerMetadata
      .flatMap { it.extras }
      .groupBy({ it.extensionKey }) { it.producerMetadata }
      .mapValues { it.value.toSet() }

    // Iterate through the consumers to generate their implementations.
    consumers
      .forEach { (consumer, crumbAnnotations) ->
        consumerExtensions.forEach { extension ->
          if (extension.isConsumerApplicable(context, consumer, crumbAnnotations)) {
            val metadata = metadataByExtension[extension.key].orEmpty()
            extension.consume(context, consumer, crumbAnnotations, metadata)
          }
        }
      }
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

@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
private inline fun <T> Iterable<*>.cast() = map { it as T }
