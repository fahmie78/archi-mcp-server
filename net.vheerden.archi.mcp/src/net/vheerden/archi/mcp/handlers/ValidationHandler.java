package net.vheerden.archi.mcp.handlers;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.ArchiModelAccessor;
import net.vheerden.archi.mcp.model.NoModelLoadedException;
import net.vheerden.archi.mcp.model.ValidationFindingsProvider;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ErrorResponse;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.ValidationFindingsResultDto;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Handler for the model-validation query tool: get-validation-findings.
 *
 * <p>Exposes Archi's built-in model Validator (the
 * {@code com.archimatetool.hammer} machinery behind "Tools &rarr; Validate
 * Model" and the Issues view) to MCP clients, so an agentic AI can read the
 * Validator's findings — errors, warnings, advice — as input knowledge when
 * planning or verifying model changes.</p>
 *
 * <p><strong>Architecture boundary:</strong> This class MUST NOT import any
 * EMF or ArchimateTool model types. Validator access goes through the
 * {@link ValidationFindingsProvider} collaborator in the model layer; the
 * model version stamped on the envelope comes from the frozen
 * {@link ArchiModelAccessor} facade (read-only {@code getModelVersion()}).</p>
 */
public class ValidationHandler {

    private static final Logger logger = LoggerFactory.getLogger(ValidationHandler.class);

    static final Set<String> VALID_SEVERITIES = Set.of("error", "warning", "advice");
    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;

    private final ArchiModelAccessor accessor;
    private final ValidationFindingsProvider findingsProvider;
    private final ResponseFormatter formatter;
    private final CommandRegistry registry;
    @SuppressWarnings("unused") // held for signature uniformity with sibling handlers
    private final SessionManager sessionManager;

    /**
     * Creates a ValidationHandler with its required dependencies.
     *
     * @param accessor         the model accessor (used for the envelope's model version)
     * @param findingsProvider the Validator findings collaborator
     * @param formatter        the response formatter for building JSON envelopes
     * @param registry         the command registry for tool registration
     * @param sessionManager   the session manager, may be null (findings are
     *                         computed live and never cached per-session)
     */
    public ValidationHandler(ArchiModelAccessor accessor,
                             ValidationFindingsProvider findingsProvider,
                             ResponseFormatter formatter,
                             CommandRegistry registry,
                             SessionManager sessionManager) {
        this.accessor = Objects.requireNonNull(accessor, "accessor must not be null");
        this.findingsProvider = Objects.requireNonNull(findingsProvider, "findingsProvider must not be null");
        this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.sessionManager = sessionManager; // nullable, matches sibling handlers
    }

    /**
     * Registers all tools provided by this handler with the command registry.
     */
    public void registerTools() {
        registry.registerTool(buildGetValidationFindingsSpec());
    }

    private McpServerFeatures.SyncToolSpecification buildGetValidationFindingsSpec() {
        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> severityParam = new LinkedHashMap<>();
        severityParam.put("type", "string");
        severityParam.put("description", "Optional severity filter: 'error', 'warning', or 'advice' — "
                + "or a comma-separated combination such as 'error,warning'. "
                + "Omit or pass 'all' to return every finding. "
                + "The per-severity counts in the result always describe the full, unfiltered run.");
        properties.put("severity", severityParam);

        Map<String, Object> limitParam = new LinkedHashMap<>();
        limitParam.put("type", "integer");
        limitParam.put("description", "Optional maximum number of findings to return (1-"
                + MAX_LIMIT + ", default " + DEFAULT_LIMIT + "). Findings are ordered errors "
                + "first, then warnings, then advice. When the result is truncated, call again "
                + "with a higher limit or a narrower severity filter.");
        properties.put("limit", limitParam);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, null, null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("get-validation-findings")
                .description("[Query] Run Archi's built-in model Validator — the same checks as "
                        + "Tools > Validate Model and the Issues view — over the loaded model and "
                        + "return its findings as structured knowledge: illegal relations (error), "
                        + "unused elements, unused relations, possible duplicates, wrong-viewpoint "
                        + "concepts and inconsistent junction relationships (warning), empty views "
                        + "and visual nesting without nesting relationships (advice). Each finding "
                        + "carries severity, the Validator's name/description/explanation, and the "
                        + "target concept's id/name/type — feed targetId to get-element to inspect "
                        + "it. Use this BEFORE planning model changes (baseline the model's known "
                        + "issues) and AFTER mutations to confirm no new findings appeared: "
                        + "findings are computed live at call time, never cached. "
                        + "Remediation guidance per finding type is in "
                        + "archimate://reference/validation-findings. "
                        + "Related: get-model-info (model overview), get-element (inspect a "
                        + "finding's target), search-elements (find concepts by name).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleGetValidationFindings)
                .build();
    }

    private McpSchema.CallToolResult handleGetValidationFindings(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling get-validation-findings request");

        try {
            Map<String, Object> args = request.arguments();

            Set<String> severityFilter = parseSeverityFilter(args);
            if (severityFilter == null) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "Invalid 'severity' value",
                        null,
                        "Use 'error', 'warning', 'advice', a comma-separated combination, "
                                + "or omit the parameter for all findings",
                        null);
                return HandlerUtils.buildResult(
                        formatter.toJsonString(formatter.formatError(error)), true);
            }

            int limit = parseLimit(args);
            if (limit < 0) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "Invalid 'limit' value",
                        null,
                        "Provide an integer between 1 and " + MAX_LIMIT,
                        null);
                return HandlerUtils.buildResult(
                        formatter.toJsonString(formatter.formatError(error)), true);
            }

            ValidationFindingsResultDto result =
                    findingsProvider.getValidationFindings(severityFilter, limit);

            List<String> nextSteps = result.modelOk()
                    ? List.of("The Validator reports no findings — the model passes all enabled checks")
                    : List.of(
                            "Use get-element with a finding's targetId to inspect the concept",
                            "Consult the validation-findings reference resource for remediation "
                                    + "guidance per finding type",
                            "Re-run get-validation-findings after applying fixes to confirm the "
                                    + "findings cleared");

            Map<String, Object> envelope = formatter.formatSuccess(
                    result,
                    nextSteps,
                    accessor.getModelVersion(),
                    result.returnedCount(),
                    result.filteredCount(),
                    result.truncated());

            return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);

        } catch (NoModelLoadedException e) {
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.MODEL_NOT_LOADED,
                    e.getMessage(),
                    null,
                    "Open an ArchiMate model in ArchimateTool",
                    null);
            return HandlerUtils.buildResult(
                    formatter.toJsonString(formatter.formatError(error)), true);

        } catch (Exception e) {
            logger.error("Unexpected error in get-validation-findings", e);
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.INTERNAL_ERROR,
                    "An unexpected error occurred while running the model Validator");
            return HandlerUtils.buildResult(
                    formatter.toJsonString(formatter.formatError(error)), true);
        }
    }

    /**
     * Parses the optional severity parameter. Returns an empty set for
     * "all"/absent, the severity set for valid values, or null for an
     * unrecognised value.
     */
    private static Set<String> parseSeverityFilter(Map<String, Object> args) {
        if (args == null || !args.containsKey("severity") || args.get("severity") == null) {
            return Set.of();
        }
        Object raw = args.get("severity");
        if (!(raw instanceof String severityArg) || severityArg.isBlank()) {
            return null;
        }
        Set<String> filter = new LinkedHashSet<>();
        for (String token : severityArg.split(",")) {
            String severity = token.trim().toLowerCase();
            if ("all".equals(severity)) {
                return Set.of();
            }
            if (!VALID_SEVERITIES.contains(severity)) {
                return null;
            }
            filter.add(severity);
        }
        return filter;
    }

    /**
     * Parses the optional limit parameter. Returns the default when absent,
     * or -1 for an invalid value.
     */
    private static int parseLimit(Map<String, Object> args) {
        if (args == null || !args.containsKey("limit") || args.get("limit") == null) {
            return DEFAULT_LIMIT;
        }
        Object raw = args.get("limit");
        int limit;
        if (raw instanceof Number number) {
            limit = number.intValue();
        } else if (raw instanceof String text) {
            try {
                limit = Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        } else {
            return -1;
        }
        return (limit >= 1 && limit <= MAX_LIMIT) ? limit : -1;
    }
}
