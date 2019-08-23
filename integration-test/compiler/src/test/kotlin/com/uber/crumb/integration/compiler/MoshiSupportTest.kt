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

package com.uber.crumb.integration.compiler

import com.google.common.collect.ImmutableSet
import com.google.common.truth.Truth.assertAbout
import com.google.testing.compile.JavaFileObjects
import com.google.testing.compile.JavaSourcesSubject
import com.google.testing.compile.JavaSourcesSubjectFactory.javaSources
import com.uber.crumb.CrumbProcessor
import org.junit.Test
import javax.tools.JavaFileObject

class MoshiSupportTest {

  @Test
  fun generatesJsonAdapterFactory() {
    val source1 = JavaFileObjects.forSourceString("test.Foo", """
package test;
import com.uber.crumb.annotations.CrumbConsumable;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
@CrumbConsumable public abstract class Foo {
  public static JsonAdapter<Foo> jsonAdapter(Moshi moshi) {
    return null;
  }
  public abstract String getName();
  public abstract boolean isAwesome();
}""")

    val source2 = JavaFileObjects.forSourceString("test.Bar", """
package test;
import com.uber.crumb.annotations.CrumbConsumable;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
@CrumbConsumable public abstract class Bar {
  public static JsonAdapter<Bar> jsonAdapter(Moshi moshi) {
    return null;
  }
  public abstract String getName();
}""")

    // Param-less adapter method
    val source3 = JavaFileObjects.forSourceString("test.Baz", """
package test;
import com.uber.crumb.annotations.CrumbConsumable;
import com.squareup.moshi.JsonAdapter;
@CrumbConsumable public abstract class Baz {
  public static JsonAdapter<Baz> jsonAdapter() {
    return null;
  }
  public abstract String getName();
}""")
    // Factory method
    val source4 = JavaFileObjects.forSourceString("test.BazFactory", """
package test;
import com.uber.crumb.annotations.CrumbConsumable;
import com.squareup.moshi.JsonAdapter;
@CrumbConsumable public abstract class BazFactory {
  public static JsonAdapter.Factory factory() {
    return null;
  }
  public abstract String getName();
}""")

    val source5 = JavaFileObjects.forSourceString("test.MyAdapterFactory", """
package test;
import com.squareup.moshi.JsonAdapter;
import com.uber.crumb.integration.annotations.MoshiFactory;
@MoshiFactory(MoshiFactory.Type.PRODUCER)
public abstract class MyAdapterFactory {
  public static JsonAdapter.Factory create() {
    return new MoshiProducer_MyAdapterFactory();
  }
}""")

    val expected = JavaFileObjects.forSourceString("test.AutoValueMoshi_MyAdapterFactory", """
package test;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import java.lang.Override;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Set;
import javax.annotation.Nullable;

final class MoshiProducer_MyAdapterFactory implements JsonAdapter.Factory {
  @Nullable
  @Override
  public JsonAdapter<?> create(Type type, Set<? extends Annotation> annotations, Moshi moshi) {
    if (!annotations.isEmpty()) return null;
    if (type.equals(Foo.class)) {
      return Foo.jsonAdapter(moshi);
    } else if (type.equals(Bar.class)) {
      return Bar.jsonAdapter(moshi);
    } else if (type.equals(Baz.class)) {
      return Baz.jsonAdapter();
    }
    JsonAdapter<?> adapter;
    if ((adapter = test.BazFactory.factory().create(type, annotations, moshi)) != null) {
      return adapter;
    }
    return null;
  }
}""")
    assertAbout<JavaSourcesSubject, Iterable<JavaFileObject>>(javaSources()).that(
        ImmutableSet.of(source1, source2, source3, source4, source5))
        .processedWith(CrumbProcessor(listOf(MoshiSupport())))
        .compilesWithoutError()
        .and()
        .generatesSources(expected)
  }
}
