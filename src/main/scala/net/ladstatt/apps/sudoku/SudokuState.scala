package net.ladstatt.apps.sudoku

import java.util.concurrent.TimeUnit

import net.ladstatt.core.CanLog
import org.opencv.core._
import org.opencv.imgproc.Imgproc

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, Promise}
import net.ladstatt.apps.sudoku.Parameters._
import net.ladstatt.apps.sudoku.SudokuAlgos._
import net.ladstatt.opencv.OpenCV._

/**
 * the result for one frame. a frame is a image from the image stream
 */

case class ImageIOChain(working: Mat,
                        grayed: Mat,
                        blurred: Mat,
                        thresholded: Mat,
                        inverted: Mat,
                        dilated: Mat,
                        eroded: Mat)


case class FrameSuccess(solution: Mat,
                        detectedCells: Cells,
                        digitSolution: SudokuDigitSolution,
                        solutionCells: Cells)

case class SudokuState(nr: Int,
                       frame: Mat,
                       cap: Int = 8,
                       minHits: Int = 20,
                       // for every position, there is a list which depicts how often a number was found in the
                       // sudoku, where the index in the list is the number (from 0 to 9, with 0 being the "empty" cell)
                       hitCounts: Array[HitCount] = Array.fill(positions.size)(Array.fill[SCount](digitRange.size)(0)),
                       digitQuality: Array[Double] = Array.fill(digitRange.size)(Double.MaxValue),
                       digitData: Array[Option[Mat]] = Array.fill(digitRange.size)(None),
                       someResult: Option[FrameSuccess] = None) extends CanLog {


  private var someSolution: Option[SudokuDigitSolution] = None

  val corners = mkCorners(frame)

  val imageIoChain: ImageIOChain =
    Await.result(for {
      imgIo <- imageIOChain(frame)
    } yield imgIo, Duration(1400, TimeUnit.MILLISECONDS))

  val detectedCorners: MatOfPoint2f =
    Await.result(for {
      corners <- detectSudokuCorners(imageIoChain.dilated) // if (!detectedCorners.empty)
    } yield corners, Duration(1400, TimeUnit.MILLISECONDS))


  // TODO REMOVE
  private def initialize(): Unit = {
    //posFrequencies.foreach(freq => digitRange.map(freq(_) = 0))
    hitCounts.transform(_ => Array.fill[SCount](Parameters.digitRange.size)(0))
    digitData.transform(_ => None)
    digitQuality.transform(_ => Double.MaxValue)
    ()
    /*
    for (i <- range) {
      digitLibrary(i) = (None, Double.MaxValue)
    } */
  }


  /**
   * This function uses an input image and a detection method to calculate the sudoku.
   *
   * @return
   */
  def calc(): Future[SudokuState] = {

    // we have to walk two paths here: either we have detected something in the image
    // stream which resembles a sudoku, or we don't and we skip the rest of the processing
    // pipeline
    if (!detectedCorners.empty) {
      for {colorWarped <- warp(frame, detectedCorners, corners)
           detectedCells <- Future.sequence(detectCells(colorWarped, TemplateDetectionStrategy.detect))
           (digitSolution, solutionCells, annotatedSolution) <- computeSolution(colorWarped, detectedCells.toArray)
           unwarped <- warp(annotatedSolution, mkCorners(annotatedSolution), detectedCorners)
           solution <- copySrcToDestWithMask(unwarped, imageIoChain.working, unwarped) // copy solution mat to input mat
      } yield copy(someResult = Some(FrameSuccess(solution, detectedCells.toArray, digitSolution, solutionCells)))
    } else {
      Future.successful(copy())
    }

  }


  def detectSudokuCorners(preprocessed: Mat): Future[MatOfPoint2f] = {
    execFuture {
      val epsilon = 0.02

      extractCurveWithMaxArea(preprocessed, coreFindContours(preprocessed)) match {
        case None => {
          logWarn("Could not detect any curve ... ")
          new MatOfPoint2f()
        }
        case Some((maxArea, c)) => {
          val expectedMaxArea = Imgproc.contourArea(mkCorners(preprocessed)) / 30
          val approxCurve = mkApproximation(new MatOfPoint2f(c.toList: _*), epsilon)
          if (maxArea > expectedMaxArea) {
            if (has4Sides(approxCurve)) {
              val corners = mkSortedCorners(approxCurve)
              if (isSomewhatSquare(corners.toList)) {
                corners
              } else {
                logWarn(s"Detected ${approxCurve.size} shape, but it doesn't look like a sudoku!")
                new MatOfPoint2f()
              }
            } else {
              logWarn(s"Detected only ${approxCurve.size} shape, but need 1x4!")
              new MatOfPoint2f()
            }
          } else {
            logWarn(s"The detected area of interest was too small ($maxArea < $expectedMaxArea).")
            new MatOfPoint2f()
          }
        }
      }
    }

  }

  def computeSolution(canvas: Mat, detectedCells: Cells): Future[(SudokuDigitSolution, Cells, Mat)] =
    Future {
      updateDigitLibrary(detectedCells)
      countHits(detectedCells)
      val currentSolution: SudokuDigitSolution =
        if (detectedNumbers.size > minHits) {
          option(getSomeSolution)({
            val someSolution = solve(mkValueMatrix)
            someSolution match {
              case None => Array()
              case Some(s) => {
                if (isValidSolution(s)) {
                  setSomeSolution(Some(s))
                }
                s
              }
            }
          },
          s => s)
        } else mkValueIntermediateMatrix

      val solutionCells = toSolutionCells(currentSolution)
      val annotatedSolution = paintSolution(canvas, detectedCells, solutionCells)
      (currentSolution, solutionCells, annotatedSolution)
    }


  /**
   * paints the solution to the canvas.
   *
   * returns the modified canvas with the solution painted upon.
   */
  private def paintSolution(canvas: Mat, detectedCells: Cells, solution: Cells): Mat = {
    if (isValid(solution)) {
      // only copy cells which are not already known
      for ((cell, i) <- solution.zipWithIndex if (detectedCells(i).value == 0)) {
        copyTo(cell.data, canvas, mkRect(i, cell.data.size))
      }
    } else {
      detectedCells.zipWithIndex.map { case (cell, pos) => paintRect(canvas, mkRect(pos, cell.data.size), color(pos), 3)}
    }
    canvas
  }


  // TODO update colors
  private def color(pos: Pos): Scalar = {
    val vals = hitCounts(pos)
    val n = vals.max.toDouble
    val s = new Scalar(0, n * 256 / cap, 256 - n * 256 / cap)
    s
  }


  def isValid(solution: Cells): Boolean = {
    solution.foldLeft(0)((acc, s) => acc + s.value) == 405
  }

  private def solve(solutionCandidate: SudokuDigitSolution) = BruteForceSolver.solve(solutionCandidate)

  def updateDigitLibrary(detectedCells: Cells): Unit = {
    for (cell <- detectedCells if (cell.value != 0 && (digitData(cell.value).isEmpty ||
      cell.quality < digitQuality(cell.value)))) {
      digitData(cell.value) = Some(cell.data)
      digitQuality(cell.value) = cell.quality
    }
  }

  private def setSomeSolution(s: Option[SudokuDigitSolution]) = someSolution = s

  private def getSomeSolution: Option[SudokuDigitSolution] = someSolution


  private def sectorWellFormed(index: Pos, value: Int): Boolean = {
    val rowSector = sectors(row(index) / 3)
    val colSector = sectors(col(index) / 3)
    val sectorVals =
      for {r <- rowSector if (r != row(index))
           c <- colSector if (c != col(index))
           (count, num) <- hitCounts(index).zipWithIndex if (count == cap)} yield num
    !sectorVals.contains(value)
  }

  private def rowColWellFormed(i: Int, value: Int): Boolean = {
    val colVals =
      for {c <- range if (c != col(i) &&
        hitCounts(i).contains(value) &&
        hitCounts(i)(value) == cap)} yield value

    val rowVals =
      for {r <- range if (r != row(i) &&
        hitCounts(i).contains(value) &&
        hitCounts(i)(value) == cap)} yield value

    colVals.isEmpty && rowVals.isEmpty
  }

  private def posWellFormed(i: Pos, value: Int): Boolean = {
    rowColWellFormed(i, value) && sectorWellFormed(i, value)
  }

  /**
   * updates the hit database.
   *
   * @param cells
   */
  private def countHits(cells: Cells): Unit = {

    def updateFrequency(i: Pos, value: Int): Unit = {
      require(0 <= value && (value <= 9), s"$value was not in interval 0 <= x <= 9 !")
      val frequencyAtPos = hitCounts(i)
      if (frequencyAtPos.max < cap) {
        frequencyAtPos(value) = (1 + frequencyAtPos(value))
        ()
      }
    }

    // TODO replace with fold
    val result =
      for ((SCell(value, _, _), i) <- cells.zipWithIndex) yield {
        if ((value == 0) || posWellFormed(i, value)) {
          updateFrequency(i, value)
          true
        } else {
          false
        }
      }
    if (!result.forall(p => p)) initialize()
  }

  // search on all positions for potential hits (don't count the "empty"/"zero" fields
  def detectedNumbers: Iterable[SCount] = {
    val areWeThereyet0 =
      for {
        frequency <- hitCounts
      } yield {
        val filtered = frequency.drop(1).filter(_ >= cap)
        if (filtered.isEmpty) 0 else filtered.max
      }

    areWeThereyet0.filter(_ != 0)
  }

  def withCap(v: Int) = v == cap

  // TODO return Array[SNum]
  def mkValueMatrix: SudokuDigitSolution = mkVM(withCap(_))

  // TODO return Array[SNum]
  private def mkValueIntermediateMatrix: SudokuDigitSolution = mkVM(_ => true)

  /*{
    (for (pos <- positions) yield {
      pos -> {
        (for ((v, i) <- hitCounts(pos).zipWithIndex) yield i).headOption.getOrElse(0)
      }
    }).toMap
  }                                            */
  /*
  {
    val h =
      for (i <- positions) yield {
        ((for ((v, i) <- hitCounts(i).zipWithIndex if (v == cap)) yield i).headOption.getOrElse(0) + 48).toChar
      }
    (for (line <- h.sliding(9, 9)) yield line.toArray).toArray
  }
    */
  def mkVM(p: Int => Boolean): SudokuDigitSolution = {
    val h =
      for (i <- positions) yield {
        ((for ((v, i) <- hitCounts(i).zipWithIndex if (v == cap)) yield i).headOption.getOrElse(0) + 48).toChar
      }
    (for (line <- h.sliding(9, 9)) yield line.toArray).toArray
  }


  // TODO replace with Array[Int]
  private def isValidSolution(solution: SudokuDigitSolution): Boolean = {
    solution.flatten.map(_.asDigit).sum == 405
    /*
    if (!solution.isEmpty) {
      val normedSolverString = solution.replaceAll( """\n""", "").substring(0, 81)
      normedSolverString.foldLeft(0)((sum, a) => sum + a.asDigit) == 405
    } else false
    */
  }

  /**
   * Performance:
   *
   * Benchmark                                          Mode   Samples         Mean   Mean error    Units
   * n.l.a.s.SudokuBenchmark.measureToSolutionCells     avgt        10        0.009        0.000    ms/op
   *
   * @return
   */
  private def toSolutionCells(solution: SudokuDigitSolution): Cells = {
    if (solution.isEmpty) {
      logWarn("Invalid solution found.")
      Array()
    } else {
      val allCells: Cells =
        (for (pos <- positions) yield {
          val value = solution.flatten.apply(pos).asDigit

          val x: Option[SCell] =
            if (value != 0) {
              val someM = digitData(value)
              (if (someM.isEmpty) {
                digitData(value) = mkFallback(value, digitData)
                digitData(value)
              } else someM)
                .map(SCell(value, 0, _))
            } else None
          x
        }).flatten.toArray

      allCells
    }
  }


  /**
   * provides a fallback if there is no digit detected for this number.
   *
   * the size and type of the mat is calculated by looking at the other elements of the digit
   * library. if none found there, just returns null
   *
   * @param number
   * @return
   */
  // TODO fallback sollte eigentlich eine mask auf dem inputbild sein (maske is the best match)
  private def mkFallback(number: Int, digitData: Array[Option[Mat]]): Option[Mat] = {
    /**
     * returns size and type of Mat's contained int he digitLibrary
     * @return
     */
    def determineMatParams(digitData: Array[Option[Mat]]): Option[(Size, Int)] = {
      digitData.flatten.headOption.map { case m => (m.size, m.`type`)}
    }

    for ((size, matType) <- determineMatParams(digitData)) yield {
      val mat = new Mat(size.height.toInt, size.width.toInt, matType).setTo(new Scalar(255, 255, 255))
      Core.putText(mat, number.toString, new Point(size.width * 0.3, size.height * 0.9), Core.FONT_HERSHEY_TRIPLEX, 2, new Scalar(0, 0, 0))
      mat
    }
  }


}