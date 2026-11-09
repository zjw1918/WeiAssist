package com.lanqi.weiassist.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundJobsTest {
    @Test fun `jobs survive being written and read back`() {
        val jobs = listOf(BackgroundJob("s", "c1", "sleep 500; echo \"done\"", 600, 42L), BackgroundJob("t", "c2", "", 120, 7L))
        assertEquals(jobs, BackgroundJobs.parse(BackgroundJobs.json(jobs)))
    }
}
