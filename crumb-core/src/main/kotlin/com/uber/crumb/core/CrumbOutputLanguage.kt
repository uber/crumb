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
 * TODO doc
 * TODO explanatory comments in code
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

  abstract fun writeTo(filer: Filer,
      packageName: String,
      fileName: String,
      dataToWrite: String,
      originatingElements: Set<Element> = emptySet()
  )

  companion object {
    private const val GENERATED_COMMENT = "Generated, do not modify!"
    private const val INDENT = "  "
    fun languageForType(element: TypeElement): CrumbOutputLanguage {
      return if (element.getAnnotation(Metadata::class.java) != null) {
        KOTLIN
      } else {
        JAVA
      }
    }
  }
}
