package edu.jhu.cobra.commons.graph.exchange

import edu.jhu.cobra.commons.graph.entity.IEntity

/**
 * Represents a filter function for selecting entities in a graph based on their ID.
 * The filter function takes an [IEntity.ID] as a parameter and returns a Boolean value indicating whether the entity should be selected or not.
 */
typealias EntityFilter = (IEntity.ID) -> Boolean