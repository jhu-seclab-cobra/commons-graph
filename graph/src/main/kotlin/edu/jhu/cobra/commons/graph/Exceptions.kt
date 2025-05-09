package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.entity.IEntity


/**
 * Thrown when an entity with the given ID does not exist in the system.
 * This is typically used when trying to access, update, or delete a non-existent entity.
 *
 * @param id The ID of the entity that was not found.
 */
class EntityNotExistException(id: IEntity.ID) : Exception("Entity ID $id does not exist.")

/**
 * Thrown when an entity with the given ID already exists in the system.
 * This is typically used when attempting to create or add an entity that already exists.
 *
 * @param id The ID of the entity that already exists.
 */
class EntityAlreadyExistException(id: IEntity.ID) : Exception("Entity ID $id already exists.")

/**
 * Exception thrown when an invalid property is used for an entity.
 * The property name is considered invalid if it starts with "meta_".
 *
 * @param propName The name of the invalid property.
 * @param eid The ID of the entity for which the invalid property was attempted.
 */
class InvalidPropNameException(propName: String, eid: IEntity.ID?) : Exception("Invalid name $propName in entity $eid.")

/**
 * Exception thrown when an operation is attempted on a storage that has already been closed.
 * This exception is used within graph storage systems to indicate that the requested
 * action is invalid because the storage context is no longer active or accessible.
 *
 * @throws AccessClosedStorageException when the storage has been closed and an operation is attempted.
 */
class AccessClosedStorageException : IllegalStateException("Try to access closed graph storage")
