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
package com.uber.crumb.sample.experimentenumscompiler

import com.google.auto.common.MoreElements.isAnnotationPresent
import com.google.auto.service.AutoService
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.WildcardTypeName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.classinspector.elements.ElementsClassInspector
import com.squareup.kotlinpoet.metadata.isObject
import com.squareup.kotlinpoet.metadata.specs.toFileSpec
import com.squareup.kotlinpoet.metadata.toImmutableKmClass
import com.uber.crumb.compiler.api.ConsumerMetadata
import com.uber.crumb.compiler.api.CrumbConsumerExtension
import com.uber.crumb.compiler.api.CrumbContext
import com.uber.crumb.compiler.api.CrumbExtension.IncrementalExtensionType
import com.uber.crumb.compiler.api.CrumbExtension.IncrementalExtensionType.ISOLATING
import com.uber.crumb.compiler.api.CrumbProducerExtension
import com.uber.crumb.compiler.api.ExtensionKey
import com.uber.crumb.compiler.api.ProducerMetadata
import com.uber.crumb.sample.experimentsenumscompiler.annotations.Experiments
import com.uber.crumb.sample.experimentsenumscompiler.annotations.ExperimentsCollector
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.AnnotationMirror
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind.CLASS
import javax.lang.model.element.ElementKind.ENUM
import javax.lang.model.element.ElementKind.ENUM_CONSTANT
import javax.lang.model.element.TypeElement
import javax.tools.Diagnostic.Kind.ERROR

/**
 * A simple crumb producer/consumer that reads "experiment enums" from libraries and writes a
 * mapping record of them in a consuming app. Could be useful for tracking what experiments are on
 * the classpath.
 */
@AutoService(CrumbProducerExtension::class, CrumbConsumerExtension::class)
class ExperimentsCompiler : CrumbProducerExtension, CrumbConsumerExtension {

  companion object {
    private const val METADATA_KEY: ExtensionKey = "ExperimentsCompiler"
  }

  override fun isConsumerApplicable(
    context: CrumbContext,
    type: TypeElement,
    annotations: Collection<AnnotationMirror>
  ): Boolean {
    return isAnnotationPresent(type, ExperimentsCollector::class.java)
  }

  override fun isProducerApplicable(
    context: CrumbContext,
    type: TypeElement,
    annotations: Collection<AnnotationMirror>
  ): Boolean {
    return isAnnotationPresent(type, Experiments::class.java)
  }

  /** This is isolating because it only depends on the consumer type instance.  */
  override fun consumerIncrementalType(
    processingEnvironment: ProcessingEnvironment
  ): IncrementalExtensionType = ISOLATING

  override fun consume(
    context: CrumbContext,
    type: TypeElement,
    annotations: Collection<AnnotationMirror>,
    metadata: Set<ConsumerMetadata>
  ) {

    // Must be a type that supports extension values
    if (type.kind != CLASS) {
      context.processingEnv
        .messager
        .printMessage(
          ERROR,
          "@${ExperimentsCollector::class.java.simpleName} is only applicable on classes when consuming!",
          type
        )
      return
    }

    val kmClass = type.toImmutableKmClass()

    // Must be an object
    if (!kmClass.isObject) {
      context.processingEnv
        .messager
        .printMessage(
          ERROR,
          "@${ExperimentsCollector::class.java.simpleName} can't be applied to $type: must be a Kotlin object class",
          type
        )
      return
    }

    val elementsInspector = ElementsClassInspector.create(context.processingEnv.elementUtils, context.processingEnv.typeUtils)
    val spec = kmClass.toFileSpec(elementsInspector, type.asClassName())
    val packageName = spec.packageName

    // Map of enum TypeElement to its members
    val experimentClasses = metadata
      .mapNotNull { it[METADATA_KEY] }
      .map { enumClass -> context.processingEnv.elementUtils.getTypeElement(enumClass) }
      .associateWith {
        it.enclosedElements
          .filter { it.kind == ENUM_CONSTANT }
          .map(Element::toString)
      }

    val initializerCode = experimentClasses
      .map { "%T::class.java to listOf(${it.value.joinToString(", ") { "%S" }})" }
      .joinToString()
    val initializerValues = experimentClasses
      .flatMap { listOf(it.key.asClassName(), *it.value.toTypedArray()) }
      .toTypedArray()
    val mapFunction = FunSpec.builder("experiments")
      .receiver(type.asClassName())
      .returns(
        Map::class.asClassName().parameterizedBy(
          Class::class.asClassName().parameterizedBy(
            WildcardTypeName.producerOf(
              Enum::class.asClassName().parameterizedBy(STAR)
            )
          ),
          List::class.asClassName().parameterizedBy(
            String::class.asTypeName()
          )
        )
      )
      .addStatement("return mapOf($initializerCode)", *initializerValues)
      .addOriginatingElement(type)
      .build()

    // Generate the file
    FileSpec.builder(packageName, "${type.simpleName}_Experiments")
      .addFunction(mapFunction)
      .build()
      .writeTo(context.processingEnv.filer)
  }

  override fun producerIncrementalType(
    processingEnvironment: ProcessingEnvironment
  ): IncrementalExtensionType = ISOLATING

  override fun produce(
    context: CrumbContext,
    type: TypeElement,
    annotations: Collection<AnnotationMirror>
  ): ProducerMetadata {
    if (type.kind != ENUM) {
      context.processingEnv
        .messager
        .printMessage(
          ERROR,
          "@${Experiments::class.java.simpleName} is only applicable on enums when producing!",
          type
        )
      return emptyMap<String, String>() to emptySet()
    }
    return mapOf(METADATA_KEY to type.qualifiedName.toString()) to setOf(type)
  }

  override fun supportedConsumerAnnotations(): Set<Class<out Annotation>> = setOf(
    ExperimentsCollector::class.java
  )

  override fun supportedProducerAnnotations(): Set<Class<out Annotation>> = setOf(
    Experiments::class.java
  )

  override val key = METADATA_KEY
}
