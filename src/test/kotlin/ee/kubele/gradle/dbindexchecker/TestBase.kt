package ee.kubele.gradle.dbindexchecker

import java.io.File
import kotlin.io.path.createTempDirectory

abstract class TestBase {
	protected fun createTempDir(prefix: String = "test"): File =
		createTempDirectory(prefix).toFile()

	protected fun createTempFile(prefix: String = "test", suffix: String = ".kt"): File =
		File.createTempFile(prefix, suffix)
}
