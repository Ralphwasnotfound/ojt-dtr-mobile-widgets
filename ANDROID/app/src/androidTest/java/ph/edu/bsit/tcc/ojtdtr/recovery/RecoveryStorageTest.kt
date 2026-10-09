package ph.edu.bsit.tcc.ojtdtr.recovery

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.Assert.*

/** Real Samsung Keystore/filesystem; isolated synthetic fixtures, no application data clearing. */
class RecoveryStorageTest {
    private lateinit var context:Context
    private lateinit var root:File
    private lateinit var alias:String
    private val uid="00000000-0000-4000-8000-000000000001"
    private val req="00000000-0000-4000-8000-000000000002"
    @Before fun setup(){
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val suffix=UUID.randomUUID().toString()
        root=File(app.noBackupFilesDir,"recovery-test-$suffix").apply{mkdirs()}
        context=object:ContextWrapper(app){override fun getNoBackupFilesDir()=root}
        alias="dtr.recovery.test.$suffix"
    }
    @After fun teardown(){root.deleteRecursively();KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(alias)}}
    private fun store()=EncryptedJournalStorage(context,alias)
    private fun disk()=File(root,"native-recovery/attempt.enc")
    private fun rejects(call:()->Unit){try{call();fail("Must fail closed")}catch(_:RecoveryStorageFailure){}}
    @Test fun encryptedJournalReopensWithNonExportableKey(){
        val j=RecoveryJournal(store());val r=j.begin(uid,req,"time_in"){true}
        val ciphertext=String(disk().readBytes(),Charsets.ISO_8859_1)
        assertFalse(ciphertext.contains(uid));assertFalse(ciphertext.contains(req));assertFalse(ciphertext.contains("PrepareIntent"))
        assertEquals(r,RecoveryJournal(store()).load())
        assertNull(KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.getKey(alias,null).encoded)
    }
    @Test fun gcmTamperRetainsEvidenceAndBlocksAttempt(){
        RecoveryJournal(store()).begin(uid,req,"time_in"){true}
        val bytes=disk().readBytes();bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();disk().writeBytes(bytes)
        rejects{RecoveryJournal(store()).load()};rejects{RecoveryJournal(store()).begin(uid,req,"time_in"){true}};assertTrue(disk().exists())
    }
    @Test fun atomicFileBackupRollbackReopensPreviousWholeRecord(){
        val r=RecoveryJournal(store()).begin(uid,req,"time_in"){true}
        File(disk().path+".bak").writeBytes(disk().readBytes());disk().writeBytes(byteArrayOf(1,2,3))
        assertEquals(r,RecoveryJournal(store()).load())
    }
    @Test fun keyLossHasNoPlaintextFallbackAndRetainsCiphertext(){
        RecoveryJournal(store()).begin(uid,req,"time_in"){true}
        KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(alias)}
        rejects{RecoveryJournal(store()).load()};assertTrue(disk().exists())
    }
    @Test fun oversizedCiphertextFailsClosed(){
        RecoveryJournal(store()).begin(uid,req,"time_in"){true};disk().writeBytes(ByteArray(100_000))
        rejects{RecoveryJournal(store()).load()};assertTrue(disk().exists())
    }
    @Test fun confirmedAtomicReplacementAndExactCleanup(){
        val j=RecoveryJournal(store());val r=j.begin(uid,req,"time_in"){true}
        val p=j.advance(r,RecoveryPhase.Prepared,{true},req,uid)
        val c=j.advance(p,RecoveryPhase.Confirmed,{true});assertEquals(c,RecoveryJournal(store()).load())
        j.clearConfirmed(c){true};assertNull(RecoveryJournal(store()).load())
    }
    @Test fun atomicRenameFailureCannotReportSuccessfulJournalReplacement() {
        disk().mkdirs()
        val sentinel=File(disk(),"unrelated-sentinel").apply{writeText("keep")}
        rejects { store().replace("synthetic-journal".toByteArray()) }
        assertEquals("keep",sentinel.readText())
    }

    @Test fun interruptedNewCiphertextRestoresPreviousWholeJournal(){
        val j=RecoveryJournal(store());val original=j.begin(uid,req,"time_in"){true}
        java.io.File(disk().path+".new").writeBytes(byteArrayOf(1,2,3))
        assertEquals(original,RecoveryJournal(store()).load())
    }
    @Test fun repeatedEncryptionUsesDifferentNonceAndPrivateFile(){
        val plaintext="synthetic-journal".toByteArray();val s=store();s.replace(plaintext);val first=disk().readBytes()
        s.replace(plaintext);val second=disk().readBytes()
        assertFalse(first.copyOfRange(1,13).contentEquals(second.copyOfRange(1,13)))
        assertArrayEquals(plaintext,s.read())
        val mode=android.system.Os.stat(disk().path).st_mode
        assertEquals(0,mode and (android.system.OsConstants.S_IROTH or android.system.OsConstants.S_IWOTH))
    }

}
