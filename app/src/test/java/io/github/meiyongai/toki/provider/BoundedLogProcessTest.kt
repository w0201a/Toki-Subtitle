package io.github.meiyongai.toki.subtitle.provider

import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class BoundedLogProcessTest {
    private class FakeProcess(private val source:InputStream):Process() {
        private val ended=CountDownLatch(1)
        override fun getInputStream()=source
        override fun getErrorStream():InputStream=ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream():OutputStream=ByteArrayOutputStream()
        override fun waitFor():Int{ended.await();return 0}
        override fun waitFor(timeout:Long,unit:TimeUnit)=ended.await(timeout,unit)
        override fun exitValue():Int{if(isAlive)throw IllegalThreadStateException();return 0}
        override fun isAlive()=ended.count>0
        override fun destroy(){ended.countDown();source.close()}
    }
    @Test fun oversizedInputStopsAtTheExactFileBudgetAndReleasesItsReservation() {
        val source=FakeProcess(ByteArrayInputStream(ByteArray(100_000){7}))
        val output=ByteArrayOutputStream();val released=AtomicInteger()
        val capture=BoundedLogProcess(source,output,1024L,10_000){released.incrementAndGet()}
        assertTrue(capture.await(3000));assertEquals(1024,output.size())
        assertEquals(1024L,capture.bytes.get());assertEquals(BoundedLogProcess.Reason.BYTES,capture.reason)
        assertFalse(source.isAlive);assertEquals(1,released.get())
    }
    @Test fun idleCaptureTimesOutWithoutNeedingAnotherLogLine() {
        val input=PipedInputStream();val producer=PipedOutputStream(input)
        val process=FakeProcess(input);val output=ByteArrayOutputStream()
        val capture=BoundedLogProcess(process,output,1024L,50)
        assertTrue(capture.await(3000));assertEquals(BoundedLogProcess.Reason.TIME,capture.reason)
        assertEquals(0,output.size());assertFalse(process.isAlive);producer.close()
    }
    @Test fun writeFailureStopsTheOwnedChildAndReleasesQuotaOnce() {
        val process=FakeProcess(ByteArrayInputStream(byteArrayOf(1,2,3)))
        val released=AtomicInteger();val output=object:OutputStream(){override fun write(value:Int){throw IOException("synthetic sink failure")}}
        val capture=BoundedLogProcess(process,output,1024,5000){released.incrementAndGet()}
        assertTrue(capture.await(3000));assertEquals(BoundedLogProcess.Reason.ERROR,capture.reason)
        capture.stop();assertFalse(process.isAlive);assertEquals(1,released.get())
    }
    @Test fun cancelledCopyDoesNotWriteTheBytesItJustRead() {
        var allowed=true
        val source=object:InputStream(){override fun read():Int{allowed=false;return 3}}
        val output=ByteArrayOutputStream()
        assertEquals(0L,BoundedLogProcess.copy(source,output,10){allowed});assertEquals(0,output.size())
    }
}
