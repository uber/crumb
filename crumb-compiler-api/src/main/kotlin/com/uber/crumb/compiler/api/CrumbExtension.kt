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
   * Convenience init callback when extension processing is beginning.
   */
  @JvmDefault
  fun init(context: CrumbContext) {

  }

  /**
   * @return the [ExtensionKey] for this extension, used to indicate what key to use in storing/retrieving
   * metadata from the classpath. This is the key that [CrumbProducerExtension] data is written to
   * and [CrumbConsumerExtension] data is read from. By default, it's the name of the extension class.
   */
  fun key(): ExtensionKey {
    return javaClass.name
  }

  /**
   * Indicates to an annotation processor environment supporting incremental annotation processing
   * (currently a feature specific to Gradle starting with version 4.8) the incremental type of an
   * Extension.
   *
   * The constants for this enum are ordered by increasing performance (but also constraints).
   *
   * @see [Gradle documentation of its incremental annotation processing](https://docs.gradle.org/current/userguide/java_plugin.html.sec:incremental_annotation_processing)
   */
  enum class IncrementalExtensionType {
    /**
     * The incrementality of this extension is unknown, or it is neither aggregating nor isolating.
     */
    UNKNOWN,

    /**
     * This extension is *aggregating*, meaning that it may generate outputs based on several
     * annotated input classes and it respects the constraints imposed on aggregating processors.
     * It is common for Crumb producer extensions to be aggregating and unusual for consumer
     * extensions to be aggregating.
     *
     * @see [Gradle definition of aggregating processors](https://docs.gradle.org/current/userguide/java_plugin.html.aggregating_annotation_processors)
     */
    AGGREGATING,

    /**
     * This extension is *isolating*, meaning roughly that its output depends on the
     * `@CrumbConsumer`/`@CrumbProducer` class and its dependencies, but not on other `@CrumbConsumer`/`@CrumbProducer`
     * classes that might be compiled at the same time. The constraints that an isolating extension must
     * respect are the same as those that Gradle imposes on an isolating annotation processor.
     * It is unusual for Crumb producer extensions to be isolating and common for consumer
     * extensions to be isolating.
     *
     * @see [Gradle definition of isolating processors](https://docs.gradle.org/current/userguide/java_plugin.html.isolating_annotation_processors)
     */
    ISOLATING
  }
}
