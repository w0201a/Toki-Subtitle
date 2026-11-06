package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class CommentRepositoryTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val account="a".repeat(64)
    private fun file()=File(System.getProperty("java.io.tmpdir"),"toki-v3-tests/${UUID.randomUUID()}.sqlite")
    private fun repository()=CommentRepository(app,file())
    private fun row(id:String,parent:String="",text:String="source $id",translation:String?=null,replies:Long=0)=
        OfflineCommentData.Row(id,"Author","handle","user",text,translation,"zh-hans",if(parent.isBlank())0 else 1,replies,parentId=parent,mediaKnown=true)
    private fun page(v:String,rows:List<OfflineCommentData.Row>,more:Boolean=false,next:Long?=null)=
        OfflineCommentData.Snapshot(account,v,1,rows,500,more,false,next)
    private fun register(repo:CommentRepository,v:String,q:OfflineCommentBudget.Quotas=OfflineCommentBudget.Quotas(200,1000,15))=
        repo.register(account,v,"model-$v",q,"zh-hans")
    @Test fun sixtyVideosRotateBeforeAnyReceivesASecondTurnAndNewVideosJoinTail() {
        repository().use { repo ->
            repeat(60){register(repo,"v$it");repo.importPage(page("v$it",listOf(row("c"))))}
            val first=mutableListOf<String>()
            repeat(60){val w=repo.claim(account,CommentRepository.Lane.TRANSLATION,0,true)!!;first+=w.video;repo.release(w)}
            assertEquals(60,first.toSet().size)
            register(repo,"new");repo.importPage(page("new",listOf(row("c"))))
            assertEquals(first.first(),repo.claim(account,CommentRepository.Lane.TRANSLATION,0,true)!!.video)
        }
    }
    @Test fun turnsSurviveReopenAndAbandonedWorkIsRecovered() {
        val f=file();val first:String
        CommentRepository(app,f).use{repo->repeat(3){register(repo,"v$it")};first=repo.claim(account,CommentRepository.Lane.SOURCE,0,true)!!.video}
        CommentRepository(app,f).use{repo->assertNotEquals(first,repo.claim(account,CommentRepository.Lane.SOURCE,0,true)!!.video)}
    }
    @Test fun failingFirstItemDoesNotBlockLaterTextOrOtherVideos() {
        repository().use{repo->register(repo,"v");repo.importPage(page("v",listOf(row("bad"),row("good"))))
            val bad=repo.claim(account,CommentRepository.Lane.TRANSLATION,0,true)!!
            repo.fail(bad,IllegalStateException("temporary"),0)
            val good=repo.claim(account,CommentRepository.Lane.TRANSLATION,1,true)!!
            assertNotEquals(bad.item,good.item);assertTrue(repo.commitTranslation(good,"成功"))
            assertEquals(1,repo.readVideo(account,"v")!!.translated)
            assertEquals("retry",repo.readVideo(account,"v")!!.translationStates[bad.item]!!.status)
        }
    }
    @Test fun originalChangeAndTargetChangeCannotAcceptOldTranslation() {
        repository().use{repo->register(repo,"v");repo.importPage(page("v",listOf(row("c"))))
            val old=repo.claim(account,CommentRepository.Lane.TRANSLATION,0,true)!!
            repo.importPage(page("v",listOf(row("c",text="new source"))))
            assertFalse(repo.commitTranslation(old,"错误译文"))
            assertNull(repo.readVideo(account,"v")!!.snapshot.rows.single().translation)
            val fresh=repo.claim(account,CommentRepository.Lane.TRANSLATION,0,true)!!
            repo.register(account,"v","",OfflineCommentBudget.Quotas(200,1000,15),"ja")
            assertFalse(repo.commitTranslation(fresh,"wrong language"))
        }
    }
    @Test fun duplicatePagesPreserveTranslationAndReplyQuotaDoesNotConsumeRoots() {
        repository().use{repo->register(repo,"v",OfflineCommentBudget.Quotas(2,2,1))
            val rows=listOf(row("a",translation="甲"),row("b"),row("r1","a"),row("r2","a"),row("r3","b"))
            repo.importPage(page("v",rows));repo.importPage(page("v",rows.map{it.copy(translation=null)}))
            val s=repo.readVideo(account,"v")!!
            assertEquals(2,s.snapshot.rows.count{it.parentId.isBlank()});assertEquals(2,s.snapshot.rows.count{it.parentId.isNotBlank()})
            assertEquals(3,s.storedReplies);assertEquals("甲",s.snapshot.rows.first{it.id=="a"}.translation)
            register(repo,"v",OfflineCommentBudget.Quotas(1,0,0));assertEquals(5,repo.readVideo(account,"v")!!.let{it.storedRoots+it.storedReplies})
        }
    }
    @Test fun pauseIsPerVideoEpochBarrierAndCacheRemainsReadable() {
        repository().use{repo->register(repo,"one");register(repo,"two");repo.importPage(page("one",listOf(row("c"))))
            val w=repo.claim(account,CommentRepository.Lane.TRANSLATION,0,true)!!
            repo.submitCommand(account,"one",CommentRepository.Command.Pause)
            assertFalse(repo.commitTranslation(w,"late"));assertEquals(1,repo.readVideo(account,"one")!!.snapshot.rows.size)
            assertEquals("two",repo.claim(account,CommentRepository.Lane.SOURCE,0,true)!!.video)
        }
    }
    @Test fun cacheReadObserveAndThreadExpansionNeverAuthorizeNetwork() {
        repository().use{repo->register(repo,"v");repo.importPage(page("v",listOf(row("c"),row("r","c"))))
            var changed=0;val subscription=repo.observeVideo(account,"v"){changed++}
            repeat(3){repo.readVideo(account,"v");assertEquals(1,repo.readThread(account,"v","c").size)}
            assertEquals(0,changed);assertNull(repo.claim(account,CommentRepository.Lane.SOURCE,0,false))
            repo.submitCommand(account,"v",CommentRepository.Command.Prepare)
            assertNotNull(repo.claim(account,CommentRepository.Lane.SOURCE,0,false));assertTrue(changed>0);subscription.close()
        }
    }
    @Test fun emojiAndMediaOnlyBodiesDoNotNeedTranslationButTruncatedTextIsNotReady() {
        assertFalse(CommentRepository.needsTranslation(row("emoji",text="😂🤣 ❤️")))
        assertFalse(CommentRepository.needsTranslation(row("blank",text="")))
        assertTrue(CommentRepository.needsTranslation(row("latin",text="Me grabbing my child")))
        repository().use{repo->register(repo,"v");repo.importPage(page("v",listOf(row("cut").copy(sourceTruncated=true))))
            assertNull(repo.claim(account,CommentRepository.Lane.TRANSLATION,0,true));assertNotEquals("ready",repo.readVideo(account,"v")!!.phase)
        }
    }
    @Test fun sourceAlternatesWithReplyStreamsAndStopsThreeNoGrowthPages() {
        repository().use{repo->register(repo,"v")
            val initial=repo.claim(account,CommentRepository.Lane.SOURCE,0,true)!!
            assertEquals("",initial.item)
            assertTrue(repo.commitPage(initial,page("v",listOf(row("a",replies=10)),true,20)))
            val reply=repo.claim(account,CommentRepository.Lane.SOURCE,0,true)!!;assertEquals("a",reply.item)
            repo.commitPage(reply,page("v",listOf(row("r","a")),true,20))
            assertEquals("",repo.claim(account,CommentRepository.Lane.SOURCE,0,true)!!.item)
        }
        repository().use{repo->register(repo,"v");repeat(3){i->val w=repo.claim(account,CommentRepository.Lane.SOURCE,0,true)!!;repo.commitPage(w,page("v",emptyList(),true,(i+1)*20L))}
            assertNull(repo.claim(account,CommentRepository.Lane.SOURCE,0,true));assertEquals("partial",repo.readVideo(account,"v")!!.phase)
        }
    }
    @Test fun legacyMigrationPreservesFilesAndImportsRowsBeyondActiveQuota() {
        val v="legacy-${UUID.randomUUID()}";val rows=(0..5).map{row("c$it",translation="译文 $it")}
        OfflineCommentArchive.save(app,page(v,rows),0)
        OfflineCommentArchive.saveModel(app,account,v,"model")
        val folder=File(app.noBackupFilesDir,"toki-offline-comments-v2/${CommentSnapshotStore.key(account,v)}")
        val before=folder.listFiles()!!.associate{it.name to it.readBytes().toList()}
        val f=file()
        CommentRepository(app,f).use{repo->repo.migrateLegacy(account,"zh-hans"){OfflineCommentBudget.Quotas(2,0,0)}
            val s=repo.readVideo(account,v)!!;assertEquals(6,s.storedRoots);assertEquals(2,s.snapshot.rows.size);assertEquals(2,s.translated)}
        CommentRepository(app,f).use{repo->repo.migrateLegacy(account,"zh-hans"){OfflineCommentBudget.Quotas(2,0,0)};assertEquals(6,repo.readVideo(account,v)!!.storedRoots)}
        assertEquals(before,folder.listFiles()!!.associate{it.name to it.readBytes().toList()})
    }
    @Test fun firstTwoRepliesAreShownWithoutHidingOtherMainComments() {
        val rows=listOf(row("a"),row("r1","a"),row("r2","a"),row("r3","a"),row("b"))
        assertEquals(listOf("a","r1","r2","b"),CommentReaderSheet.visibleRows(rows,emptySet(),40).map{it.id})
        assertEquals(listOf("a","r1","r2","r3","b"),CommentReaderSheet.visibleRows(rows,setOf("a"),40).map{it.id})
    }
    @Test fun singleCommentCommandDoesNotAuthorizeSourceMediaOrOtherComments() {
        repository().use { repo -> register(repo,"v");repo.importPage(page("v",listOf(row("wanted"),row("other"))))
            repo.submitCommand(account,"v",CommentRepository.Command.Translate("wanted"))
            assertNull(repo.claim(account,CommentRepository.Lane.SOURCE,0,false))
            val w=repo.claim(account,CommentRepository.Lane.TRANSLATION,0,false)!!
            assertEquals("wanted",w.item);repo.commitTranslation(w,"译文")
            assertNull(repo.claim(account,CommentRepository.Lane.TRANSLATION,0,false))
        }
    }
    @Test fun nonProgressingCursorKeepsReceivedOriginalInsteadOfRollingBackThePage() {
        repository().use { repo -> register(repo,"v")
            val w=repo.claim(account,CommentRepository.Lane.SOURCE,0,true)!!
            assertTrue(repo.commitPage(w,page("v",listOf(row("captured",translation="已保存")),true,0)))
            val s=repo.readVideo(account,"v")!!
            assertEquals("captured",s.snapshot.rows.single().id);assertEquals("partial",s.phase)
            assertNull(repo.claim(account,CommentRepository.Lane.SOURCE,0,true))
        }
    }
    @Test fun screenshotCommentCanFinishTextAndMediaAndRemainReadableAfterReopen() {
        val f=file();val media=OfflineCommentData.Media("f".repeat(64),OfflineCommentData.MediaKind.IMAGE,listOf("https://example.invalid/not-requested"))
        CommentRepository(app,f).use { repo -> register(repo,"screenshot")
            repo.importPage(page("screenshot",listOf(row("screenshot-row",text="Me grabbing my child").copy(media=listOf(media)))))
            var s=repo.readVideo(account,"screenshot")!!;assertEquals(0,s.translated);assertEquals(0,s.mediaReady)
            val text=repo.claim(account,CommentRepository.Lane.TRANSLATION,0,true)!!;assertTrue(repo.commitTranslation(text,"我抱起我的孩子"))
            val asset=repo.claim(account,CommentRepository.Lane.MEDIA,0,true)!!
            val bytes=java.io.ByteArrayOutputStream().also{android.graphics.Bitmap.createBitmap(2,2,android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
            OfflineCommentMediaStore.store(app,account,"screenshot",media,bytes,{repo.active(asset)},{})
            assertTrue(repo.commitMedia(asset));s=repo.readVideo(account,"screenshot")!!;assertEquals(1,s.translated);assertEquals(1,s.mediaReady)
        }
        CommentRepository(app,f).use { repo -> val s=repo.readVideo(account,"screenshot")!!
            assertEquals("我抱起我的孩子",s.snapshot.rows.single().translation);assertEquals(1,s.mediaReady)
            assertNull(repo.claim(account,CommentRepository.Lane.MEDIA,0,false));assertNull(repo.claim(account,CommentRepository.Lane.TRANSLATION,0,false))
        }
    }
    @Test fun noGrowthIsPerStreamAndHistoricalRowsDoNotMakeTheFirstFreshPageLookStuck() {
        repository().use { repo -> register(repo,"v");val cached=listOf(row("a",translation="甲"))
            repo.importPage(page("v",cached,true,20))
            repeat(3) { i -> val w=repo.claim(account,CommentRepository.Lane.SOURCE,0,true)!!
                repo.commitPage(w,page("v",cached,true,(i+1)*20L))
            }
            assertNotNull(repo.claim(account,CommentRepository.Lane.SOURCE,0,true))
        }
    }

    @Test fun deviceArchiveMigrationIsLossless() {
        val fixture=System.getenv("TOKI_COMMENT_FIXTURE")?.let(::File)
        org.junit.Assume.assumeTrue("Private read-only device fixture not supplied",fixture?.isDirectory==true)
        val base=File(app.noBackupFilesDir,"toki-offline-comments-v2")
        fixture!!.copyRecursively(base,overwrite=false)
        val accounts=fixture.listFiles()!!.filter{it.isDirectory}.mapNotNull{folder->
            try{org.json.JSONObject(File(folder,"index.json").readText()).getString("account")}catch(_:Exception){null}
        }.distinct()
        repository().use { repo ->
            val dbField=CommentRepository::class.java.getDeclaredField("db").apply{isAccessible=true}
            val db=dbField.get(repo) as android.database.sqlite.SQLiteDatabase
            for(a in accounts) {
                repo.migrateLegacy(a,"zh-hans"){OfflineCommentBudget.Quotas(2500,2500,2500)}
                for(folder in fixture.listFiles()!!.filter{it.isDirectory}) {
                    val index=org.json.JSONObject(File(folder,"index.json").readText());if(index.getString("account")!=a)continue
                    val v=index.getString("video");val pages=index.getJSONObject("pages");val originals=mutableSetOf<String>();val translations=mutableSetOf<List<String>>()
                    for(name in pages.keys()) {
                        val p=CommentSnapshotCodec.decode(File(folder,"$name.bin").readBytes())
                        p.rows.forEach{r->originals+=r.id;if(!r.translation.isNullOrBlank())CommentTranslationKey.normalizedTarget(r.language)?.let{target->translations+=listOf(r.id,CommentRepository.hash(r.source),target)}}
                    }
                    val state=repo.readVideo(a,v)!!
                    assertEquals("unique original rows",originals.size,state.storedRoots+state.storedReplies)
                    for(key in translations)db.rawQuery("SELECT text FROM translations WHERE account=? AND video=? AND id=? AND source_hash=? AND target=?",arrayOf(a,v,key[0],key[1],key[2])).use{c->assertTrue("saved translation must survive migration",c.moveToFirst());assertTrue(c.getString(0).isNotBlank())}
                    assertEquals("all valid pages import without error",0,state.migrationErrors)
                }
            }
        }
    }

    @Test fun progressCountersDoNotDecodeCommentBodiesAndMatchPreparedScope() {
        repository().use{repo->register(repo,"v",OfflineCommentBudget.Quotas(1,0,0))
            repo.importPage(page("v",listOf(row("one",translation="译文"))))
            assertEquals("ready",repo.readVideo(account,"v")!!.phase)
            assertEquals(1,repo.progressCounts(account).getValue("ready"))
            val db=CommentRepository::class.java.getDeclaredField("db").apply{isAccessible=true}.get(repo) as android.database.sqlite.SQLiteDatabase
            db.execSQL("UPDATE comments SET payload='not-json' WHERE account=?",arrayOf(account))
            assertEquals("Diagnostics must only read counters, not parse every archived body",1,repo.progressCounts(account).getValue("originals"))
        }
    }
    @Test fun schedulerHasNoWakeWhenIdlePausedOrUnauthorizedButKeepsRetryDeadline() {
        repository().use{repo->register(repo,"v");repo.importPage(page("v",listOf(row("one"))))
            assertNull(repo.nextDelay(account,CommentRepository.Lane.TRANSLATION,1000,false))
            val work=repo.claim(account,CommentRepository.Lane.TRANSLATION,1000,true)!!
            assertNull(repo.nextDelay(account,CommentRepository.Lane.TRANSLATION,1000,true))
            repo.fail(work,IllegalStateException("retry"),1000)
            assertEquals(5000L,repo.nextDelay(account,CommentRepository.Lane.TRANSLATION,1000,true))
            repo.submitCommand(account,"v",CommentRepository.Command.Pause)
            assertNull(repo.nextDelay(account,CommentRepository.Lane.TRANSLATION,1000,true))
        }
    }
    @Test fun readerMigrationOnlyTouchesItsPinnedVideo() {
        val one="read-one-${UUID.randomUUID()}";val two="read-two-${UUID.randomUUID()}"
        for(v in listOf(one,two)){OfflineCommentArchive.save(app,page(v,listOf(row("one",translation="译文"))),0);OfflineCommentArchive.saveModel(app,account,v,"model")}
        repository().use{repo->repo.migrateLegacy(account,"zh-hans",onlyVideo=one){OfflineCommentBudget.Quotas(200,1000,15)}
            assertNotNull(repo.readVideo(account,one));assertNull(repo.readVideo(account,two))
            repo.migrateLegacy(account,"zh-hans"){OfflineCommentBudget.Quotas(200,1000,15)}
            assertNotNull(repo.readVideo(account,two))
        }
    }

    @Test fun existingAccountCachesAreReadableFromSharedDeviceScopeWithoutDeletingOldPartitions() {
        repository().use{repo->
            register(repo,"shared");repo.importPage(page("shared",listOf(row("c",translation="已保存"))))
            val scope=HostOfflineComments.LOCAL_SCOPE
            repo.migrateLocalScope(scope)
            assertEquals("已保存",repo.readVideo(scope,"shared")!!.snapshot.rows.single().translation)
            assertEquals("已保存",repo.readVideo(account,"shared")!!.snapshot.rows.single().translation)
            repo.migrateLocalScope(scope)
            assertEquals(1,repo.readVideo(scope,"shared")!!.snapshot.rows.size)
        }
    }
    @Test fun legacyPagesAndMediaAreReusedInDeviceScopeRegardlessOfLoginPartition() {
        val video="shared-legacy-${UUID.randomUUID()}"
        val media=OfflineCommentData.Media("e".repeat(64),OfflineCommentData.MediaKind.IMAGE,listOf("https://p16.tiktokcdn.com/test-local-only.png"))
        val bytes=java.io.ByteArrayOutputStream().also{android.graphics.Bitmap.createBitmap(2,2,android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
        OfflineCommentMediaStore.store(app,account,video,media,bytes,{true},{})
        OfflineCommentArchive.save(app,page(video,listOf(row("c",translation="旧译文").copy(media=listOf(media)))),0)
        OfflineCommentArchive.saveModel(app,account,video,"model")
        repository().use{repo->val scope=HostOfflineComments.LOCAL_SCOPE
            repo.migrateLegacy(scope,"zh-hans",onlyVideo=video){OfflineCommentBudget.Quotas(200,1000,15)}
            val state=repo.readVideo(scope,video)!!
            assertEquals("旧译文",state.snapshot.rows.single().translation)
            assertEquals(1,state.mediaReady)
            assertEquals(OfflineCommentMediaStore.file(app,account,video,media),OfflineCommentMediaStore.file(app,scope,video,media))
        }
    }

    @Test fun transportLeaseChecksNeverWaitForTheRepositoryMonitorAndPauseInvalidatesThem() {
        repository().use{repo->register(repo,"v");repo.importPage(page("v",listOf(row("one"))))
            val work=repo.claim(account,CommentRepository.Lane.TRANSLATION,0,true)!!
            val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
            val holder=Thread{ synchronized(repo){entered.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS)} };holder.start();assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
            try{val checked=java.util.concurrent.CountDownLatch(1);val thread=Thread{assertTrue(repo.leaseAlive(work));checked.countDown()};thread.start()
                assertTrue("Transport callback must not wait for database monitor",checked.await(300,java.util.concurrent.TimeUnit.MILLISECONDS))
            }finally{release.countDown();holder.join(2000)}
            repo.submitCommand(account,"v",CommentRepository.Command.Pause);assertFalse(repo.leaseAlive(work));assertFalse(repo.commitTranslation(work,"late"))
        }
    }
    @Test fun localeFreeDigestsPreserveEveryExistingKeyByte() {
        val bytes=ByteArray(256){it.toByte()}
        val canonical=bytes.joinToString(""){String.format(java.util.Locale.US,"%02x",it.toInt() and 255)}
        assertEquals(canonical,DigestHex.encode(bytes))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",CommentRepository.hash(""))
    }

    @Test fun realNativeTwentyItemPagesContinueUntilTheConfiguredMainCommentLimit() {
        repository().use{repo->val scope=HostOfflineComments.LOCAL_SCOPE;repo.register(scope,"fresh","model",OfflineCommentBudget.Quotas(60,0,0),"zh-hans")
            repeat(3){i->val work=repo.claim(scope,CommentRepository.Lane.SOURCE,0,true)!!
                val rows=(i*20 until (i+1)*20).map{row("c$it",translation="译文")}
                val response=HostOfflineComments.capturedSnapshot(scope,"fresh",1,rows,2500,true,(i+1)*20L,false)
                assertTrue(repo.commitPage(work,response))
            }
            assertEquals(60,repo.readVideo(scope,"fresh")!!.snapshot.rows.size)
            assertEquals("ready",repo.readVideo(scope,"fresh")!!.phase)
        }
    }

    @Test fun serverExhaustionBelowConfiguredCountNeverClaimsTheRequestedCountWasCompleted() {
        repository().use{repo->register(repo,"short",OfflineCommentBudget.Quotas(200,0,0))
            val w=repo.claim(account,CommentRepository.Lane.SOURCE,0,true)!!
            repo.commitPage(w,page("short",listOf(row("one",translation="译文")),false,null))
            assertEquals(1,repo.readVideo(account,"short")!!.snapshot.rows.size)
            assertEquals("partial",repo.readVideo(account,"short")!!.phase)
            assertEquals(0,repo.progressCounts(account).getValue("ready"))
        }
    }

    @Test fun embeddedReplyShortageOrPartialMetadataDoesNotStopTheMainCursorBeforeConfiguredCount() {
        repository().use{repo->register(repo,"threads",OfflineCommentBudget.Quotas(60,0,0))
            repeat(3){i->val w=repo.claim(account,CommentRepository.Lane.SOURCE,0,true)!!
                val rows=(i*20 until (i+1)*20).map{row("c$it",translation="译文",replies=35)}
                // Older persisted response flags may include missing embedded replies; continue valid main cursors.
                val native=HostOfflineComments.capturedSnapshot(account,"threads",1,rows,1200,true,(i+1)*20L,true)
                assertTrue(repo.commitPage(w,native))
            }
            assertEquals(60,repo.readVideo(account,"threads")!!.snapshot.rows.size)
            assertEquals("ready",repo.readVideo(account,"threads")!!.phase)
        }
    }

    @Test fun concurrentWorkersClaimDistinctVideosInTicketOrderAndNeverShareAnItemLease() {
        repository().use{repo->repeat(8){i->register(repo,"p$i");repo.importPage(page("p$i",listOf(row("a"),row("b"))))}
            val first=(0 until 4).map{repo.claim(account,CommentRepository.Lane.TRANSLATION,0,true)!!}
            assertEquals(listOf("p0","p1","p2","p3"),first.map{it.video})
            assertEquals(4,first.map{it.lease}.toSet().size)
            val rest=(0 until 4).map{repo.claim(account,CommentRepository.Lane.TRANSLATION,0,true)!!}
            assertEquals(listOf("p4","p5","p6","p7"),rest.map{it.video})
            assertNull(repo.claim(account,CommentRepository.Lane.TRANSLATION,0,true))
            assertTrue(repo.commitTranslation(first.first(),"译文"))
            val next=repo.claim(account,CommentRepository.Lane.TRANSLATION,0,true)!!
            assertEquals("p0",next.video);assertNotEquals(first.first().item,next.item)
        }
    }

    @Test fun incrementalTextCompletionCountsOnceAndSurvivesReopenWithoutReconcilingOtherFacts() {
        val f=file()
        CommentRepository(app,f).use { repo ->
            register(repo,"fast",OfflineCommentBudget.Quotas(2,0,0))
            repo.importPage(page("fast",listOf(row("a"),row("b"))))
            val first=repo.claim(account,CommentRepository.Lane.TRANSLATION,0,true)!!
            assertEquals("source a",first.row!!.source)
            assertTrue(repo.commitTranslation(first,"甲"));assertFalse(repo.commitTranslation(first,"duplicate"))
            assertEquals(0,repo.progressCounts(account).getValue("ready"))
            val second=repo.claim(account,CommentRepository.Lane.TRANSLATION,0,true)!!
            assertTrue(repo.commitTranslation(second,"乙"))
            assertEquals(1,repo.progressCounts(account).getValue("ready"))
            assertNull(repo.claim(account,CommentRepository.Lane.TRANSLATION,0,true))
        }
        CommentRepository(app,f).use { repo ->
            assertEquals(1,repo.progressCounts(account).getValue("ready"))
            assertEquals(2,repo.readVideo(account,"fast")!!.translated)
            assertEquals(listOf("甲","乙"),repo.readVideo(account,"fast")!!.snapshot.rows.map{it.translation})
        }
    }

    @Test fun incrementalTextCompletionPreservesOtherLanesAndSourceChangesReconcileItsCounters() {
        repository().use { repo ->
            register(repo,"fast",OfflineCommentBudget.Quotas(1,1,1))
            repo.importPage(page("fast",listOf(row("a",replies=1)),true,20))
            val source=repo.claim(account,CommentRepository.Lane.SOURCE,0,true)!!
            val text=repo.claim(account,CommentRepository.Lane.TRANSLATION,0,true)!!
            assertTrue(repo.commitTranslation(text,"甲"));assertTrue(repo.active(source))
            assertTrue(repo.leaseAlive(source));assertEquals(1,repo.readVideo(account,"fast")!!.translated)
            repo.importPage(page("fast",listOf(row("a",text="updated source"))))
            assertEquals(0,repo.readVideo(account,"fast")!!.translated)
            val fresh=repo.claim(account,CommentRepository.Lane.TRANSLATION,0,true)!!
            assertEquals("updated source",fresh.row!!.source)
            assertTrue(repo.commitTranslation(fresh,"新译文"))
            assertEquals(1,repo.readVideo(account,"fast")!!.translated)
            assertEquals(0,repo.progressCounts(account).getValue("ready"))
        }
    }

}
