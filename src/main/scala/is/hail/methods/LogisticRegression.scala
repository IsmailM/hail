package is.hail.methods

import breeze.linalg._
import is.hail.annotations.Annotation
import is.hail.expr._
import is.hail.stats.RegressionUtils._
import is.hail.stats._
import is.hail.utils._
import is.hail.variant._
import org.apache.spark.rdd.RDD

object LogisticRegression {

  def apply(vds: VariantDataset, test: String, ySA: String, covSA: Array[String], root: String): VariantDataset = {

    if (!vds.wasSplit)
      fatal("logreg requires bi-allelic VDS. Run split_multi or filter_multi first")

    def tests = Map("wald" -> WaldTest, "lrt" -> LikelihoodRatioTest, "score" -> ScoreTest, "firth" -> FirthTest)

    val logRegTest = tests(test)

    val (y, cov, completeSamples) = RegressionUtils.getPhenoCovCompleteSamples(vds, ySA, covSA)
    val sampleMask = vds.sampleIds.map(completeSamples.toSet).toArray

    if (!y.forall(yi => yi == 0d || yi == 1d))
      fatal(s"For logistic regression, phenotype must be Boolean or numeric with all values equal to 0 or 1")

    if (!tests.isDefinedAt(test))
      fatal(s"Supported tests are ${ tests.keys.mkString(", ") }, got: $test")

    val n = y.size
    val k = cov.cols
    val d = n - k - 1

    if (d < 1)
      fatal(s"$n samples and $k ${ plural(k, "covariate") } including intercept implies $d degrees of freedom.")

    info(s"Running $test logreg on $n samples with $k ${ plural(k, "covariate") } including intercept...")

    val nullModel = new LogisticRegressionModel(cov, y)
    val nullFit = nullModel.fit()

    if (!nullFit.converged)
      fatal("Failed to fit (unregulatized) logistic regression null model (covariates only): " + (
        if (nullFit.exploded)
          s"exploded at Newton iteration ${ nullFit.nIter }"
        else
          "Newton iteration failed to converge"))

    val sc = vds.sparkContext
    val sampleMaskBc = sc.broadcast(sampleMask)
    val yBc = sc.broadcast(y)
    val covBc = sc.broadcast(cov)
    val nullFitBc = sc.broadcast(nullFit)
    val logRegTestBc = sc.broadcast(logRegTest)

    val pathVA = Parser.parseAnnotationRoot(root, Annotation.VARIANT_HEAD)
    val (newVAS, inserter) = vds.insertVA(logRegTest.`type`, pathVA)
    val emptyStats = logRegTest.emptyStats

    vds.mapAnnotations { case (v, va, gs) =>
      val maskedGts = gs.zipWithIndex.filter { case (g, i) => sampleMaskBc.value(i) }.map(_._1.gt)

      val logregAnnot =
        buildGtColumn(maskedGts)
          .map { gts =>
            val X = DenseMatrix.horzcat(covBc.value, gts)
            logRegTestBc.value.test(X, yBc.value, nullFitBc.value).toAnnotation(emptyStats)
          }
          .orNull

      val newAnnotation = inserter(va, logregAnnot)
      assert(newVAS.typeCheck(newAnnotation))
      newAnnotation
    }.copy(vaSignature = newVAS)
  }
}

case class LogisticRegression(rdd: RDD[(Variant, Annotation)], `type`: Type)