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

package com.uber.synapse

import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName

import javax.lang.model.element.Element
import javax.lang.model.element.PackageElement
import javax.lang.model.element.TypeElement
import javax.lang.model.type.TypeKind
import javax.lang.model.type.TypeMirror
import javax.lang.model.util.Elements
import javax.lang.model.util.Types

/*
 * Internal utils for Synapse.
 */

/**
 * Checks if a given `type` implements a given `targetInterface` anywhere in its ancestry.
 *
 * @param elementUtils Elements instance
 * @param typeUtils Types instance
 * @return true if it does implement it somewhere in its ancestry, false if not.
 */
internal inline fun <reified T : Any> TypeElement.implementsInterface(
    elementUtils: Elements,
    typeUtils: Types): Boolean {
  val targetInterface = T::class.java
  var localType = this
  if (!targetInterface.isInterface) {
    throw IllegalArgumentException(targetInterface.name + " is not an interface!")
  }
  var typeMirror = localType.asType()
  val typeElement = elementUtils.getTypeElement(targetInterface.canonicalName) ?: return false
  val targetType = typeElement.asType()
  if (!localType.interfaces.isEmpty() || typeMirror.kind != TypeKind.NONE) {
    while (typeMirror.kind != TypeKind.NONE) {
      if ((typeUtils.asElement(typeMirror) as TypeElement).implements(typeUtils, targetType)) {
        return true
      }
      localType = typeUtils.asElement(typeMirror) as TypeElement
      typeMirror = localType.superclass
    }
  }
  return false
}

internal fun TypeElement.implements(typeUtils: Types, target: TypeMirror): Boolean {
  // check if it implements valid interfaces
  for (ifaceInput in interfaces) {
    var iface = ifaceInput
    while (iface.kind != TypeKind.NONE && iface.toString() != "java.lang.Object") {
      if (typeUtils.isSameType(iface, target)) {
        return true
      }
      // go up
      val ifaceElement = typeUtils.asElement(iface) as TypeElement
      if (ifaceElement.implements(typeUtils, target)) {
        return true
      }
      // then move on
      iface = ifaceElement.superclass
    }
  }
  return false
}

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
internal fun TypeElement.packageName(): String {
  var localType = this
  while (true) {
    val enclosing = localType.enclosingElement
    if (enclosing is PackageElement) {
      return enclosing.qualifiedName.toString()
    }
    localType = enclosing as TypeElement
  }
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
