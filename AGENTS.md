# Kestra Jenkins Plugin

## What

- Provides plugin components under `io.kestra.plugin.jenkins`.
- Includes classes such as `JobBuild`, `JobInfo`.

## Why

- What user problem does this solve? Teams need to trigger Jenkins jobs and retrieve build or job metadata from orchestrated workflows instead of relying on manual console work, ad hoc scripts, or disconnected schedulers.
- Why would a team adopt this plugin in a workflow? It keeps Jenkins steps in the same Kestra flow as upstream preparation, approvals, retries, notifications, and downstream systems.
- What operational/business outcome does it enable? It reduces manual handoffs and fragmented tooling while improving reliability, traceability, and delivery speed for processes that depend on Jenkins.

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
