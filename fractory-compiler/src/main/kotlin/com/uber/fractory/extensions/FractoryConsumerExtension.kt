package com.uber.fractory.extensions

import com.squareup.javapoet.TypeSpec
import com.uber.fractory.ConsumerMetadata
import com.uber.fractory.FractoryContext
import javax.lang.model.element.AnnotationMirror
import javax.lang.model.element.TypeElement

/**
 * Interface for FractoryConsumer extensions.
 */
interface FractoryConsumerExtension : FractoryExtension {

  fun supportedConsumerAnnotations(): Set<Annotation> {
    return emptySet()
  }

  /**
   * Determines whether or not a given type is applicable to this.
   *
   * Note: If you need anything from the processingEnv, it is recommended to save it here.
   *
   * @param context FractoryContext
   * @param type the type to check
   * @param annotations the Fractory annotations on this type
   * @return true if the type is applicable.
   */
  fun isConsumerApplicable(context: FractoryContext,
      type: TypeElement,
      annotations: Collection<AnnotationMirror>): Boolean

  /**
   * Creates a cortex implementation method for the extension.
   *
   * @param context FractoryContext
   * @param type in-progress [TypeSpec.Builder].
   * @param extras extras.
   */
  fun consume(context: FractoryContext,
      type: TypeElement,
      extras: Set<ConsumerMetadata>)
}
