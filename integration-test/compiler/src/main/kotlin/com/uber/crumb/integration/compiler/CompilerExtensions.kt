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

package com.uber.crumb.integration.compiler

import com.google.auto.common.MoreElements
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.element.Element
import javax.lang.model.element.TypeElement

/*
 * Copied from TypeElementExt.
 */

/**
 * Returns the name of the given type, including any enclosing types but not the package.
 *
 * @return the class name string.
 */
internal fun TypeElement.classNameOf(): String {
  val name = qualifiedName.toString()
  val pkgName = packageName()
  return if (pkgName.isEmpty()) name else name.substring(pkgName.length + 1)
}

/**
 * Returns the name of the package that the given type is in. If the type is in the default
 * (unnamed) package then the name is the empty string.
 *
 * @return the package name.
 */
internal fun Element.packageName(): String {
  return MoreElements.getPackage(this).qualifiedName.toString()
}

/**
 * @return the raw type. Useful if it's a parameterized type.
 */
internal fun Element.rawType(): TypeName {
  var type = TypeName.get(asType())
  if (type is ParameterizedTypeName) {
    type = type.rawType
  }
  return type
}

/** Return a list of elements annotated with [T]. */
internal inline fun <reified T : Annotation> RoundEnvironment.findElementsAnnotatedWith(): Set<Element>
    = getElementsAnnotatedWith(T::class.java)
