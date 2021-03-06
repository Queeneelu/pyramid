package edu.neu.ccs.pyramid.regression.linear_regression;

import edu.neu.ccs.pyramid.dataset.DataSet;
import edu.neu.ccs.pyramid.dataset.MultiLabel;
import edu.neu.ccs.pyramid.dataset.RegDataSet;
import edu.neu.ccs.pyramid.optimization.Terminator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.mahout.math.Vector;

import java.util.*;
import java.util.stream.IntStream;

/**
 * Friedman, Jerome, Trevor Hastie, and Rob Tibshirani.
 * "Regularization paths for generalized linear models via coordinate descent."
 * Journal of statistical software 33.1 (2010): 1.
 * Created by chengli on 2/18/15.
 * There is no restriction on instance weights
 * The loss function is weighted square error/total weight + penalty
 */
public class ElasticNetLinearRegOptimizer {
    private static final Logger logger = LogManager.getLogger();
    private double regularization = 0;
    private double l1Ratio = 0;
    private Terminator terminator;
    private LinearRegression linearRegression;
    private DataSet dataSet;
    private double[] labels;
    double[] instanceWeights;
    double sumWeights;
    private boolean isActiveSet = false;

    public boolean isActiveSet() {
        return isActiveSet;
    }

    public void setActiveSet(boolean activeSet) {
        isActiveSet = activeSet;
    }

    public ElasticNetLinearRegOptimizer(LinearRegression linearRegression, DataSet dataSet, double[] labels, double[] instanceWeights, double sumWeights) {
        this.linearRegression = linearRegression;
        this.dataSet = dataSet;
        this.labels = labels;
        this.instanceWeights = instanceWeights;
        this.terminator = new Terminator();
        this.sumWeights = sumWeights;
        this.isActiveSet = false;
    }

    public ElasticNetLinearRegOptimizer(LinearRegression linearRegression, DataSet dataSet, double[] labels, double[] instanceWeights) {
        this(linearRegression, dataSet, labels, instanceWeights, Arrays.stream(instanceWeights).parallel().sum());
    }

    public ElasticNetLinearRegOptimizer(LinearRegression linearRegression, DataSet dataSet, double[] labels) {
        this(linearRegression,dataSet,labels,defaultWeights(dataSet.getNumDataPoints()));
    }

    public ElasticNetLinearRegOptimizer(LinearRegression linearRegression, RegDataSet dataSet) {
        this(linearRegression,dataSet,dataSet.getLabels());
    }

    public double getRegularization() {
        return regularization;
    }

    public void setRegularization(double regularization) {
        this.regularization = regularization;
    }

    public double getL1Ratio() {
        return l1Ratio;
    }

    public void setL1Ratio(double l1Ratio) {
        this.l1Ratio = l1Ratio;
    }

    public Terminator getTerminator() {
        return terminator;
    }


    public void optimize(){

        if (!isActiveSet) {
            normalOptimize();
        } else {
            // it's for CBM internal updates for now.
            this.terminator.setMode(Terminator.Mode.FINISH_MAX_ITER);
            activeSetOptimize();
        }

    }

    private void activeSetOptimize() {
        double[] scores = new double[dataSet.getNumDataPoints()];
        IntStream.range(0,dataSet.getNumDataPoints()).parallel().forEach(i->
                scores[i] = linearRegression.predict(dataSet.getRow(i)));
        // initialize iterations
        iterate(scores);
        terminator.add(1.0);
        BitSet activeSet = updateActiveSet();
        // only when activeSet does not change
        boolean shouldTerminate = false;
        while (!shouldTerminate) {
            int maxIter = 0;
            while (true) {
                activeSetIterate(scores, activeSet);
                terminator.add(1.0);
                if (terminator.shouldTerminate() || (++maxIter>5)){
                    break;
                }
            }
            iterate(scores);
            terminator.add(1.0);
            if (terminator.shouldTerminate()) {
                break;
            }
            BitSet latestActiveSet = updateActiveSet();
            shouldTerminate = isActiveSetChanged(activeSet, latestActiveSet);
            activeSet = latestActiveSet;
        }
    }

    private boolean isActiveSetChanged(BitSet activeSet, BitSet latestActiveSet) {
        if (activeSet.cardinality() != latestActiveSet.cardinality()) {
            return false;
        }
        if (activeSet.equals(latestActiveSet)) {
            return true;
        }
        return false;
    }

    private BitSet updateActiveSet() {
        BitSet activeSet = new BitSet();
        for (Vector.Element element : linearRegression.getWeights().getWeightsWithoutBias().nonZeroes()) {
            activeSet.set(element.index());
        }
        return activeSet;
    }

    private void normalOptimize() {
        double[] scores = new double[dataSet.getNumDataPoints()];
        IntStream.range(0,dataSet.getNumDataPoints()).parallel().forEach(i->
                scores[i] = linearRegression.predict(dataSet.getRow(i)));

        double lastLoss = loss(linearRegression,scores,labels,instanceWeights, sumWeights);
        if (logger.isDebugEnabled()){
            logger.debug("initial loss = "+lastLoss);
        }

        while(true){
            iterate(scores);
            double loss = loss(linearRegression,scores,labels,instanceWeights,sumWeights);
            if (logger.isDebugEnabled()){
                logger.debug("loss = "+loss);
            }
            terminator.add(loss);
            if (terminator.shouldTerminate()){
                if (logger.isDebugEnabled()){
                    logger.debug("final loss = "+loss);
                }
                break;
            }
        }
    }


    private void activeSetIterate(double[] scores, BitSet activeSet) {
        // if no weight at all, only minimize the penalty
        if (sumWeights==0){
            // if there is a penalty
            if (regularization>0){
                for (int j=0;j<dataSet.getNumFeatures();j++){
                    linearRegression.getWeights().setWeight(j,0);
                }
            }
            return;
        }
        double oldBias = linearRegression.getWeights().getBias();
        double newBias = IntStream.range(0,dataSet.getNumDataPoints()).parallel().mapToDouble(i ->
                instanceWeights[i]*(labels[i]-scores[i] + oldBias)).sum()/sumWeights;
        linearRegression.getWeights().setBias(newBias);
        //update scores
        double difference = newBias - oldBias;
        if (difference != 0) {
            IntStream.range(0,dataSet.getNumDataPoints()).parallel().forEach(i -> scores[i] = scores[i] + difference);
        }
        for (int j = activeSet.nextSetBit(0); j >= 0; j = activeSet.nextSetBit(j+1)) {
            optimizeOneFeature(scores,j);
        }
    }

    private void iterate(double[] scores){
        // if no weight at all, only minimize the penalty
        if (sumWeights==0){
            // if there is a penalty
            if (regularization>0){
                for (int j=0;j<dataSet.getNumFeatures();j++){
                    linearRegression.getWeights().setWeight(j,0);
                }
            }
            return;
        }
        double oldBias = linearRegression.getWeights().getBias();
        double newBias = IntStream.range(0,dataSet.getNumDataPoints()).parallel().mapToDouble(i ->
                instanceWeights[i]*(labels[i]-scores[i] + oldBias)).sum()/sumWeights;
        linearRegression.getWeights().setBias(newBias);
        //update scores
        double difference = newBias - oldBias;
        if (difference != 0) {
            IntStream.range(0,dataSet.getNumDataPoints()).parallel().forEach(i -> scores[i] = scores[i] + difference);
        }
        for (int j=0;j<dataSet.getNumFeatures();j++){
            optimizeOneFeature(scores,j);
        }
    }


    private void optimizeOneFeature(double[] scores, int featureIndex){
        double oldCoeff = linearRegression.getWeights().getWeightsWithoutBias().get(featureIndex);
        double fit = 0;
        double denominator = 0;
        Vector featureColumn = dataSet.getColumn(featureIndex);
        for (Vector.Element element: featureColumn.nonZeroes()){
            int i = element.index();
            double x = element.get();
            double partialResidual = labels[i] - scores[i] + x*oldCoeff;
            double tmp = instanceWeights[i]*x;
            fit += tmp*partialResidual;
            denominator += x*tmp;
        }
        fit /= sumWeights;
        double numerator = softThreshold(fit);
        // TODO: regularization*(1-l1Ratio): repeated calculations
        denominator = denominator/sumWeights + regularization*(1-l1Ratio);
        // if denominator = 0, this feature is useless, assign 0 to the coefficient
        double newCoeff = 0;
        if (denominator!=0){
            newCoeff = numerator/denominator;
        }


        linearRegression.getWeights().setWeight(featureIndex,newCoeff);
        //update scores
        double difference = newCoeff - oldCoeff;
        if (difference!=0){
            for (Vector.Element element: featureColumn.nonZeroes()){
                int i = element.index();
                double x = element.get();
                scores[i] = scores[i] +  difference*x;
            }
        }
    }


    private double loss(LinearRegression linearRegression, double[] scores, double[] labels, double[] instanceWeights, double sumWeights){
        double mse = IntStream.range(0,scores.length).parallel().mapToDouble(i ->
                instanceWeights[i] * Math.pow(labels[i] - scores[i], 2))
                .sum();
        double penalty = penalty(linearRegression);
        return mse/(2*sumWeights) + penalty;
    }

    private double penalty(LinearRegression linearRegression){
        Vector vector = linearRegression.getWeights().getWeightsWithoutBias();
        double normCombination = (1-l1Ratio)*0.5*Math.pow(vector.norm(2),2) +
                l1Ratio*vector.norm(1);
        return regularization * normCombination;
    }

    private static double softThreshold(double z, double gamma){
        if (z>0 && gamma < Math.abs(z)){
            return z-gamma;
        }
        if (z<0 && gamma < Math.abs(z)){
            return z+gamma;
        }
        return 0;
    }

    //TODO: regularization * l1Ratio: repeated calculations
    private double softThreshold(double z){
        return softThreshold(z, regularization*l1Ratio);
    }

    // todo double check: what's the meaning of weight; what happens if default weight = 1; how will that affect hyper parameters?
    private static double[] defaultWeights(int numData){
        double[] weights = new double[numData];
        double weight = 1.0;
        Arrays.fill(weights,weight);
        return weights;
    }




}