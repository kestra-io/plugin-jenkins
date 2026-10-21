# Kestra Jenkins Plugin

## What

- Provides plugin components under `io.kestra.plugin.jenkins`.
- Includes tasks and triggers: `JobBuild`, `JobInfo`, `ListBuilds`, and `Trigger`.

## Why

- What user problem does this solve? Teams need to trigger Jenkins jobs, poll for execution status, retrieve historical run data, and react to completed builds from orchestrated workflows instead of relying on manual console work, ad hoc scripts, or disconnected schedulers.
- Why would a team adopt this plugin in a workflow? It keeps Jenkins steps in the same Kestra flow as upstream preparation, approvals, retries, notifications, and downstream systems.
- What operational/business outcome does it enable? It reduces manual handoffs and fragmented tooling while improving reliability, traceability, and delivery speed for processes that depend on Jenkins.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `jenkins`

### Key Plugin Classes

- `io.kestra.plugin.jenkins.JobBuild`: Triggers a Jenkins job with parameters, wait, timeout cancellation, and kill hooks.
- `io.kestra.plugin.jenkins.JobInfo`: Fetches metadata and result status for a specific build number or alias.
- `io.kestra.plugin.jenkins.ListBuilds`: Lists builds for a job supporting `FETCH`, `FETCH_ONE`, and `STORE` modes.
- `io.kestra.plugin.jenkins.Trigger`: Polling trigger using a length-prefixed SHA-256 namespace KV watermark.

### Project Structure

```
plugin-jenkins/
├── src/main/java/io/kestra/plugin/jenkins/
├── src/test/java/io/kestra/plugin/jenkins/
├── src/main/resources/doc/
├── src/main/resources/metadata/
├── build.gradle
└── README.md
```

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
