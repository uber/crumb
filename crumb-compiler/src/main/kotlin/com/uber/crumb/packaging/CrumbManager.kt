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

package com.uber.crumb.packaging

import com.google.common.base.Preconditions
import com.sun.tools.javac.processing.JavacProcessingEnvironment
import com.uber.crumb.CrumbProcessor.Companion.OPTION_EXTRA_LOCATIONS
import okio.Okio
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
 * A utility class that helps adding build specific objects to the jar file
 * and their extraction later on.
 *
 * Adapted from the [Android DataBinding library](https://android.googlesource.com/platform/frameworks/data-binding/+/master).
 */
class CrumbManager(private val env: ProcessingEnvironment,
    private val crumbLog: CrumbLog) {

  companion object {
    private const val CRUMB_PREFIX = "META-INF/com.uber.crumb/"
  }

  @Throws(IOException::class)
  fun <T : Serializable> load(extension: String): Set<T> {
    val fileManager = (env as JavacProcessingEnvironment).context.get(JavaFileManager::class.java)
    val classLoader = fileManager.getClassLoader(StandardLocation.CLASS_PATH)
    Preconditions.checkArgument(classLoader is URLClassLoader,
        "Classloader must be an instance of URLClassLoader. %s", classLoader)

    val urlClassLoader = classLoader as URLClassLoader
    val objects = mutableSetOf<T>()
    val extraLocations = env.options[OPTION_EXTRA_LOCATIONS]
        ?.split(":")
        ?.map { URL("file:$it") }
        ?: emptyList()
    for (url in (urlClassLoader.urLs + extraLocations)) {
      crumbLog.d("Checking url %s for crumb data", url)
      try {
        val file = File(url.toURI())
        if (!file.exists()) {
          crumbLog.d("Cannot load file for %s", url)
          continue
        }
        if (file.isDirectory) {
          // probably exported classes dir.
          loadFromDirectory(extension, file, objects)
        } else {
          // assume it is a zip file
          loadFomZipFile(extension, file, objects)
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
  private fun <T : Serializable> loadFromDirectory(extension: String, from: File,
      into: MutableSet<T>) {
    from.walkTopDown()
        .filter { it.name.endsWith(extension) }
        .forEach { file ->
          try {
            Okio.buffer(Okio.source(file)).inputStream().use {
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
            crumbLog.e(e, "Could not read crumb file. %s",
                file.absolutePath)
          }
        }
  }

  @Throws(IOException::class)
  private fun <T : Serializable> loadFomZipFile(extension: String, from: File,
      into: MutableSet<T>) {
    val zipFile = ZipFile(from)
    zipFile.entries()
        .asSequence()
        .filter { it.name.endsWith(extension) }
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
                crumbLog.e(e, "Could not merge in crumb file from %s", from.absolutePath)
              }
            }
          } catch (e: ClassNotFoundException) {
            crumbLog.e(e, "Could not read crumb file. %s", from.absolutePath)
          }
        }
  }

  @Throws(IOException::class, ClassNotFoundException::class)
  private fun fromInputStream(inputStream: InputStream): Serializable? {
    ObjectInputStream(inputStream).use { return it.readObject() as Serializable }
  }

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
              crumbLog.d("Wrote crumb file %s %s", packageName, fileName)
            }
          }
    } catch (e: IOException) {
      crumbLog.e(e, "Could not write to crumb file: %s", fileName)
    }
  }
}
