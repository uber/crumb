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

import com.google.gson.TypeAdapterFactory;
import com.squareup.moshi.JsonAdapter;
import com.uber.crumb.annotations.CrumbProducer;
import com.uber.crumb.annotations.extensions.GsonFactory;
import com.uber.crumb.annotations.extensions.MoshiFactory;

@GsonFactory
@MoshiFactory
@CrumbProducer
public abstract class Lib3Producer {

  public static TypeAdapterFactory gson() {
    return new GsonProducer_Lib3Producer();
  }

  public static JsonAdapter.Factory moshi() {
    return new MoshiProducer_Lib3Producer();
  }
}
