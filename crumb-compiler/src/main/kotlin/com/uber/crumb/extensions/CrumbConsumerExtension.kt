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

package com.uber.crumb.extensions

import com.squareup.javapoet.TypeSpec
import com.uber.crumb.ConsumerMetadata
import com.uber.crumb.CrumbContext
import javax.lang.model.element.AnnotationMirror
import javax.lang.model.element.TypeElement

/**
 * Interface for CrumbConsumer extensions.
 */
interface CrumbConsumerExtension : CrumbExtension {

  fun supportedConsumerAnnotations(): Set<Class<out Annotation>> {
    return emptySet()
  }

  /**
   * Determines whether or not a given type is applicable to this.
   *
   * Note: If you need anything from the processingEnv, it is recommended to save it here.
   *
   * @param context CrumbContext
   * @param type the type to check
   * @param annotations the Crumb annotations on this type
   * @return true if the type is applicable.
   */
  fun isConsumerApplicable(context: CrumbContext,
      type: TypeElement,
      annotations: Collection<AnnotationMirror>): Boolean

  /**
   * Creates a cortex implementation method for the extension.
   *
   * @param context CrumbContext
   * @param type in-progress [TypeSpec.Builder].
   * @param extras extras.
   */
  fun consume(context: CrumbContext,
      type: TypeElement,
      annotations: Collection<AnnotationMirror>,
      metadata: Set<ConsumerMetadata>)
}
