/*
 * Copyright 2018. Uber Technologies
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
package com.uber.crumb.integration.consumer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.squareup.moshi.Moshi;
import com.uber.crumb.integration.lib1.Lib1Enum;
import com.uber.crumb.integration.lib1.Lib1Model;
import com.uber.crumb.integration.lib2.Lib2Enum;
import com.uber.crumb.integration.lib2.Lib2Model;
import com.uber.crumb.integration.lib3.Lib3Enum;
import com.uber.crumb.integration.lib3.Lib3Model;
import java.io.IOException;
import org.junit.Test;

import static com.google.common.truth.Truth.assertThat;
import static junit.framework.TestCase.fail;

public final class IntegrationConsumerTest {

  // Expected JSON outputs. Note they're different because gson and moshi don't necessarily
  // output keys in the same
  // order.
  private static final String EXPECTED_GSON_JSON =
      "{\"lib1Model\":{\"foo\":\"lib1Model\"},"
          + "\"lib1Enum\":\"foo\",\"lib2Model\":{\"foo\":\"lib2Model\"},\"lib2Enum\":"
          + "\"foo\",\"lib3Model\":{\"foo\":\"lib3Model\"},\"lib3Enum\":\"foo\"}";
  private static final String EXPECTED_MOSHI_JSON =
      "{\"lib1Enum\":\"foo\",\"lib1Model\""
          + ":{\"foo\":\"lib1Model\"},\"lib2Enum\":\"foo\",\"lib2Model\":{\"foo\":\"lib2Model\"}"
          + ",\"lib3Enum\":\"foo\",\"lib3Model\":{\"foo\":\"lib3Model\"}}";

  private final Gson gson =
      new GsonBuilder().registerTypeAdapterFactory(IntegrationConsumer.gson()).create();

  private final Moshi moshi = new Moshi.Builder().add(IntegrationConsumer.moshi()).build();

  @Test
  public void verifyGson() {
    TestData data =
        new TestData(
            Lib1Model.create("lib1Model"),
            Lib1Enum.FOO,
            Lib2Model.create("lib2Model"),
            Lib2Enum.FOO,
            Lib3Model.create("lib3Model"),
            Lib3Enum.FOO);

    String json = gson.toJson(data);
    assertThat(json).isEqualTo(EXPECTED_GSON_JSON);
    TestData newData = gson.fromJson(json, TestData.class);
    assertThat(data).isEqualTo(newData);

    // Another check to make sure we're not messing up the adapter on AutoValue_ prefixes
    assertThat(gson.getAdapter(newData.lib1Model.getClass()))
        .isInstanceOf(Lib1Model.typeAdapter(gson).getClass());
  }

  @Test
  public void verifyMoshi() {
    TestData data =
        new TestData(
            Lib1Model.create("lib1Model"),
            Lib1Enum.FOO,
            Lib2Model.create("lib2Model"),
            Lib2Enum.FOO,
            Lib3Model.create("lib3Model"),
            Lib3Enum.FOO);

    String json = moshi.adapter(TestData.class).toJson(data);
    assertThat(json).isEqualTo(EXPECTED_MOSHI_JSON);
    TestData newData = null;
    try {
      newData = moshi.adapter(TestData.class).fromJson(json);
    } catch (IOException e) {
      fail("Moshi deserialization failed: " + e.getMessage());
    }
    assertThat(data).isEqualTo(newData);

    // Another check to make sure we're not messing up the adapter on AutoValue_ prefixes
    //noinspection ConstantConditions
    assertThat(moshi.adapter(newData.lib1Model.getClass()))
        .isInstanceOf(Lib1Model.jsonAdapter(moshi).getClass());
  }

  public static final class TestData {

    public final Lib1Model lib1Model;
    public final Lib1Enum lib1Enum;
    public final Lib2Model lib2Model;
    public final Lib2Enum lib2Enum;
    public final Lib3Model lib3Model;
    public final Lib3Enum lib3Enum;

    public TestData(
        Lib1Model lib1Model,
        Lib1Enum lib1Enum,
        Lib2Model lib2Model,
        Lib2Enum lib2Enum,
        Lib3Model lib3Model,
        Lib3Enum lib3Enum) {
      this.lib1Model = lib1Model;
      this.lib1Enum = lib1Enum;
      this.lib2Model = lib2Model;
      this.lib2Enum = lib2Enum;
      this.lib3Model = lib3Model;
      this.lib3Enum = lib3Enum;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      TestData testData = (TestData) o;

      if (!lib1Model.equals(testData.lib1Model)) {
        return false;
      }
      if (lib1Enum != testData.lib1Enum) {
        return false;
      }
      if (!lib2Model.equals(testData.lib2Model)) {
        return false;
      }
      if (lib2Enum != testData.lib2Enum) {
        return false;
      }
      if (!lib3Model.equals(testData.lib3Model)) {
        return false;
      }
      return lib3Enum == testData.lib3Enum;
    }

    @Override
    public int hashCode() {
      int result = lib1Model.hashCode();
      result = 31 * result + lib1Enum.hashCode();
      result = 31 * result + lib2Model.hashCode();
      result = 31 * result + lib2Enum.hashCode();
      result = 31 * result + lib3Model.hashCode();
      result = 31 * result + lib3Enum.hashCode();
      return result;
    }
  }
}
