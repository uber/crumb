Contributing to Crumb
=======================

The Uber team welcomes contributions of all kinds, from simple bug reports through documentation, test cases,
bugfixes, and features.

Workflow
--------

We love GitHub issues!

For small feature requests, an issue first proposing it for discussion or demo implementation in a PR suffice.

For big features, please open an issue so that we can agree on the direction, and hopefully avoid 
investing a lot of time on a feature that might need reworking.

Small pull requests for things like typos, bugfixes, etc are always welcome.

DOs and DON'Ts
--------------

* DO include tests when adding new features. When fixing bugs, start with adding a test that highlights how the current behavior is broken.
* DO keep the discussions focused. When a new or related topic comes up it's often better to create new issue than to side track the discussion.
* DO run all Gradle verification tasks (`./gradlew check`) before submitting a pull request

* DON'T submit PRs that alter licensing related files or headers. If you believe there's a problem with them, file an issue and we'll be happy to discuss it.

### Code style

This project uses [GoogleJavaFormat](https://github.com/google/GoogleJavaFormat) and [ktlint](https://github.com/shyiko/ktlint), 
provided via the [spotless](https://github.com/diffplug/spotless) gradle plugin.

If you find that one of your pull reviews does not pass the CI server check due to a code style conflict, you can
easily fix it by running: `./gradlew spotlessApply`.
