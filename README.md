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
metadata.

`@CrumbQualifier` - This annotation can be used on custom annotations to indicate that they
are relevant for Crumb and used by extensions.

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

## Example

Say you document experiments in an app using enums, and want to collect all these from various 
libraries at the app level to surface in an experimentation configuration.

You could manually keep track of all these experiments, but that'd be pretty annoying. Instead,
let's automate this with Crumb.

A given experiment enum looks like this in our library:

```java
public enum LibraryAExperiments {
  FOO,
  BAR,
  BAZ
}
```

We could write a Crumb extension that reads this and writes its location to the classpath. Let's
define an `@Experiments` annotation and mark this.

```java
@Experiments
@CrumbProducer
public enum LibraryAExperiments {
  FOO,
  BAR,
  BAZ
}
```

`@CrumbProducer` is a required annotation to indicate to `CrumbProcessor` that this element is 
important for producing metadata.
`@Experiments` is a custom annotation that our extension looks for in its `isProducerApplicable` 
check. If we annotate this annotation with `@CrumbQualifier`, the `CrumbProcessor` will give it 
directly to extensions for reference. You can define any custom annotation you want. Extensions also
have direct access to the `TypeElement`, so you can really use any signaling you want.

So now we've indicated on the type that we want information extracted and stored. How does this look
in our extension?

```java
@AutoService(ProducerExtension.class)
public class ExperimentsCompiler implements ProducerExtension {
  
  @Override
  public String key() {
    return "ExperimentsCompiler";
  }
  
  @Override
  public boolean isProducerApplicable(CrumbContext context,
      TypeElement type,
      Collection<AnnotationMirror> annotations) {
    // Check for the Experiments annotation here
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
also declared the key returned by `key()`. `context` is a holder class with access to the current 
`ProcessingEnvironment` and `RoundEnvironment`, `type` is the `@CrumbProducer`-annotated type, and 
`annotations` are the `@CrumbQualifier`-annotated annotations found on that `type`. For convenience,
we're going to assume that all of these holders have a static `experiments()` method.

What about the consumer side? We can make one top-level `ExperimentsHolder` class that just delegates to
discovered downstream experiments. We can generate this code directly with JavaPoet, so we'll make the
holder class abstract, then generate the implementation as a subclass.
Our desired API looks like this:

```java
public abstract class ExperimentsHolder {

  public static Map<Class, List<String>> experiments() {
    return Experiments_ExperimentsHolder.EXPERIMENTS;
  }

}
```

Let's wire Crumb here. The symmetric counterpart to `@CrumbProducer` is `@CrumbConsumer`. We can reuse
the `@Experiments` annotation here too since the `@CrumbConsumer` annotation signals that this is not
a producer context.

```java
@CrumbConsumer
@Experiments
public abstract class ExperimentsHolder {

  public static Map<Class, List<String>> experiments() {
    return Experiments_ExperimentsHolder.EXPERIMENTS;
  }

}
```

This is all the information we need for our extension
```java
@AutoService(ConsumerExtension.class)
public class ExperimentsCompiler implements ConsumerExtension {
  
  @Override
  public String key() {
    return "ExperimentsCompiler";
  }
  
  @Override
  public boolean isConsumerApplicable(CrumbContext context,
      TypeElement type,
      Collection<AnnotationMirror> annotations) {
    // Check for the Experiments annotation here
  }
  
  @Override
  public void consume(CrumbContext context,
      TypeElement type,
      Collection<AnnotationMirror> annotations,
      Set<Map<String, String>> metadata) {
    // Each map is an instance of the Map we returned in the producer above
    
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
    
    // Now that we have each enum class and their members, use 'em!
  }
}
```

This closes the loop from our producers to the consumer. Ultimately, we could leverage JavaPoet to 
generate a backing implementation that looks like this:

```java
public final class Experiments_ExperimentsHolder extends ExperimentsHolder {
  public static final Map<Class, List<String>> EXPERIMENTS = new LinkedHashMap<>();

  static {
    EXPERIMENTS.put(LibraryExperiments.class, Arrays.asList("FOO", "BAR", "BAZ"));
  }
}
```

Note that both extension examples are called `ExperimentsCompiler`. Each interface is fully interoperable
with the other, so you can make one extension that implements both interfaces for code sharing.

```java
@AutoService({ProducerExtension.class, ConsumerExtension.class})
public class ExperimentsCompiler implements ProducerExtension, ConsumerExtension {
  // ...
}
```

You can find a fleshed out version of this example under the `:sample` directory.

### Packaging

If compiling into an Android app, you probably want to exclude the crumbs from the APK. You can do
so via the `packagingOptions` closure:

```gradle
packagingOptions {
  exclude "META-INF/com-uber-crumb/**"
}
```

### Download

[![Maven Central](https://img.shields.io/maven-central/v/com.uber.crumb/crumb-compiler.svg)](https://mvnrepository.com/artifact/com.uber.crumb/crumb-compiler)
```gradle
compile 'com.uber.crumb:crumb-annotations:x.y.z'
compile 'com.uber.crumb:crumb-compiler:x.y.z'
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
