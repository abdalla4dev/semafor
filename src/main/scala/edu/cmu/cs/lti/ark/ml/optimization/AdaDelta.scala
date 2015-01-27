package edu.cmu.cs.lti.ark.ml.optimization

import breeze.linalg.{DenseVector, Vector => Vec}
import edu.cmu.cs.lti.ark.ml.Vectors.toDenseVector
import edu.cmu.cs.lti.ark.ml.optimization.MiniBatch.addLossAndGradient

import scala.math.sqrt
import scala.util.Random


/**
 * An implementation of the ADADELTA algorithm as described in
 * Zeiler, Matthew D., "ADADELTA: An Adaptive Learning Rate Method"
 * http://arxiv.org/abs/1212.5701
 *
 * Modified to add L1 + L2 regularization, implemented with
 * proximal steps.
 *
 * @param decay the exponential rate to decay running averages at. 0 < decay < 1
 *              (1 - \rho in the paper)
 * @param smoothing a small positive constant to "better condition the denominator"
 *                  (\epsilon in the paper)
 * @param l1Strength the L1 regularization strength
 */
case class AdaDelta(decay: Double = 0.05,
                    smoothing: Double = 1e-6,
                    l1Strength: Double = 1e-5,
                    l2Strength: Double = 0.0) {

  @inline private[this] def smoothSqrt(x: Double): Double = sqrt(x + smoothing)

  @inline private[this] val oneMinusDecay = 1 - decay

  @inline def decayingAvg(oldAvg: Double, newVal: Double): Double = {
    oneMinusDecay * oldAvg + decay * newVal
  }

  /** Proximal mapping for L1 (a.k.a. "soft-threshold") */
  @inline private[this] def l1Proximal(scale: Double)(x: Double): Double = {
    if (x > scale) {
      x - scale
    } else if (x < -scale) {
      x + scale
    } else {
      0.0
    }
  }

  /** Proximal mapping for L2. Scales `x` down by `1 / (scale + 1)` */
  @inline private[this] def l2Proximal(scale: Double)(x: Double): Double = {
    x / (scale + 1.0)
  }

  /**
   * Proximal mapping for L1 + L2.
   * Closed-form solution to `\argmin_y { (scale * regLoss(y)) + ||y-x||^2 }`.
   * `scale` should be the same as the learning rate applied to the respective
   * gradient component.
   */
  @inline private[this] def proximal(scale: Double)(x: Double): Double = {
    val thresholded = if (l1Strength == 0.0) {
      x
    } else {
      l1Proximal(scale * l1Strength)(x)
    }
    if (l2Strength == 0.0) {
      thresholded
    } else {
      l2Proximal(scale * l2Strength)(thresholded)
    }
  }

  /** The starting state for a run of AdaDelta */
  def start(initialWeights: Vec[Double]): State = {
    val size = initialWeights.length
    new State(initialWeights.copy, DenseVector.zeros(size), DenseVector.zeros(size))
  }

  /** The (mutable) state of a run of AdaDelta */
  case class State(weights: Vec[Double],
                   avgSquaredGradient: Vec[Double],
                   avgSquaredDelta: Vec[Double],
                   regLoss: Double = 0.0) {
    /**
     * Takes a step in the general direction of `gradient`,
     * but uses adaptive, per-component learning rates.
     * `gradient` should not include regularization;
     * regularization is handled specially, by applying a proximal mapping.
     * Destructively modifies `weights`, `avgSquaredGradient`, `avgSquaredDelta`,
     * and returns the new State.
     *
     * TODO: Modifying state in place is in order to reduce memory usage. Is it necessary?
     */
    def step(gradient: Vec[Double]): State = {
      val denseGrad = toDenseVector(gradient)
      val regLosses = for (i <- (0 until denseGrad.length).par) yield {
        // modify each component in place
        val g = denseGrad(i)
        val w = weights(i)
        val oldAvgSqDelta = avgSquaredDelta(i)
        val newAvgSqGrad = decayingAvg(avgSquaredGradient(i), g * g)
        val rate = smoothSqrt(oldAvgSqDelta) / smoothSqrt(newAvgSqGrad)
        val delta = rate * g
        avgSquaredGradient(i) = newAvgSqGrad
        avgSquaredDelta(i) = decayingAvg(oldAvgSqDelta, delta * delta)
        val newWeight = proximal(rate)(w - delta)
        weights(i) = newWeight
        // yield regularization loss due to this component
        l1Strength * math.abs(newWeight) +.5 * l2Strength * newWeight * newWeight
      }
      copy(regLoss = regLosses.sum)
    }
  }

}

case class MiniBatch[T](trainingData: IndexedSeq[T],
                        lossFn: SubDifferentiableLoss[T],
                        l1Strength: Double,
                        l2Strength: Double,
                        batchSize: Int) {

  private[this] val batchRecip = 1.0 / batchSize

  /**
   * Returns an (infinite) iterator of (State, iteration, batchNum).
   * Weights get clobbered as you iterate, so make copies if you want to keep them.
   */
  def optimizationPath(initialWeights: Vec[Double]): Iterator[(AdaDelta#State, Double, Int, Int)] = {
    var state = AdaDelta().start(initialWeights)
    // sample batches without replacement
    for (
      iteration <- Iterator.from(0);
      (batch, batchNum) <- Random.shuffle(trainingData).grouped(batchSize).zipWithIndex
    ) yield {
      val weights = state.weights
      // compute gradients in parallel
      val lossesAndGrads = batch.par.map(lossFn.lossAndGradient(weights))
      val (loss, gradient) = lossesAndGrads.reduce(addLossAndGradient)
      // scale to per-instance
      state = state.step(gradient * batchRecip)
      (state, loss * batchRecip, iteration, batchNum)
    }
  }
}

object MiniBatch {
  @inline private def addLossAndGradient(a: (Double, Vec[Double]), b: (Double, Vec[Double])): (Double, Vec[Double]) = {
    (a._1 + b._1, a._2 + b._2)
  }
}