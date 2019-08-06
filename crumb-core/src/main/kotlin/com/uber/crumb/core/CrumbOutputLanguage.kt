/*
 * Copyright (c) 2019. Uber Technologies
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

package com.uber.crumb.core

import com.squareup.javapoet.AnnotationSpec
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.TypeSpec
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.uber.crumb.annotations.internal.CrumbIndex
import javax.annotation.processing.Filer
import javax.lang.model.element.Element
import javax.lang.model.element.Modifier.FINAL
import javax.lang.model.element.Modifier.PRIVATE
import javax.lang.model.element.TypeElement

private typealias KotlinTypeSpec = com.squareup.kotlinpoet.TypeSpec
private typealias KotlinAnnotationSpec = com.squareup.kotlinpoet.AnnotationSpec

/**
 * Supported output languages for Crumb metadata. When [writeTo] is called, a simple empty class is generated to hold a
 * [CrumbIndex] annotation containing all the crumb metadata specified.
 */
enum class CrumbOutputLanguage {
  JAVA {
    override fun writeTo(
        filer: Filer,
        packageName: String,
        fileName: String,
        dataToWrite: String,
        originatingElements: Set<Element>
    ) {
      val typeSpec = TypeSpec.classBuilder(fileName)
          .addJavadoc(EXPLANATORY_COMMENT)
          .addAnnotation(AnnotationSpec.builder(CrumbIndex::class.java)
              .addMember("value", "\$S", dataToWrite)
              .build())
          .addModifiers(FINAL)
          .addMethod(MethodSpec.constructorBuilder().addModifiers(PRIVATE).build())
          .apply {
            originatingElements.forEach { addOriginatingElement(it) }
          }
          .build()
      JavaFile.builder(packageName, typeSpec)
          .addFileComment(GENERATED_COMMENT)
          .indent(INDENT)
          .build()
          .writeTo(filer)
    }
  },
  KOTLIN {
    override fun writeTo(
        filer: Filer,
        packageName: String,
        fileName: String,
        dataToWrite: String,
        originatingElements: Set<Element>
    ) {
      val typeSpec = KotlinTypeSpec.objectBuilder(fileName)
          .addKdoc(EXPLANATORY_COMMENT)
          .addAnnotation(KotlinAnnotationSpec.builder(CrumbIndex::class)
              .addMember("%S", dataToWrite)
              .build())
          .addModifiers(KModifier.PRIVATE)
          .apply {
            this.originatingElements += originatingElements
          }
          .build()
      FileSpec.builder(packageName, fileName)
          .addComment(GENERATED_COMMENT)
          .addType(typeSpec)
          .indent(INDENT)
          .build()
          .writeTo(filer)
    }
  };

  /**
   * Writes crumb metadata for this language.
   *
   * @param filer A [Filer] to write with.
   * @param packageName The package to write to. Note that this should be the package that all metadata index-holder
   *                    types are written to, and not necessarily the package name of the source element.
   * @param fileName The file name.
   * @param dataToWrite The metadata to write to the eventual [CrumbIndex].
   * @param originatingElements Any originating elements for the metadata.
   */
  abstract fun writeTo(filer: Filer,
      packageName: String,
      fileName: String,
      dataToWrite: String,
      originatingElements: Set<Element> = emptySet()
  )

  companion object {
    private const val EXPLANATORY_COMMENT = "This type + annotation exists for sharing information to the Crumb annotation processor and should not be considered public API."
    private const val GENERATED_COMMENT = "Generated, do not modify!"
    private const val INDENT = "  "

    /**
     * @return the target language that should be used for a given [element]. In principle, this tries to match the
     * output language to the source language.
     */
    fun languageForType(element: TypeElement): CrumbOutputLanguage {
      return if (element.getAnnotation(Metadata::class.java) != null) {
        KOTLIN
      } else {
        JAVA
      }
    }
  }
}
