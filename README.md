# Fractory

Factory, factory-factory, and factory-composition generation. Not a typo.

Factories are great. Common in java and can often allow for numerous levels of composition and 
indirection. At Uber, we generate our model classes as gson serializable AutoValue classes. For each
 module, we generate an adapter factory (think `TypeAdapterFactory` for Gson or 
 `JsonAdapter.Factory` for Moshi) to handle serialization of all the models in that module via 
 single point of entry. `auto-value-gson` and `auto-value-moshi` help solve this issue with their 
factory processors, but they're strictly limited to AutoValue. We needed some added flexibility 
in order to support non-AutoValue classes or no-arg `____adapter()` methods and also a means to 
easily support Moshi and Gson simultaneously (during a transition). 

This is where `@Fractory` comes in, which is an equivalent functionality-wise of 
`auto-value-gson/moshi`'s factories but with the ability to do the above. It also starts to 
separate the main factory into something that supports an extensions API (albeit gson and moshi 
support are both still just packaged in to the artifact, but easily extracted now). In order for 
models to be opted in, they must be annotated with `@FractoryNode`. Fractory looks for them right now, but 
plan is to extend this to allow for customizing what other annotations it can look for. FractoryNode is 
used since `@AutoValue` can't be used for all cases, and also to not tightly couple this to it.

Carrying on with the models example, we eventually hit another challenge - multiple modules. While 
manually adding one module's factory to your gson/moshi instance is easy, adding *n* number of them 
from unknown, possibly changing sources (depending on dependencies) becomes a much harder task to 
solve. This is where `@Cortex` comes in. Cortex support is basically the generation of one 
per-module (usually per-app) factory that delegates to any `@Fractory`-annotated factories found on 
the classpath. This means that if you have libraries A, B, and C on your dependencies with their own
 fractory factories and module D with a cortex implementation, D's cortex will be a factory that 
delegates to A, B, and C's factories. This layer of indirection solves the above problem.

Cortex support works by taking a feather out of android databinding's cap and sprinkling 
breadcrumbs into the jars for reading later. During Fractory processing, metadata about the 
fractory-using modules is written into the classpath (and subsequently into the final jar) that 
cortex processing later will dig out of the classpath. 

Initially this was simply writing the fully qualified classname of the fractory factory to a file 
with a known extension, then the generated cortex would just be a factory with those classes inlined
 as fields and delegated to. We had issues with LinearAlloc later though, and in an effort to reduce
 the number of classes on the classpath, a lazy reflection-based approach was implemented instead. 
This approach actually doesn't use the fractory factories at all currently (though they are 
generated) and instead packs in a lot more information about the models in that module with a little
 information about them (adapter method signature, name, etc). The generated cortex is a relatively 
large class that reads like this:
  - if the `type`'s `rawType` is annotated with `@FractoryNode`
  - extract the package name and class name
  - switch on the package name, if it matches, call the corresponding generated method with handling
    for that module (this indirection is necessary because a single big switch for all models made 
    the method exceed Java's method length limit).
  - Within the module-specific method, switch on the model name and reflectively invoke its 
    `adapter()` method with the correct parameters when it matches.
    
Best way to understand this is to look at the generated code, for which there's a simple integration 
test under the `integration` directory with a multi-project structure.

Right now, Fractory supports only gson and moshi. The plan would be to support any kind of 
factory-factory composition via its extension API in the future though, such as supporting it for
[inspector](https://github.com/hzsweers/inspector).

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
