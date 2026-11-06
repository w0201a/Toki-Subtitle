package io.github.meiyongai.toki.subtitle.hook

internal object OfflineCommentDownloadProgress {
    fun advance(cursor:Long,page:OfflineCommentData.Snapshot):Long? {
        require(cursor>=0)
        require(page.rows.none{it.sourceTruncated}){"Comment source truncated; cache incomplete"}
        if(!page.hasMore)return null
        val next=page.nextCursor
        require(next!=null&&next>cursor){"Non-progressing offline comment cursor"}
        return next
    }
    /** A cursor can advance while the server repeats the same ids. Stop a bounded no-growth run,
     * retain the partial archive, and continue pending replies rather than downloading forever. */
    fun noGrowth(previous: Int, before: Set<String>, after: Set<String>, hasMore: Boolean): Int =
        if (!hasMore || after.any { it !in before }) 0 else (previous + 1).coerceAtMost(3)
    fun translated(page:OfflineCommentData.Snapshot,target:String):Boolean=page.rows.all{
        it.source.isBlank()||!it.sourceTruncated&&!it.translation.isNullOrBlank()&&
            CommentTranslationKey.normalizedTarget(it.language)==target
    }
    fun complete(expectedTop:Long,actualTop:Long,expectedReplies:Map<String,Long>,actualReplies:Map<String,Long>):Boolean=
        actualTop>=expectedTop&&expectedReplies.all{(id,count)->(actualReplies[id]?:0L)>=count}
}
