package edu.jhu.cobra.commons.graph


/**
 * Thrown when an entity with the given ID does not exist.
 *
 * Used when trying to access, update, or delete a non-existent entity.
 *
 * @constructor Creates an exception for a missing entity.
 * @param id The ID of the entity that was not found.
 * @see IEntity.ID
 */
class EntityNotExistException(id: IEntity.ID) : Exception("Entity ID $id does not exist.")

/**
 * Thrown when an entity with the given ID already exists.
 *
 * Used when attempting to create or add an entity that already exists.
 *
 * @constructor Creates an exception for an existing entity.
 * @param id The ID of the entity that already exists.
 * @see IEntity.ID
 */
class EntityAlreadyExistException(id: IEntity.ID) : Exception("Entity ID $id already exists.")

/**
 * Thrown when an invalid property name is used for an entity.
 *
 * The property name is invalid if it starts with "meta_".
 *
 * @constructor Creates an exception for an invalid property name.
 * @param propName The name of the invalid property.
 * @param eid The ID of the entity for which the invalid property was attempted.
 * @see IEntity.ID
 */
class InvalidPropNameException(propName: String, eid: IEntity.ID?) : Exception("Invalid name $propName in entity $eid.")

/**
 * Thrown when an operation is attempted on a storage that has already been closed.
 *
 * Used within graph storage systems to indicate that the storage context is no longer active or accessible.
 *
 * @constructor Creates an exception for accessing closed storage.
 */
class AccessClosedStorageException : IllegalStateException("Try to access closed graph storage")
