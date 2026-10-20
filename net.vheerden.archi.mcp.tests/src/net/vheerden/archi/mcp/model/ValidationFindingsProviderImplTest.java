package net.vheerden.archi.mcp.model;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Set;

import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IJunction;

import net.vheerden.archi.mcp.response.dto.ValidationFindingDto;
import net.vheerden.archi.mcp.response.dto.ValidationFindingsResultDto;

/**
 * Unit tests for {@link ValidationFindingsProviderImpl}.
 *
 * <p>Headless-safe: drives {@code runValidation} directly with
 * {@code IArchimateFactory}-built models — no OSGi runtime, no
 * {@code IEditorModelManager}. The two checkers that need the workbench
 * (invalid-relations and viewpoint, via {@code ArchiLabelProvider} /
 * {@code ViewpointManager} static state) are behind the provider's
 * per-checker guard, so they skip cleanly here; the fixtures below name every
 * concept so the remaining checkers never fall back to that machinery
 * either.</p>
 */
public class ValidationFindingsProviderImplTest {

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;

    // ---- Model resolution ----

    @Test(expected = NoModelLoadedException.class)
    public void shouldThrowNoModelLoaded_whenSupplierYieldsNoModel() {
        new ValidationFindingsProviderImpl(() -> null)
                .getValidationFindings(Set.of(), 10);
    }

    @Test
    public void shouldValidateModelFromSupplier() {
        IArchimateModel model = emptyModel();
        ValidationFindingsProvider provider = new ValidationFindingsProviderImpl(() -> model);

        ValidationFindingsResultDto result = provider.getValidationFindings(Set.of(), 10);

        assertNotNull(result);
    }

    // ---- Finding production ----

    @Test
    public void shouldReportUnusedElementWarning_withTargetIdentity() {
        IArchimateModel model = emptyModel();
        IArchimateElement orphan = factory.createApplicationComponent();
        orphan.setName("Orphan Component");
        model.getFolder(FolderType.APPLICATION).getElements().add(orphan);

        ValidationFindingsResultDto result =
                new ValidationFindingsProviderImpl(() -> model)
                        .getValidationFindings(Set.of(), 100);

        ValidationFindingDto finding = findByName(result.findings(), "Unused Element");
        assertNotNull("expected an Unused Element warning", finding);
        assertEquals("warning", finding.severity());
        assertEquals(orphan.getId(), finding.targetId());
        assertEquals("Orphan Component", finding.targetName());
        assertEquals("ApplicationComponent", finding.targetType());
        assertEquals("element", finding.targetKind());
        assertNotNull(finding.description());
        assertNotNull(finding.explanation());
    }

    @Test
    public void shouldReportBogusJunctionAsError() {
        IArchimateModel model = emptyModel();
        IFolder folder = model.getFolder(FolderType.APPLICATION);

        IArchimateElement source = factory.createApplicationComponent();
        source.setName("Source Component");
        IArchimateElement target = factory.createApplicationComponent();
        target.setName("Target Component");
        IJunction junction = factory.createJunction();
        junction.setName("Mixing Junction");
        folder.getElements().add(source);
        folder.getElements().add(target);
        folder.getElements().add(junction);

        // A Junction combining two different relationship types is an error.
        IArchimateRelationship flow = factory.createFlowRelationship();
        flow.setName("flow in");
        flow.connect(source, junction);
        IArchimateRelationship triggering = factory.createTriggeringRelationship();
        triggering.setName("trigger out");
        triggering.connect(junction, target);
        folder.getElements().add(flow);
        folder.getElements().add(triggering);

        ValidationFindingsResultDto result =
                new ValidationFindingsProviderImpl(() -> model)
                        .getValidationFindings(Set.of(), 100);

        ValidationFindingDto finding = findByName(result.findings(), "Junction relationships");
        assertNotNull("expected a Junction relationships error", finding);
        assertEquals("error", finding.severity());
        assertEquals(junction.getId(), finding.targetId());
        assertEquals("Junction", finding.targetType());
    }

    @Test
    public void shouldReportCleanModel_whenNoCheckersFire() {
        IArchimateModel model = emptyModel();

        ValidationFindingsResultDto result =
                new ValidationFindingsProviderImpl(() -> model)
                        .getValidationFindings(Set.of(), 100);

        assertTrue(result.modelOk());
        assertEquals(0, result.totalCount());
        assertTrue(result.findings().isEmpty());
    }

    // ---- Filtering, ordering, paging ----

    @Test
    public void shouldKeepFullCounts_whenSeverityFiltered() {
        IArchimateModel model = modelWithErrorAndWarning();

        ValidationFindingsResultDto result =
                new ValidationFindingsProviderImpl(() -> model)
                        .getValidationFindings(Set.of("error"), 100);

        // Counts describe the full run...
        assertTrue(result.errorCount() >= 1);
        assertTrue(result.warningCount() >= 1);
        assertEquals(result.errorCount() + result.warningCount() + result.adviceCount(),
                result.totalCount());
        // ...but only errors are returned
        assertFalse(result.findings().isEmpty());
        for (ValidationFindingDto finding : result.findings()) {
            assertEquals("error", finding.severity());
        }
    }

    @Test
    public void shouldOrderErrorsBeforeWarnings() {
        IArchimateModel model = modelWithErrorAndWarning();

        ValidationFindingsResultDto result =
                new ValidationFindingsProviderImpl(() -> model)
                        .getValidationFindings(Set.of(), 100);

        int lastError = -1;
        int firstWarning = Integer.MAX_VALUE;
        List<ValidationFindingDto> findings = result.findings();
        for (int i = 0; i < findings.size(); i++) {
            if ("error".equals(findings.get(i).severity())) {
                lastError = i;
            }
            if ("warning".equals(findings.get(i).severity()) && firstWarning == Integer.MAX_VALUE) {
                firstWarning = i;
            }
        }
        assertTrue("fixture should produce both severities", lastError >= 0 && firstWarning < findings.size());
        assertTrue("errors must sort before warnings", lastError < firstWarning);
    }

    @Test
    public void shouldTruncateToLimit_andReportFilteredCount() {
        IArchimateModel model = emptyModel();
        IFolder folder = model.getFolder(FolderType.APPLICATION);
        for (int i = 0; i < 5; i++) {
            IArchimateElement element = factory.createApplicationComponent();
            element.setName("Unused Component " + i);
            folder.getElements().add(element);
        }

        ValidationFindingsResultDto result =
                new ValidationFindingsProviderImpl(() -> model)
                        .getValidationFindings(Set.of(), 2);

        assertEquals(2, result.returnedCount());
        assertEquals(2, result.findings().size());
        assertTrue(result.truncated());
        assertTrue(result.filteredCount() >= 5);
        assertEquals(result.filteredCount(), result.totalCount());
    }

    // ---- Fixtures ----

    private IArchimateModel emptyModel() {
        IArchimateModel model = factory.createArchimateModel();
        model.setDefaults();
        model.setName("Validation Test Model");
        return model;
    }

    /** A model guaranteed to produce at least one error and one warning. */
    private IArchimateModel modelWithErrorAndWarning() {
        IArchimateModel model = emptyModel();
        IFolder folder = model.getFolder(FolderType.APPLICATION);

        // Error: junction mixing relationship types
        IArchimateElement a = factory.createApplicationComponent();
        a.setName("A Component");
        IArchimateElement b = factory.createApplicationComponent();
        b.setName("B Component");
        IJunction junction = factory.createJunction();
        junction.setName("Mixed Junction");
        folder.getElements().add(a);
        folder.getElements().add(b);
        folder.getElements().add(junction);
        IArchimateRelationship flow = factory.createFlowRelationship();
        flow.connect(a, junction);
        IArchimateRelationship trigger = factory.createTriggeringRelationship();
        trigger.connect(junction, b);
        folder.getElements().add(flow);
        folder.getElements().add(trigger);

        // Warning: element in no view
        IArchimateElement orphan = factory.createBusinessActor();
        orphan.setName("Unviewed Actor");
        model.getFolder(FolderType.BUSINESS).getElements().add(orphan);

        return model;
    }

    private static ValidationFindingDto findByName(List<ValidationFindingDto> findings, String name) {
        return findings.stream()
                .filter(f -> name.equals(f.name()))
                .findFirst()
                .orElse(null);
    }
}
