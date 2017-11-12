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

package com.uber.fractory

import com.google.common.collect.ImmutableSet
import com.google.common.truth.Truth.assertAbout
import com.google.testing.compile.JavaFileObjects
import com.google.testing.compile.JavaSourcesSubject
import com.google.testing.compile.JavaSourcesSubjectFactory.javaSources
import org.junit.Test
import javax.tools.JavaFileObject

class FractoryProcessorTest {

  @Test
  fun notAbstractShouldFail() {
    val source1 = JavaFileObjects.forSourceString("test.Foo", """
package test;
import com.uber.fractory.annotations.FractoryConsumable;
import com.google.gson.TypeAdapter;
import com.google.gson.Gson;
@FractoryConsumable public abstract class Foo {
  public static TypeAdapter<Foo> typeAdapter(Gson gson) {
    return null;
  }
  public abstract String getName();
  public abstract boolean isAwesome();
}""")

    val source2 = JavaFileObjects.forSourceString("test.MyAdapterFactory", """
package test;
import com.google.gson.TypeAdapterFactory;
import com.uber.fractory.annotations.FractoryProducer;
@FractoryProducer
public class MyAdapterFactory implements TypeAdapterFactory {
  public static TypeAdapterFactory create() {
    return new FractoryProducer_MyAdapterFactory();
  }
}""")

    assertAbout<JavaSourcesSubject, Iterable<JavaFileObject>>(javaSources())
        .that(ImmutableSet.of(source1, source2))
        .processedWith(FractoryProcessor())
        .failsToCompile()
        .withErrorContaining("Must be abstract!")
  }

  @Test
  fun testSearchUpComplexAncestry() {
    val source1 = JavaFileObjects.forSourceString("test.Foo", """
package test;
import com.uber.fractory.annotations.FractoryConsumable;
import com.google.gson.TypeAdapter;
import com.google.gson.Gson;
@FractoryConsumable public abstract class Foo {
  public static TypeAdapter<Foo> typeAdapter(Gson gson) {
    return null;
  }
  public abstract String getName();
  public abstract boolean isAwesome();
}""")

    val source2 = JavaFileObjects.forSourceString("test.Bar", """
package test;
import com.uber.fractory.annotations.FractoryConsumable;
import com.google.gson.TypeAdapter;
import com.google.gson.Gson;
@FractoryConsumable public abstract class Bar {
  public static TypeAdapter<Bar> jsonAdapter(Gson gson) {
    return null;
  }
  public abstract String getName();
}""")

    val source3 = JavaFileObjects.forSourceString("test.IMyAdapterFactoryBase", """
package test;
import com.google.gson.TypeAdapterFactory;
public interface IMyAdapterFactoryBase extends TypeAdapterFactory {
}""")

    val source4 = JavaFileObjects.forSourceString("test.MyAdapterFactory", """
package test;
import com.google.gson.TypeAdapterFactory;
import com.uber.fractory.annotations.FractoryProducer;
@FractoryProducer
public abstract class MyAdapterFactory implements IMyAdapterFactoryBase {
  public static TypeAdapterFactory create() {
    return new FractoryProducer_MyAdapterFactory();
  }
}""")

    val expected = JavaFileObjects.forSourceString("test.FractoryProducer_MyAdapterFactory", """
package test;
import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import java.lang.Override;
import java.lang.SuppressWarnings;

final class FractoryProducer_MyAdapterFactory extends MyAdapterFactory {
  @Override
  @SuppressWarnings("unchecked")
  public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
    Class<T> rawType = (Class<T>) type.getRawType();
    if (Foo.class.isAssignableFrom(rawType)) {
      return (TypeAdapter<T>) Foo.typeAdapter(gson);
    } else if (Bar.class.isAssignableFrom(rawType)) {
      return (TypeAdapter<T>) Bar.jsonAdapter(gson);
    } else {
      return null;
    }
  }
}""")

    assertAbout<JavaSourcesSubject, Iterable<JavaFileObject>>(javaSources())
        .that(ImmutableSet.of(source1, source2, source3, source4))
        .processedWith(FractoryProcessor())
        .compilesWithoutError()
        .and()
        .generatesSources(expected)
  }

  @Test
  fun testNoMatchingModelsForFactoryShouldFail() {
    val modelName = "test.Foo"
    val model = JavaFileObjects.forSourceString(modelName, """
package test;
import com.uber.fractory.annotations.FractoryConsumable;
@FractoryConsumable public abstract class Foo {
  public abstract String getName();
  public abstract boolean isAwesome();
}""")

    val factoryName = "test.MyAdapterFactory"
    val factory = JavaFileObjects.forSourceString(factoryName, """
package test;
import com.google.gson.TypeAdapterFactory;
import com.uber.fractory.annotations.FractoryProducer;
@FractoryProducer
public abstract class MyAdapterFactory implements TypeAdapterFactory {
  public static TypeAdapterFactory create() {
    return new FractoryProducer_MyAdapterFactory();
  }
}""")

    assertAbout<JavaSourcesSubject, Iterable<JavaFileObject>>(javaSources())
        .that(ImmutableSet.of(model, factory))
        .processedWith(FractoryProcessor())
        .failsToCompile()
        .withErrorContaining("""
          |No @FractoryConsumable-annotated elements applicable for the given @FractoryProducer-annotated element with the current fractory extensions
          |  FractoryProducer: $factoryName
          |  Extension: GsonSupport
        """.trimMargin())
  }

  @Test
  fun noMatchingExtensionsShouldFail() {
    val factoryName = "test.MyAdapterFactory"
    val factory = JavaFileObjects.forSourceString(factoryName, """
package test;
import com.uber.fractory.annotations.FractoryProducer;
@FractoryProducer
public abstract class MyAdapterFactory {
  public static TypeAdapterFactory create() {
    return new FractoryProducer_MyAdapterFactory();
  }
}""")

    assertAbout<JavaSourcesSubject, Iterable<JavaFileObject>>(javaSources())
        .that(ImmutableSet.of(factory))
        .processedWith(FractoryProcessor())
        .failsToCompile()
        .withErrorContaining("""
          |No extensions applicable for the given @FractoryProducer-annotated element
          |  Detected producers: [$factoryName]
          |  Available extensions: [GsonSupport, MoshiSupport]
        """.trimMargin())
  }

}
