package io.github.meiyongai.toki.subtitle.hook

import java.util.concurrent.atomic.AtomicInteger

/** Network concurrency is bounded independently of executor queue length. Source and media stay single-flight. */
internal class CommentConcurrency(private val translations:Int=4) {
    init { require(translations in 1..8) }
    private val running=CommentRepository.Lane.entries.associateWith{AtomicInteger()}
    fun limit(lane:CommentRepository.Lane)=if(lane==CommentRepository.Lane.TRANSLATION)translations else 1
    fun acquire(lane:CommentRepository.Lane):Boolean {
        val counter=running.getValue(lane)
        while(true){val value=counter.get();if(value>=limit(lane))return false;if(counter.compareAndSet(value,value+1))return true}
    }
    fun release(lane:CommentRepository.Lane){check(running.getValue(lane).decrementAndGet()>=0)}
    fun active(lane:CommentRepository.Lane)=running.getValue(lane).get()
}
