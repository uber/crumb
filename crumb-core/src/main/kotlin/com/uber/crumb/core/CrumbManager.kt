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
package com.uber.crumb.core

import com.uber.crumb.annotations.internal.CrumbIndex
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.Element
import javax.lang.model.element.PackageElement

/**
 * A utility class that helps with generating types to hold [CrumbIndexes][CrumbIndex] of metadata and reading them
 * later.
 *
 * @property env A given [ProcessingEnvironment] instance.
 * @property crumbLog A [CrumbLog] instance for logging information.
 */
class CrumbManager(
  private val env: ProcessingEnvironment,
  private val crumbLog: CrumbLog
) {

  /**
   * This loads a given [Set]<String> from the available [CrumbIndex] instances in the given [packageName].
   *
   * @param packageName The target package to load types containing [CrumbIndex] annotations from.
   * @return the loaded [Set]<String>, or an empty set if none were found.
   */
  fun load(packageName: String): Set<BufferedSource> {
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
      element.getAnnotation(CrumbIndex::class.java)?.run {
        Buffer().apply { write(value) }
      }
    }
  }

  /**
   * This facilitates writing data to a [CrumbIndex] type in the given [packageName].[fileName] with the contents
   * written to the returned [BufferedSink].
   *
   * @param packageName The package name to use for the file in writing. Note that this should be the package that all
   *                    metadata index-holder types are written to, and not necessarily the package name of the source
   *                    element.
   * @param fileName The file name to use in writing.
   * @param outputLanguage The target output language.
   * @param originatingElements Any originating elements for the metadata.
   * @return A [BufferedSink] to write metadata to. This will (only) be written to the eventual [CrumbIndex] once
   *         [BufferedSink.close] is called.
   */
  fun store(
    packageName: String,
    fileName: String,
    outputLanguage: CrumbOutputLanguage,
    originatingElements: Set<Element> = emptySet()
  ): BufferedSink {
    return outputLanguage.writeTo(env.filer, packageName, fileName, originatingElements)
  }
}
