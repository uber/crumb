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

package com.uber.crumb.sample.pluginscompiler

import com.google.auto.common.MoreElements.isAnnotationPresent
import com.google.auto.service.AutoService
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import com.uber.crumb.compiler.api.ConsumerMetadata
import com.uber.crumb.compiler.api.CrumbConsumerExtension
import com.uber.crumb.compiler.api.CrumbContext
import com.uber.crumb.compiler.api.CrumbProducerExtension
import com.uber.crumb.compiler.api.ExtensionKey
import com.uber.crumb.compiler.api.ProducerMetadata
import com.uber.crumb.sample.pluginscompiler.annotations.Plugin
import com.uber.crumb.sample.pluginscompiler.annotations.PluginPoint
import me.eugeniomarletti.kotlin.metadata.KotlinClassMetadata
import me.eugeniomarletti.kotlin.metadata.classKind
import me.eugeniomarletti.kotlin.metadata.kaptGeneratedOption
import me.eugeniomarletti.kotlin.metadata.kotlinMetadata
import org.jetbrains.kotlin.serialization.ProtoBuf.Class.Kind
import java.io.File
import javax.lang.model.element.AnnotationMirror
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ElementKind.CLASS
import javax.lang.model.element.Modifier
import javax.lang.model.element.Modifier.ABSTRACT
import javax.lang.model.element.TypeElement
import javax.lang.model.type.MirroredTypesException
import javax.lang.model.type.TypeMirror
import javax.lang.model.util.ElementFilter
import javax.tools.Diagnostic
import javax.tools.Diagnostic.Kind.ERROR

/**
 * A simple crumb producer/consumer that reads plugin implementations from libraries and writes a
 * mapping record of them in a consuming app. Could be useful for reading plugin implementations downstream, and very
 * similar in implementation to a service loader.
 */
@AutoService(CrumbProducerExtension::class, CrumbConsumerExtension::class)
class PluginsCompiler : CrumbProducerExtension, CrumbConsumerExtension {

  companion object {
    private const val METADATA_KEY: ExtensionKey = "PluginsCompiler"
  }

  override fun isConsumerApplicable(context: CrumbContext,
      type: TypeElement,
      annotations: Collection<AnnotationMirror>): Boolean {
    return isAnnotationPresent(type, PluginPoint::class.java)
  }

  override fun isProducerApplicable(context: CrumbContext,
      type: TypeElement,
      annotations: Collection<AnnotationMirror>): Boolean {
    return isAnnotationPresent(type, Plugin::class.java)
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
              "@${PluginPoint::class.java.simpleName} is only applicable on classes when consuming!",
              type)
      return
    }

    val kmetadata = type.kotlinMetadata

    if (kmetadata == null) {
      context.processingEnv
          .messager
          .printMessage(ERROR,
              "@${PluginPoint::class.java.simpleName} unreadable on $type. Please ensure the standard library is a " +
                  "dependency of this project.",
              type)
      return
    }

    if (kmetadata !is KotlinClassMetadata) {
      context.processingEnv
          .messager
          .printMessage(ERROR,
              "@${PluginPoint::class.java.simpleName} can't be applied to $type: must be a class.",
              type)
      return
    }

    val classData = kmetadata.data
    val (nameResolver, classProto) = classData

    // Must be an object class.
    if (classProto.classKind != Kind.OBJECT) {
      context.processingEnv
          .messager
          .printMessage(ERROR,
              "@${PluginPoint::class.java.simpleName} can't be applied to $type: must be a Kotlin object class",
              type)
      return
    }

    val packageName = nameResolver.getString(classProto.fqName)
        .substringBeforeLast('/')
        .replace('/', '.')

    // Read the pluginpoint's target type, e.g. "MyPluginInterface"
    val pluginPoint = type.getAnnotation(PluginPoint::class.java)
    val targetPlugin: TypeMirror = try {
      pluginPoint.value
      throw IllegalStateException("This shouldn't actually happen")
    } catch (e: MirroredTypesException) {
      e.typeMirrors[0]
    }

    // List of plugin TypeElements
    val pluginClasses = metadata
        .mapNotNull { it[METADATA_KEY] }
        .map { pluginClass -> context.processingEnv.elementUtils.getTypeElement(pluginClass) }
        .filter {
          context
              .processingEnv
              .typeUtils
              .isAssignable(it.asType(), targetPlugin)
        }
        .toSet()

    val initializerCode = "return setOf(${pluginClasses.joinToString { "%T()" }})"
    val initializerValues = pluginClasses
        .map { it.asClassName() }
        .toTypedArray()
    val pluginsFunction = FunSpec.builder("obtain")
        .receiver(type.asClassName())
        .returns(ParameterizedTypeName.get(Set::class.asClassName(),
            targetPlugin.asTypeName()))
        .addStatement(initializerCode, *initializerValues)
        .build()

    // Generate the file
    val generatedDir = context.processingEnv.options[kaptGeneratedOption]?.let(::File)
        ?: throw IllegalStateException("Could not resolve kotlin generated directory!")
    FileSpec.builder(packageName, "${type.simpleName}_Plugins")
        .addFunction(pluginsFunction)
        .build()
        .writeTo(generatedDir)
  }

  override fun produce(context: CrumbContext,
      type: TypeElement,
      annotations: Collection<AnnotationMirror>): ProducerMetadata {
    // Must be a class
    if (type.kind != ElementKind.CLASS) {
      context
          .processingEnv
          .messager
          .printMessage(
              Diagnostic.Kind.ERROR,
              "@${Plugin::class.java.simpleName} is only applicable on classes!",
              type)
      return mapOf()
    }

    // Must be instantiable (not abstract)
    if (ABSTRACT in type.modifiers) {
      context
          .processingEnv
          .messager
          .printMessage(
              Diagnostic.Kind.ERROR,
              "@${Plugin::class.java.simpleName} is not applicable on abstract classes!",
              type)
      return mapOf()
    }

    // If they define a default constructor, it must be public
    val defaultConstructor = ElementFilter.constructorsIn(type.enclosedElements)
        .firstOrNull { it.isDefault }
    if (defaultConstructor?.modifiers?.contains(Modifier.PUBLIC) == true) {
      context
          .processingEnv
          .messager
          .printMessage(
              Diagnostic.Kind.ERROR,
              "Must have a public default constructor to be usable in plugin points.",
              type)
      return mapOf()
    }
    return mapOf(METADATA_KEY to type.qualifiedName.toString())
  }

  override fun supportedConsumerAnnotations() = setOf(PluginPoint::class.java)

  override fun supportedProducerAnnotations() = setOf(Plugin::class.java)

  override fun key() = METADATA_KEY
}
