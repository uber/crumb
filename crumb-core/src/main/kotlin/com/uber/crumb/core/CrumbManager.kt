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

import com.sun.tools.javac.processing.JavacProcessingEnvironment
import com.uber.crumb.core.CrumbManager.Companion.OPTION_EXTRA_LOCATIONS
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.net.URISyntaxException
import java.net.URL
import java.net.URLClassLoader
import java.util.zip.ZipFile
import javax.annotation.processing.ProcessingEnvironment
import javax.tools.JavaFileManager
import javax.tools.StandardLocation

/**
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

  companion object {
    /**
     * A colon-delimited list of extra locations to search in loading.
     */
    const val OPTION_EXTRA_LOCATIONS = "crumb.options.extraLocations"
    private const val CRUMB_PREFIX = "META-INF/com.uber.crumb/"
  }

  /**
   * This loads a given [Set]<[T]> from the Crumb `META-INF` store that matches the given [nameFilter].
   *
   * @param nameFilter a name filter to match on. Conventionally, one could use a "known" file extension used for file
   *                   names in [store].
   * @return the loaded [Set]<[T]>, or an empty set if none were found.
   */
  @Throws(IOException::class)
  fun <T : Serializable> load(nameFilter: (String) -> Boolean): Set<T> {
    val fileManager = (env as JavacProcessingEnvironment).context.get(JavaFileManager::class.java)
    val classLoader = fileManager.getClassLoader(StandardLocation.CLASS_PATH)
    check(classLoader is URLClassLoader) {
      "Classloader must be an instance of URLClassLoader. $classLoader"
    }

    val urlClassLoader = classLoader as URLClassLoader
    val objects = mutableSetOf<T>()
    val extraLocations = env.options[OPTION_EXTRA_LOCATIONS]
        ?.split(":")
        ?.map { URL("file:$it") }
        ?: emptyList()
    for (url in (urlClassLoader.urLs + extraLocations)) {
      crumbLog.d("Checking url %s for Crumb data", url)
      try {
        val file = File(url.toURI())
        if (!file.exists()) {
          crumbLog.d("Cannot load file for %s", url)
          continue
        }
        if (file.isDirectory) {
          // probably exported classes dir.
          loadFromDirectory(nameFilter, file, objects)
        } else {
          // assume it is a zip file
          loadFomZipFile(nameFilter, file, objects)
        }
      } catch (e: IOException) {
        crumbLog.d("Cannot open zip file from %s", url)
      } catch (e: URISyntaxException) {
        crumbLog.d("Cannot open zip file from %s", url)
      }
    }
    return objects
  }

  @Throws(IOException::class)
  private fun <T : Serializable> loadFromDirectory(nameFilter: (String) -> Boolean,
      from: File,
      into: MutableSet<T>) {
    from.walkTopDown()
        .filter { nameFilter(it.name) }
        .forEach { file ->
          try {
            file.inputStream().use {
              try {
                val item = fromInputStream(it)
                item?.let {
                  into += (item as T)
                  crumbLog.d("Loaded item %s from file", item)
                }
              } catch (e: IOException) {
                crumbLog.e(e, "Could not merge in crumbs from %s", file.absolutePath)
              }
            }
          } catch (e: ClassNotFoundException) {
            crumbLog.e(e, "Could not read Crumb file. %s",
                file.absolutePath)
          }
        }
  }

  @Throws(IOException::class)
  private fun <T : Serializable> loadFomZipFile(nameFilter: (String) -> Boolean,
      from: File,
      into: MutableSet<T>) {
    val zipFile = ZipFile(from)
    zipFile.entries()
        .asSequence()
        .filter { nameFilter(it.name) }
        .forEach { entry ->
          try {
            zipFile.getInputStream(entry).use {
              try {
                val item = fromInputStream(it)
                crumbLog.d("loaded item $item from zip file")
                item?.let {
                  into += (item as T)
                }
              } catch (e: IOException) {
                crumbLog.e(e, "Could not merge in Crumb file from %s", from.absolutePath)
              }
            }
          } catch (e: ClassNotFoundException) {
            crumbLog.e(e, "Could not read Crumb file. %s", from.absolutePath)
          }
        }
  }

  @Throws(IOException::class, ClassNotFoundException::class)
  private fun fromInputStream(inputStream: InputStream): Serializable? {
    ObjectInputStream(inputStream).use { return it.readObject() as Serializable }
  }

  /**
   * This writes a given [Serializable] [objectToWrite] to the Crumb `META-INF` store.
   *
   * @param packageName the package name to use for the file in writing.
   * @param fileName the file name to use in writing.
   * @param objectToWrite the [Serializable] object to write.
   */
  @Throws(IOException::class)
  fun store(
      packageName: String,
      fileName: String,
      objectToWrite: Serializable) {
    try {
      env.filer.createResource(
          StandardLocation.CLASS_OUTPUT,
          "",
          "$CRUMB_PREFIX$packageName/$fileName")
          .openOutputStream()
          .use { ios ->
            ObjectOutputStream(ios).use { oos ->
              oos.writeObject(objectToWrite)
              crumbLog.d("Wrote Crumb file %s %s", packageName, fileName)
            }
          }
    } catch (e: IOException) {
      crumbLog.e(e, "Could not write to Crumb file: %s", fileName)
    }
  }
}
