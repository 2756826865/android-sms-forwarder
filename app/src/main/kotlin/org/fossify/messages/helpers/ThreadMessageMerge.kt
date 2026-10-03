package org.fossify.messages.helpers

import org.fossify.messages.models.Message

/** SMS and MMS use independent provider IDs; fresh provider state wins over a cached copy. */
internal fun mergeThreadMessageSources(
    provider: List<Message>,
    cached: List<Message>,
    dateBefore: Int = -1,
    includeScheduled: Boolean = true,
): List<Message> = (cached + provider)
    .associateBy { it.id to it.isMMS }
    .values
    .filter { (dateBefore == -1 || it.date < dateBefore) && (includeScheduled || !it.isScheduled) }
