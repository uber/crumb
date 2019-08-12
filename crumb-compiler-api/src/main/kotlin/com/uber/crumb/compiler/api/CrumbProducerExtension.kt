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

import com.uber.crumb.annotations.CrumbProducer
import com.uber.crumb.annotations.CrumbQualifier
import com.uber.crumb.compiler.api.CrumbExtension.IncrementalExtensionType
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.AnnotationMirror
import javax.lang.model.element.TypeElement

/**
 * Interface for [CrumbProducer] extensions.
 */
interface CrumbProducerExtension : CrumbExtension {

  /**
   * Supported producer annotations, if any, that the CrumbProcessor should collect on this
   * extension's behalf. Empty by default.
   */
  @JvmDefault
  fun supportedProducerAnnotations(): Set<Class<out Annotation>> {
    return emptySet()
  }

  /**
   * Determines whether or not a given type is applicable to this extension.
   *
   * *Note:* If you need anything from the processingEnv for later, it is recommended to save it here.
   *
   * @param context the [CrumbContext].
   * @param type the type to check.
   * @param annotations collected [CrumbQualifier]-annotated annotations on [type].
   * @return true if the type is applicable.
   */
  @JvmDefault
  fun isProducerApplicable(context: CrumbContext,
      type: TypeElement,
      annotations: Collection<AnnotationMirror>): Boolean {
    // Note: AutoCommon's MoreElements#isAnnotationPresent() is a safer option but not used here to avoid the dependency
    return supportedProducerAnnotations().any {
      type.getAnnotation(it) != null
    }
  }

  /**
   * Invoked to request this extension's [ProducerMetadata].
   *
   * @param context the [CrumbContext].
   * @param type the type this is producing from.
   * @param annotations collected [CrumbQualifier]-annotated annotations on [type].
   * @return the aggregated [ProducerMetadata].
   */
  fun produce(context: CrumbContext,
      type: TypeElement,
      annotations: Collection<AnnotationMirror>): ProducerMetadata

  /**
   * Determines the incremental type of this Extension.
   *
   * The [ProcessingEnvironment] can be used, among other things, to obtain the processor
   * options, using [ProcessingEnvironment.getOptions].
   *
   * The actual incremental type of the Crumb processor as a whole will be the loosest
   * incremental types of the Extensions present in the annotation processor path. The default
   * returned value is [IncrementalExtensionType.UNKNOWN], which will disable incremental
   * annotation processing entirely.
   */
  @JvmDefault
  fun producerIncrementalType(processingEnvironment: ProcessingEnvironment): IncrementalExtensionType {
    return IncrementalExtensionType.UNKNOWN
  }

}
