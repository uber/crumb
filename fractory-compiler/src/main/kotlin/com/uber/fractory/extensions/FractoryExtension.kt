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

package com.uber.fractory.extensions

import com.squareup.javapoet.MethodSpec
import com.uber.fractory.ExtensionArgs
import com.uber.fractory.ExtensionArgsInput

import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.Element
import javax.lang.model.element.TypeElement
import javax.lang.model.util.Elements
import javax.lang.model.util.Types

/**
 * Interface for Fractory extensions.
 */
interface FractoryExtension {

  /**
   * Determines whether or not a given type is applicable to this. Specifically, it will check if it has a
   * static method returning a JsonAdapter.
   *
   * @param processingEnv Processing environment
   * @param type the type to check
   * @return true if the type is applicable.
   */
  fun isApplicable(processingEnv: ProcessingEnvironment, type: TypeElement): Boolean

  /**
   * Checks if a given type is supported. Different than [isApplicable]
   * in that it actually looks up the type's ancestry to see if it implements a required interface.
   *
   * This is safe to call multiple times as results are cached.
   *
   * @param elementUtils Elements instance
   * @param typeUtils Types instance
   * @param type the type to check
   * @return true if supported.
   */
  fun isTypeSupported(
      elementUtils: Elements,
      typeUtils: Types,
      type: TypeElement): Boolean

  fun createFractoryImplementationMethod(elements: List<Element>,
      extras: ExtensionArgsInput): MethodSpec

  /**
   * Creates a cortex implementation method for the extension.
   *
   * @param extras extras.
   * @return the implemented create method.
   */
  fun createCortexImplementationMethod(extras: Set<ExtensionArgs>): Set<MethodSpec>
}
