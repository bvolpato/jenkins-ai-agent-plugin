# Contributing

Contributions are welcome. This document covers the basics for getting started.

## Prerequisites

- Java 17 or later
- Maven 3.9 or later
- A local Jenkins instance (optional, for manual testing)

## Building

```bash
mvn clean verify
```

This runs compilation, tests, SpotBugs, JaCoCo coverage, and format checking in one go.

## Code Style

The project uses [Google Java Format](https://github.com/google/google-java-format) with the AOSP style variant. The build enforces formatting via `fmt-maven-plugin`.

To auto-format before committing:

```bash
mvn com.spotify.fmt:fmt-maven-plugin:format
```

## Testing

Tests use the [Jenkins Test Harness](https://github.com/jenkinsci/jenkins-test-harness), which spins up a real Jenkins instance for integration tests. Unit tests use JUnit 4.

Test fixtures for log parsing live in `src/test/resources/io/jenkins/plugins/aiagentjob/fixtures/`. When adding support for a new agent format or modifying parsing logic, add or update the corresponding `.jsonl` fixture.

Run tests:

```bash
mvn clean test
```

Coverage reports are generated under `target/site/jacoco/` after `mvn verify`.

## Project Structure

```
src/main/java/          # Plugin source
src/main/resources/     # Jelly views, help files, config
src/test/java/          # Tests
src/test/resources/     # Test fixtures (JSONL logs)
```

## Pull Requests

1. Fork the repository and create a feature branch.
2. Make your changes with tests.
3. Run `mvn clean verify` to ensure everything passes.
4. Submit a pull request against `main`.

CI runs on every PR with Java 17 and 21 on Ubuntu.

## Reporting Issues

Open an issue on GitHub with:
- Jenkins version
- Plugin version
- Steps to reproduce
- Expected vs actual behavior
