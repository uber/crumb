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
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.annotation.processing.ProcessingEnvironment
import javax.tools.JavaFileManager
import javax.tools.StandardLocation

/**
 * A utility class that helps adding build specific objects to the jar file
 * and their extraction later on.
 *
 *
 * Adapted from the DataBinding lib: https://android.googlesource.com/platform/frameworks/data-binding/+/master
 */
object GenerationalClassUtil {

  fun <T : Serializable> loadObjects(filter: ExtensionFilter, env: ProcessingEnvironment): Set<T> {
    val fileManager = (env as JavacProcessingEnvironment).context.get(JavaFileManager::class.java)
    val classLoader = fileManager.getClassLoader(StandardLocation.CLASS_PATH)
    Preconditions.checkArgument(classLoader is URLClassLoader,
        "Class loader must be an" + "instance of URLClassLoader. %s", classLoader)

    val urlClassLoader = classLoader as URLClassLoader
    val objects = mutableSetOf<T>()
    val extraLocations = env.options[OPTION_EXTRA_LOCATIONS]
        ?.split(":")
        ?.map { URL("file:$it") }
        ?: emptyList()
    for (url in (urlClassLoader.urLs + extraLocations)) {
      CrumbLog.d("checking url %s for intermediate data", url)
      try {
        val file = File(url.toURI())
        if (!file.exists()) {
          CrumbLog.d("cannot load file for %s", url)
          continue
        }
        if (file.isDirectory) {
          // probably exported classes dir.
          loadFromDirectory(file, filter, objects)
        } else {
          // assume it is a zip file
          loadFomZipFile(file, filter, objects)
        }
      } catch (e: IOException) {
        CrumbLog.d("cannot open zip file from %s", url)
      } catch (e: URISyntaxException) {
        CrumbLog.d("cannot open zip file from %s", url)
      }

    }

    return objects
  }

  private fun <T : Serializable> loadFromDirectory(directory: File, filter: ExtensionFilter,
      objects: MutableSet<T>) {
    directory.walkTopDown()
        .filter { filter.accept(it.name) }
        .forEach { file ->
          try {
            Okio.buffer(Okio.source(file)).inputStream().use {
              try {
                val item = fromInputStream(it)
                item?.let {
                  objects += (item as T)
                  CrumbLog.d("loaded item %s from file", item)
                }
              } catch (e: IOException) {
                CrumbLog.e(e, "Could not merge in intermediates from %s", file.absolutePath)
              }
            }
          } catch (e: ClassNotFoundException) {
            CrumbLog.e(e, "Could not read intermediate file. %s",
                file.absolutePath)
          }
        }
  }

  @Throws(IOException::class)
  private fun <T : Serializable> loadFomZipFile(file: File, filter: ExtensionFilter,
      objects: MutableSet<T>) {
    val zipFile = ZipFile(file)
    zipFile.entries()
        .asSequence()
        .filter { filter.accept(it.name) }
        .forEach { entry ->
          try {
            zipFile.getInputStream(entry).use {
              try {
                val item = fromInputStream(it)
                CrumbLog.d("loaded item $item from zip file")
                item?.let {
                  objects += (item as T)
                }
              } catch (e: IOException) {
                CrumbLog.e(e, "Could not merge in intermediate file from %s", file.absolutePath)
              }
            }
          } catch (e: ClassNotFoundException) {
            CrumbLog.e(e, "Could not read intermediate file. %s", file.absolutePath)
          }
        }
  }

  @Throws(IOException::class, ClassNotFoundException::class)
  private fun fromInputStream(inputStream: InputStream): Serializable? {
    ObjectInputStream(inputStream).use { return it.readObject() as Serializable }
  }

  fun writeIntermediateFile(
      processingEnv: ProcessingEnvironment,
      packageName: String,
      fileName: String,
      objectToWrite: Serializable) {
    try {
      // Try to write to kapt generated if present, otherwise fall back to standard filer output
      val intermediate = processingEnv.options["kapt.kotlin.generated"]?.let(::File)
          ?.toPath()
          ?.let {
            var outputDirectory = it
            if (packageName.isNotEmpty()) {
              for (packageComponent in packageName.split('.').dropLastWhile { it.isEmpty() }) {
                outputDirectory = outputDirectory.resolve(packageComponent)
              }
              Files.createDirectories(outputDirectory)
            }
            val outputPath = outputDirectory.resolve(fileName)
            Files.newOutputStream(outputPath)
          }
          ?: processingEnv.filer.createResource(
              StandardLocation.CLASS_OUTPUT,
              packageName,
              fileName)
              .openOutputStream()

      intermediate.use { ios ->
        ObjectOutputStream(ios).use { oos ->
          oos.writeObject(objectToWrite)
          CrumbLog.d("wrote intermediate file %s %s", packageName, fileName)
        }
      }
    } catch (e: IOException) {
      CrumbLog.e(e, "Could not write to intermediate file: %s", fileName)
    }
  }

  enum class ExtensionFilter constructor(val extension: String) {
    CRUMB("-crumbinfo.bin");

    fun accept(entryName: String): Boolean {
      return entryName.endsWith(extension)
    }
  }
}
