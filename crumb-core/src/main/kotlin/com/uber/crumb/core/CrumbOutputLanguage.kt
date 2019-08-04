package com.uber.crumb.core

import com.squareup.javapoet.AnnotationSpec
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.TypeSpec
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.uber.crumb.annotations.internal.CrumbIndex
import javax.annotation.processing.Filer
import javax.lang.model.element.Modifier.FINAL
import javax.lang.model.element.Modifier.PRIVATE
import javax.lang.model.element.TypeElement

/**
 * TODO doc
 * TODO explanatory comments in code
 * TODO originating elements
 */
enum class CrumbOutputLanguage {
  JAVA {
    override fun writeTo(
        filer: Filer,
        packageName: String,
        fileName: String,
        dataToWrite: String
    ) {
      val typeSpec = TypeSpec.classBuilder(fileName)
          .addAnnotation(AnnotationSpec.builder(CrumbIndex::class.java)
              .addMember("value", dataToWrite)
              .build())
          .addModifiers(FINAL)
          .addMethod(MethodSpec.constructorBuilder().addModifiers(PRIVATE).build())
          .build()
      JavaFile.builder(packageName, typeSpec)
          .addFileComment("Generated, do not modify!")
          .indent("  ")
          .build()
          .writeTo(filer)
    }
  },
  KOTLIN {
    override fun writeTo(
        filer: Filer,
        packageName: String,
        fileName: String,
        dataToWrite: String
    ) {
      val typeSpec = com.squareup.kotlinpoet.TypeSpec.objectBuilder(fileName)
          .addAnnotation(com.squareup.kotlinpoet.AnnotationSpec.builder(CrumbIndex::class)
              .addMember("", dataToWrite)
              .build())
          .addModifiers(KModifier.PRIVATE)
          .build()
      FileSpec.builder(packageName, fileName)
          .addComment("Generated, do not modify!")
          .addType(typeSpec)
          .indent("  ")
          .build()
          .writeTo(filer)
    }
  };

  abstract fun writeTo(filer: Filer,
      packageName: String,
      fileName: String,
      dataToWrite: String
  )

  companion object {
    fun languageForType(element: TypeElement): CrumbOutputLanguage {
      return if (element.getAnnotation(Metadata::class.java) != null) {
        KOTLIN
      } else {
        JAVA
      }
    }
  }
}
