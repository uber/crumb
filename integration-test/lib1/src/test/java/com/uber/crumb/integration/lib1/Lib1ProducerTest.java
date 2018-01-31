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

package com.uber.crumb.integration.lib1;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import org.junit.Test;

import static com.google.common.truth.Truth.assertThat;
import static junit.framework.TestCase.fail;

public final class Lib1ProducerTest {

  private static final String EXPECTED_MODEL_JSON = "{\"foo\":\"foo\"}";
  private static final String EXPECTED_ENUM_JSON = "\"foo\"";
  private final Gson gson = new GsonBuilder().registerTypeAdapterFactory(Lib1Producer.gson())
      .create();
  private final Moshi moshi = new Moshi.Builder().add(Lib1Producer.moshi())
      .build();

  @Test public void verifyGson() {
    Lib1Model model = Lib1Model.create("foo");
    String modelJson = gson.toJson(model);
    assertThat(modelJson).isEqualTo(EXPECTED_MODEL_JSON);
    Lib1Model returnedModel = gson.fromJson(modelJson, Lib1Model.class);
    assertThat(returnedModel).isEqualTo(model);

    Lib1Enum lib1Enum = Lib1Enum.FOO;
    String lib1EnumJson = gson.toJson(lib1Enum);
    assertThat(lib1EnumJson).isEqualTo(EXPECTED_ENUM_JSON);
    Lib1Enum returnedEnum = gson.fromJson(lib1EnumJson, Lib1Enum.class);
    assertThat(returnedEnum).isEqualTo(lib1Enum);
  }

  @Test public void verifyMoshi() {
    Lib1Model model = Lib1Model.create("foo");
    String modelJson = moshi.adapter(Lib1Model.class)
        .toJson(model);
    assertThat(modelJson).isEqualTo(EXPECTED_MODEL_JSON);
    Lib1Model returnedModel = null;
    try {
      returnedModel = moshi.adapter(Lib1Model.class)
          .fromJson(modelJson);
    } catch (IOException e) {
      fail("Moshi model deserialization failed: " + e.getMessage());
    }
    assertThat(returnedModel).isEqualTo(model);

    Lib1Enum lib1Enum = Lib1Enum.FOO;
    String lib1EnumJson = moshi.adapter(Lib1Enum.class)
        .toJson(lib1Enum);
    assertThat(lib1EnumJson).isEqualTo(EXPECTED_ENUM_JSON);
    Lib1Enum returnedEnum = null;
    try {
      returnedEnum = moshi.adapter(Lib1Enum.class)
          .fromJson(lib1EnumJson);
    } catch (IOException e) {
      fail("Moshi enum deserialization failed: " + e.getMessage());
    }
    assertThat(returnedEnum).isEqualTo(lib1Enum);
  }
}
