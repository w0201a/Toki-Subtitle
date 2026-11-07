package io.github.meiyongai.toki.subtitle.hook

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
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
class CommentMigrationIntegrityTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val account="a".repeat(64)
    private val scope=HostOfflineComments.LOCAL_SCOPE
    private val quotas=OfflineCommentBudget.Quotas(1,0,0)
    private fun database()=File(System.getProperty("java.io.tmpdir"),"toki-v3-migration/${UUID.randomUUID()}.sqlite")
    private fun legacy(video:String,owner:String=account):File {
        val row=OfflineCommentData.Row("one","a","h","u","original","旧译文","zh-hans",0,0,mediaKnown=true)
        OfflineCommentArchive.save(app,OfflineCommentData.Snapshot(owner,video,0,listOf(row),1,false,false),0,verifiedReplyTotals=true)
        OfflineCommentArchive.saveModel(app,owner,video,"model")
        val folder=File(app.noBackupFilesDir,"toki-offline-comments-v2/${CommentSnapshotStore.key(owner,video)}")
        val index=File(folder,"index.json");val json=JSONObject(index.readText())
        // Index a missing synthetic page. No existing fixture or user file is removed/corrupted.
        json.getJSONObject("pages").put(CommentRepository.hash("missing:$owner:$video"),JSONObject().put("cursor",20).put("root","").put("replyTotalsVersion",1))
        index.writeText(json.toString())
        return folder
    }
    private fun assertPartial(repo:CommentRepository,video:String,errors:Int=1,owner:String=scope) {
        val state=repo.readVideo(owner,video)!!
        assertEquals(errors,state.migrationErrors)
        assertEquals("partial",state.phase)
        assertTrue(state.snapshot.truncated)
        assertTrue(state.reason.contains("migration_errors:$errors"))
        assertEquals("旧译文",state.snapshot.rows.single().translation)
        assertEquals(1,repo.progressCounts(owner).getValue("partial"))
        assertEquals(0,repo.progressCounts(owner).getValue("ready"))
    }
    @Test fun deviceScopeReportsBadLegacyPageAcrossReconcileAndReopenWithoutChangingFiles() {
        val video="bad-local-${UUID.randomUUID()}";val folder=legacy(video);val db=database()
        val before=folder.listFiles()!!.associate{it.name to it.readBytes().toList()}
        CommentRepository(app,db).use { repo ->
            repo.migrateLegacy(scope,"zh-hans",video){quotas};assertPartial(repo,video)
            repo.register(scope,video,"",quotas,"zh-hans");assertPartial(repo,video)
        }
        CommentRepository(app,db).use { repo ->
            repo.migrateLegacy(scope,"zh-hans",video){quotas};assertPartial(repo,video)
        }
        assertEquals(before,folder.listFiles()!!.associate{it.name to it.readBytes().toList()})
    }
    @Test fun errorsFromTwoLegacyPartitionsAreAggregatedOnlyForTheirVideo() {
        val video="two-partitions-${UUID.randomUUID()}";legacy(video);legacy(video,"b".repeat(64))
        CommentRepository(app,database()).use { repo ->
            repo.migrateLegacy(scope,"zh-hans",video){quotas};assertPartial(repo,video,2)
            repo.register(scope,"unrelated","",quotas,"zh-hans")
            assertEquals(0,repo.readVideo(scope,"unrelated")!!.migrationErrors)
        }
    }
    @Test fun unchangedOldCheckpointIsBoundBeforeFastPathAndDoesNotHideFailure() {
        val video="old-checkpoint-${UUID.randomUUID()}";val folder=legacy(video);val db=database()
        CommentRepository(app,db).use { repo ->
            repo.register(scope,video,"model",quotas,"zh-hans")
            val good=folder.listFiles()!!.first{it.extension=="bin"}
            repo.importPage(CommentSnapshotCodec.decode(good.readBytes()).copy(account=scope))
        }
        SQLiteDatabase.openDatabase(db.path,null,SQLiteDatabase.OPEN_READWRITE).use { sql ->
            val index=File(folder,"index.json");val stamp="${index.lastModified()}:${index.length()}"
            sql.execSQL("INSERT INTO migration VALUES(?,?,1)",arrayOf(folder.name+":"+scope,stamp))
        }
        CommentRepository(app,db).use { repo ->
            repo.migrateLegacy(scope,"zh-hans",video){quotas};assertPartial(repo,video)
            repo.register(scope,video,"",quotas,"zh-hans");assertPartial(repo,video)
        }
    }
    @Test fun sqliteCopyCarriesFailureAndDirectLocalImportDoesNotDoubleCountIt() {
        val video="copy-error-${UUID.randomUUID()}";legacy(video);val db=database()
        CommentRepository(app,db).use { repo ->
            repo.migrateLegacy(account,"zh-hans",video){quotas};assertPartial(repo,video,owner=account)
            repo.migrateLocalScope(scope,video);assertPartial(repo,video)
            repo.migrateLegacy(scope,"zh-hans",video){quotas};assertPartial(repo,video)
            repo.migrateLocalScope(scope,video);assertPartial(repo,video)
        }
        CommentRepository(app,db).use { repo ->
            repo.migrateLocalScope(scope,video);assertPartial(repo,video)
            assertPartial(repo,video,owner=account)
        }
    }
    @Test fun versionFourCheckpointUpgradeRepairsCountersWithoutReimportingOrRewritingOldRows() {
        val video="upgrade-${UUID.randomUUID()}";val folder=legacy(video);val db=database()
        CommentRepository(app,db).use { repo ->
            repo.register(account,video,"model",quotas,"zh-hans")
            val good=folder.listFiles()!!.first{it.extension=="bin"}
            repo.importPage(CommentSnapshotCodec.decode(good.readBytes()))
            assertEquals("ready",repo.readVideo(account,video)!!.phase)
        }
        SQLiteDatabase.openDatabase(db.path,null,SQLiteDatabase.OPEN_READWRITE).use { sql ->
            sql.version=4
            sql.execSQL("INSERT INTO migration VALUES(?,'old-checkpoint',1)",arrayOf(folder.name))
        }
        CommentRepository(app,db).use { repo ->
            assertPartial(repo,video,owner=account)
            repo.register(account,video,"",quotas,"zh-hans");assertPartial(repo,video,owner=account)
            repo.migrateLocalScope(scope,video);assertPartial(repo,video)
        }
        SQLiteDatabase.openDatabase(db.path,null,SQLiteDatabase.OPEN_READONLY).use { sql ->
            assertEquals(5,sql.version)
            sql.rawQuery("SELECT stamp,errors FROM migration WHERE folder=?",arrayOf(folder.name)).use { c ->
                assertTrue(c.moveToFirst());assertEquals("old-checkpoint",c.getString(0));assertEquals(1,c.getInt(1))
            }
        }
    }
    @Test fun repairingOnlyTheMissingSyntheticPageClearsItsFailureWithoutDeletingHistory() {
        val video="repair-page-${UUID.randomUUID()}";val folder=legacy(video);val db=database()
        CommentRepository(app,db).use { repo ->
            repo.migrateLegacy(scope,"zh-hans",video){quotas};assertPartial(repo,video)
            val good=folder.listFiles()!!.first{it.extension=="bin"}
            File(folder,CommentRepository.hash("missing:$account:$video")+".bin").writeBytes(good.readBytes())
            val index=File(folder,"index.json");index.writeText(JSONObject(index.readText()).put("repairRevision",UUID.randomUUID().toString()).toString())
            repo.migrateLegacy(scope,"zh-hans",video){quotas}
            assertEquals(0,repo.readVideo(scope,video)!!.migrationErrors)
            assertEquals("ready",repo.readVideo(scope,video)!!.phase)
            assertEquals(1,repo.progressCounts(scope).getValue("ready"))
            assertEquals("旧译文",repo.readVideo(scope,video)!!.snapshot.rows.single().translation)
        }
        CommentRepository(app,db).use { repo ->
            assertEquals(0,repo.readVideo(scope,video)!!.migrationErrors)
            assertEquals("ready",repo.readVideo(scope,video)!!.phase)
        }
    }
}
