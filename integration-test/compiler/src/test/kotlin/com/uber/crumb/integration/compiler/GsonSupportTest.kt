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
package com.uber.crumb.integration.compiler

import com.google.common.collect.ImmutableSet
import com.google.common.truth.Truth.assertAbout
import com.google.testing.compile.JavaFileObjects
import com.google.testing.compile.JavaSourcesSubject
import com.google.testing.compile.JavaSourcesSubjectFactory.javaSources
import com.uber.crumb.CrumbProcessor
import org.junit.Test
import javax.tools.JavaFileObject

class GsonSupportTest {

  @Test
  fun generatesTypeAdapterFactory() {
    val source1 = JavaFileObjects.forSourceString(
      "test.Foo",
      """
package test;
import com.uber.crumb.annotations.CrumbConsumable;
import com.google.gson.TypeAdapter;
import com.google.gson.Gson;
@CrumbConsumable public abstract class Foo {
  public static TypeAdapter<Foo> typeAdapter(Gson gson) {
      return null;
  }
  public abstract String getName();
  public abstract boolean isAwesome();
}"""
    )

    val source2 = JavaFileObjects.forSourceString(
      "test.Bar",
      """
package test;
import com.uber.crumb.annotations.CrumbConsumable;
import com.google.gson.TypeAdapter;
import com.google.gson.Gson;
@CrumbConsumable public abstract class Bar {
  public static TypeAdapter<Bar> jsonAdapter(Gson gson) {
    return null;
  }
  public abstract String getName();
}"""
    )

    val source3 = JavaFileObjects.forSourceString(
      "test.MyAdapterFactory",
      """
package test;
import com.google.gson.TypeAdapterFactory;
import com.uber.crumb.integration.annotations.GsonFactory;
@GsonFactory(GsonFactory.Type.PRODUCER)
public abstract class MyAdapterFactory {
  public static TypeAdapterFactory create() {
      return new GsonProducer_MyAdapterFactory();
        }
}"""
    )

    val expected = JavaFileObjects.forSourceString(
      "test.GsonProducer_MyAdapterFactory",
      """
package test;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import java.lang.Override;
import java.lang.SuppressWarnings;
import javax.annotation.Nullable;

final class GsonProducer_MyAdapterFactory implements TypeAdapterFactory {
  @Nullable
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
}"""
    )

    assertAbout<JavaSourcesSubject, Iterable<JavaFileObject>>(javaSources())
      .that(ImmutableSet.of(source1, source2, source3))
      .processedWith(CrumbProcessor(listOf(GsonSupport())))
      .compilesWithoutError()
      .and()
      .generatesSources(expected)
  }
}
