package net.vheerden.archi.mcp.model;

import java.util.Set;

import net.vheerden.archi.mcp.response.dto.ValidationFindingsResultDto;

/**
 * Provides agentic-AI access to the findings of Archi's built-in model
 * Validator (the {@code com.archimatetool.hammer} bundle behind
 * "Tools &rarr; Validate Model" and the Issues view).
 *
 * <p>This is a deliberate sibling of {@link ArchiModelAccessor}, not a new
 * method on it: the accessor interface and its implementation are frozen by
 * {@code tools/size-ratchet.sh} (byte-identical signature baseline, exact LOC
 * and public-method ceilings), so new read capabilities are added as separate
 * collaborators in the {@code model} package. Handlers depend on this
 * interface only, keeping the handler layer free of EMF/Archi imports.</p>
 */
public interface ValidationFindingsProvider {

    /**
     * Runs the Validator checks over the currently loaded model and returns
     * the findings as DTOs.
     *
     * <p>Findings are computed live at call time; nothing is cached, so the
     * result always reflects the model's current state.</p>
     *
     * @param severityFilter severities to include ("error", "warning",
     *                       "advice"); null or empty means all severities
     * @param limit          maximum number of findings to return (the result's
     *                       counts still describe the full, unfiltered run)
     * @return the validation result; never null
     * @throws NoModelLoadedException if no ArchiMate model is currently loaded
     * @throws ModelAccessException   if the validation run fails
     */
    ValidationFindingsResultDto getValidationFindings(Set<String> severityFilter, int limit);
}
