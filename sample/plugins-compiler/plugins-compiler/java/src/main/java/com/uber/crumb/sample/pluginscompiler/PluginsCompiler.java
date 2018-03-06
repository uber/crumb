/*
 * Copyright 2018. Uber Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.uber.crumb.sample.pluginscompiler;

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
import com.uber.crumb.compiler.api.CrumbConsumerExtension;
import com.uber.crumb.compiler.api.CrumbContext;
import com.uber.crumb.compiler.api.CrumbProducerExtension;
import com.uber.crumb.sample.pluginscompiler.annotations.Plugin;
import com.uber.crumb.sample.pluginscompiler.annotations.PluginPoint;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.MirroredTypesException;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;

import static com.google.auto.common.MoreElements.isAnnotationPresent;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static javax.lang.model.element.Modifier.ABSTRACT;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;

/**
 * A simple crumb producer/consumer that reads plugin implementations from libraries and writes a
 * mapping record of them in a consuming app. Could be useful for reading plugin implementations
 * downstream, and very similar in implementation to a service loader.
 */
@AutoService({CrumbProducerExtension.class, CrumbConsumerExtension.class})
public final class PluginsCompiler implements CrumbProducerExtension, CrumbConsumerExtension {

  private static final String METADATA_KEY = "PluginsCompiler";

  @Override
  public boolean isConsumerApplicable(
      CrumbContext context, TypeElement type, Collection<? extends AnnotationMirror> annotations) {
    return isAnnotationPresent(type, PluginPoint.class);
  }

  @Override
  public boolean isProducerApplicable(
      CrumbContext context, TypeElement type, Collection<? extends AnnotationMirror> annotations) {
    return isAnnotationPresent(type, Plugin.class);
  }

  @Override
  public void consume(
      CrumbContext context,
      TypeElement type,
      Collection<? extends AnnotationMirror> annotations,
      Set<? extends Map<String, String>> metadata) {

    // Must be an abstract class because we're generating the backing implementation.
    if (type.getKind() != ElementKind.CLASS) {
      context
          .getProcessingEnv()
          .getMessager()
          .printMessage(
              Diagnostic.Kind.ERROR,
              "@"
                  + PluginPoint.class.getSimpleName()
                  + " is only applicable on classes when consuming!",
              type);
      return;
    } else if (!type.getModifiers().contains(ABSTRACT)) {
      context
          .getProcessingEnv()
          .getMessager()
          .printMessage(Diagnostic.Kind.ERROR, "Must be abstract!", type);
    }

    // Read the pluginpoint's target type, e.g. "MyPluginInterface"
    PluginPoint pluginPoint = type.getAnnotation(PluginPoint.class);
    TypeMirror targetPlugin = getTargetPlugin(pluginPoint);

    // List of plugin TypeElements
    ImmutableSet<TypeElement> pluginClasses =
        metadata
            .stream()
            .map(data -> data.get(METADATA_KEY))
            .map(
                pluginClass ->
                    context.getProcessingEnv().getElementUtils().getTypeElement(pluginClass))
            .filter(
                pluginType ->
                    context
                        .getProcessingEnv()
                        .getTypeUtils()
                        .isAssignable(pluginType.asType(), targetPlugin))
            .collect(toImmutableSet());

    FieldSpec pluginsSetField =
        FieldSpec.builder(
                ParameterizedTypeName.get(ClassName.get(Set.class), TypeName.get(targetPlugin)),
                "PLUGINS",
                PUBLIC,
                STATIC,
                FINAL)
            .initializer(CodeBlock.of("new $T<>()", LinkedHashSet.class))
            .build();

    CodeBlock.Builder staticInitBlock = CodeBlock.builder();
    pluginClasses.forEach(
        plugin ->
            staticInitBlock.addStatement(
                "$N.add(new $T())", pluginsSetField, TypeName.get(plugin.asType())));

    TypeSpec generatedClass =
        TypeSpec.classBuilder("Plugins_" + type.getSimpleName().toString())
            .addModifiers(PUBLIC, FINAL)
            .superclass(TypeName.get(type.asType()))
            .addField(pluginsSetField)
            .addStaticBlock(staticInitBlock.build())
            .build();

    try {
      JavaFile.builder(MoreElements.getPackage(type).getQualifiedName().toString(), generatedClass)
          .build()
          .writeTo(context.getProcessingEnv().getFiler());
    } catch (IOException e) {
      context
          .getProcessingEnv()
          .getMessager()
          .printMessage(
              Diagnostic.Kind.ERROR,
              "Failed to write generated plugins mapping! " + e.getMessage(),
              type);
    }
  }

  private TypeMirror getTargetPlugin(PluginPoint pluginPoint) {
    try {
      pluginPoint.value();
    } catch (MirroredTypesException e) {
      return e.getTypeMirrors().get(0);
    }
    throw new RuntimeException(
        "Could not inspect PluginPoint value. Java annotation processing is weird.");
  }

  @Override
  public Map<String, String> produce(
      CrumbContext context, TypeElement type, Collection<? extends AnnotationMirror> annotations) {
    // Must be a class
    if (type.getKind() != ElementKind.CLASS) {
      context
          .getProcessingEnv()
          .getMessager()
          .printMessage(
              Diagnostic.Kind.ERROR,
              "@" + Plugin.class.getSimpleName() + " is only applicable on classes!",
              type);
      return ImmutableMap.of();
    }

    // Must be instantiable (not abstract)
    if (type.getModifiers().contains(ABSTRACT)) {
      context
          .getProcessingEnv()
          .getMessager()
          .printMessage(
              Diagnostic.Kind.ERROR,
              "@" + Plugin.class.getSimpleName() + " is not applicable on abstract classes!",
              type);
      return ImmutableMap.of();
    }

    // If they define a default constructor, it must be public
    Optional<ExecutableElement> defaultConstructor =
        ElementFilter.constructorsIn(type.getEnclosedElements())
            .stream()
            .filter(ExecutableElement::isDefault)
            .findFirst();
    if (defaultConstructor.isPresent()
        && !defaultConstructor.get().getModifiers().contains(PUBLIC)) {
      context
          .getProcessingEnv()
          .getMessager()
          .printMessage(
              Diagnostic.Kind.ERROR,
              "Must have a public default constructor to be usable in plugin points.",
              type);
      return ImmutableMap.of();
    }

    return ImmutableMap.of(METADATA_KEY, type.getQualifiedName().toString());
  }

  @Override
  public Set<Class<? extends Annotation>> supportedConsumerAnnotations() {
    return ImmutableSet.of(PluginPoint.class);
  }

  @Override
  public Set<Class<? extends Annotation>> supportedProducerAnnotations() {
    return ImmutableSet.of(Plugin.class);
  }

  @Override
  public String key() {
    return METADATA_KEY;
  }
}
