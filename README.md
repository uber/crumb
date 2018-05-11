Crumb
=====

Crumb is an annotation processor that exposes a simple and flexible API to breadcrumb  
metadata across compilation boundaries. Working with dependencies manually is usually fine, but there's often 
cases where developers will want to automatically gather and act on information from those dependencies (code 
generation, gathering metrics, etc). Tools like `ServiceLoader` can solve some cases like this, but lack flexibility and
can be slow at runtime.

This is where Crumb comes in. Crumb's API is an annotation-based, consumer/producer system where extensions
can opt in to consuming or producing metadata. Extensions run at compile-time to produce or consume this 
metadata, while Crumb's processor manages this metadata for them (serializing, storing, retrieving, orchestrating the 
data to appropriate consumers, etc). This allows developers to propagate arbitrary data across compilation boundaries.

Some example usages:
  * Implementing compile-time `ServiceLoader`-style automatic discovery of downstream implementations of an interface
  * Automatically gathering adapters for model serialization (such as `TypeAdapter`s for json serialization with Gson)
  * Automatic registration or reporting of experiments in feature libraries
  * Automatic registration of buildable components in a DI system, such as Dagger modules
  
More in-depth examples can be found at the bottom of this README.

## Download

[![Maven Central](https://img.shields.io/maven-central/v/com.uber.crumb/crumb-compiler.svg)](https://mvnrepository.com/artifact/com.uber.crumb/crumb-compiler)
```gradle
compile 'com.uber.crumb:crumb-annotations:x.y.z'
compile 'com.uber.crumb:crumb-core:x.y.z'
compile 'com.uber.crumb:crumb-compiler:x.y.z'
compile 'com.uber.crumb:crumb-compiler-api:x.y.z'
```

Snapshots of the development version are available in [Sonatype's snapshots repository][snapshots].

## API

### Annotations

There are four annotations in the `crumb-annotations` artifact:

`@CrumbProducer` - This annotation can be used on custom annotations to signal to the 
processor that elements annotated with the custom annotation are used to produce metadata.

`@CrumbConsumer` - This annotation can be used on custom annotations to signal to the 
processor that elements annotated with the custom annotation are used to consume metadata.

`@CrumbQualifier` - This annotation can be used on custom annotations to indicate that elements
annotated with the custom annotation are relevant for Crumb and used by extensions.

`@CrumbConsumable` - A convenience annotation that can be used to indicate that this type should be
available to the Crumb processor and any of its extensions (since processors have to declare which
annotations they support).

### Extensions API

There are two extension interfaces that follow a Producer/Consumer symmetry. The API (and compiler
implementation) is in Kotlin, but seamlessly interoperable with Java. The API is SPI-based, so implementations can be 
wired up with something like [AutoService][autoservice].

Both interfaces extend from a `CrumbExtension` base interface, that just has a method `key()`. This
method has a default implementation in Kotlin that just returns the fully qualified class name of 
the extension. This is used to key the extension name when storing and retrieving metadata.

The API usually gives a `CrumbContext` instance when calling into extensions, which just contains
useful information like references to the `ProcessingEnvironment` or `RoundEnvironment`.

`CrumbProducerExtension` - This interface is used to declare a producer extension. These extensions
are called into when a type is trying to produce metadata to write to the classpath. The API is:
  * `supportedProducerAnnotations()` - Returns a set of supported annotations. Has a default 
  implementation in Kotlin (empty), and is used to indicate to the compiler which annotations
  should be included in processing (since annotation processors have to declared which annotations 
  they need).
  * `isProducerApplicable(context: CrumbContext, type: TypeElement, annotations: Collection<AnnotationMirror>` -
  Returns a boolean indicating whether or not this producer is applicable to a given type/annotations combination.
  The `annotations` are any `@CrumbQualifier`-annotated annotations found on `type`. Extensions may use 
  whatever signaling they see fit though.
  * `produce(context: CrumbContext, type: TypeElement, annotations: Collection<AnnotationMirror>` -
  This is the call to produce metadata, and just returns a `Map<String, String>` (`typealias`'d in 
  Kotlin to `ProducerMetadata`). Consumers can put whatever they want in this map (so be 
  responsible!). The `type` and `annotations` parameters are the same as from `isProducerApplicable()`.

`CrumbConsumerExtension` - This interface is used to declare a consumer extension. These extensions
are called into when a type is trying to consume metadata to from the classpath. The API is:
  * `supportedConsumerAnnotations()` - Returns a set of supported annotations. Has a default 
  implementation in Kotlin (empty), and is used to indicate to the compiler which annotations
  should be included in processing (since annotation processors have to declared which annotations 
  they need).
  * `isConsumerApplicable(context: CrumbContext, type: TypeElement, annotations: Collection<AnnotationMirror>` -
  Returns a boolean indicating whether or not this consumer is applicable to a given type/annotations combination.
  The `annotations` are any `@CrumbQualifier`-annotated annotations found on `type`. Extensions may use 
  whatever signaling they see fit though.
  * `consume(context: CrumbContext, type: TypeElement, annotations: Collection<AnnotationMirror>, metadata: Set<ConsumerMetadata>)` -
  This is the call to consume metadata, and is given a `Set<Map<String, String>>` (`typealias`'d in 
  Kotlin to `ConsumerMetadata`). This is a set of all `ProducerMetadata` maps discovered on the 
  classpath returned for this extension's declared `key()`. The `type` and `annotations` parameters 
  are the same as from `isConsumerApplicable()`.

## CrumbManager

Crumb's core functionality can be leveraged independently from the `compiler` artifact via the 
`crumb-core` artifact. This can be useful for integration within existing tooling, and contains
a `CrumbManager` and `CrumbLog` API. The `crumb-compiler` artifact is an advanced frontend
over this utility.

`CrumbManager` has a simple `load` and `store` API, and `CrumbLog` is a logging mechanism to help
with debugging issues.

## Packaging

To exclude crumbs from being compiled into an Android application APK, add the following exclusion 
via the `packagingOptions` closure:

```gradle
packagingOptions {
  exclude "META-INF/com.uber.crumb/**"
}
```

## Kotlin Issues

There is one issue we've encountered when using this in plain Kotlin Gradle projects:

[Resources in project dependencies are not available in consuming projects](https://youtrack.jetbrains.com/issue/KT-23724)

Note that this is only when consuming data in a plain Kotlin project. Android projects work fine.

## Example: Plugin Loader

To demonstrate the functionality of Crumb, a real-world example must be used, a hypothetical plugin 
system to automatically gather and instantiate implementations of the `Translations` interface from 
downstream dependencies. Conceptually this is similar to a 
[`ServiceLoader`](https://docs.oracle.com/javase/7/docs/api/java/util/ServiceLoader.html), but at 
compile-time and with annotations.

To prevent a traditional approach of manually loading the implementations, Crumb makes it possible to 
automatically discover and utilize the `Translations` classes on the classpath.

#### Producing metadata

A given `Translations` implementation looks like this in a library:

```java
public class EnglishTranslations implements Translations {
  // Implemented stuff!
}
```

The plugin implementation then needs to be registered into the plugin manager upstream. A Crumb
extension can convey this information to consumers of the library by writing its
location to Crumb and retrieving it on the other side. For this example, a custom `@Plugin` 
annotation is used to mark these translations implementations.

```java
@CrumbProducer
public @interface Plugin {}
```

Note that it's annotated with `@CrumbProducer` so that the `CrumbProcessor` knows that this 
`@Plugin` annotation is used to produce metadata. Now this annotation can be applied to the 
implementation class:

```java
@Plugin
public class EnglishTranslations implements Translations {
  // Implemented stuff!
}
```

Now that the implementation is denoted via the `@Plugin` annotation, the next step is implementing 
the `ProducerExtension` for this:

```java
@AutoService(ProducerExtension.class)
public class PluginsCompiler implements ProducerExtension {
  
  @Override
  public String key() {
    return "PluginsCompiler";
  }
  
  @Override
  public boolean isProducerApplicable(CrumbContext context,
      TypeElement type,
      Collection<AnnotationMirror> annotations) {
    // Check for the @Plugin annotation here
  }
  
  @Override
  public Map<String, String> produce(CrumbContext context,
      TypeElement type,
      Collection<AnnotationMirror> annotations) {
    // <Error checking>
    return ImmutableMap.of(METADATA_KEY,
            type.getQualifiedName().toString());
  }
}
```

Crumb will take the returned metadata and make it available to any extension that 
also declared the key returned by `key()`. 
  * `context` is a holder class with access to the current `ProcessingEnvironment` and 
  `RoundEnvironment`
  * `type` is the `@CrumbProducer`-annotated type (`EnglishTranslations`)
  * `annotations` are the `@CrumbQualifier`-annotated annotations found on that `type`. For 
  simplicity, all holders are required to have a static `obtain()` method.

#### Consuming metadata

For the consumer side, our example will have a top-level `TranslationsPluginManager` class that just delegates to
discovered downstream translations. With a `ConsumerExtension`, downstream services can be consumed
and codegen'd directly with JavaPoet. For simplicity, this manager will follow an auto-value style 
pattern of having an abstract class with the generated implementation as a subclass.

The desired API looks like this:

```java
public abstract class TranslationsPluginManager {

  public static Set<Translations> obtain() {
    return Plugins_TranslationsPluginManager.PLUGINS;
  }

}
```

Crumb can be wired in here. The symmetric counterpart to `@CrumbProducer` is `@CrumbConsumer`, so 
this example uses a similar `@PluginPoint` annotation here for consuming. This time it's annotated
with `@CrumbConsumer` to indicate that it's for consumption.

```java
@CrumbConsumer
public @interface PluginPoint {
  /* The target plugin interface. */
  Class<?> value();
}
```

This is then added to the manager class, specifying the `Translations` class as its target interface so
that it only registers implementations of that interface.

```java
@PluginPoint(Translations.class)
public abstract class TranslationsPluginManager {

  public static Set<Translations> obtain() {
    return Plugins_TranslationsPluginManager.PLUGINS;
  }

}
```

This is all the information needed for the `ConsumerExtension`. Implementation of it looks like 
this:

```java
@AutoService(ConsumerExtension.class)
public class PluginsCompiler implements ConsumerExtension {
  
  @Override
  public String key() {
    return "PluginsCompiler";
  }
  
  @Override
  public boolean isConsumerApplicable(CrumbContext context,
      TypeElement type,
      Collection<AnnotationMirror> annotations) {
    // Check for the PluginPoint annotation here
  }
  
  @Override
  public void consume(CrumbContext context,
      TypeElement type,
      Collection<AnnotationMirror> annotations,
      Set<Map<String, String>> metadata) {
    // Each map is an instance of the Map we returned in the producer above
    
    PluginPoint targetPlugin = type.getAnnotation(PluginPoint.class).value(); // Not how it actually works, but here for readability
    
    // List of plugin TypeElements
    ImmutableSet<TypeElement> pluginClasses =
        metadata
            .stream()
            // Pull our metadata out by the key used to put it in
            .map(data -> data.get(METADATA_KEY))
            // Resolve the plugin implementation class
            .map(pluginClass ->
                context.getProcessingEnv().getElementUtils().getTypeElement(pluginClass))
            // Filter out anything that doesn't implement the targetPlugin interface
            .filter(pluginType ->
                context
                    .getProcessingEnv()
                    .getTypeUtils()
                    .isAssignable(pluginType.asType(), targetPlugin))
            .collect(toImmutableSet());
    
    // pluginClasses contains a set of all downstream plugin type implementations. This 
  }
}
```

This closes the loop from the producers to the consumer. `pluginClasses` contains a set of all 
downstream plugin type implementations and could leverage `JavaPoet` to generate a backing 
implementation that looks like this:

```java
public final class Plugins_TranslationsPluginManager extends TranslationsPluginManager {
  public static final Set<Translations> PLUGINS = new LinkedHashSet<>();

  static {
    PLUGINS.add(new EnglishTranslations());
  }
}
```

Note that both extension examples are called `PluginsCompiler`. Each interface is fully 
interoperable with the other, so it's possible to make one extension that implements both interfaces for 
code sharing.

```java
@AutoService({ProducerExtension.class, ConsumerExtension.class})
public class PluginsCompiler implements ProducerExtension, ConsumerExtension {
  // ...
}
```

The complete implemented version of this example can be found under the `:sample:plugins-compiler` 
directory.

There's also an example `experiments-compiler` demonstrating how to trace enum-denoted experiments
names to consumers.

![pluginsamplediagram](https://user-images.githubusercontent.com/1361086/38119512-2100da1a-3377-11e8-8cad-35e3938d17ec.png)

License
-------

    Copyright (C) 2018 Uber Technologies

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

 [autoservice]: https://github.com/google/auto/tree/master/service
 [snapshots]: https://oss.sonatype.org/content/repositories/snapshots/
