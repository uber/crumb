/*
 * Copyright 2019. Uber Technologies
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
package com.uber.crumb.integration.localmodels;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import com.uber.crumb.annotations.CrumbConsumable;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Set;
import javax.annotation.Nullable;

@CrumbConsumable
public enum LocalEnum {
  FOO;

  public static TypeAdapter<LocalEnum> typeAdapter() {
    return new TypeAdapter<LocalEnum>() {
      @Override
      public void write(JsonWriter out, LocalEnum value) throws IOException {
        out.value(value.name().toLowerCase());
      }

      @Override
      public LocalEnum read(JsonReader in) throws IOException {
        return LocalEnum.valueOf(in.nextString().toUpperCase());
      }
    };
  }

  public static JsonAdapter.Factory jsonAdapter() {
    return new JsonAdapter.Factory() {
      @Nullable
      @Override
      public JsonAdapter<?> create(Type type, Set<? extends Annotation> annotations, Moshi moshi) {
        Class<?> rawType = Types.getRawType(type);
        if (rawType.isAssignableFrom(LocalEnum.class)) {
          return new JsonAdapter<LocalEnum>() {
            @Override
            public LocalEnum fromJson(com.squareup.moshi.JsonReader reader) throws IOException {
              return LocalEnum.valueOf(reader.nextString().toUpperCase());
            }

            @Override
            public void toJson(com.squareup.moshi.JsonWriter writer, LocalEnum value)
                throws IOException {
              writer.value(value.name().toLowerCase());
            }
          };
        }
        return null;
      }
    };
  }
}
