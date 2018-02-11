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

package com.uber.crumb.sample.experimentenumscompiler;

import com.google.auto.common.MoreElements;
import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.uber.crumb.CrumbContext;
import com.uber.crumb.extensions.CrumbConsumerExtension;
import com.uber.crumb.extensions.CrumbProducerExtension;
import com.uber.crumb.sample.experimentsenumscompiler.annotations.Experiments;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

import static com.google.auto.common.MoreElements.asType;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static javax.lang.model.element.Modifier.ABSTRACT;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;

/**
 * A simple crumb producer/consumer that reads "experiment enums" from libraries and writes a
 * mapping record of them in a consuming app. Could be useful for tracking what experiments are on
 * the classpath.
 */
@AutoService({ CrumbProducerExtension.class, CrumbConsumerExtension.class })
public final class ExperimentsCompiler implements CrumbProducerExtension, CrumbConsumerExtension {

  private static final String EXPERIMENTS_NAME = Experiments.class.getCanonicalName();
  private static final String METADATA_KEY = "ExperimentsCompiler";

  @Override public boolean isConsumerApplicable(CrumbContext context,
      TypeElement type,
      Collection<? extends AnnotationMirror> annotations) {
    return isExperimentsAnnotationPresent(annotations);
  }

  @Override public boolean isProducerApplicable(CrumbContext context,
      TypeElement type,
      Collection<? extends AnnotationMirror> annotations) {
    return isExperimentsAnnotationPresent(annotations);
  }

  private boolean isExperimentsAnnotationPresent(Collection<? extends AnnotationMirror>
      annotations) {
    for (AnnotationMirror annotation : annotations) {
      TypeElement annotationTypeElement = asType(annotation.getAnnotationType()
          .asElement());
      if (annotationTypeElement.getQualifiedName()
          .contentEquals(EXPERIMENTS_NAME)) {
        return true;
      }
    }
    return false;
  }

  @Override public void consume(CrumbContext context,
      TypeElement type,
      Collection<? extends AnnotationMirror> annotations,
      Set<? extends Map<String, String>> metadata) {

    // Must be an abstract class because we're generating the backing implementation.
    if (type.getKind() != ElementKind.CLASS) {
      context.getProcessingEnv()
          .getMessager()
          .printMessage(Diagnostic.Kind.ERROR,
              "@"
                  + Experiments.class.getSimpleName()
                  + " is only applicable on classes when consuming!",
              type);
      return;
    } else if (!type.getModifiers()
        .contains(ABSTRACT)) {
      context.getProcessingEnv()
          .getMessager()
          .printMessage(Diagnostic.Kind.ERROR, "Must be abstract!", type);
    }

    // Map of enum TypeElement to its members
    Map<TypeElement, Set<String>> experimentClasses = metadata.stream()
        .map(data -> data.get(METADATA_KEY))
        .map(enumClass -> context.getProcessingEnv()
            .getElementUtils()
            .getTypeElement(enumClass))
        .collect(toMap(typeElement -> typeElement,
            typeElement -> typeElement.getEnclosedElements()
                .stream()
                .filter(e -> e.getKind() == ElementKind.ENUM_CONSTANT)
                .map(Object::toString)
                .collect(toSet())));

    FieldSpec mapField = FieldSpec.builder(ParameterizedTypeName.get(ClassName.get(Map.class),
        TypeName.get(Class.class),
        ParameterizedTypeName.get(List.class, String.class)), "EXPERIMENTS", PUBLIC, STATIC, FINAL)
        .initializer(CodeBlock.of("new $T<>()", LinkedHashMap.class))
        .build();

    CodeBlock.Builder staticInitBlock = CodeBlock.builder();
    experimentClasses.forEach((key, value) -> staticInitBlock.addStatement(
        "$N.put($T.class, $T.asList($L))",
        mapField,
        TypeName.get(key.asType()),
        TypeName.get(Arrays.class),
        String.join(", ",
            value.stream()
                .map(s -> "\"" + s + "\"")
                .collect(toList()))));

    TypeSpec generatedClass = TypeSpec.classBuilder("Experiments_" + type.getSimpleName()
        .toString())
        .addModifiers(PUBLIC, FINAL)
        .superclass(TypeName.get(type.asType()))
        .addField(mapField)
        .addStaticBlock(staticInitBlock.build())
        .build();

    try {
      JavaFile.builder(MoreElements.getPackage(type)
          .getQualifiedName()
          .toString(), generatedClass)
          .build()
          .writeTo(context.getProcessingEnv()
              .getFiler());
    } catch (IOException e) {
      context.getProcessingEnv()
          .getMessager()
          .printMessage(Diagnostic.Kind.ERROR,
              "Failed to write generated experiments mapping! " + e.getMessage(),
              type);
    }
  }

  @Override public Map<String, String> produce(CrumbContext context,
      TypeElement type,
      Collection<? extends AnnotationMirror> annotations) {
    if (type.getKind() != ElementKind.ENUM) {
      context.getProcessingEnv()
          .getMessager()
          .printMessage(Diagnostic.Kind.ERROR,
              "@"
                  + Experiments.class.getSimpleName()
                  + " is only applicable on enums when producing!",
              type);
      return ImmutableMap.of();
    }
    return ImmutableMap.of(METADATA_KEY,
        type.getQualifiedName()
            .toString());
  }

  @Override public Set<Class<? extends Annotation>> supportedConsumerAnnotations() {
    return ImmutableSet.of(Experiments.class);
  }

  @Override public Set<Class<? extends Annotation>> supportedProducerAnnotations() {
    return ImmutableSet.of(Experiments.class);
  }

  @Override public String key() {
    return METADATA_KEY;
  }
}
