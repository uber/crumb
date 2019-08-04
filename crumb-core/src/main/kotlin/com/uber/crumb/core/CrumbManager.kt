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

@file:Suppress("UNCHECKED_CAST")

package com.uber.crumb.core

import com.uber.crumb.annotations.internal.CrumbIndex
import java.io.Serializable
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.PackageElement

/**
 * // TODO new docs
 * A utility class that helps adding build specific objects to the jar file and their extraction later on. This
 * specifically works by reading/writing metadata in the Crumb `META-INF` location of jars from the classpath.
 *
 * Optionally, a colon-delimited list of extra locations to search for in loading can be specified via specifying the
 * [OPTION_EXTRA_LOCATIONS] option in the given [env].
 *
 * Adapted from the [Android DataBinding library](https://android.googlesource.com/platform/frameworks/data-binding/+/master).
 */
class CrumbManager(private val env: ProcessingEnvironment,
    private val crumbLog: CrumbLog) {

  /**
   * This loads a given [Set]<[T]> from the Crumb `META-INF` store that matches the given [nameFilter].
   *
   * @param nameFilter a name filter to match on. Conventionally, one could use a "known" file extension used for file
   *                   names in [store].
   * @return the loaded [Set]<[T]>, or an empty set if none were found.
   */
  fun load(packageName: String): Set<String> {
    // If this package is null, it means there are no classes with this package name. One way this
    // could happen is if we process an annotation and reach this point without writing something
    // to the package. We do not error check here because that shouldn't happen with the
    // current implementation.
    val crumbGenPackage: PackageElement? = env.elementUtils.getPackageElement(packageName)

    if (crumbGenPackage == null) {
      crumbLog.e("No @CrumbIndex-annotated elements found in $packageName")
      return emptySet()
    }

    return crumbGenPackage.enclosedElements.mapNotNullTo(mutableSetOf()) { element ->
      element.getAnnotation(CrumbIndex::class.java)?.value
    }
  }

  /**
   * This writes a given [String] [dataToWrite] to the Crumb `META-INF` store.
   *
   * @param packageName the package name to use for the file in writing.
   * @param fileName the file name to use in writing.
   * @param dataToWrite the [Serializable] object to write.
   */
  fun store(
      packageName: String,
      fileName: String,
      dataToWrite: String,
      outputLanguage: CrumbOutputLanguage) {
    outputLanguage.writeTo(env.filer, packageName, fileName, dataToWrite)
  }
}
