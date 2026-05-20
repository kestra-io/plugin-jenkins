# How to use the Jenkins plugin

Trigger Jenkins builds and fetch build information from Kestra flows.

## Authentication

Set `serverUrl` to your Jenkins instance URL, `username` to your Jenkins user, and `apiToken` to your Jenkins API token. Store credentials in [secrets](https://kestra.io/docs/concepts/secret) and apply them globally with [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults).

## Tasks

`JobBuild` triggers a Jenkins job — set `jobName` to the job name or folder path (e.g. `team/project/job`) and optionally `parameters` as a map of build parameter names to values.

`JobInfo` fetches details about a specific build — set `jobName` and `buildNumber`. The output `info` map contains the raw Jenkins build JSON including result, duration, and display name.
