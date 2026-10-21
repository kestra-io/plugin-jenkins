# How to use the Jenkins plugin

Trigger Jenkins builds, monitor their lifecycle, list historical runs, and poll for build completions from Kestra flows.

## Authentication

Set `serverUrl` to your Jenkins instance URL, `username` to your Jenkins user, and `apiToken` to your Jenkins API token. Store credentials in [secrets](https://kestra.io/docs/concepts/secret) and set them on each task or trigger.

## Tasks

### JobBuild
Triggers a Jenkins job via `buildWithParameters`:
- `jobName`: Name or foldered path (e.g., `team/project/job`).
- `parameters`: Optional map of build parameter names to values.
- `wait`: Set to `true` to block until the build finishes.
- `waitTimeout`: Maximum duration to wait before timing out (defaults to `PT10M`).
- `pollInterval`: Polling frequency (minimum 1s, defaults to `PT2S`).
- `cancelOnTimeout`: Set to `true` to cancel the queued item or stop the build on timeout.
- `allowedStatuses`: List of acceptable build results when `wait` is true (defaults to `['SUCCESS']`).
Outputs `status`, `queueId`, `buildNumber`, `buildUrl`, `result`, and `info`.

### JobInfo
Fetches details about a specific build:
- `jobName`: Name or foldered path.
- `buildNumber`: Build number (e.g., `"1"`) or alias (`lastBuild`, `lastSuccessfulBuild`, etc.). Defaults to `"lastBuild"`.
Outputs `number`, `url`, `result`, `building`, `duration`, `timestamp`, and `info`.

### ListBuilds
Lists historical builds from the Jenkins job API tree:
- `jobName`: Name or foldered path.
- `fetchType`: `FETCH` (inline list), `FETCH_ONE` (first item), or `STORE` (internal storage file).
- `result`: Optional filter by `BuildResult` (`SUCCESS`, `UNSTABLE`, `FAILURE`, `NOT_BUILT`, `ABORTED`).
- `since`: Optional timestamp filter.
- `max`: Maximum builds to return (1-100, defaults to `50`).
Outputs `builds`, `build`, `uri`, and `size`.

## Triggers

### Trigger
Polling trigger that evaluates Jenkins builds for a specific job:
- `jobName`: Name or foldered path.
- `interval`: Polling interval (defaults to `PT1M`).
- `result`: Optional filter by `BuildResult`.
- `initialBuildNumber`: Optional starting build number to avoid re-firing older runs.
Tracks progress using a length-prefixed SHA-256 watermark in the namespace KV store to guarantee exactly-once firing, holding back evaluations until all lower in-progress builds complete.
Outputs `buildNumber`, `buildUrl`, `result`, `duration`, `timestamp`, `fullDisplayName`, `build`, and `builds`.
