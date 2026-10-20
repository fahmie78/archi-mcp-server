package net.vheerden.archi.mcp.response.dto;

import java.util.List;

/**
 * Data Transfer Object for the result of running Archi's built-in model
 * Validator over the loaded model.
 *
 * <p>The per-severity counts always describe the <em>full</em> validation
 * run, before any severity filter or limit is applied, so an agent can see
 * the whole picture even when it paged or filtered the {@code findings}
 * list. {@code filteredCount} is the number of findings that matched the
 * requested severity filter before {@code limit} was applied;
 * {@code truncated} is true when {@code returnedCount} &lt;
 * {@code filteredCount}.</p>
 */
public record ValidationFindingsResultDto(
    boolean modelOk,
    int errorCount,
    int warningCount,
    int adviceCount,
    int totalCount,
    int filteredCount,
    int returnedCount,
    boolean truncated,
    List<ValidationFindingDto> findings
) {
}
