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

import com.google.common.collect.ImmutableSet
import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.squareup.javapoet.AnnotationSpec
import com.squareup.javapoet.ArrayTypeName
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.NameAllocator
import com.squareup.javapoet.ParameterSpec
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeVariableName
import com.squareup.javapoet.WildcardTypeName
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonAdapter.Factory
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import com.uber.fractory.ExtensionArgs
import com.uber.fractory.ExtensionArgsInput
import com.uber.fractory.MoshiTypes
import com.uber.fractory.annotations.FractoryNode
import com.uber.fractory.asPackageAndName
import com.uber.fractory.implementsInterface
import com.uber.fractory.rawType
import java.lang.Exception
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.Element
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier.PRIVATE
import javax.lang.model.element.Modifier.PUBLIC
import javax.lang.model.element.Modifier.STATIC
import javax.lang.model.element.TypeElement
import javax.lang.model.util.ElementFilter
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic
import kotlin.String
import kotlin.properties.Delegates

typealias ModelName = String

/**
 * Gson support for Fractory.
 */
class GsonSupport : FractoryExtension {

  companion object {
    private const val AV_PREFIX = "AutoValue_"
    private const val EXTRAS_KEY = "fractory.extensions.gson"
  }

  private val metaMapAdapter = Moshi.Builder()
      .add(GsonSupportMetaAdapter.FACTORY)
      .build()
      .adapter<Map<ModelName, GsonSupportMeta>>(
          MoshiTypes.newParameterizedType(
              Map::class.java,
              String::class.java,
              GsonSupportMeta::class.java))

  override fun toString() = "GsonSupport"

  /**
   * Determines whether or not a given type is applicable to this. Specifically, it will check if it has a
   * static method returning a TypeAdapter.
   *
   * @param processingEnv Processing environment
   * @param type the type to check
   * @return true if the type is applicable.
   */
  override fun isApplicable(processingEnv: ProcessingEnvironment, type: TypeElement): Boolean {
    // check that the class contains a public static method returning a TypeAdapter
    val typeName = TypeName.get(type.asType())
    val typeAdapterType = ParameterizedTypeName.get(
        ClassName.get(TypeAdapter::class.java), typeName)
    val returnedTypeAdapter: TypeName = ElementFilter.methodsIn(type.enclosedElements)
        .filter { it.modifiers.containsAll(setOf(STATIC, PUBLIC)) }
        .find { method ->
          val returnType = TypeName.get(method.returnType)
          if (returnType == typeAdapterType) {
            return true
          }
          return@find returnType == typeAdapterType
              || returnType == typeAdapterType.rawType
              || returnType is ParameterizedTypeName && returnType.rawType == typeAdapterType.rawType
        }?.let { TypeName.get(it.returnType) } ?: return false

    // emit a warning if the user added a method returning a TypeAdapter, but not of the right type
    if (returnedTypeAdapter is ParameterizedTypeName) {
      val argument = returnedTypeAdapter.typeArguments[0]

      // If the original type uses generics, users don't have to nest the generic type args
      if (typeName is ParameterizedTypeName && typeName.rawType == argument) {
        return true
      } else {
        processingEnv.messager.printMessage(Diagnostic.Kind.WARNING,
            String.format(
                "Found public static method returning TypeAdapter<%s> on %s class. Skipping GsonTypeAdapter generation.",
                argument, type))
      }
    } else {
      processingEnv.messager.printMessage(Diagnostic.Kind.WARNING,
          "Found public static method returning TypeAdapter with no type arguments, skipping GsonTypeAdapter generation.")
    }

    return false
  }

  /**
   * Checks if a given type is supported. Different than [.isApplicable]
   * in that it actually looks up the type's ancestry to see if it implements a TypeAdapterFactory.
   *
   * This is safe to call multiple times as results are cached.
   *
   * @param elementUtils Elements instance
   * @param typeUtils Types instance
   * @param type the type to check
   * @return true if supported.
   */
  override fun isTypeSupported(
      elementUtils: Elements,
      typeUtils: Types,
      type: TypeElement): Boolean {
    return type.implementsInterface<TypeAdapterFactory>(elementUtils, typeUtils)
  }

  /**
   * Creates a fractory implementation method for the gson support. In this case, it builds the create() method of
   * [TypeAdapterFactory].
   *
   * @param elements the elements to check in the factory.
   * @return the implemented create method.
   */
  override fun createFractoryImplementationMethod(elements: List<Element>,
      extras: ExtensionArgsInput): MethodSpec {
    val gson = ParameterSpec.builder(Gson::class.java, "gson").build()
    val t = TypeVariableName.get("T")
    val type = ParameterSpec
        .builder(ParameterizedTypeName.get(ClassName.get(TypeToken::class.java), t), "type")
        .build()
    val result = ParameterizedTypeName.get(ClassName.get(TypeAdapter::class.java), t)
    val create = MethodSpec.methodBuilder("create")
        .addModifiers(PUBLIC)
        .addTypeVariable(t)
        .addAnnotation(Override::class.java)
        .addAnnotation(AnnotationSpec.builder(SuppressWarnings::class.java)
            .addMember("value", "\"unchecked\"")
            .build())
        .addParameters(ImmutableSet.of(gson, type))
        .returns(result)
        .addStatement("Class<\$T> rawType = (Class<\$T>) \$N.getRawType()", t, t, type)

    val modelsMap = mutableMapOf<String, GsonSupportMeta>()
    elements.forEachIndexed { i, element ->
      val elementType = element.rawType()
      val fqcn = elementType.toString()
      if (i == 0) {
        create.beginControlFlow("if (\$T.class.isAssignableFrom(rawType))", elementType)
      } else {
        create.nextControlFlow("else if (\$T.class.isAssignableFrom(rawType))", elementType)
      }
      getTypeAdapterMethod(element)?.let { typeAdapterMethod ->
        val typeAdapterName = typeAdapterMethod.simpleName.toString()
        val params = typeAdapterMethod.parameters
        val argCount: Int
        when {
          params == null || params.size == 0 -> {
            argCount = 0
            create.addStatement("return (TypeAdapter<\$T>) \$T.$typeAdapterName()", t,
                elementType)
          }
          params.size == 1 -> {
            argCount = 1
            create.addStatement("return (TypeAdapter<\$T>) \$T.$typeAdapterName(\$N)", t,
                elementType, gson)
          }
          else -> {
            argCount = 1
            create.addStatement(
                "return (TypeAdapter<\$T>) \$T.$typeAdapterName(\$N, (\$T) \$N)", t,
                elementType, gson, params[1], type)
          }
        }
        modelsMap.put(fqcn, GsonSupportMeta(typeAdapterName, argCount))
      }
    }
    create.nextControlFlow("else")
    create.addStatement("return null")
    create.endControlFlow()

    extras.put(EXTRAS_KEY, metaMapAdapter.toJson(modelsMap))
    return create.build()
  }

  /**
   * Creates a cortex implementation method for the gson support. In this case, it builds the create() method of
   * [TypeAdapterFactory].
   *
   * @param extras extras.
   * @return the implemented create method + any others it needs to function.
   */
  override fun createCortexImplementationMethod(extras: Set<ExtensionArgs>): Set<MethodSpec> {
    // Get a mapping of model names -> GsonSupportMeta
    val metaMaps = extras
        .filter { it.contains(EXTRAS_KEY) }
        .map { metaMapAdapter.fromJson(it[EXTRAS_KEY]!!)!! }
        .flatMap { it.entries }
        .associateBy({ it.key }, { it.value })

    // Organize them by package, so packageName -> Map<ModelName, GsonSupportMeta>
    val modelsByPackage = mutableMapOf<String, MutableMap<String, GsonSupportMeta>>()
    metaMaps.entries
        .forEach {
          val (packageName, name) = it.key.asPackageAndName()
          modelsByPackage.getOrPut(packageName, { mutableMapOf() }).put(name, it.value)
        }

    val methods = mutableSetOf<MethodSpec>()

    // NameAllocator to create valid method names
    val nameAllocator = NameAllocator()

    // Some other boilerplate we'll need
    val t = TypeVariableName.get("T")
    val result = ParameterizedTypeName.get(ClassName.get(TypeAdapter::class.java), t)
    val gson = ParameterSpec.builder(Gson::class.java, "gson").build()

    // A utility createTypeAdapter method for methods to use and not worry about reflection stuff
    val typeAdapterCreator = MethodSpec.methodBuilder("createTypeAdapter")
        .addModifiers(PRIVATE, STATIC)
        .addTypeVariable(t)
        .returns(result)
        .addParameter(String::class.java, "modelName")
        .addParameter(String::class.java, "methodName")
        .addParameter(ArrayTypeName.of(Object::class.java), "args")
        .varargs()
        .beginControlFlow("try")
        // If we have args, we need to create a Class[] to give the getMethod() call to properly resolve
        .beginControlFlow("if (args != null && args.length > 0)")
        .addStatement("\$1T[] params = new \$1T[args.length]", Class::class.java)
        .beginControlFlow("for (int i = 0; i < args.length; i++)")
        .addStatement("params[i] = args[i].getClass()")
        .endControlFlow()
        .addStatement("\treturn (\$T) \$T.forName(modelName)" +
            ".getMethod(methodName, params).invoke(null, args)",
            result,
            Class::class.java)
        .nextControlFlow("else")
        .addStatement("\treturn (\$T) \$T.forName(modelName).getMethod(methodName).invoke(null)",
            result,
            Class::class.java)
        .endControlFlow()
        .nextControlFlow("catch (\$T e)",
            Exception::class.java) // Can't use ReflectiveOperationException till API 19
        .addStatement("throw new \$T(\$S, e)", RuntimeException::class.java,
            "Cortex reflective typeAdapter " +
                "invocation failed.")
        .endControlFlow()
        .build()

    val nameResolver = MethodSpec.methodBuilder("resolveNameGsonSupport")
        .addModifiers(PRIVATE, STATIC)
        .returns(String::class.java)
        .addParameter(String::class.java, "simpleName")
        .beginControlFlow("if (simpleName.startsWith(\$S))", AV_PREFIX)
        .addStatement("return simpleName.substring(\$S.length())", AV_PREFIX)
        .nextControlFlow("else")
        .addStatement("return simpleName")
        .endControlFlow()
        .build()

    // Create the main create() method for the TypeAdapterFactory
    val type = ParameterSpec
        .builder(ParameterizedTypeName.get(ClassName.get(TypeToken::class.java), t), "type")
        .build()
    val create = MethodSpec.methodBuilder("create")
        .addModifiers(PUBLIC)
        .addTypeVariable(t)
        .addAnnotation(Override::class.java)
        .addParameters(ImmutableSet.of(gson, type))
        .returns(result)
        /*
         * First we want to pull out the package and simple names
         * The idea here is that we'll split on package names initially and then split on simple names in each
         * package-specific method's switch statements.
         *
         * This only covers the model name and autovalue name, but maybe some day we can make this more flexible
         * by going up and finding the first neuron-annotated class as the root if we need to.
         *
         * Note that we only get the package name first. If we get a match, then we snag the simple name and
         * possibly strip the AutoValue_ prefix if necessary.
         */
        .addStatement("\$T<\$T> rawType = \$N.getRawType()",
            Class::class.java,
            WildcardTypeName.supertypeOf(t),
            type)
        .addStatement("if (rawType.isPrimitive()) return null")
        .addStatement("if (!rawType.isAnnotationPresent(\$T.class)) return null",
            FractoryNode::class.java)
        .addStatement("String packageName = rawType.getPackage().getName()")

    // Begin the switch
    create.beginControlFlow("switch (packageName)", type)
    modelsByPackage.forEach { packageName, entries ->
      // Create the package-specific method
      val packageCreatorMethod = MethodSpec.methodBuilder(
          nameAllocator.newName("${packageName}TypeAdapter"))
          .addModifiers(PRIVATE, STATIC)
          .addTypeVariable(t)
          .returns(result)
          .addParameter(Gson::class.java, "gson")
          .addParameter(String::class.java, "name")
          .addCode(createPackageSwitch(packageName, entries, gson))
          .build()

      // Switch on the package name and return the result from the corresponding method
      create.addCode("case \$S:\n", packageName)
      create.addStatement("\treturn \$N(\$N, \$N(rawType.getSimpleName()))",
          packageCreatorMethod,
          gson,
          nameResolver)
      methods += packageCreatorMethod
    }

    // Default is always to return null in adapters
    create.addCode("default:\n")
        .addStatement("return null")
        .endControlFlow()

    methods += nameResolver
    methods += typeAdapterCreator
    methods += create.build()

    return methods
  }

  /**
   * Creates a package-specific `switch` implementation that switches on a simple model name.
   *
   * @param packageName the package name for this model data
   * @param data the model data for this package
   * @param gson the gson parameter to reference to if necessary
   */
  private fun createPackageSwitch(
      packageName: String,
      data: Map<String, GsonSupportMeta>,
      gson: ParameterSpec): CodeBlock {
    val code = CodeBlock.builder()
    code.beginControlFlow("switch (name)")
    data.forEach { modelName, (methodName, argCount) ->
      code.add("case \$S:\n", modelName)
      code.add(CodeBlock.builder()
          .add("\treturn createTypeAdapter(\$S, \$S",
              "$packageName.$modelName",
              methodName)
          .apply {
            if (argCount == 1) {
              // These need a gson instance to defer to for other type adapters
              add(", \$N", gson)
            }
          }
          .add(");\n")
          .build())
    }
    code.add("default:")
        .addStatement("\nreturn null")
        .endControlFlow()
    return code.build()
  }

  private fun getTypeAdapterMethod(element: Element): ExecutableElement? {
    val type = TypeName.get(element.asType())
    val typeAdapterType = ParameterizedTypeName.get(ClassName.get(TypeAdapter::class.java), type)
    return ElementFilter.methodsIn(element.enclosedElements)
        .filter { it.modifiers.containsAll(setOf(STATIC, PUBLIC)) }
        .find {
          val returnType = TypeName.get(it.returnType)
          when (returnType) {
            typeAdapterType -> return it
            is ParameterizedTypeName -> {
              val argument = returnType.typeArguments[0]

              // If the original type uses generics, user's don't have to nest the generic type args
              if (type is ParameterizedTypeName) {
                if (type.rawType == argument) {
                  return@find true
                }
              }
              false
            }
            else -> false
          }
        }
  }
}

class GsonSupportMetaAdapter : JsonAdapter<GsonSupportMeta>() {

  companion object {
    private val NAMES = arrayOf("methodName", "argCount")
    private val OPTIONS = JsonReader.Options.of(*NAMES)

    val FACTORY = Factory { type, _, _ ->
      when (type) {
        GsonSupportMeta::class.java -> GsonSupportMetaAdapter()
        else -> null
      }
    }
  }

  override fun fromJson(reader: JsonReader): GsonSupportMeta {
    var methodName by Delegates.notNull<String>()
    var argCount by Delegates.notNull<Int>()
    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.selectName(OPTIONS)) {
        0 -> methodName = reader.nextString()
        1 -> argCount = reader.nextInt()
        else -> throw IllegalArgumentException("Unrecognized name: ${reader.nextName()}")
      }
    }
    reader.endObject()
    return GsonSupportMeta(methodName, argCount)
  }

  override fun toJson(writer: com.squareup.moshi.JsonWriter, model: GsonSupportMeta?) {
    model?.run {
      writer.beginObject()
          .name("methodName")
          .value(methodName)
          .name("argCount")
          .value(argCount)
      writer.endObject()
    }
  }
}

data class GsonSupportMeta(val methodName: String, val argCount: Int)
