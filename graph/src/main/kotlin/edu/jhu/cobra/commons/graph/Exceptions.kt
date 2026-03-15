package edu.jhu.cobra.commons.graph

/**
 * Thrown when an entity with the given ID does not exist.
 *
 * @constructor Creates an exception for a missing entity.
 * @param id The ID of the entity that was not found.
 */
class EntityNotExistException(
    id: String,
) : Exception("Entity ID $id does not exist.") {
    constructor(id: Int) : this(id.toString())
}

/**
 * Thrown when an entity with the given ID already exists.
 *
 * @constructor Creates an exception for an existing entity.
 * @param id The ID of the entity that already exists.
 */
class EntityAlreadyExistException(
    id: String,
) : Exception("Entity ID $id already exists.") {
    constructor(id: Int) : this(id.toString())
}

/**
 * Thrown when an invalid property name is used on an entity.
 *
 * @constructor Creates an exception for an invalid property name.
 * @param propName The invalid property name.
 * @param entityId The entity ID, or null if not applicable.
 */
class InvalidPropNameException(
    propName: String,
    entityId: String?,
) : Exception("Invalid name $propName in entity $entityId.")

/**
 * Thrown when an operation is attempted on a storage that has already been closed.
 *
 * @constructor Creates an exception for accessing closed storage.
 */
class AccessClosedStorageException : IllegalStateException("Try to access closed graph storage")

/**
 * Thrown when attempting to modify an entity that belongs to a frozen layer.
 *
 * Frozen layers are immutable; only entities in the active layer can be deleted.
 *
 * @constructor Creates an exception for a frozen-layer modification attempt.
 * @param id The ID of the entity in the frozen layer.
 */
class FrozenLayerModificationException(
    id: String,
) : IllegalStateException("Cannot modify frozen-layer entity: $id") {
    constructor(id: Int) : this(id.toString())
}
