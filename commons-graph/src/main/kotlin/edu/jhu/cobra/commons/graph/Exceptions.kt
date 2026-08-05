package edu.jhu.cobra.commons.graph

/**
 * Base type for all graph-layer failures.
 *
 * Callers that treat any graph failure uniformly catch [GraphException];
 * callers reacting to one condition catch the concrete subtype.
 *
 * @param message Human-readable failure description.
 * @param cause The underlying cause, or null when the failure originates in the graph layer.
 */
public abstract class GraphException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Thrown when an entity with the given ID does not exist.
 *
 * @constructor Creates an exception for a missing entity.
 * @param entityId The ID of the entity that was not found.
 * @property entityId The ID of the entity that was not found.
 */
public class EntityNotExistException(
    public val entityId: String,
) : GraphException("Entity ID $entityId does not exist.") {
    public constructor(id: Int) : this(id.toString())
}

/**
 * Thrown when an entity with the given ID already exists.
 *
 * @constructor Creates an exception for an existing entity.
 * @param entityId The ID of the entity that already exists.
 * @property entityId The ID of the entity that already exists.
 */
public class EntityAlreadyExistException(
    public val entityId: String,
) : GraphException("Entity ID $entityId already exists.") {
    public constructor(id: Int) : this(id.toString())
}

/**
 * Thrown when an invalid property name is used on an entity.
 *
 * @constructor Creates an exception for an invalid property name.
 * @param propName The invalid property name.
 * @param entityId The entity ID, or null if not applicable.
 * @property propName The invalid property name.
 * @property entityId The entity ID, or null if not applicable.
 */
public class InvalidPropNameException(
    public val propName: String,
    public val entityId: String?,
) : GraphException("Invalid name $propName in entity $entityId.")

/**
 * Thrown when attempting to modify an entity that belongs to a frozen layer.
 *
 * Frozen layers are immutable; only entities in the active layer can be deleted.
 *
 * @constructor Creates an exception for a frozen-layer modification attempt.
 * @param entityId The ID of the entity in the frozen layer.
 * @property entityId The ID of the entity in the frozen layer.
 */
public class FrozenLayerModificationException(
    public val entityId: String,
) : GraphException("Cannot modify frozen-layer entity: $entityId") {
    public constructor(id: Int) : this(id.toString())
}
