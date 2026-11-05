package io.github.meiyongai.toki.subtitle.provider

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** The child only owns a pipe, never an unbounded file descriptor. The app controls every persisted byte. */
internal class BoundedLogProcess(private val process: Process, private val output: OutputStream,
    private val byteLimit: Long, millisLimit: Long, private val release: () -> Unit = {}) {
    enum class Reason { RUNNING, COMPLETE, BYTES, TIME, USER, ERROR }
    companion object {
        private val watchdog = Executors.newSingleThreadScheduledExecutor { task -> Thread(task,"toki-log-limit").apply { isDaemon=true } }
        internal fun copy(input: InputStream, output: OutputStream, limit: Long, active: () -> Boolean): Long {
            require(limit > 0); val buffer=ByteArray(16*1024); var written=0L
            while (written < limit && active()) {
                val count=input.read(buffer,0,minOf(buffer.size.toLong(),limit-written).toInt())
                if (count < 0 || !active()) break
                if (count == 0) continue
                output.write(buffer,0,count); written+=count
            }
            return written
        }
    }
    private val ended=AtomicBoolean(false); private val done=CountDownLatch(1)
    private val outcome=AtomicReference(Reason.RUNNING)
    val reason: Reason get() = outcome.get()
    val bytes=AtomicLong(0L)
    val isRunning: Boolean get() = !ended.get() && done.count > 0
    private val sinkLock=Any()
    private val released=AtomicBoolean(false)
    @Volatile private var expiry: java.util.concurrent.ScheduledFuture<*>?=null
    private fun finishOutput() {
        synchronized(sinkLock) {
            if(released.compareAndSet(false,true)) {
                try { output.close() } catch (_:Exception) { }
                try { release() } finally { done.countDown() }
            }
        }
        expiry?.cancel(false)
    }
    init {
        require(byteLimit>0 && millisLimit>0)
        expiry=watchdog.schedule({stop(Reason.TIME)},millisLimit,TimeUnit.MILLISECONDS)
        Thread({
            try {
                process.inputStream.use { input ->
                    output.use { sink ->
                        val writer=object:OutputStream() {
                            override fun write(value:Int){synchronized(sinkLock){if(!ended.get()){sink.write(value);bytes.incrementAndGet()}}}
                            override fun write(buffer:ByteArray,offset:Int,length:Int){synchronized(sinkLock){if(!ended.get()){sink.write(buffer,offset,length);bytes.addAndGet(length.toLong())}}}
                        }
                        copy(input,writer,byteLimit){!ended.get()}
                    }
                }
                if (!ended.get()) outcome.compareAndSet(Reason.RUNNING,if(bytes.get()>=byteLimit)Reason.BYTES else Reason.COMPLETE)
            } catch (_:Exception) { outcome.compareAndSet(Reason.RUNNING,Reason.ERROR) }
            finally {
                ended.set(true)
                try { process.destroy() } catch (_:Exception) { }
                finishOutput()
            }
        },"toki-log-writer").apply { isDaemon=true;start() }
    }
    fun stop(reason:Reason=Reason.USER) {
        if (ended.compareAndSet(false,true)) {
            outcome.compareAndSet(Reason.RUNNING,reason)
            try { process.destroy() } catch (_:Exception) { }
            // Close the file and release capacity independently of a blocked vendor pipe read.
            finishOutput()
            // Closing the pipe is isolated so a vendor stream cannot block the watchdog or the UI.
            Thread({ try { process.inputStream.close() } catch (_:Exception) { } },"toki-log-close").apply { isDaemon=true;start() }
        }
    }
    fun await(millis:Long):Boolean=done.await(millis,TimeUnit.MILLISECONDS)
}
