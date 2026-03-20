package ee.kubele.gradle.dbindexchecker

/**
 * Suppresses db-index-checker warnings for the annotated repository method.
 *
 * Usage:
 *   @SuppressIndexCheck                          // suppress all warnings for this method
 *   @SuppressIndexCheck("column1", "column2")   // suppress only specific columns
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class SuppressIndexCheck(vararg val columns: String)
