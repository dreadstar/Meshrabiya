# Meshrabiya (lib-meshrabiya)

This folder contains the Meshrabiya mesh networking library used by Orbot.

For developer documentation about test flags, socket timeout defaults, and how to run Meshrabiya tests, see the top-level project README:

- Main README: `../../README.md` (look for the section "Meshrabiya: test-mode flags, socket timeouts, and running tests")

Quick notes:

- The library exposes a `SocketTimeoutsProvider` and a `TestSocketTimeoutsProvider` useful for unit tests.
- Unit tests should inject `TestSocketTimeoutsProvider` rather than rely on JVM system properties.
- Meshrabiya modules target Java 21; ensure `JAVA_HOME` points to a Java 21 JDK when running tests/builds.

If you'd like, I can expand this module README with examples specific to the library internals.
