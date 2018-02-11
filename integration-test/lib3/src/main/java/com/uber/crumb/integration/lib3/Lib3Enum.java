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

package com.uber.crumb.integration.lib3;

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

@CrumbConsumable public enum Lib3Enum {
  FOO;

  public static TypeAdapter<Lib3Enum> typeAdapter() {
    return new TypeAdapter<Lib3Enum>() {
      @Override public void write(JsonWriter out, Lib3Enum value) throws IOException {
        out.value(value.name()
            .toLowerCase());
      }

      @Override public Lib3Enum read(JsonReader in) throws IOException {
        return Lib3Enum.valueOf(in.nextString()
            .toUpperCase());
      }
    };
  }

  public static JsonAdapter.Factory jsonAdapter() {
    return new JsonAdapter.Factory() {
      @Nullable @Override
      public JsonAdapter<?> create(Type type, Set<? extends Annotation> annotations, Moshi moshi) {
        Class<?> rawType = Types.getRawType(type);
        if (rawType.isAssignableFrom(Lib3Enum.class)) {
          return new JsonAdapter<Lib3Enum>() {
            @Override public Lib3Enum fromJson(com.squareup.moshi.JsonReader reader)
                throws IOException {
              return Lib3Enum.valueOf(reader.nextString()
                  .toUpperCase());
            }

            @Override public void toJson(com.squareup.moshi.JsonWriter writer, Lib3Enum value)
                throws IOException {
              writer.value(value.name()
                  .toLowerCase());
            }
          };
        }
        return null;
      }
    };
  }
}
