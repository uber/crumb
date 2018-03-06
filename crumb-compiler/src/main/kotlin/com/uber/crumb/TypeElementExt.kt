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

import com.google.auto.common.AnnotationMirrors
import com.google.auto.common.MoreElements
import javax.lang.model.element.Element
import javax.lang.model.element.TypeElement

/*
 * Internal utils for Crumb.
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
 * @return annotations on this element that are annotated with type [T].
 */
internal inline fun <reified T : Annotation> Element.annotatedAnnotations()
    = AnnotationMirrors.getAnnotatedAnnotations(this, T::class.java)
