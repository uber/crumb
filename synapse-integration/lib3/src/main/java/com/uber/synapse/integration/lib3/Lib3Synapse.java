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

package com.uber.synapse.integration.lib3;

import com.google.gson.TypeAdapterFactory;
import com.squareup.moshi.JsonAdapter;
import com.uber.synapse.annotations.Synapse;

@Synapse public abstract class Lib3Synapse implements TypeAdapterFactory, JsonAdapter.Factory {

  public static Lib3Synapse create() {
    return new Synapse_Lib3Synapse();
  }
}
