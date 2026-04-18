# Kestra Jenkins Plugin

## What

- Provides plugin components under `io.kestra.plugin.jenkins`.
- Includes classes such as `JobBuild`, `JobInfo`.

## Why

- This plugin integrates Kestra with Jenkins.
- It provides tasks that trigger Jenkins jobs and retrieve build or job metadata.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `jenkins`

### Key Plugin Classes

- `io.kestra.plugin.jenkins.JobBuild`
- `io.kestra.plugin.jenkins.JobInfo`

### Project Structure

```
plugin-jenkins/
├── src/main/java/io/kestra/plugin/jenkins/
├── src/test/java/io/kestra/plugin/jenkins/
├── build.gradle
└── README.md
```

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
