package com.littlekitty.xiaokele.handoff

/**
 * Internal explicit-intent extras only. There is deliberately no public URI
 * scheme, no exported intent-filter for these keys and no implicit deeplink
 * contract in this phase. Components always construct an explicit Intent whose
 * component is MainActivity.
 */
object InternalIntentContract {
    const val EXTRA_EVENT_ID = "com.littlekitty.xiaokele.internal.extra.EVENT_ID"
    const val EXTRA_TODO_ID = "com.littlekitty.xiaokele.internal.extra.TODO_ID"
    const val EXTRA_EXECUTION_ID = "com.littlekitty.xiaokele.internal.extra.EXECUTION_ID"
    const val EXTRA_DELIVERY_ID = "com.littlekitty.xiaokele.internal.extra.DELIVERY_ID"
    const val EXTRA_SOURCE = "com.littlekitty.xiaokele.internal.extra.SOURCE"

    const val SOURCE_NOTIFICATION = "NOTIFICATION"
    const val SOURCE_WIDGET = "WIDGET"
}
