package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.value.StrVal

/**
 * The canonical text of node ID [id], the `core` of `StrVal(id)`.
 *
 * Two texts that stand for the same bytes have one canonical text. Text without an unpaired surrogate,
 * and text that is already canonical, is returned as the same instance.
 */
internal fun canonicalId(id: NodeID): NodeID = StrVal(id).core
