package com.callguard.app.screening

import com.callguard.app.policy.*
import com.callguard.app.risk.RiskSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import java.util.concurrent.*
import java.util.concurrent.atomic.*

class ScreeningCoordinatorTest {
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val workers = Executors.newFixedThreadPool(2)
    private val writes = Executors.newSingleThreadExecutor()
    @After fun close() { timer.shutdownNow();workers.shutdownNow();writes.shutdownNow() }
    private fun now() = System.nanoTime()/1_000_000
    private fun request(budget:Long=800) = ScreeningRequest("event",null,true,100,ScreeningDeadline(now(),budget))
    private fun state(block:Boolean=false) = ScreeningState(PolicyInput(100,blockRule=block,snapshot=RiskSnapshot()))

    @Test fun cloudDeadlinePreservesLocalSilenceAndSubmitsOnlyOnce() {
        val output=AtomicReference<PolicyResult>();val calls=AtomicInteger();val done=CountDownLatch(1)
        val release=CountDownLatch(1)
        val source=object:ScreeningDataSource {
            override fun load(request:ScreeningRequest,publish:(ScreeningState)->Unit):ScreeningState =
                state().copy(input=PolicyInput(100,snapshot=RiskSnapshot(userLabel=com.callguard.app.risk.UserLabel.HARASSMENT)))
            override fun persist(request:ScreeningRequest,state:ScreeningState,result:PolicyResult)=Unit
        }
        val gateway=com.callguard.app.cloud.CloudRiskGateway { _,_,local,_ -> release.await();local.copy(decision=Decision.ALLOW) }
        ScreeningCoordinator(source,workers,timer,writes,MonotonicClock(::now),cloud=gateway)
            .screen(request(100),{output.set(it);calls.incrementAndGet();done.countDown()},{})
        try {
            assertTrue(done.await(1,TimeUnit.SECONDS));assertEquals(Decision.SILENCE,output.get().decision)
            assertTrue("CLOUD_TIMEOUT" in output.get().matchedRules)
            assertEquals(65,output.get().riskScore)
        } finally {release.countDown()}
        workers.shutdown();assertTrue(workers.awaitTermination(1,TimeUnit.SECONDS));assertEquals(1,calls.get())
    }
    @Test fun cloudExceptionPreservesLocalBlackAndWhiteRules() {
        for(block in listOf(false,true)) {
            val results=mutableListOf<PolicyResult>()
            val source=object:ScreeningDataSource {
                override fun load(request:ScreeningRequest,publish:(ScreeningState)->Unit)=ScreeningState(PolicyInput(100,allowRule=!block,blockRule=block))
                override fun persist(request:ScreeningRequest,state:ScreeningState,result:PolicyResult)=Unit
            }
            val gateway=com.callguard.app.cloud.CloudRiskGateway {_,_,_,_->throw IllegalStateException()}
            ScreeningCoordinator(source,Executor {it.run()},timer,Executor {it.run()},MonotonicClock(::now),cloud=gateway)
                .screen(request(),{results+=it},{})
            assertEquals(if(block) Decision.BLOCK else Decision.ALLOW,results.single().decision)
            assertTrue(results.single().fallbackUsed)
        }
    }

    @Test fun blockedReadCannotBlockDeadlineAndLateResultCannotResubmit() {
        val started=CountDownLatch(1);val release=CountDownLatch(1);val finished=CountDownLatch(1)
        val responded=CountDownLatch(1);val saved=CountDownLatch(1);val calls=AtomicInteger()
        val output=AtomicReference<PolicyResult>()
        val source=object:ScreeningDataSource {
            override fun load(request:ScreeningRequest,publish:(ScreeningState)->Unit):ScreeningState {
                started.countDown();release.await();finished.countDown();return state(true)
            }
            override fun persist(request:ScreeningRequest,state:ScreeningState,result:PolicyResult) { saved.countDown() }
        }
        val start=now()
        ScreeningCoordinator(source,workers,timer,writes,MonotonicClock(::now)).screen(request(150),{
            output.set(it);calls.incrementAndGet();responded.countDown()
        },{})
        try {
            assertTrue(started.await(1,TimeUnit.SECONDS));assertTrue(responded.await(2,TimeUnit.SECONDS))
            assertTrue(now()-start<2000);assertEquals(Decision.ALLOW,output.get().decision)
            assertTrue(output.get().fallbackUsed);assertTrue(output.get().matchedRules.contains("LOCAL_DEADLINE"))
            assertTrue(saved.await(1,TimeUnit.SECONDS))
        } finally { release.countDown() }
        assertTrue(finished.await(1,TimeUnit.SECONDS))
        workers.shutdown();assertTrue(workers.awaitTermination(1,TimeUnit.SECONDS));assertEquals(1,calls.get())
    }

    @Test fun failurePreservesAlreadyVerifiedRulesAndWritesAfterResponse() {
        for (block in listOf(false,true)) {
            val order=mutableListOf<String>();val results=mutableListOf<PolicyResult>()
            val source=object:ScreeningDataSource {
                override fun load(request:ScreeningRequest,publish:(ScreeningState)->Unit):ScreeningState {
                    publish(state(block).copy(input=PolicyInput(100,allowRule=!block,blockRule=block)))
                    throw IllegalStateException("simulated private data must not escape")
                }
                override fun persist(request:ScreeningRequest,state:ScreeningState,result:PolicyResult) { order += "database" }
            }
            ScreeningCoordinator(source,Executor { it.run() },timer,Executor { it.run() },MonotonicClock(::now))
                .screen(request(), {results+=it;order+="response"},{order+="evidence"})
            assertEquals(listOf("response","evidence","database"),order)
            assertEquals(if(block) Decision.BLOCK else Decision.ALLOW,results.single().decision)
            assertTrue(results.single().fallbackUsed)
        }
    }

    @Test fun responseFailureNeverWritesSuccessfulEvidenceOrRetries() {
        val source=object:ScreeningDataSource {
            override fun load(request:ScreeningRequest,publish:(ScreeningState)->Unit)=state()
            override fun persist(request:ScreeningRequest,state:ScreeningState,result:PolicyResult) { fail("must not persist") }
        }
        val statuses=mutableListOf<String>();var calls=0
        ScreeningCoordinator(source,Executor {it.run()},timer,Executor {it.run()},MonotonicClock(::now)) { _, status -> statuses+=status }
            .screen(request(),{calls++;throw IllegalStateException()},{fail("must not claim response success")})
        assertEquals(1,calls);assertEquals(listOf("response_failed"),statuses)
    }

    @Test fun queueSaturationFallsBackWithoutExecutingReader() {
        val source=object:ScreeningDataSource {
            override fun load(request:ScreeningRequest,publish:(ScreeningState)->Unit):ScreeningState = error("not reached")
            override fun persist(request:ScreeningRequest,state:ScreeningState,result:PolicyResult)=Unit
        }
        val results=mutableListOf<PolicyResult>()
        ScreeningCoordinator(source,Executor {throw RejectedExecutionException()},timer,Executor {it.run()},MonotonicClock(::now))
            .screen(request(),{results+=it},{})
        assertEquals(Decision.ALLOW,results.single().decision);assertTrue(results.single().matchedRules.contains("LOCAL_QUEUE_FULL"))
    }

    @Test fun postResponseWriteFailureCannotChangeDecision() {
        val statuses=mutableListOf<String>();var count=0
        val source=object:ScreeningDataSource {
            override fun load(request:ScreeningRequest,publish:(ScreeningState)->Unit)=state(true)
            override fun persist(request:ScreeningRequest,state:ScreeningState,result:PolicyResult) { throw IllegalStateException() }
        }
        ScreeningCoordinator(source,Executor {it.run()},timer,Executor {it.run()},MonotonicClock(::now)){ _, status -> statuses+=status }
            .screen(request(),{count++;assertEquals(Decision.BLOCK,it.decision)},{throw IllegalStateException()})
        assertEquals(1,count);assertEquals(listOf("evidence_write_failed","record_write_failed"),statuses)
    }

    @Test fun deadlineUsesMonotonicBoundary() {
        val deadline=ScreeningDeadline(100,800)
        assertEquals(1L,deadline.remainingMs(899));assertTrue(deadline.expired(900));assertEquals(0L,deadline.remainingMs(1000))
    }
}
