package io.kestra.plugin.jenkins;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
    title = "Jenkins build result status",
    description = """
        Possible final completion states for a Jenkins build: `SUCCESS`, `UNSTABLE`, `FAILURE`, `NOT_BUILT`, or `ABORTED`.
        """
)
public enum BuildResult {
    SUCCESS,
    UNSTABLE,
    FAILURE,
    NOT_BUILT,
    ABORTED
}
