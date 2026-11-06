package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CommentConcurrencyTest {
    @Test fun fourTranslationsCanRunButSourceAndMediaRemainSingleFlight() {
        val pool=CommentConcurrency()
        repeat(4){assertTrue(pool.acquire(CommentRepository.Lane.TRANSLATION))}
        assertFalse(pool.acquire(CommentRepository.Lane.TRANSLATION));assertEquals(4,pool.active(CommentRepository.Lane.TRANSLATION))
        for(lane in listOf(CommentRepository.Lane.SOURCE,CommentRepository.Lane.MEDIA)){assertTrue(pool.acquire(lane));assertFalse(pool.acquire(lane));pool.release(lane)}
        repeat(4){pool.release(CommentRepository.Lane.TRANSLATION)};assertEquals(0,pool.active(CommentRepository.Lane.TRANSLATION))
    }
    @Test fun simultaneousAdmissionNeverExceedsFourAndAllSlotsAreReclaimed() {
        val pool=CommentConcurrency();val ready=CountDownLatch(20);val start=CountDownLatch(1);val acquired=CountDownLatch(4);val finish=CountDownLatch(1);val peak=AtomicInteger()
        val threads=(0 until 20).map{Thread{
            ready.countDown();start.await()
            if(pool.acquire(CommentRepository.Lane.TRANSLATION)){peak.updateAndGet{old->maxOf(old,pool.active(CommentRepository.Lane.TRANSLATION))};acquired.countDown();finish.await();pool.release(CommentRepository.Lane.TRANSLATION)}
        }.apply{start()}}
        assertTrue(ready.await(3,TimeUnit.SECONDS));start.countDown()
        try{assertTrue(acquired.await(3,TimeUnit.SECONDS));assertEquals(4,pool.active(CommentRepository.Lane.TRANSLATION));assertTrue(peak.get()<=4)}
        finally{finish.countDown();threads.forEach{it.join(3000)}}
        assertEquals(0,pool.active(CommentRepository.Lane.TRANSLATION))
    }
}
