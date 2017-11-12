package com.uber.fractory.extensions

import com.squareup.javapoet.TypeSpec
import com.uber.fractory.FractoryContext
import com.uber.fractory.ProducerMetadata
import javax.lang.model.element.AnnotationMirror
import javax.lang.model.element.TypeElement

/**
 * Interface for [FractoryProducer] extensions.
 */
interface FractoryProducerExtension : FractoryExtension {

  fun supportedProducerAnnotations(): Set<Annotation> {
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
  fun isProducerApplicable(context: FractoryContext,
      type: TypeElement,
      annotations: Collection<AnnotationMirror>): Boolean

  /**
   * Invoked to tell the
   *
   * @param context FractoryContext
   * @param builder in-progress [TypeSpec.Builder].
   * @return the arguments
   */
  fun produce(context: FractoryContext,
      type: TypeElement,
      annotations: Collection<AnnotationMirror>): ProducerMetadata

}
