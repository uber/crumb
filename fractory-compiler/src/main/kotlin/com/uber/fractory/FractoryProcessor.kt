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

import com.squareup.javapoet.ClassName
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.TypeSpec
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
import java.io.IOException
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.Modifier.ABSTRACT
import javax.lang.model.element.Modifier.FINAL
import javax.lang.model.element.Modifier.PUBLIC
import javax.lang.model.element.TypeElement
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic
import javax.tools.Diagnostic.Kind.ERROR
import javax.tools.Diagnostic.Kind.WARNING
import kotlin.properties.Delegates

typealias ExtensionName = String
typealias ExtensionArgs = Map<String, String>
typealias ProducerMetadata = Map<String, String>
typealias MoshiTypes = com.squareup.moshi.Types

/**
 * Generates a Fractory that adapts all [FractoryConsumer] and [FractoryProducer] annotated types.
 */
open class FractoryProcessor : AbstractProcessor() {

  companion object {
    private const val FRACTORY_PRODUCER_PREFIX = "FractoryProducer_"
    private const val FRACTORY_CONSUMER_PREFIX = "FractoryConsumer_"
  }

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
        .onEach { checkAbstract(it) }
        .forEach { producer ->
          val context = FractoryContext(processingEnv, roundEnv)
          val qualifierAnnotations = producer.annotationMirrors
              .filter {
                it.annotationType.asElement().getAnnotation(FractoryQualifier::class.java) != null
              }
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

          val globalExtras = mutableMapOf<ExtensionName, ProducerMetadata>()
          val adapterName = producer.classNameOf()
          val packageName = producer.packageName()
          val factorySpecBuilder = TypeSpec.classBuilder(
              ClassName.get(packageName, FRACTORY_PRODUCER_PREFIX + adapterName))
              .addModifiers(FINAL)
              .superclass(ClassName.get(packageName, adapterName))
          val emptyFactory = factorySpecBuilder.build()
          applicableExtensions.forEach { extension ->
            val extras: ProducerMetadata = extension.produce(context, producer, factorySpecBuilder,
                qualifierAnnotations)
            globalExtras.put(extension.javaClass.name, extras)
          }
          val factorySpec = factorySpecBuilder.build()
          if (emptyFactory != factorySpec) {
            val file = JavaFile.builder(packageName, factorySpec).build()
            file.writeToFiler()?.run {
              // Write metadata to resources for consumers to pick up
              val fractoryModel = FractoryModel("$packageName.$adapterName", globalExtras)
              val json = fractoryAdapter.toJson(fractoryModel)
              GenerationalClassUtil.writeIntermediateFile(processingEnv,
                  packageName,
                  adapterName + GenerationalClassUtil.ExtensionFilter.FRACTORY.extension,
                  json)
            }
          } else {
            error(producer, "No modifications were made to this producer.")
          }
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
    val extrasByExtension = mutableMapOf<ExtensionName, MutableSet<ExtensionArgs>>()
    producerMetadata.map { it.extras }
        .flatMap { it.entries }
        .forEach {
          extrasByExtension.getOrPut(it.key, { mutableSetOf() }).add(it.value)
        }

    // Iterate through the consumers to generate their implementations.
    consumers.cast<TypeElement>()
        .onEach { checkAbstract(it) }
        .forEach { consumer ->
          val context = FractoryContext(processingEnv, roundEnv)
          val qualifierAnnotations = consumer.annotationMirrors
              .filter {
                it.annotationType.asElement().getAnnotation(FractoryQualifier::class.java) != null
              }
          val adapterName = consumer.classNameOf()
          val packageName = consumer.packageName()
          val factorySpecBuilder = TypeSpec.classBuilder(
              ClassName.get(packageName, FRACTORY_CONSUMER_PREFIX + adapterName))
              .addModifiers(PUBLIC, FINAL)
              .superclass(ClassName.get(packageName, adapterName))
          val emptyFactory = factorySpecBuilder.build()
          consumerExtensions.forEach { extension ->
            if (extension.isConsumerApplicable(context, consumer, qualifierAnnotations)) {
              val extras = extrasByExtension[extension.javaClass.name] ?: setOf<ExtensionArgs>()
              extension.consume(context, consumer, factorySpecBuilder, extras)
            }
          }

          val factorySpec = factorySpecBuilder.build()
          if (emptyFactory != factorySpec) {
            JavaFile.builder(packageName, factorySpec).build()
                .writeToFiler()
          } else {
            error(consumer, "No modifications were made to this consumer.")
          }
        }
  }

  /**
   * Writes a file to a filer or reports an error if it fails.
   *
   * @return true if successful, false if not.
   */
  private fun JavaFile.writeToFiler(): Unit? {
    return try {
      writeTo(processingEnv.filer)
    } catch (e: IOException) {
      error("Failed to write Fractory: " + e.localizedMessage)
      null
    }
  }

  /**
   * Checks if an element is abstract or not and errors if not.
   *
   * @param element element to check
   * @return true if abstract, false if not.
   */
  private fun checkAbstract(element: TypeElement) {
    if (ABSTRACT !in element.modifiers) {
      error(element, "Must be abstract!")
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

  private val extrasAdapter = moshi.adapter<Map<ExtensionName, ExtensionArgs>>(
      MoshiTypes.newParameterizedType(
          Map::class.java,
          String::class.java,
          MoshiTypes.newParameterizedType(Map::class.java,
              String::class.java,
              String::class.java)))

  override fun fromJson(reader: JsonReader): FractoryModel {
    var name by Delegates.notNull<ExtensionName>()
    var extras by Delegates.notNull<Map<ExtensionName, ExtensionArgs>>()
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
    val extras: Map<ExtensionName, ExtensionArgs>)

fun String.asPackageAndName(): Pair<String, String> {
  val lastIndex = lastIndexOf(".")
  val modelPackage = substring(0, lastIndex)
  val modelSimpleName = substring(lastIndex + 1)
  return Pair(modelPackage, modelSimpleName)
}
