package edu.jhu.cobra.commons.graph

/**
 * Thrown when an entity with the given ID does not exist.
 *
 * @constructor Creates an exception for a missing entity.
 * @param id The ID of the entity that was not found.
 */
public class EntityNotExistException(
    id: String,
) : Exception("Entity ID $id does not exist.") {
    public constructor(id: Int) : this(id.toString())
}

/**
 * Thrown when an entity with the given ID already exists.
 *
 * @constructor Creates an exception for an existing entity.
 * @param id The ID of the entity that already exists.
 */
public class EntityAlreadyExistException(
    id: String,
) : Exception("Entity ID $id already exists.") {
    public constructor(id: Int) : this(id.toString())
}

/**
 * Thrown when an invalid property name is used on an entity.
 *
 * @constructor Creates an exception for an invalid property name.
 * @param propName The invalid property name.
 * @param entityId The entity ID, or null if not applicable.
 */
public class InvalidPropNameException(
    propName: String,
    entityId: String?,
) : Exception("Invalid name $propName in entity $entityId.")

/**
 * Thrown when attempting to modify an entity that belongs to a frozen layer.
 *
 * Frozen layers are immutable; only entities in the active layer can be deleted.
 *
 * @constructor Creates an exception for a frozen-layer modification attempt.
 * @param id The ID of the entity in the frozen layer.
 */
public class FrozenLayerModificationException(
    id: String,
) : IllegalStateException("Cannot modify frozen-layer entity: $id") {
    public constructor(id: Int) : this(id.toString())
}
