package app.morsel.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CoachStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun savesLoadsAndClears() = runBlocking {
        val dir = File(folder.root, "coach")
        val store = CoachStore(dir)
        assertTrue(store.load().isEmpty())

        val chat = listOf(CoachMessage(true, "How am I doing?", 1L), CoachMessage(false, "Down **1.2 kg**.\nKeep going.", 2L))
        store.save(chat)
        assertEquals(chat, CoachStore(dir).load())
        assertFalse(File(dir, "chat.json.tmp").exists())

        store.save(chat.take(1))
        assertEquals(chat.take(1), store.load())

        store.clear()
        assertTrue(store.load().isEmpty())
        assertFalse(File(dir, "chat.json").exists())
    }

    @Test
    fun brokenFileReadsAsEmpty() = runBlocking {
        val dir = folder.newFolder("coach")
        File(dir, "chat.json").writeText("{not json")
        assertTrue(CoachStore(dir).load().isEmpty())
    }
}
