package org.fossify.messages.helpers
object Counts{var messages=0;var conversations=0;fun reset(){messages=0;conversations=0}}
fun refreshMessages(){Counts.messages++}
fun refreshConversations(){Counts.conversations++}
