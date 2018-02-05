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

import com.google.common.collect.ImmutableSet
import com.google.common.truth.Truth.assertAbout
import com.google.testing.compile.JavaFileObjects
import com.google.testing.compile.JavaSourcesSubject
import com.google.testing.compile.JavaSourcesSubjectFactory.javaSources
import com.uber.crumb.CrumbProcessor
import org.junit.Test
import javax.tools.JavaFileObject

class CrumbProcessorTest {

  @Test
  fun testNoMatchingModelsForFactoryShouldFail() {
    val modelName = "test.Foo"
    val model = JavaFileObjects.forSourceString(modelName, """
package test;
import com.uber.crumb.annotations.CrumbConsumable;
@CrumbConsumable public abstract class Foo {
  public abstract String getName();
  public abstract boolean isAwesome();
}""")

    val factoryName = "test.MyAdapterFactory"
    val factory = JavaFileObjects.forSourceString(factoryName, """
package test;
import com.google.gson.TypeAdapterFactory;
import com.uber.crumb.annotations.CrumbProducer;
import com.uber.crumb.annotations.extensions.GsonFactory;
@GsonFactory
@CrumbProducer
public abstract class MyAdapterFactory {
  public static TypeAdapterFactory create() {
    return new GsonProducer_MyAdapterFactory();
  }
}""")

    assertAbout<JavaSourcesSubject, Iterable<JavaFileObject>>(javaSources())
        .that(ImmutableSet.of(model, factory))
        .processedWith(CrumbProcessor())
        .failsToCompile()
        .withErrorContaining("""
          |No @CrumbConsumable-annotated elements applicable for the given @CrumbProducer-annotated element with the current crumb extensions
          |  CrumbProducer: $factoryName
          |  Extension: GsonSupport
        """.trimMargin())
  }

  @Test
  fun noMatchingExtensionsShouldFail() {
    val factoryName = "test.MyAdapterFactory"
    val factory = JavaFileObjects.forSourceString(factoryName, """
package test;
import com.uber.crumb.annotations.CrumbProducer;
@CrumbProducer
public abstract class MyAdapterFactory {
  public static TypeAdapterFactory create() {
    return new GsonProducer_MyAdapterFactory();
  }
}""")

    assertAbout<JavaSourcesSubject, Iterable<JavaFileObject>>(javaSources())
        .that(ImmutableSet.of(factory))
        .processedWith(CrumbProcessor())
        .failsToCompile()
        .withErrorContaining("""
          |No extensions applicable for the given @CrumbProducer-annotated element
          |  Detected producers: [$factoryName]
          |  Available extensions: [GsonSupport, MoshiSupport]
        """.trimMargin())
  }

}
