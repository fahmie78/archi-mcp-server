package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.eclipse.emf.ecore.EObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.hammer.ArchiHammerPlugin;
import com.archimatetool.hammer.preferences.IPreferenceConstants;
import com.archimatetool.hammer.validation.checkers.DuplicateElementChecker;
import com.archimatetool.hammer.validation.checkers.EmptyViewsChecker;
import com.archimatetool.hammer.validation.checkers.IChecker;
import com.archimatetool.hammer.validation.checkers.InvalidRelationsChecker;
import com.archimatetool.hammer.validation.checkers.JunctionsChecker;
import com.archimatetool.hammer.validation.checkers.NestedElementsChecker;
import com.archimatetool.hammer.validation.checkers.UnusedElementsChecker;
import com.archimatetool.hammer.validation.checkers.UnusedRelationsChecker;
import com.archimatetool.hammer.validation.checkers.ViewpointChecker;
import com.archimatetool.hammer.validation.issues.AdviceType;
import com.archimatetool.hammer.validation.issues.ErrorType;
import com.archimatetool.hammer.validation.issues.IIssue;
import com.archimatetool.hammer.validation.issues.WarningType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateComponent;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.INameable;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.ValidationFindingDto;
import net.vheerden.archi.mcp.response.dto.ValidationFindingsResultDto;

/**
 * Default {@link ValidationFindingsProvider}: drives the same public checkers
 * that Archi's {@code com.archimatetool.hammer.validation.Validator} runs for
 * "Tools &rarr; Validate Model" and the Issues view, over the currently
 * loaded model.
 *
 * <p><strong>Why not call {@code Validator} directly:</strong>
 * {@code Validator.validate()} reads its enablement flags from
 * {@code ArchiHammerPlugin.getInstance().getPreferenceStore()}, which NPEs
 * outside a booted OSGi workbench (headless tests, early startup). This class
 * instantiates the eight checkers itself and reads the same preference keys
 * defensively — honouring the user's Validator preferences when the OSGi
 * runtime is present, defaulting to all-enabled when it is not.</p>
 *
 * <p><strong>Per-checker guard:</strong> two checkers (invalid-relations and
 * viewpoint) reach {@code ArchiLabelProvider}, whose static initialiser needs
 * the workbench image registry; headless that surfaces as
 * {@code ExceptionInInitializerError} — an {@code Error}, not an
 * {@code Exception}. Each checker therefore runs behind a {@code Throwable}
 * guard that logs and skips, so one unavailable checker can never fail the
 * whole validation run.</p>
 *
 * <p>Read-only: no command stack, no SWT thread, mirroring the existing read
 * paths in {@code ArchiModelAccessorImpl}.</p>
 */
public class ValidationFindingsProviderImpl implements ValidationFindingsProvider {

    private static final Logger logger = LoggerFactory.getLogger(ValidationFindingsProviderImpl.class);

    /**
     * Resolves the model to validate. Lazy so that constructing this provider
     * (e.g. from {@code HandlerRegistrar} in a plain-JUnit wiring test) never
     * touches the OSGi singleton; {@link IEditorModelManager} is first
     * referenced when a tool call actually arrives.
     */
    private final Supplier<IArchimateModel> modelSupplier;

    /**
     * Creates a provider over the model currently registered with the global
     * {@link IEditorModelManager#INSTANCE} (the first loaded model, matching
     * {@code ArchiModelAccessorImpl}'s detection rule).
     */
    public ValidationFindingsProviderImpl() {
        this(() -> {
            List<IArchimateModel> models = IEditorModelManager.INSTANCE.getModels();
            return (models == null || models.isEmpty()) ? null : models.get(0);
        });
    }

    /**
     * Creates a provider over a supplied model. Package-visible for testing
     * with an in-memory model and no OSGi runtime.
     *
     * @param modelSupplier supplies the model to validate, or null when no
     *                      model is loaded
     */
    ValidationFindingsProviderImpl(Supplier<IArchimateModel> modelSupplier) {
        this.modelSupplier = modelSupplier;
    }

    @Override
    public ValidationFindingsResultDto getValidationFindings(Set<String> severityFilter, int limit) {
        IArchimateModel model = modelSupplier.get();
        if (model == null) {
            throw new NoModelLoadedException();
        }
        try {
            return runValidation(model, severityFilter, limit);
        } catch (NoModelLoadedException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelAccessException(
                    "Error running the model Validator",
                    e, ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * Collects model contents, runs every enabled checker behind its guard,
     * and maps the issues to DTOs. Package-visible so tests can drive it with
     * an {@code IArchimateFactory}-built model directly.
     */
    ValidationFindingsResultDto runValidation(IArchimateModel model,
                                              Set<String> severityFilter, int limit) {
        List<IArchimateElement> elements = new ArrayList<>();
        List<IArchimateRelationship> relations = new ArrayList<>();
        List<IArchimateDiagramModel> views = new ArrayList<>();

        for (Iterator<EObject> iter = model.eAllContents(); iter.hasNext();) {
            EObject eObject = iter.next();
            if (eObject instanceof IArchimateRelationship relation) {
                relations.add(relation);
            } else if (eObject instanceof IArchimateElement element) {
                elements.add(element);
            } else if (eObject instanceof IArchimateDiagramModel view) {
                views.add(view);
            }
        }

        List<IIssue> errors = new ArrayList<>();
        List<IIssue> warnings = new ArrayList<>();
        List<IIssue> advice = new ArrayList<>();

        // The same eight checkers com.archimatetool.hammer.validation.Validator runs,
        // gated by the same preference keys.
        collect(new InvalidRelationsChecker(relations),
                IPreferenceConstants.PREFS_HAMMER_CHECK_INVALID_RELATIONS, errors, warnings, advice);
        collect(new UnusedElementsChecker(elements),
                IPreferenceConstants.PREFS_HAMMER_CHECK_UNUSED_ELEMENTS, errors, warnings, advice);
        collect(new UnusedRelationsChecker(relations),
                IPreferenceConstants.PREFS_HAMMER_CHECK_UNUSED_RELATIONS, errors, warnings, advice);
        collect(new EmptyViewsChecker(views),
                IPreferenceConstants.PREFS_HAMMER_CHECK_EMPTY_VIEWS, errors, warnings, advice);
        collect(new ViewpointChecker(views),
                IPreferenceConstants.PREFS_HAMMER_CHECK_VIEWPOINT, errors, warnings, advice);
        collect(new NestedElementsChecker(views),
                IPreferenceConstants.PREFS_HAMMER_CHECK_NESTING, errors, warnings, advice);
        collect(new DuplicateElementChecker(elements),
                IPreferenceConstants.PREFS_HAMMER_CHECK_DUPLICATE_ELEMENTS, errors, warnings, advice);
        collect(new JunctionsChecker(elements),
                IPreferenceConstants.PREFS_HAMMER_CHECK_JUNCTIONS, errors, warnings, advice);

        // Severity-grouped ordering: errors, then warnings, then advice.
        List<IIssue> ordered = new ArrayList<>(errors.size() + warnings.size() + advice.size());
        ordered.addAll(errors);
        ordered.addAll(warnings);
        ordered.addAll(advice);

        boolean allSeverities = severityFilter == null || severityFilter.isEmpty();
        List<ValidationFindingDto> filtered = new ArrayList<>();
        for (IIssue issue : ordered) {
            String severity = severityOf(issue);
            if (allSeverities || severityFilter.contains(severity)) {
                filtered.add(toDto(issue, severity));
            }
        }

        int filteredCount = filtered.size();
        boolean truncated = filteredCount > limit;
        List<ValidationFindingDto> returned = truncated
                ? List.copyOf(filtered.subList(0, limit))
                : List.copyOf(filtered);

        int totalCount = ordered.size();
        return new ValidationFindingsResultDto(
                totalCount == 0,
                errors.size(),
                warnings.size(),
                advice.size(),
                totalCount,
                filteredCount,
                returned.size(),
                truncated,
                returned);
    }

    /**
     * Runs one checker if enabled, routing its issues into the severity lists.
     * Any failure (including {@link Error}s from workbench-only static
     * initialisers) is logged and the checker skipped.
     */
    private void collect(IChecker checker, String preferenceKey,
                         List<IIssue> errors, List<IIssue> warnings, List<IIssue> advice) {
        if (!isCheckerEnabled(preferenceKey)) {
            return;
        }
        final List<? extends IIssue> issues;
        try {
            issues = checker.getIssues();
        } catch (Throwable t) { // NOSONAR — deliberate: workbench-only checkers fail with Error headless
            logger.warn("Validator checker {} unavailable, skipping its findings: {}",
                    checker.getClass().getSimpleName(), t.toString());
            return;
        }
        for (IIssue issue : issues) {
            if (issue instanceof ErrorType) {
                errors.add(issue);
            } else if (issue instanceof WarningType) {
                warnings.add(issue);
            } else if (issue instanceof AdviceType) {
                advice.add(issue);
            }
        }
    }

    /**
     * Reads the hammer Validator preference for a checker. Defaults to enabled
     * when the OSGi runtime (and therefore the preference store) is absent.
     */
    private boolean isCheckerEnabled(String preferenceKey) {
        try {
            ArchiHammerPlugin plugin = ArchiHammerPlugin.getInstance();
            if (plugin == null) {
                return true;
            }
            return plugin.getPreferenceStore().getBoolean(preferenceKey);
        } catch (Throwable t) { // NOSONAR — defensive: never let preference lookup break validation
            logger.debug("Could not read Validator preference {}, defaulting to enabled",
                    preferenceKey, t);
            return true;
        }
    }

    private static String severityOf(IIssue issue) {
        if (issue instanceof ErrorType) {
            return "error";
        }
        if (issue instanceof WarningType) {
            return "warning";
        }
        return "advice";
    }

    /**
     * Maps an issue to its DTO, resolving the issue's target object to the
     * identity an agent can act on. Findings raised against a diagram
     * component resolve to the underlying ArchiMate concept.
     */
    private static ValidationFindingDto toDto(IIssue issue, String severity) {
        String targetId = null;
        String targetName = null;
        String targetType = null;
        String targetKind = "unknown";

        Object object = issue.getObject();
        if (object instanceof IDiagramModelArchimateComponent component) {
            object = component.getArchimateConcept();
        }

        if (object instanceof IArchimateRelationship) {
            targetKind = "relationship";
        } else if (object instanceof IArchimateElement) {
            targetKind = "element";
        } else if (object instanceof IDiagramModel) {
            targetKind = "view";
        } else if (object instanceof IFolder) {
            targetKind = "folder";
        } else if (object instanceof IArchimateModel) {
            targetKind = "model";
        }

        if (object instanceof IIdentifier identifiable) {
            targetId = identifiable.getId();
        }
        if (object instanceof INameable nameable) {
            targetName = nameable.getName();
        }
        if (object instanceof EObject eObject) {
            targetType = eObject.eClass().getName();
        }

        return new ValidationFindingDto(
                severity,
                issue.getName(),
                issue.getDescription(),
                issue.getExplanation(),
                targetId,
                targetName,
                targetType,
                targetKind);
    }
}
