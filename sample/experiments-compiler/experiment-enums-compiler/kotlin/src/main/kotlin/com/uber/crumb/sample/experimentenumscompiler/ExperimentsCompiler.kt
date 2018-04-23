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

package com.uber.crumb.sample.experimentenumscompiler

import com.google.auto.common.MoreElements.isAnnotationPresent
import com.google.auto.service.AutoService
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.WildcardTypeName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import com.uber.crumb.compiler.api.ConsumerMetadata
import com.uber.crumb.compiler.api.CrumbConsumerExtension
import com.uber.crumb.compiler.api.CrumbContext
import com.uber.crumb.compiler.api.CrumbProducerExtension
import com.uber.crumb.compiler.api.ExtensionKey
import com.uber.crumb.compiler.api.ProducerMetadata
import com.uber.crumb.sample.experimentsenumscompiler.annotations.Experiments
import com.uber.crumb.sample.experimentsenumscompiler.annotations.ExperimentsCollector
import me.eugeniomarletti.kotlin.metadata.KotlinClassMetadata
import me.eugeniomarletti.kotlin.metadata.classKind
import me.eugeniomarletti.kotlin.metadata.kaptGeneratedOption
import me.eugeniomarletti.kotlin.metadata.kotlinMetadata
import org.jetbrains.kotlin.serialization.ProtoBuf.Class.Kind
import java.io.File
import javax.lang.model.element.AnnotationMirror
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind.CLASS
import javax.lang.model.element.ElementKind.ENUM
import javax.lang.model.element.ElementKind.ENUM_CONSTANT
import javax.lang.model.element.TypeElement
import javax.tools.Diagnostic
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

  override fun isConsumerApplicable(context: CrumbContext,
      type: TypeElement,
      annotations: Collection<AnnotationMirror>): Boolean {
    return isAnnotationPresent(type, ExperimentsCollector::class.java)
  }

  override fun isProducerApplicable(context: CrumbContext,
      type: TypeElement,
      annotations: Collection<AnnotationMirror>): Boolean {
    return isAnnotationPresent(type, Experiments::class.java)
  }

  override fun consume(context: CrumbContext,
      type: TypeElement,
      annotations: Collection<AnnotationMirror>,
      metadata: Set<ConsumerMetadata>) {

    // Must be a type that supports extension values
    if (type.kind != CLASS) {
      context.processingEnv
          .messager
          .printMessage(ERROR,
              "@${ExperimentsCollector::class.java.simpleName} is only applicable on classes when consuming!",
              type)
      return
    }

    val kmetadata = type.kotlinMetadata

    if (kmetadata !is KotlinClassMetadata) {
      context.processingEnv
          .messager
          .printMessage(ERROR,
              "@${ExperimentsCollector::class.java.simpleName} can't be applied to $type: must be a class. KMetadata was $kmetadata and annotations were [${type.annotationMirrors.joinToString { it.annotationType.asElement().simpleName }}]",
              type)
      return
    }

    val classData = kmetadata.data
    val (nameResolver, classProto) = classData

    // Must be an abstract class because we're generating the backing implementation.
    if (classProto.classKind != Kind.OBJECT) {
      context.processingEnv
          .messager
          .printMessage(ERROR,
              "@${ExperimentsCollector::class.java.simpleName} can't be applied to $type: must be a Kotlin object class",
              type)
      return
    }

    val packageName = nameResolver.getString(classProto.fqName)
        .substringBeforeLast('/')
        .replace('/', '.')

    // Map of enum TypeElement to its members
    val experimentClasses = metadata
        .mapNotNull { it[METADATA_KEY] }
        .map { enumClass -> context.processingEnv.elementUtils.getTypeElement(enumClass) }
        .associate {
          it to it.enclosedElements
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
        .returns(ParameterizedTypeName.get(Map::class.asClassName(),
            ParameterizedTypeName.get(Class::class.asClassName(),
                WildcardTypeName.subtypeOf(
                    ParameterizedTypeName.get(Enum::class.asClassName(), WildcardTypeName.subtypeOf(ANY)))),
            ParameterizedTypeName.get(List::class.asClassName(),
                String::class.asTypeName())))
        .addStatement("return mapOf($initializerCode)", *initializerValues)
        .build()

    // Generate the file
    val generatedDir = context.processingEnv.options[kaptGeneratedOption]?.let(::File)
        ?: throw IllegalStateException("Could not resolve kotlin generated directory!")
    FileSpec.builder(packageName, "${type.simpleName}_Experiments")
        .addFunction(mapFunction)
        .build()
        .writeTo(generatedDir)
  }

  override fun produce(context: CrumbContext,
      type: TypeElement,
      annotations: Collection<AnnotationMirror>): ProducerMetadata {
    if (type.kind != ENUM) {
      context.processingEnv
          .messager
          .printMessage(ERROR,
              "@${Experiments::class.java.simpleName} is only applicable on enums when producing!",
              type)
      return emptyMap()
    }
    return mapOf(METADATA_KEY to type.qualifiedName.toString())
  }

  override fun supportedConsumerAnnotations(): Set<Class<out Annotation>> = setOf(
      ExperimentsCollector::class.java)

  override fun supportedProducerAnnotations(): Set<Class<out Annotation>> = setOf(
      Experiments::class.java)

  override fun key() = METADATA_KEY
}
