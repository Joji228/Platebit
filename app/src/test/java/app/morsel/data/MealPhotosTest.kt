package app.morsel.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MealPhotosTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun onlyFilesDirectlyInTheMealFolderCount() {
        val files = temp.newFolder("files")
        val meals = File(files, "meals").apply { mkdirs() }
        assertTrue(MealPhotos.isMealPhoto(File(meals, "meal_1.jpg").path, meals))

        // What a crafted backup could try: the database, other folders, or climbing out.
        assertFalse(MealPhotos.isMealPhoto(File(temp.root, "databases/morsel.db").path, meals))
        assertFalse(MealPhotos.isMealPhoto(File(meals, "../../databases/morsel.db").path, meals))
        assertFalse(MealPhotos.isMealPhoto(File(meals, "sub/meal.jpg").path, meals))
        assertFalse(MealPhotos.isMealPhoto(meals.path, meals))
        assertFalse(MealPhotos.isMealPhoto("", meals))
        assertFalse(MealPhotos.isMealPhoto(null, meals))
    }
}