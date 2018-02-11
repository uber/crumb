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

package com.uber.crumb.compiler.api

/**
 * Base extension for CrumbExtensions. This class isn't one you would implement directly.
 */
interface CrumbExtension {

  /**
   * @return the [ExtensionKey] for this extension, used to indicate what key to use in storing/retrieving
   * metadata from the classpath. This is the key that [CrumbProducerExtension] data is written to
   * and [CrumbConsumerExtension] data is read from. By default, it's the name of the extension class.
   */
  fun key(): ExtensionKey {
    return javaClass.name
  }

}
