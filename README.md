Crumb
=====

Most of the time, code's interaction with its external dependencies is limited to public APIs 
defined in them, but sometimes it's convenient to be able to sprinkle in metadata or bread**crumb**s 
in the source dependency that can be read by consumers.

Crumb is a annotation processor that exposes a simple and flexible API to breadcrumb that 
information across compilation boundaries. The API is a consumer/producer system where extensions
can opt in to consuming or producing metadata. Crumb will manage this metadata for them 
(serializing, storing, retrieving, orchestrating the data to appropriate consumers, etc), and 
extensions can focus on doing whatever it is they need to do!

## API

#### Annotations

There are four annotations in the `:annotations` artifact:

`@CrumbProducer` - This annotation can be used on custom annotations to signal to the 
processor that elements annotated with the custom annotation are used to produce metadata.

`@CrumbConsumer` - This annotation can be used on custom annotations to signal to the 
processor that elements annotated with the custom annotation are used to consume metadata.

`@CrumbQualifier` - This annotation can be used on custom annotations to indicate that elements
annotated with the custom annotation are relevant for Crumb and used by extensions.

`@CrumbConsumable` - A convenience annotation that can be used to indicate that this type should be
available to the Crumb processor and any of its extensions (since processors have to declare which
annotations they support).

#### Extensions API

There are two extension interfaces that follow a Producer/Consumer symmetry. The API (and compiler
implementation) is in Kotlin, but seamlessly interoperable with Java. The API is SPI-based, so implementations can be wired up with something like [AutoService][autoservice].

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

## Example 1
#### Plugin Loader

To demonstrate the functionality of Crumb, a real-world example must be used, a hypothetical plugin 
system to automatically gather and instantiate implementations of the `Feature` interface from 
downstream dependencies. Conceptually this is similar to a 
[`ServiceLoader`](https://docs.oracle.com/javase/7/docs/api/java/util/ServiceLoader.html), but at 
compile-time and with annotations.

To prevent a traditional approach of manually loading the implementations, Crumb allows us to 
automatically discover and utilize the Feature classes amongst the dependencies.

A given feature implementation looks like this in our library:

```java
public class LibraryPluginImpl implements Feature {
  // Implemented stuff!
}
```

We could write a Crumb extension that reads this and writes its location to the classpath. Let's
define an `@Plugin` annotation to mark this.

```java
@CrumbProducer
public @interface Plugin {}
```

We annotate it with `@CrumbProducer` so that the `CrumbProcessor` knows that this `@Plugin` 
annotation is used to produce metadata. Now we can apply this annotation to our class:

```java
@Plugin
public class LibraryPluginImpl implements Feature {
  // Implemented stuff!
}
```

`@Plugin` is a custom annotation that our extension looks for in its `isProducerApplicable` 
check. You can define any custom annotation you want. Extensions also have direct access to the 
`TypeElement`, so you can really use any signaling of your choice.

So now we've indicated on the type that we want information extracted and stored. How does this look
in our extension?

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

And that's it! Crumb will take the returned metadata and make it available to any extension that 
also declared the key returned by `key()`. 
  * `context` is a holder class with access to the current `ProcessingEnvironment` and 
  `RoundEnvironment`
  * `type` is the `@CrumbProducer`-annotated type (`LibraryPluginImpl`)
  * `annotations` are the `@CrumbQualifier`-annotated annotations found on that `type`. For 
  simplicity, all holders are required to have a static `plugins()` method.

What about the consumer side? We can make one top-level `PluginManager` class that just delegates to
discovered downstream features. We can generate this code directly with JavaPoet, so we'll make the
holder class abstract, then generate the implementation as a subclass.
Our desired API looks like this:

```java
public abstract class PluginManager {

  public static Set<Feature> plugins() {
    return Plugins_PluginManager.PLUGINS;
  }

}
```

Let's wire Crumb here. The symmetric counterpart to `@CrumbProducer` is `@CrumbConsumer`. We can 
make a similar annotation here for consuming:

```java
@CrumbConsumer
public @interface PluginPoint {
  /* The target plugin interface. */
  Class<?> value();
}
```

Then add this annotation to the holder:

```java
@PluginPoint(Feature.class)
public abstract class PluginManager {

  public static Set<Feature> plugins() {
    return Plugins_PluginManager.PLUGINS;
  }

}
```

This is all the information we need for the extension!

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
    
    // Now that we have each plugin class, use 'em!
  }
}
```

This closes the loop from our producers to the consumer. Ultimately, we could leverage `JavaPoet` to 
generate a backing implementation that looks like this:

```java
public final class Plugins_PluginManager extends PluginManager {
  public static final Set<Feature> PLUGINS = new LinkedHashSet<>();

  static {
    PLUGINS.add(new LibraryPluginImpl());
  }
}
```

Note that both extension examples are called `PluginsCompiler`. Each interface is fully 
interoperable with the other, so you could make one extension that implements both interfaces for 
code sharing.

```java
@AutoService({ProducerExtension.class, ConsumerExtension.class})
public class PluginsCompiler implements ProducerExtension, ConsumerExtension {
  // ...
}
```

You can find the complete implemented version of this example under the `:sample:plugins-compiler` 
directory.

There's also an example `experiments-compiler` demonstrating how to trace enum-denoted experiments
names to consumers.

### Packaging

If compiling into an Android app, you probably want to exclude the crumbs from the APK. You can do
so via the `packagingOptions` closure:

```gradle
packagingOptions {
  exclude "META-INF/com.uber.crumb/**"
}
```

### Download

[![Maven Central](https://img.shields.io/maven-central/v/com.uber.crumb/crumb-compiler.svg)](https://mvnrepository.com/artifact/com.uber.crumb/crumb-compiler)
```gradle
compile 'com.uber.crumb:crumb-annotations:x.y.z'
compile 'com.uber.crumb:crumb-compiler:x.y.z'
compile 'com.uber.crumb:crumb-compiler-api:x.y.z'
```

Snapshots of the development version are available in [Sonatype's snapshots repository][snapshots].

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

 [autoservice]: https://github.com/google/auto/service
 [snapshots]: https://oss.sonatype.org/content/repositories/snapshots/
