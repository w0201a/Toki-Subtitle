package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import io.github.meiyongai.toki.subtitle.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CommentReplyAdapterTest {
    @Test fun recycledRootButtonsRetainTheirOwnCursorAndNeverRequestRepliesForAnUnrelatedRow() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        io.github.meiyongai.toki.subtitle.provider.ConfigClient.putValues(activity,
            mapOf(TranslationNetworkPolicy.KEY to false))
        assertTrue(io.github.meiyongai.toki.subtitle.provider.ConfigClient.isReady)
        HookRuntime.start("reply-adapter-test") { _, _ -> }; HookRuntime.attach(activity)
        val connectivity = activity.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val shadow = org.robolectric.Shadows.shadowOf(connectivity)
        val network = org.robolectric.shadows.ShadowNetwork.newInstance(42)
        val info = org.robolectric.shadows.ShadowNetworkInfo.newInstance(android.net.NetworkInfo.DetailedState.CONNECTED,
            android.net.ConnectivityManager.TYPE_WIFI, 0, true, true)
        shadow.addNetwork(network, info); shadow.setActiveNetworkInfo(info)
        val capabilities = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        org.robolectric.Shadows.shadowOf(capabilities).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        org.robolectric.Shadows.shadowOf(capabilities).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadow.setNetworkCapabilities(checkNotNull(connectivity.activeNetwork), capabilities)
        assertTrue(TranslationNetworkPolicy.capture().permitsNetwork())
        val parent = ListView(activity)
        val root = OfflineCommentData.Row("root1", "a", "h", "u", "source", null, "", 0, 2)
        val second = root.copy(id = "root2")
        val reply = root.copy(id = "reply", parentId = "root1", depth = 1)
        var valid = true
        val requested = mutableListOf<Pair<String, Long?>>()
        val adapter = OfflineCommentListAdapter(activity, { valid }) { }
        adapter.loadReplies = { snapshot, row -> requested += row.id to snapshot.replyCursors[row.id]?.next }
        adapter.submit(OfflineCommentData.Snapshot("a".repeat(64), "v", 1, listOf(root, reply, second), 2, false, false,
            replyCursors = mapOf("root2" to OfflineCommentData.ReplyCursor(40, true))))
        val view = adapter.getView(0, null, parent) as LinearLayout
        val button = view.findViewWithTag<Button>("comment-online-replies")
        assertEquals(View.VISIBLE, button.visibility)
        assertEquals(activity.getString(R.string.comment_reply_online_first), button.text.toString())
        button.performClick()
        assertEquals(listOf("root1" to null), requested)
        view.findViewWithTag<Button>("comment-local-replies").performClick()
        assertEquals(3, adapter.count)
        adapter.getView(2, view, parent)
        assertEquals(activity.getString(R.string.comment_reply_online_next), button.text.toString())
        button.performClick()
        assertEquals("root2" to 40L, requested.last())
        adapter.getView(1, view, parent)
        assertEquals(View.GONE, button.visibility)
        button.performClick() // Even a forced click on a recycled hidden button may not submit a reply row.
        assertEquals(2, requested.size)
        adapter.getView(0, view, parent)
        valid = false; button.performClick()
        assertEquals(2, requested.size)
        adapter.submit(null)
        button.performClick()
        assertEquals(2, requested.size)
        controller.pause().stop().destroy()
    }
}
