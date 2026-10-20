package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.model.NoModelLoadedException;
import net.vheerden.archi.mcp.model.ValidationFindingsProvider;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.ValidationFindingDto;
import net.vheerden.archi.mcp.response.dto.ValidationFindingsResultDto;

/**
 * Unit tests for {@link ValidationHandler}.
 *
 * <p>Uses a stub accessor ({@link BaseTestAccessor}) and a stub
 * {@link ValidationFindingsProvider} — no EMF/OSGi runtime required. Follows
 * the established handler test pattern from {@link SearchHandlerTest}.</p>
 */
public class ValidationHandlerTest {

    private CommandRegistry registry;
    private ResponseFormatter formatter;
    private ObjectMapper objectMapper;

    @Before
    public void setUp() {
        registry = new CommandRegistry();
        formatter = new ResponseFormatter();
        objectMapper = new ObjectMapper();
    }

    // ---- Tool Registration Tests ----

    @Test
    public void shouldRegisterGetValidationFindingsTool() {
        newHandler(new StubProvider(SAMPLE_RESULT)).registerTools();

        assertEquals(1, registry.getToolCount());
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-validation-findings");
        assertEquals("get-validation-findings", spec.tool().name());
    }

    @Test
    public void shouldHaveQueryCategoryTagAndSchemaInToolSpec() {
        newHandler(new StubProvider(SAMPLE_RESULT)).registerTools();

        McpSchema.Tool tool = findToolSpec("get-validation-findings").tool();
        assertNotNull(tool.description());
        assertTrue(tool.description().startsWith("[Query]"));
        assertNotNull(tool.inputSchema());
        assertEquals("object", tool.inputSchema().type());
        assertTrue(tool.inputSchema().properties().containsKey("severity"));
        assertTrue(tool.inputSchema().properties().containsKey("limit"));
    }

    // ---- Happy-path Tests ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnFindingsInSuccessEnvelope() throws Exception {
        newHandler(new StubProvider(SAMPLE_RESULT)).registerTools();

        McpSchema.CallToolResult result = callTool(Map.of());

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> payload = (Map<String, Object>) envelope.get("result");
        assertNotNull(payload);
        assertEquals(Boolean.FALSE, payload.get("modelOk"));
        assertEquals(1, ((Number) payload.get("errorCount")).intValue());
        assertEquals(1, ((Number) payload.get("warningCount")).intValue());
        assertEquals(1, ((Number) payload.get("adviceCount")).intValue());
        assertEquals(3, ((Number) payload.get("totalCount")).intValue());
        List<Object> findings = (List<Object>) payload.get("findings");
        assertEquals(3, findings.size());

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(3, ((Number) meta.get("resultCount")).intValue());
        assertEquals(3, ((Number) meta.get("totalCount")).intValue());
        assertEquals(Boolean.FALSE, meta.get("isTruncated"));
        assertNotNull(meta.get("modelVersion"));
        assertNotNull(envelope.get("nextSteps"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReportCleanModel_whenNoFindings() throws Exception {
        ValidationFindingsResultDto clean = new ValidationFindingsResultDto(
                true, 0, 0, 0, 0, 0, 0, false, List.of());
        newHandler(new StubProvider(clean)).registerTools();

        McpSchema.CallToolResult result = callTool(Map.of());

        assertFalse(result.isError());
        Map<String, Object> payload = (Map<String, Object>) parseJson(result).get("result");
        assertEquals(Boolean.TRUE, payload.get("modelOk"));
        assertEquals(0, ((Number) payload.get("totalCount")).intValue());
    }

    // ---- Parameter pass-through Tests ----

    @Test
    public void shouldPassSeverityFilterAndLimitToProvider() {
        StubProvider provider = new StubProvider(SAMPLE_RESULT);
        newHandler(provider).registerTools();

        McpSchema.CallToolResult result = callTool(Map.of("severity", "error", "limit", 5));

        assertFalse(result.isError());
        assertEquals(Set.of("error"), provider.lastSeverityFilter);
        assertEquals(5, provider.lastLimit);
    }

    @Test
    public void shouldPassEmptyFilterForSeverityAll() {
        StubProvider provider = new StubProvider(SAMPLE_RESULT);
        newHandler(provider).registerTools();

        callTool(Map.of("severity", "all"));

        assertTrue(provider.lastSeverityFilter == null || provider.lastSeverityFilter.isEmpty());
    }

    @Test
    public void shouldAcceptCommaSeparatedSeverityList() {
        StubProvider provider = new StubProvider(SAMPLE_RESULT);
        newHandler(provider).registerTools();

        callTool(Map.of("severity", "error, warning"));

        assertEquals(Set.of("error", "warning"), provider.lastSeverityFilter);
    }

    @Test
    public void shouldUseDefaults_whenParamsAbsent() {
        StubProvider provider = new StubProvider(SAMPLE_RESULT);
        newHandler(provider).registerTools();

        callTool(Map.of());

        assertTrue(provider.lastSeverityFilter == null || provider.lastSeverityFilter.isEmpty());
        assertEquals(ValidationHandler.DEFAULT_LIMIT, provider.lastLimit);
    }

    // ---- Error Tests ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameter_whenSeverityUnknown() throws Exception {
        newHandler(new StubProvider(SAMPLE_RESULT)).registerTools();

        McpSchema.CallToolResult result = callTool(Map.of("severity", "bogus"));

        assertTrue(result.isError());
        Map<String, Object> error = (Map<String, Object>) parseJson(result).get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameter_whenLimitOutOfRange() throws Exception {
        newHandler(new StubProvider(SAMPLE_RESULT)).registerTools();

        assertTrue(callTool(Map.of("limit", 0)).isError());
        assertTrue(callTool(Map.of("limit", 501)).isError());

        McpSchema.CallToolResult result = callTool(Map.of("limit", "not-a-number"));
        assertTrue(result.isError());
        Map<String, Object> error = (Map<String, Object>) parseJson(result).get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnModelNotLoaded_whenNoModelLoaded() throws Exception {
        ValidationFindingsProvider throwing = (filter, limit) -> {
            throw new NoModelLoadedException();
        };
        newHandler(throwing).registerTools();

        McpSchema.CallToolResult result = callTool(Map.of());

        assertTrue(result.isError());
        Map<String, Object> error = (Map<String, Object>) parseJson(result).get("error");
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInternalError_whenProviderFailsUnexpectedly() throws Exception {
        ValidationFindingsProvider throwing = (filter, limit) -> {
            throw new IllegalStateException("boom");
        };
        newHandler(throwing).registerTools();

        McpSchema.CallToolResult result = callTool(Map.of());

        assertTrue(result.isError());
        Map<String, Object> error = (Map<String, Object>) parseJson(result).get("error");
        assertEquals("INTERNAL_ERROR", error.get("code"));
    }

    // ---- Helpers & Stubs ----

    private static final ValidationFindingsResultDto SAMPLE_RESULT = new ValidationFindingsResultDto(
            false, 1, 1, 1, 3, 3, 3, false,
            List.of(
                    new ValidationFindingDto("error", "Illegal relation",
                            "The relationship is not permitted", "Explanation text",
                            "rel-1", "flows", "FlowRelationship", "relationship"),
                    new ValidationFindingDto("warning", "Unused Element",
                            "Element is not used in any view", "Explanation text",
                            "elem-1", "Orphan Service", "ApplicationService", "element"),
                    new ValidationFindingDto("advice", "Empty View",
                            "View contains no objects", "Explanation text",
                            "view-1", "Empty Overview", "ArchimateDiagramModel", "view")));

    private ValidationHandler newHandler(ValidationFindingsProvider provider) {
        return new ValidationHandler(new BaseTestAccessor(true), provider,
                formatter, registry, null);
    }

    private McpSchema.CallToolResult callTool(Map<String, Object> args) {
        return findToolSpec("get-validation-findings")
                .callHandler().apply(null, new McpSchema.CallToolRequest(
                        "get-validation-findings", args));
    }

    private McpServerFeatures.SyncToolSpecification findToolSpec(String toolName) {
        return registry.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not found: " + toolName));
    }

    private Map<String, Object> parseJson(McpSchema.CallToolResult result) throws Exception {
        McpSchema.TextContent content = (McpSchema.TextContent) result.content().get(0);
        return objectMapper.readValue(content.text(),
                new TypeReference<Map<String, Object>>() {});
    }

    /**
     * Stub provider returning a canned result and recording the arguments it
     * was called with.
     */
    private static class StubProvider implements ValidationFindingsProvider {

        private final ValidationFindingsResultDto result;
        Set<String> lastSeverityFilter;
        int lastLimit = -1;

        StubProvider(ValidationFindingsResultDto result) {
            this.result = result;
        }

        @Override
        public ValidationFindingsResultDto getValidationFindings(
                Set<String> severityFilter, int limit) {
            this.lastSeverityFilter = severityFilter;
            this.lastLimit = limit;
            return result;
        }
    }
}
