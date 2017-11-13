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

`@CrumbProducer` - This annotation signals to the processor that this element is used to produce
metadata.

`@CrumbConsumer` - This annotation signals to the processor that this element is used to consume
metadata from downstream.

`@CrumbQualifier` - This annotation can be used on custom other annotations to indicate that they
are relevant for Crumb and given to extensions in their APIs.

`@CrumbConsumable` - A convenience annotation that can be used to indicate that this type should be
available to the Crumb processor and any of its extensions (since processors have to declare which
annotations they support).

#### Extensions API

There are two extension interfaces that follow a Producer/Consumer symmetry. The API (and compiler
implementation) are in Kotlin, but seamlessly interoperable with Java. The API is SPI-based, so you
can wire up your implementation with something like [AutoService][autoservice].

Both interfaces extend from a `CrumbExtension` base interface, that just has a method `key()`. This
method has a default implementation in Kotlin that just returns the fully qualified class name of 
the extension. This is used to key the extension name when storing and retrieving metadata.

The API usually gives a `CrumbContext` instance when calling into extensions, which just contains
useful information like references to the `ProcessingEnvironment` or `RoundEnvironment`.

`CrumbProducerExtension` - This interface is used to declare a producer extension. These extensions
are called into when a type is trying to produce metadata to write to the classpath. The API is:
  * `supportedProducerAnnotations()` - Returns a list of supported annotations. Has a default 
  implementation in Kotlin (empty), and is used to indicate to the compiler which annotations
  should be included in processing (since annotation processors have to declared which annotations 
  they need).
  * `isProducerApplicable(context: CrumbContext, type: TypeElement, annotations: Collection<AnnotationMirror>` -
  Returns a boolean indicating whether or not this producer is applicable to a given type/annotations combination.
  The `annotations` are any `@CrumbQualifier`-annotated annotations found on `type`. Extensions may use 
  whatever signaling they see fit though.
  * `produce(context: CrumbContext, type: TypeElement, annotations: Collection<AnnotationMirror>` -
  This is the call to produce metadata, and just returns a Map<String, String> (`typealias`'d in 
  Kotlin to `ProducerMetadata`). Consumers can put whatever they want in this map (so be 
  responsible!). The `type` and `annotations` parameters are the same as from `isProducerApplicable()`.

`CrumbConsumerExtension` - This interface is used to declare a consumer extension. These extensions
are called into when a type is trying to consume metadata to from the classpath. The API is:
  * `supportedConsumerAnnotations()` - Returns a list of supported annotations. Has a default 
  implementation in Kotlin (empty), and is used to indicate to the compiler which annotations
  should be included in processing (since annotation processors have to declared which annotations 
  they need).
  * `isConsumerApplicable(context: CrumbContext, type: TypeElement, annotations: Collection<AnnotationMirror>` -
  Returns a boolean indicating whether or not this consumer is applicable to a given type/annotations combination.
  The `annotations` are any `@CrumbQualifier`-annotated annotations found on `type`. Extensions may use 
  whatever signaling they see fit though.
  * `consume(context: CrumbContext, type: TypeElement, annotations: Collection<AnnotationMirror>, metadata: Set<ConsumerMetadata>)` -
  This is the call to consume metadata, and is given a Set<Map<String, String>> (`typealias`'d in 
  Kotlin to `ConsumerMetadata`). This is a set of all `ProducerMetadata` maps discovered on the 
  classpath returned for this extension's declared `key()`. The `type` and `annotations` parameters 
  are the same as from `isConsumerApplicable()`.

## Example

Gson is a popular JSON serialization library for the JVM. Its basic building blocks are 
`TypeAdapter` and `TypeAdapterFactory`, where the latter is just a factory that returns 
`TypeAdapter` implementations for a given Class/Type/etc. These adapters and factories must be 
registered with a Gson instance in order for them to be delegated to. If you are in a multi-module
project setup, or have otherwise multiple dependencies that expose adapters for serialization, this
can be inconvenient to have to manually wire up. It's error-prone, tedious, and adds friction.

Crumb can make this manageable by writing metadata about those downstream `TypeAdapterFactory`s to
the classpath for the consuming application to read.

Say you have a factory like so in Library A

```java
public class LibraryAFactory implements TypeAdapterFactory {
  // ...
  public static LibraryAFactory create() {
    // ...
  }
}
```

We could write a Crumb extension that reads this and writes its location to the classpath.

```java
@GsonFactory
@CrumbProducer
public class LibraryAFactory implements TypeAdapterFactory {
  // ...
  public static LibraryAFactory create() {
    // ...
  }
}
```

`@CrumbProducer` is a required annotation to indicate to `CrumbProcessor` that this element is 
important for producing metadata.
`@GsonFactory` is a custom annotation that our extension looks for in its `isProducerApplicable` 
check. If we annotate this annotation with `@CrumbQualifier`, the `CrumbProcessor` will give it 
directly to extensions for reference. You can define any custom annotation you want. Extensions also
have direct access to the `TypeElement`, so you can really use any signaling you want.

So now we've indicated on the type that we want information extracted and stored. How does this look
in our extension?

```java
@AutoService(ProducerExtension.class)
public class GsonExtension implements ProducerExtension {
  
  @Override
  public String key() {
    return "GsonExtension";
  }
  
  @Override
  public boolean isProducerApplicable(CrumbContext context,
      TypeElement type,
      Collection<AnnotationMirror> annotations) {
    // Check for the GsonFactory annotation here
  }
  
  @Override
  public Map<String, String> produce(CrumbContext context,
      TypeElement type,
      Collection<AnnotationMirror> annotations) {
    String fullyQualifiedName = type.getQualifiedName();
    Map<String, String> metadata = new HashMap<>();
    metadata.put("pathToClass", fullyQualifiedName);
    return metadata;
  }
}
```

And that's it! Crumb will take the returned metadata and make it available to any extension that 
also declared the key returned by `key()`. `context` is a holder class with access to the current 
`ProcessingEnvironment` and `RoundEnvironment`, `type` is the `@CrumbProducer`-annotated type, and 
`annotations` are the `@CrumbQualifier`-annotated annotations found on that `type`. For convenience,
we're going to assume that all of these factories have a static `create()` method.

What about the consumer side? We can make one top-level `TypeAdapterFactory` that just delegates to
discovered downstream factories. We can generate this code directly with Javapoet, so we'll make the
factory abstract and implement `TypeAdapterFactory`, then generate the implementation as a subclass.
Our desired API looks like this:

```java
public abstract class MyApplicationFactory implements TypeAdapterFactory {
  public static MyApplicationFactory create() {
    return new Generated_MyApplicationFactory(); // This class is generated!
  }
}
```

Let's wire Crumb here. The symmetric counterpart to `@CrumbProducer` is `@CrumbConsumer`. We can reuse
the `@GsonFactory` annotation here too since the `@CrumbConsumer` annotation signals that this is not
a producer context.

```java
@GsonFactory
@CrumbConsumer
public abstract class MyApplicationFactory implements TypeAdapterFactory {
  public static MyApplicationFactory create() {
    return new Generated_MyApplicationFactory(); // This class is generated!
  }
}
```

This is all the information we need for our extension
```java
@AutoService(ConsumerExtension.class)
public class GsonExtension implements ConsumerExtension {
  
  @Override
  public String key() {
    return "GsonExtension";
  }
  
  @Override
  public boolean isConsumerApplicable(CrumbContext context,
      TypeElement type,
      Collection<AnnotationMirror> annotations) {
    // Check for the GsonFactory annotation here
  }
  
  @Override
  public void consume(CrumbContext context,
      TypeElement type,
      Collection<AnnotationMirror> annotations,
      Set<Map<String, String>> metadata) {
    // Each map is an instance of the Map we returned in the producer above
    for (Map<String, String> map : metadata) {
      String fullyQualifiedName = map.get("pathToClass");
      // Use the fully qualified name to statically reference the downstream type!
    }
  }
}
```

This closes the loop from our producers to the consumer. Ultimately, we could leverage JavaPoet to 
generate a backing implementation that looks like this:

```java
public final class Generated_MyApplicationFactory extends MyApplicationFactory {
  public final TypeAdapterFactory libraryAFactory = LibraryAFactory.create(); // Extracted from Crumb
  
  @Override
  public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
    // ...
  }
}
```

Note that both extension examples are called `GsonExtension`. Each interface is fully interoperable
with the other, so you can make one extension that implements both interfaces for code sharing.

```java
public class GsonExtension implements ProducerExtension, ConsumerExtension {
  // ...
}
```

Note that this currently isn't supported if you use `AutoService`, but I've contributed a PR to do so
[here](https://github.com/google/auto/pull/548).

You can find a fleshed out version of this example under the `:integration` directory.

### Download

[![Maven Central](https://img.shields.io/maven-central/v/com.uber.crumb/crumb-compiler.svg)](https://mvnrepository.com/artifact/com.uber.crumb/crumb-compiler)
```gradle
compile 'com.uber.crumb:crumb-annotations:x.y.z'
compile 'com.uber.crumb:crumb-compiler:x.y.z'
```

Snapshots of the development version are available in [Sonatype's snapshots repository][snapshots].

License
-------

    Copyright (C) 2017 Uber Technologies

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
