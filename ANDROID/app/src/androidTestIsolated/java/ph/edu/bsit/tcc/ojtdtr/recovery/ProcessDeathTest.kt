package ph.edu.bsit.tcc.ojtdtr.recovery

import android.app.Application
import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** No network adapter, credentials, image, location, or widget reference. Separate fixture UID only. */
class ProcessDeathTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val case get() = InstrumentationRegistry.getArguments().getString("case")!!
    private val run get() = InstrumentationRegistry.getArguments().getString("run")!!.also { check(it.matches(Regex("[0-9a-f]{32}"))) }
    private val owner = "00000000-0000-4000-8000-000000000001"
    private val request = "00000000-0000-4000-8000-000000000002"
    private val upload = "00000000-0000-4000-8000-000000000003"
    private val session = "00000000-0000-4000-8000-000000000004"
    private val alias get() = "isolated.processdeath.$run.$case"
    private val directory get() = "processdeath-$run-$case"
    private fun store() = EncryptedJournalStorage(context, alias, directory)
    private fun identity() {
        check(context.packageName == "ph.edu.bsit.tcc.ojtdtr.recoveryfixture")
        check(context.applicationContext.javaClass == Application::class.java)
        for(permission in listOf("android.permission.INTERNET", "android.permission.CAMERA",
            "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION")) {
            check(context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_DENIED)
        }
        val info=context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_ACTIVITIES or android.content.pm.PackageManager.GET_RECEIVERS or
            android.content.pm.PackageManager.GET_SERVICES or android.content.pm.PackageManager.GET_PROVIDERS)
        check(info.activities?.size == 1 && info.activities!!.single().name == ProcessDeathActivity::class.java.name)
        check(!info.activities!!.single().exported)
        check(info.receivers.isNullOrEmpty() && info.services.isNullOrEmpty() && info.providers.isNullOrEmpty())
        check(case in setOf("committed", "before", "migration", "blocked", "mismatch", "corrupt", "keyloss"))
    }
    private fun expected() = RecoveryRecord(schema=2, revision=2, owner=owner, request=request,
        action="time_in", phase=RecoveryPhase.Prepared, upload=upload, session=session)
    private fun old() = RecoveryRecord(owner=owner, request=request, action="time_in", phase=RecoveryPhase.PrepareIntent)
    private fun marker(name: String, value: String) = File(context.filesDir, "$run-$case-$name").writeText(value)
    private fun blockedRecovery(journal: RecoveryJournal, uid: String, entered: CountDownLatch): Pair<AttendanceRecovery, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val recovery = AttendanceRecovery(scope, journal, deadline=600_000)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            recovery.bind(uid, {true}, object: RecoveryReader {
                override suspend fun receipt(record: RecoveryRecord): String = error("No receipt permitted")
                override suspend fun observation(record: RecoveryRecord): RecoveryObservation? {
                    entered.countDown()
                    awaitCancellation() // Deliberately blocked fake read, zero HTTP/mutation calls.
                }
            })
        }
        return recovery to scope
    }
    @Test fun seedAndAwaitKill() {
        identity()
        val activity = ActivityScenario.launch(ProcessDeathActivity::class.java)
        val storage=store(); val journal=RecoveryJournal(storage)
        assertNull(journal.load()) // Each case is new; do not delete or reset any existing evidence.
        if(case=="before") {
            val entered=CountDownLatch(1)
            val paused=RecoveryJournal(object:JournalStorage {
                override fun read()=storage.read()
                override fun remove()=error("No removal")
                override fun replace(bytes:ByteArray) { entered.countDown(); CountDownLatch(1).await() }
            })
            Thread { paused.begin(owner,request,"time_in"){true} }.start()
            assertTrue(entered.await(10,TimeUnit.SECONDS))
            assertNull(RecoveryJournal(store()).load())
        } else if(case=="migration") {
            val bytes=kotlinx.serialization.json.Json.encodeToString(old()).toByteArray()
            try { storage.replace(bytes) } finally { bytes.fill(0) }
            assertEquals(old(),journal.load())
            val entered=CountDownLatch(1)
            val blocked=RecoveryJournal(object:JournalStorage {
                override fun read()=storage.read()
                override fun remove()=error("No removal")
                override fun replace(bytes:ByteArray) { entered.countDown(); CountDownLatch(1).await() }
            })
            Thread { blocked.recoverIdentity(old(),upload,session){true} }.start()
            assertTrue(entered.await(10,TimeUnit.SECONDS))
        } else if(case!="before") {
            val begun=journal.begin(owner,request,"time_in"){true}
            journal.advance(begun,RecoveryPhase.Prepared,{true},upload,session)
            assertEquals(expected(),RecoveryJournal(store()).load())
            val bytes=File(context.noBackupFilesDir,"$directory/attempt.enc").readBytes()
            assertFalse(String(bytes,Charsets.ISO_8859_1).contains(owner))
            assertNull(KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.getKey(alias,null).encoded)
            if(case=="blocked") {
                val entered=CountDownLatch(1); blockedRecovery(journal,owner,entered)
                assertTrue(entered.await(10,TimeUnit.SECONDS))
            }
            if(case=="corrupt") {
                val file=File(context.noBackupFilesDir,"$directory/attempt.enc")
                bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();file.writeBytes(bytes)
            }
            if(case=="keyloss") KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(alias)}
        }
        activity.close() // Activity exit never owns journal deletion.
        marker("ready", "$run $case ${Process.myPid()}")
        CountDownLatch(1).await() // Host SIGKILL only after durable-read assertions/marker.
    }
    @Test fun freshProcessVerifiesEvidence() {
        identity()
        val parts=File(context.filesDir,"$run-$case-ready").readText().split(" ")
        assertEquals(listOf(run,case),parts.take(2))
        val oldPid=parts[2].toInt()
        assertNotEquals(oldPid,Process.myPid())
        val activity=ActivityScenario.launch(ProcessDeathActivity::class.java)
        val disk=File(context.noBackupFilesDir,"$directory/attempt.enc")
        val ciphertextBefore=if(disk.isFile) disk.readBytes() else null
        val journal=RecoveryJournal(store())
        when(case) {
            "before" -> assertNull(journal.load())
            "migration" -> assertEquals(old(),journal.load())
            "corrupt", "keyloss" -> {
                try { journal.load();fail("Must fail closed") } catch(_:RecoveryStorageFailure) {}
                assertTrue(File(context.noBackupFilesDir,"$directory/attempt.enc").isFile)
                try { journal.begin(owner,request,"time_in"){true};fail("Must block") } catch(_:RecoveryStorageFailure) {}
            }
            else -> {
                assertEquals(expected(),journal.load())
                if(case=="mismatch") {
                    val calls=CountDownLatch(1)
                    val (recovery,scope)=blockedRecovery(journal,session,calls)
                    runBlocking { recovery.awaitIdle() }
                    assertEquals(1L,calls.count)
                    assertEquals(RecoveryStatus.Unresolved,recovery.state.value)
                    assertTrue(recovery.blocksSubmission());scope.cancel()
                }
                assertEquals(expected(),journal.load())
            }
        }
        activity.close()
        // Failure, owner mismatch and reopening must never silently replace/delete evidence.
        assertArrayEquals(ciphertextBefore, if(disk.isFile) disk.readBytes() else null)
        marker("verified", "$run $case PASS $oldPid ${Process.myPid()}")
    }
}
