/* NEST (New Scala Test)
 * Copyright 2007-2013 LAMP/EPFL
 * @author Philipp Haller
 */

package scala.tools
package partest
package nest

import utils.Properties._
import scala.tools.nsc.Properties.{propOrFalse, setProp, versionMsg}
import scala.collection.mutable
import TestKinds._
import scala.reflect.internal.util.Collections.distinctBy
import scala.util.{ Try, Success, Failure }
import scala.concurrent.duration.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.NANOSECONDS

class AbstractRunner(val config: RunnerSpec.Config, protected final val testSourcePath: String, val fileManager: FileManager) {

  // lazy because colorEnabled is overridden in SBTRunner
  // TODO: integrate NestUI into AbstractRunner
  private lazy val nestUI: NestUI = new NestUI(
    verbose = config.optVerbose,
    debug = config.optDebug || propOrFalse("partest.debug"),
    terse = config.optTerse,
    colorEnabled = colorEnabled
  )

  val javaCmdPath: String            = PartestDefaults.javaCmd
  val javacCmdPath: String           = PartestDefaults.javacCmd
  val scalacExtraArgs: Seq[String]   = Seq.empty
  val javaOpts: String               = PartestDefaults.javaOpts
  val scalacOpts: String             = PartestDefaults.scalacOpts

  protected val printSummary         = true
  protected val partestCmd           = "test/partest"
  protected val colorEnabled         = sys.props contains "partest.colors"

  private[this] var totalTests       = 0
  private[this] val passedTests      = mutable.ListBuffer[TestState]()
  private[this] val failedTests      = mutable.ListBuffer[TestState]()

  private[this] var summarizing      = false
  private[this] var elapsedMillis    = 0L
  private[this] var expectedFailures = 0
  private[this] var onlyIndividualTests = false


  import nestUI._
  import nestUI.color._

  private[this] def comment(s: String) = echo(magenta("# " + s))

  private[this] def levyJudgment() = {
    if (totalTests == 0) echoMixed("No tests to run.")
    else if (elapsedMillis == 0) echoMixed("Test Run ABORTED")
    else if (isSuccess) echoPassed("Test Run PASSED")
    else echoFailed("Test Run FAILED")
  }

  private[this] def passFailString(passed: Int, failed: Int, skipped: Int): String = {
    val total = passed + failed + skipped
    val isSuccess = failed == 0
    def p0 = s"$passed/$total"
    def p  = ( if (isSuccess) bold(green(p0)) else p0 ) + " passed"
    def f  = if (failed == 0) "" else bold(red("" + failed)) + " failed"
    def s  = if (skipped == 0) "" else bold(yellow("" + skipped)) + " skipped"

    oempty(p, f, s) mkString ", "
  }

  private[this] def isSuccess = failedTests.size == expectedFailures

  def issueSummaryReport(): Unit = {
    // Don't run twice
    if (!summarizing) {
      summarizing = true

      val passed0   = passedTests.toList
      val failed0   = failedTests.toList
      val passed    = passed0.size
      val failed    = failed0.size
      val skipped   = totalTests - (passed + failed)
      val passFail  = passFailString(passed, failed, skipped)
      val elapsed   = if (elapsedMillis > 0) " (elapsed time: " + elapsedString(elapsedMillis) + ")" else ""
      val message   = passFail + elapsed

      if (failed0.nonEmpty) {
        if (nestUI.verbose) {
          echo(bold(cyan("##### Transcripts from failed tests #####\n")))
          failed0 foreach { state =>
            comment(partestCmd + " " + state.testFile)
            echo(state.transcriptString + "\n")
          }
        }

        def files_s = failed0.map(_.testFile).mkString(""" \""" + "\n  ")
        echo("# Failed test paths (this command will update checkfiles)")
        echo(partestCmd + " --update-check \\\n  " + files_s + "\n")
      }

      if (printSummary) {
        echo(message)
        levyJudgment()
      }
    }
  }

  /** Run the tests and return the success status */
  def run(): Boolean = {
    setUncaughtHandler

    // TODO: make this immutable
    PathSettings.testSourcePath = testSourcePath

    if (config.optVersion) echo(versionMsg)
    else if (config.optHelp) nestUI.usage()
    else {
      val (individualTests, invalid) = config.parsed.residualArgs map (p => Path(p)) partition denotesTestPath
      if (invalid.nonEmpty) {
        if (nestUI.verbose)
          invalid foreach (p => echoWarning(s"Discarding invalid test path " + p))
        else if (!nestUI.terse)
          echoWarning(s"Discarding ${invalid.size} invalid test paths")
      }

      config.optTimeout foreach (x => setProp("partest.timeout", x))

      if (!nestUI.terse)
        nestUI.echo(banner)

      val grepExpr = config.optGrep getOrElse ""

      // If --grep is given we suck in every file it matches.
      // TODO: intersect results of grep with specified kinds, if any
      val greppedTests = if (grepExpr == "") Nil else {
        val paths = grepFor(grepExpr)
        if (paths.isEmpty)
          echoWarning(s"grep string '$grepExpr' matched no tests.\n")

        paths.sortBy(_.toString)
      }

      val isRerun = config.optFailed
      val rerunTests = if (isRerun) TestKinds.failedTests else Nil
      def miscTests = individualTests ++ greppedTests ++ rerunTests

      val givenKinds = standardKinds filter config.parsed.isSet
      val kinds = (
        if (givenKinds.nonEmpty) givenKinds
        else if (miscTests.isEmpty && invalid.isEmpty) standardKinds // If no kinds, --grep, or individual tests were given, assume --all, unless there were invalid files specified
        else Nil
      )
      val kindsTests = kinds flatMap testsFor

      def testContributors = {
        List(
          if (rerunTests.isEmpty) "" else "previously failed tests",
          if (kindsTests.isEmpty) "" else s"${kinds.size} named test categories",
          if (greppedTests.isEmpty) "" else s"${greppedTests.size} tests matching '$grepExpr'",
          if (individualTests.isEmpty) "" else "specified tests"
        ) filterNot (_ == "") mkString ", "
      }

      val allTests: Array[Path] = distinctBy(miscTests ++ kindsTests)(_.toCanonical) sortBy (_.toString) toArray
      val grouped = (allTests groupBy kindOf).toArray sortBy (x => standardKinds indexOf x._1)

      onlyIndividualTests = individualTests.nonEmpty && rerunTests.isEmpty && kindsTests.isEmpty && greppedTests.isEmpty
      totalTests = allTests.size
      expectedFailures = propOrNone("partest.errors") match {
        case Some(num)  => num.toInt
        case _          => 0
      }
      val expectedFailureMessage = if (expectedFailures == 0) "" else s" (expecting $expectedFailures to fail)"
      echo(s"Selected $totalTests tests drawn from $testContributors$expectedFailureMessage\n")
      if (config.optNoExec) echoMixed("Under --no-exec, tests will be compiled but not run! Runnable tests will be marked skipped!")

      val (_, millis) = timed {
        for ((kind, paths) <- grouped) {
          val num = paths.size
          val ss = if (num == 1) "" else "s"
          comment(s"starting $num test$ss in $kind")
          val results = runTestsForFiles(paths map (_.jfile.getAbsoluteFile), kind)
          val (passed, failed) = results partition (_.isOk)

          passedTests ++= passed
          failedTests ++= failed
          if (failed.nonEmpty) {
            comment(passFailString(passed.size, failed.size, 0) + " in " + kind)
          }
          echo("")
        }
      }
      this.elapsedMillis = millis
      issueSummaryReport()
    }
    isSuccess
  }

  def banner = {
    val baseDir = fileManager.compilerUnderTest.parent.toString
    def relativize(path: String) = path.replace(baseDir, s"$$baseDir").replace(PathSettings.srcDir.toString, "$sourceDir")
    val vmBin  = javaHome + fileSeparator + "bin"
    val vmName = "%s (build %s, %s)".format(javaVmName, javaVmVersion, javaVmInfo)

    s"""|Partest version:     ${Properties.versionNumberString}
        |Compiler under test: ${relativize(fileManager.compilerUnderTest.getAbsolutePath)}
        |Scala version is:    $versionMsg
        |Scalac options are:  ${(scalacExtraArgs ++ scalacOpts.split(' ')).mkString(" ")}
        |Compilation Path:    ${relativize(FileManager.joinPaths(fileManager.testClassPath))}
        |Java binaries in:    $vmBin
        |Java runtime is:     $vmName
        |Java options are:    $javaOpts
        |baseDir:             $baseDir
        |sourceDir:           ${PathSettings.srcDir}
    """.stripMargin
    // |Available processors:       ${Runtime.getRuntime().availableProcessors()}
    // |Java Classpath:             ${sys.props("java.class.path")}
  }

  def onFinishTest(testFile: File, result: TestState, durationMs: Long): TestState = {
    result
  }

  def runTest(testFile: File): TestState = {
    val start = System.nanoTime()
    val runner = new Runner(testFile, this, nestUI)
    var stopwatchDuration: Option[Long] = None

    // when option "--failed" is provided execute test only if log
    // is present (which means it failed before)
    val state =
    if (config.optFailed && !runner.logFile.canRead)
      runner.genPass()
    else {
      val (state, durationMs) =
        try runner.run()
        catch {
          case t: Throwable => throw new RuntimeException(s"Error running $testFile", t)
        }
      stopwatchDuration = Some(durationMs)
      nestUI.reportTest(state, runner, durationMs, diffOnFail = config.optShowDiff || onlyIndividualTests , logOnFail = config.optShowLog || onlyIndividualTests)
      runner.cleanup()
      state
    }
    val end = System.nanoTime()
    val durationMs = stopwatchDuration.getOrElse(TimeUnit.NANOSECONDS.toMillis(end - start))
    onFinishTest(testFile, state, durationMs)
  }

  def runTestsForFiles(kindFiles: Array[File], kind: String): Array[TestState] = {
    nestUI.resetTestNumber(kindFiles.size)

    val pool              = Executors newFixedThreadPool PartestDefaults.numThreads
    val futures           = kindFiles map (f => pool submit callable(runTest(f.getAbsoluteFile)))

    pool.shutdown()
    Try (pool.awaitTermination(PartestDefaults.waitTime) {
      throw TimeoutException(PartestDefaults.waitTime)
    }) match {
      case Success(_) => futures map (_.get)
      case Failure(e) =>
        e match {
          case TimeoutException(d)      =>
            nestUI.warning("Thread pool timeout elapsed before all tests were complete!")
          case ie: InterruptedException =>
            nestUI.warning("Thread pool was interrupted")
            ie.printStackTrace()
        }
        pool.shutdownNow()     // little point in continuing
      // try to get as many completions as possible, in case someone cares
      val results = for (f <- futures) yield {
        try {
          Some(f.get(0, NANOSECONDS))
        } catch {
          case _: Throwable => None
        }
      }
        results.flatten
    }
  }
}
