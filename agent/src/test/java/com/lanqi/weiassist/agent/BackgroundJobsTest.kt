package com.lanqi.weiassist.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundJobsTest {
    private fun job(session: String, call: String) = BackgroundJob(session, call, "sleep 500", 600, 42L)

    @Test fun `only jobs whose waiter died are orphans, and a finished one is gone`() {
        val jobs = BackgroundJobs()
        jobs.add(job("s", "c1"))
        assertEquals(emptyList<BackgroundJob>(), jobs.orphaned())
        jobs.orphanAll()
        jobs.add(job("s", "c2"))
        assertEquals(listOf(job("s", "c1")), jobs.orphaned())
        jobs.remove("s", "c1")
        assertEquals(emptyList<BackgroundJob>(), jobs.orphaned())
        assertEquals(listOf(job("s", "c2")), jobs.jobs.value)
    }

    @Test fun `ending a session drops its jobs`() {
        val jobs = BackgroundJobs()
        jobs.add(job("s", "c1")); jobs.add(job("t", "c2"))
        jobs.orphanAll()
        jobs.removeSession("s")
        assertEquals(listOf(job("t", "c2")), jobs.orphaned())
    }
}
