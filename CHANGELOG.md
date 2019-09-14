Changelog
=========

Version 0.1.0
-------------

_2019-09-14_

### Incremental annotation processing support ([#43](https://github.com/uber/crumb/pull/43))

Crumb is now fully compatible with incremental annotation processing, and extensions APIs have been
updated to allow extensions to opt in and declare what kind of incrementalism they support. This
system is similar to AutoValue's API for allowing extensions to declare their incremental support.
Full documentation can be found in Javadocs.

### New element-based breadcrumbing mechanism ([#39](https://github.com/uber/crumb/pull/39))

Before, Crumb would breadcrumb metadata across compilation boundaries via writing files to 
resources. This had a few issues, but most importantly it would never be compatible with incremental 
annotation processing. This new system instead writes what we call "Crumb indexes", which are holder
classes that are annotated with a `@CrumbIndex` annotation containing the metadata instead. Crumb
consumers will just read all indexes in this known package now instead, and this has enabled us to
support incremental annotation processing.

### Metadata wire format changes

#### Raw bytes ([#42](https://github.com/uber/crumb/pull/42))

CrumbManager now stores metadata as raw bytes, with reads and writes exposed as 
[Okio](https://github.com/square/okio) `BufferedSource` and `BufferedSink` types ([#46](https://github.com/uber/crumb/pull/46)). 

#### Wire and Protos ([#44](https://github.com/uber/crumb/pull/44))

When using Crumb's annotation processor, it stores metadata as raw gzip'd protocol buffers via 
[Wire](https://github.com/square/wire). Note that Wire's runtime is temporarily shaded in for now
due to a [known Kapt MPP bug](https://youtrack.jetbrains.com/issue/KT-31641).

### Consumers can now consumer local producer-generated models ([#49](https://github.com/uber/crumb/pull/49))

Before, consumers could not consumer metadata produced by producers in the same compilation/project.
Now they can!

### Misc

**Enhancement:** Extension interfaces now have sane defaults for applicability checks and also utilize
`@JvmDefault` where appropriate. Note that this requires both targeting jdk8 and opting in to `@JvmDefault`. ([#44](https://github.com/uber/crumb/pull/44))

**Fix:** Check loaderForExtensions before loading extensions ([#34](https://github.com/uber/crumb/pull/34))

Dependency updates

    Kotlin: 1.3.50
    KotlinPoet: 1.3.0
    JavaPoet: 1.11.1
    
Thanks contributors that helped with documentation, feedback, and reviews! [@vanniktech](https://github.com/vanniktech) [@drd](https://github.com/drd)

Initial release!

Version 0.0.1
-------------

_2018-04-10_

Initial release!
