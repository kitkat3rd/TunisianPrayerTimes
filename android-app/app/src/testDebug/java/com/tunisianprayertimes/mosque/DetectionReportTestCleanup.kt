package com.tunisianprayertimes.mosque

import com.tunisianprayertimes.mosque.trace.SilenceTraceStore

// Robolectric resets SQLite connection pointers after each test, even in a shared sandbox.
internal fun closeDetectionReportsAfterTest() = SilenceTraceStore.closeForTest()
