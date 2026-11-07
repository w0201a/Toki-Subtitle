package io.github.meiyongai.toki.subtitle.hook

/** Snapshot-scoped lookup: adapter binds and mode/scroll updates never rescan every reply. */
internal class CommentReaderIndex(rows:List<OfflineCommentData.Row>) {
    val byId:Map<String,OfflineCommentData.Row> = rows.associateBy{it.id}
    val roots:List<OfflineCommentData.Row> = rows.filter{it.parentId.isBlank()}
    private val threads:Map<String,List<OfflineCommentData.Row>> = rows.asSequence().filter{it.parentId.isNotBlank()}
        .mapNotNull { row -> rootOf(row)?.let { it to row } }.groupBy({it.first},{it.second})
    private fun rootOf(row:OfflineCommentData.Row):String? {
        var current=row;var hops=0
        while(current.parentId.isNotBlank()&&hops++<4)current=byId[current.parentId]?:return null
        return current.id.takeIf{current.parentId.isBlank()}
    }
    fun replies(root:String):List<OfflineCommentData.Row> = threads[root].orEmpty()
    fun matches(id:String,source:String):Boolean = byId[id]?.source==source
    fun visible(expanded:Set<String>,rootLimit:Int):List<OfflineCommentData.Row> = roots.take(rootLimit).flatMap { root ->
        listOf(root)+replies(root.id).let{if(root.id in expanded)it else it.take(2)}
    }
}
