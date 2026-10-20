package net.vheerden.archi.mcp.response.dto;

/**
 * Data Transfer Object for a single finding reported by Archi's built-in
 * model Validator (the {@code com.archimatetool.hammer} "Validate Model" /
 * Issues view machinery).
 *
 * <p>Each finding carries its severity, the Validator's own name /
 * description / explanation texts, and the identity of the model object the
 * finding is about so an agent can follow up with {@code get-element} or a
 * mutation tool. For findings raised against a diagram component the target
 * identity is the underlying ArchiMate concept — the actionable entity.</p>
 */
public record ValidationFindingDto(
    String severity,
    String name,
    String description,
    String explanation,
    String targetId,
    String targetName,
    String targetType,
    String targetKind
) {
}
