package edu.neu.ccs.pyramid.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.neu.ccs.pyramid.calibration.*;

import edu.neu.ccs.pyramid.configuration.Config;
import edu.neu.ccs.pyramid.dataset.*;
import edu.neu.ccs.pyramid.eval.CalibrationEval;
import edu.neu.ccs.pyramid.eval.MLMeasures;
import edu.neu.ccs.pyramid.multilabel_classification.*;
import edu.neu.ccs.pyramid.multilabel_classification.cbm.CBM;
import edu.neu.ccs.pyramid.multilabel_classification.predictor.IndependentPredictor;
import edu.neu.ccs.pyramid.multilabel_classification.predictor.SupportPredictor;
import edu.neu.ccs.pyramid.util.*;
import org.apache.commons.io.FileUtils;
import org.apache.mahout.math.Vector;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class BRLRCalibration {
    public static void main(Config config) throws Exception{

        Logger logger = Logger.getAnonymousLogger();
        String logFile = config.getString("output.log");
        FileHandler fileHandler = null;
        if (!logFile.isEmpty()){
            new File(logFile).getParentFile().mkdirs();
            //todo should append?
            fileHandler = new FileHandler(logFile, true);
            java.util.logging.Formatter formatter = new SimpleFormatter();
            fileHandler.setFormatter(formatter);
            logger.addHandler(fileHandler);
            logger.setUseParentHandlers(false);
        }
        logger.info(config.toString());
        
        if (config.getBoolean("calibrate")){
            calibrate(config, logger);
        }

        if(config.getBoolean("tuneCTAT")){
            tuneCTAT(config,logger);
        }

        if (config.getBoolean("test")){
            test(config, logger);
        }

        if (fileHandler!=null){
            fileHandler.close();
        }
    }
    public static void main(String[] args) throws Exception {
        Config config = new Config(args[0]);
        main(config);

    }


    private static void calibrate(Config config, Logger logger) throws Exception{


        logger.info("start training calibrators");
        MultiLabelClfDataSet train = TRECFormat.loadMultiLabelClfDataSet(config.getString("input.trainData"), DataSetType.ML_CLF_SEQ_SPARSE, true);

        MultiLabelClfDataSet cal = TRECFormat.loadMultiLabelClfDataSet(config.getString("input.calibrationData"), DataSetType.ML_CLF_SEQ_SPARSE, true);

        MultiLabelClfDataSet valid = TRECFormat.loadMultiLabelClfDataSet(config.getString("input.validData"), DataSetType.ML_CLF_SEQ_SPARSE, true);

        CBM cbm = (CBM) Serialization.deserialize(Paths.get(config.getString("output.dir"),"model_predictions",config.getString("output.modelFolder"),"models","classifier"));

        List<Integer> labelCalIndices = IntStream.range(0, cal.getNumDataPoints()).filter(i->i%2==0).boxed().collect(Collectors.toList());
        List<Integer> setCalIndices = IntStream.range(0, cal.getNumDataPoints()).filter(i->i%2==1).boxed().collect(Collectors.toList());

        MultiLabelClfDataSet labelCalData = DataSetUtil.sampleData(cal, labelCalIndices);
        MultiLabelClfDataSet setCalData = DataSetUtil.sampleData(cal, setCalIndices);

        logger.info("start training label calibrator");
        LabelCalibrator labelCalibrator = null;
        switch (config.getString("labelCalibrator")){
            case "isotonic":
                labelCalibrator = new IsoLabelCalibrator(cbm, labelCalData);
                break;
            case "none":
                labelCalibrator = new IdentityLabelCalibrator();
                break;
        }
        logger.info("finish training label calibrator");

        logger.info("start training set calibrator");

        List<PredictionFeatureExtractor> extractors = new ArrayList<>();

        if (config.getBoolean("brProb")){
            extractors.add(new BRProbFeatureExtractor());
        }

        if (config.getBoolean("setPrior")){
            extractors.add(new PriorFeatureExtractor(train));
        }

        if (config.getBoolean("card")){
            extractors.add(new CardFeatureExtractor());
        }

        if (config.getBoolean("encodeLabel")){
            extractors.add(new LabelBinaryFeatureExtractor(cbm.getNumClasses(),train.getLabelTranslator()));
        }

        PredictionFeatureExtractor predictionFeatureExtractor = new CombinedPredictionFeatureExtractor(extractors);

        CalibrationDataGenerator calibrationDataGenerator = new CalibrationDataGenerator(labelCalibrator,predictionFeatureExtractor);
        CalibrationDataGenerator.TrainData caliTrainingData = calibrationDataGenerator.createCaliTrainingData(setCalData,cbm,config.getInt("numCandidates"));


        RegDataSet calibratorTrainData = caliTrainingData.regDataSet;
        System.out.println("dimension="+calibratorTrainData.getNumFeatures());
        double[] weights = caliTrainingData.instanceWeights;

        VectorCalibrator setCalibrator = null;

        switch (config.getString("setCalibrator")){
            case "cardinality_isotonic":
                setCalibrator = new VectorCardIsoSetCalibrator(calibratorTrainData, 0, 2);
                break;
            case "reranker":
                RerankerTrainer rerankerTrainer = RerankerTrainer.newBuilder()
                            .numCandidates(config.getInt("numCandidates"))
                            .monotonic(config.getBoolean("monotonic"))
                            .numIterations(config.getInt("numIterations"))
                            .numLeaves(config.getInt("numLeaves"))
                            .build();
                setCalibrator = rerankerTrainer.train(calibratorTrainData, weights,cbm,predictionFeatureExtractor,labelCalibrator);
                break;
            case "isotonic":
                setCalibrator = new VectorIsoSetCalibrator(calibratorTrainData,0);
                break;
            case "none":
                setCalibrator = new VectorIdentityCalibrator(0);
                break;
            default:
                throw new IllegalArgumentException("illegal setCalibrator");
        }

        logger.info("finish training set calibrator");

        Serialization.serialize(labelCalibrator,Paths.get(config.getString("output.dir"),"model_predictions",config.getString("output.modelFolder"),"models",
                "calibrators",config.getString("output.calibratorFolder"),"label_calibrator").toFile());
        Serialization.serialize(setCalibrator,Paths.get(config.getString("output.dir"),"model_predictions",config.getString("output.modelFolder"),"models",
                "calibrators",config.getString("output.calibratorFolder"),"set_calibrator").toFile());
        Serialization.serialize(predictionFeatureExtractor,Paths.get(config.getString("output.dir"),"model_predictions",config.getString("output.modelFolder"),"models",
                "calibrators",config.getString("output.calibratorFolder"),"prediction_feature_extractor").toFile());
        logger.info("finish training calibrators");



        List<MultiLabel> support = (List<MultiLabel>) Serialization.deserialize(Paths.get(config.getString("output.dir"),"model_predictions",config.getString("output.modelFolder"),"models","support").toFile());



        MultiLabelClassifier classifier = null;
        switch (config.getString("predict.mode")){
            case "independent":
                classifier = new IndependentPredictor(cbm,labelCalibrator);
                break;
            case "support":
                classifier = new edu.neu.ccs.pyramid.multilabel_classification.predictor.SupportPredictor(cbm, labelCalibrator, support);
                break;

            case "reranker":
                classifier = (Reranker)setCalibrator;
                break;

            default:
                throw new IllegalArgumentException("illegal predict.mode");
        }
        MultiLabel[] predictions = classifier.predict(cal);

        MultiLabel[] predictions_valid = classifier.predict(valid);


        if (true) {
            logger.info("calibration performance on "+config.getString("input.calibrationFolder")+ " set");

            List<CalibrationDataGenerator.CalibrationInstance> instances = IntStream.range(0, cal.getNumDataPoints()).parallel()
                    .boxed().map(i -> calibrationDataGenerator.createInstance(cbm, cal.getRow(i),predictions[i],cal.getMultiLabels()[i]))
                    .collect(Collectors.toList());

            eval(instances, setCalibrator, logger);
        }

        logger.info("classification performance on "+config.getString("input.validFolder")+" set");
        logger.info(new MLMeasures(valid.getNumClasses(),valid.getMultiLabels(), predictions_valid).toString());

        if (true) {
            logger.info("calibration performance on "+ config.getString("input.validFolder")+" set");

            List<CalibrationDataGenerator.CalibrationInstance> instances = IntStream.range(0, valid.getNumDataPoints()).parallel()
                    .boxed().map(i -> calibrationDataGenerator.createInstance(cbm, valid.getRow(i),predictions_valid[i],valid.getMultiLabels()[i]))
                    .collect(Collectors.toList());

            eval(instances, setCalibrator, logger);



        }



    }


    private static void tuneCTAT(Config config, Logger logger)throws Exception{
        MultiLabelClfDataSet valid = TRECFormat.loadMultiLabelClfDataSet(config.getString("input.validData"), DataSetType.ML_CLF_SEQ_SPARSE,true);
        CBM cbm = (CBM) Serialization.deserialize(Paths.get(config.getString("output.dir"),"model_predictions",config.getString("output.modelFolder"),"models","classifier"));
        LabelCalibrator labelCalibrator = (LabelCalibrator) Serialization.deserialize(Paths.get(config.getString("output.dir"),"model_predictions",config.getString("output.modelFolder"),"models",
                "calibrators",config.getString("output.calibratorFolder"),"label_calibrator").toFile());
        VectorCalibrator setCalibrator = (VectorCalibrator) Serialization.deserialize(Paths.get(config.getString("output.dir"),"model_predictions",config.getString("output.modelFolder"),"models",
                "calibrators",config.getString("output.calibratorFolder"),"set_calibrator").toFile());
        PredictionFeatureExtractor predictionFeatureExtractor = (PredictionFeatureExtractor) Serialization.deserialize(Paths.get(config.getString("output.dir"),"model_predictions",config.getString("output.modelFolder"),"models",
                "calibrators",config.getString("output.calibratorFolder"),"prediction_feature_extractor").toFile());

        List<MultiLabel> support = (List<MultiLabel>) Serialization.deserialize(Paths.get(config.getString("output.dir"),"model_predictions",config.getString("output.modelFolder"),"models","support").toFile());



        MultiLabelClassifier classifier = null;
        switch (config.getString("predict.mode")){
            case "independent":
                classifier = new IndependentPredictor(cbm,labelCalibrator);
                break;
            case "support":
                classifier = new edu.neu.ccs.pyramid.multilabel_classification.predictor.SupportPredictor(cbm, labelCalibrator, support);
                break;

            case "reranker":
                classifier = (Reranker)setCalibrator;
                break;

            default:
                throw new IllegalArgumentException("illegal predict.mode");
        }

        MultiLabel[] predictions_valid = classifier.predict(valid);

        CalibrationDataGenerator calibrationDataGenerator = new CalibrationDataGenerator(labelCalibrator,predictionFeatureExtractor);

        List<CalibrationDataGenerator.CalibrationInstance> instances = IntStream.range(0, valid.getNumDataPoints()).parallel()
                .boxed().map(i -> calibrationDataGenerator.createInstance(cbm, valid.getRow(i),predictions_valid[i],valid.getMultiLabels()[i]))
                .collect(Collectors.toList());
        double targetAccuracy = config.getDouble("CTAT.targetAccuracy");
        double confidenceThreshold = CTAT.findThreshold(generateStream(instances,setCalibrator),targetAccuracy).getConfidenceThreshold();


        logger.info("confidence threshold for target accuracy "+targetAccuracy +" = " +confidenceThreshold);

        FileUtils.writeStringToFile(Paths.get(config.getString("output.dir"),"model_predictions",config.getString("output.modelFolder"),"models",
                   "ctat",config.getString("CTAT.name")).toFile(),""+confidenceThreshold);

        double confidenceThresholdClipped = CTAT.clip(confidenceThreshold,config.getDouble("CTAT.lowerBound"),config.getDouble("CTAT.upperBound"));

        FileUtils.writeStringToFile(Paths.get(config.getString("output.dir"),"model_predictions",config.getString("output.modelFolder"),"models",
                "ctat",config.getString("CTAT.name")+"_clipped").toFile(),""+confidenceThresholdClipped);

    }








    private static void test(Config config, Logger logger) throws Exception{
        MultiLabelClfDataSet test = TRECFormat.loadMultiLabelClfDataSet(config.getString("input.testData"), DataSetType.ML_CLF_SEQ_SPARSE, true);
        CBM cbm = (CBM) Serialization.deserialize(Paths.get(config.getString("output.dir"),"model_predictions",config.getString("output.modelFolder"),"models","classifier"));
        LabelCalibrator labelCalibrator = (LabelCalibrator) Serialization.deserialize(Paths.get(config.getString("output.dir"),"model_predictions",config.getString("output.modelFolder"),"models",
                "calibrators",config.getString("output.calibratorFolder"),"label_calibrator").toFile());
        VectorCalibrator setCalibrator = (VectorCalibrator) Serialization.deserialize(Paths.get(config.getString("output.dir"),"model_predictions",config.getString("output.modelFolder"),"models",
                "calibrators",config.getString("output.calibratorFolder"),"set_calibrator").toFile());
        PredictionFeatureExtractor predictionFeatureExtractor = (PredictionFeatureExtractor) Serialization.deserialize(Paths.get(config.getString("output.dir"),"model_predictions",config.getString("output.modelFolder"),"models",
                "calibrators",config.getString("output.calibratorFolder"),"prediction_feature_extractor").toFile());



        List<MultiLabel> support = (List<MultiLabel>) Serialization.deserialize(Paths.get(config.getString("output.dir"),"model_predictions",config.getString("output.modelFolder"),"models","support").toFile());



        MultiLabelClassifier classifier = null;
        switch (config.getString("predict.mode")){
            case "independent":
                classifier = new IndependentPredictor(cbm,labelCalibrator);
                break;
            case "support":
                classifier = new edu.neu.ccs.pyramid.multilabel_classification.predictor.SupportPredictor(cbm, labelCalibrator, support);
                break;

            case "reranker":
                classifier = (Reranker)setCalibrator;
                break;

            default:
                throw new IllegalArgumentException("illegal predict.mode");
        }
        MultiLabel[] predictions = classifier.predict(test);

        logger.info("test performance");
        logger.info(new MLMeasures(test.getNumClasses(),test.getMultiLabels(), predictions).toString());

        CalibrationDataGenerator calibrationDataGenerator = new CalibrationDataGenerator(labelCalibrator,predictionFeatureExtractor);

        if (true) {
            logger.info("calibration performance on "+config.getString("input.testFolder")+" set");

            List<CalibrationDataGenerator.CalibrationInstance> instances = IntStream.range(0, test.getNumDataPoints()).parallel()
                    .boxed().map(i -> calibrationDataGenerator.createInstance(cbm, test.getRow(i),predictions[i],test.getMultiLabels()[i]))
                    .collect(Collectors.toList());
            double targetAccuracy = config.getDouble("CTAT.targetAccuracy");

            eval(instances, setCalibrator, logger);

            double confidenceThreshold = Double.parseDouble(FileUtils.readFileToString(Paths.get(config.getString("output.dir"),"model_predictions",config.getString("output.modelFolder"),"models",
                    "ctat",config.getString("CTAT.name")).toFile()));
            CTAT.Summary summary = CTAT.applyThreshold(generateStream(instances,setCalibrator),confidenceThreshold);
            logger.info("autocoding performance on dataset "+config.getString("input.testFolder")+"  with unclipped confidence threshold "+summary.getConfidenceThreshold());
            logger.info("autocoding percentage = "+ summary.getAutoCodingPercentage());
            logger.info("autocoding accuracy = "+ summary.getAutoCodingAccuracy());
            logger.info("number of autocoded documents = "+ summary.getNumAutoCoded());
            logger.info("number of correct autocoded documents = "+ summary.getNumCorrectAutoCoded());

            double confidenceThresholdClipped = Double.parseDouble(FileUtils.readFileToString(Paths.get(config.getString("output.dir"),"model_predictions",config.getString("output.modelFolder"),"models",
                    "ctat",config.getString("CTAT.name")+"_clipped").toFile()));
            CTAT.Summary summaryClipped = CTAT.applyThreshold(generateStream(instances,setCalibrator),confidenceThresholdClipped);
            logger.info("autocoding performance on dataset "+config.getString("input.testFolder")+"  with clipped confidence threshold "+summaryClipped.getConfidenceThreshold());
            logger.info("autocoding percentage = "+ summaryClipped.getAutoCodingPercentage());
            logger.info("autocoding accuracy = "+ summaryClipped.getAutoCodingAccuracy());
            logger.info("number of autocoded documents = "+ summaryClipped.getNumAutoCoded());
            logger.info("number of correct autocoded documents = "+ summaryClipped.getNumCorrectAutoCoded());
        }


        MultiLabelClassifier fClassifier = classifier;

        boolean simpleCSV = true;
        if (simpleCSV){
            File testDataFile = new File(config.getString("input.testData"));
            File csv = Paths.get(config.getString("output.dir"),"model_predictions",config.getString("output.modelFolder"),"predictions",testDataFile.getName()+"_reports","report.csv").toFile();
            csv.getParentFile().mkdirs();
            List<Integer> list = IntStream.range(0,test.getNumDataPoints()).boxed().collect(Collectors.toList());
            ParallelStringMapper<Integer> mapper = (list1, i) -> simplePredictionAnalysisCalibrated(cbm, labelCalibrator, setCalibrator,
                    test, i, fClassifier,predictionFeatureExtractor);
            ParallelFileWriter.mapToString(mapper,list, csv,100);
        }


        boolean topSets = true;
        if (topSets){
            File testDataFile = new File(config.getString("input.testData"));
            File csv = Paths.get(config.getString("output.dir"),"model_predictions",config.getString("output.modelFolder"),"predictions",testDataFile.getName()+"_reports","top_sets.csv").toFile();
            csv.getParentFile().mkdirs();
            List<Integer> list = IntStream.range(0,test.getNumDataPoints()).boxed().collect(Collectors.toList());
            ParallelStringMapper<Integer> mapper = (list1, i) -> topKSets(config, cbm, labelCalibrator, setCalibrator,
                    test, i, fClassifier,predictionFeatureExtractor);
            ParallelFileWriter.mapToString(mapper,list, csv,100);
        }



        boolean rulesToJson = config.getBoolean("report.showPredictionDetail");
        if (rulesToJson){
            logger.info("start writing rules to json");
            int ruleLimit = config.getInt("report.rule.limit");
            int numDocsPerFile = config.getInt("report.numDocsPerFile");
            int numFiles = (int)Math.ceil((double)test.getNumDataPoints()/numDocsPerFile);

            double probThreshold=config.getDouble("report.classProbThreshold");
            int labelSetLimit = config.getInt("report.labelSetLimit");


            IntStream.range(0,numFiles).forEach(i->{
                int start = i*numDocsPerFile;
                int end = start+numDocsPerFile;
                List<MultiLabelPredictionAnalysis> partition = IntStream.range(start,Math.min(end,test.getNumDataPoints())).parallel().mapToObj(a->
                        BRLRInspector.analyzePrediction(cbm, labelCalibrator, setCalibrator, test, fClassifier, predictionFeatureExtractor, a,  ruleLimit,labelSetLimit, probThreshold))
                        .collect(Collectors.toList());
                ObjectMapper mapper = new ObjectMapper();
                File testDataFile = new File(config.getString("input.testData"));
                File jsonFile = Paths.get(config.getString("output.dir"),"model_predictions",config.getString("output.modelFolder"),"predictions",testDataFile.getName()+"_reports","report_"+(i+1)+".json").toFile();
                try {
                    mapper.writeValue(jsonFile, partition);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                logger.info("progress = "+ Progress.percentage(i+1,numFiles));
            });

            logger.info("finish writing rules to json");
        }

    }


    public static String simplePredictionAnalysisCalibrated(CBM cbm,
                                                             LabelCalibrator labelCalibrator,
                                                             VectorCalibrator setCalibrator,
                                                             MultiLabelClfDataSet dataSet,
                                                             int dataPointIndex,
                                                             MultiLabelClassifier classifier,
                                                            PredictionFeatureExtractor predictionFeatureExtractor){
        StringBuilder sb = new StringBuilder();
        MultiLabel trueLabels = dataSet.getMultiLabels()[dataPointIndex];
        String id = dataSet.getIdTranslator().toExtId(dataPointIndex);
        LabelTranslator labelTranslator = dataSet.getLabelTranslator();
        double[] classProbs = cbm.predictClassProbs(dataSet.getRow(dataPointIndex));
        double[] calibratedClassProbs = labelCalibrator.calibratedClassProbs(classProbs);

        MultiLabel predicted = classifier.predict(dataSet.getRow(dataPointIndex));

        List<Integer> classes = new ArrayList<Integer>();
        for (int k = 0; k < dataSet.getNumClasses(); k++){
            if (dataSet.getMultiLabels()[dataPointIndex].matchClass(k)
                    ||predicted.matchClass(k)){
                classes.add(k);
            }
        }

        Comparator<Pair<Integer,Double>> comparator = Comparator.comparing(Pair::getSecond);
        List<Pair<Integer,Double>> list = classes.stream().map(l -> {
            if (l < cbm.getNumClasses()) {
                return new Pair<>(l, calibratedClassProbs[l]);
            } else {
                return new Pair<>(l, 0.0);
            }
        }).sorted(comparator.reversed()).collect(Collectors.toList());
        for (Pair<Integer,Double> pair: list){
            int label = pair.getFirst();
            double prob = pair.getSecond();
            int match = 0;
            if (trueLabels.matchClass(label)){
                match=1;
            }
            sb.append(id).append("\t").append(labelTranslator.toExtLabel(label)).append("\t")
                    .append("single").append("\t").append(prob)
                    .append("\t").append(match).append("\n");
        }

        PredictionCandidate predictedCandidate = new PredictionCandidate();
        predictedCandidate.multiLabel = predicted;
        predictedCandidate.labelProbs = calibratedClassProbs;
        predictedCandidate.x = dataSet.getRow(dataPointIndex);
        Vector feature = predictionFeatureExtractor.extractFeatures(predictedCandidate);
        double probability = setCalibrator.calibrate(feature);


        List<Integer> predictedList = predicted.getMatchedLabelsOrdered();
        sb.append(id).append("\t");
        for (int i=0;i<predictedList.size();i++){
            sb.append(labelTranslator.toExtLabel(predictedList.get(i)));
            if (i!=predictedList.size()-1){
                sb.append(",");
            }
        }
        sb.append("\t");
        int setMatch = 0;
        if (predicted.equals(trueLabels)){
            setMatch=1;
        }
        sb.append("set").append("\t").append(probability).append("\t").append(setMatch).append("\n");
        return sb.toString();
    }


    private static String topKSets(Config config,
                                CBM cbm,
                                LabelCalibrator labelCalibrator,
                                VectorCalibrator setCalibrator,
                                MultiLabelClfDataSet dataSet,
                                int dataPointIndex, MultiLabelClassifier classifier, PredictionFeatureExtractor predictionVectorizer){
        StringBuilder sb = new StringBuilder();
        String id = dataSet.getIdTranslator().toExtId(dataPointIndex);
        LabelTranslator labelTranslator = dataSet.getLabelTranslator();
        int top = config.getInt("report.labelSetLimit");

        List<Pair<MultiLabel,Double>> topK;
        if (classifier instanceof SupportPredictor){
            topK = TopKFinder.topKinSupport(dataSet.getRow(dataPointIndex),cbm,labelCalibrator,setCalibrator,
                    predictionVectorizer,((SupportPredictor)classifier).getSupport(),top);
        } else {
            topK = TopKFinder.topK(dataSet.getRow(dataPointIndex),cbm,labelCalibrator,setCalibrator,
                    predictionVectorizer,top);
        }


        for (Pair<MultiLabel,Double> pair: topK){
            MultiLabel set = pair.getFirst();
            double probability = pair.getSecond();
            List<Integer> predictedList = set.getMatchedLabelsOrdered();
            sb.append(id).append("\t");
            for (int i=0;i<predictedList.size();i++){
                sb.append(labelTranslator.toExtLabel(predictedList.get(i)));
                if (i!=predictedList.size()-1){
                    sb.append(",");
                }
            }
            sb.append("\t");
            sb.append(probability);
            sb.append("\n");
        }

        return sb.toString();
    }



    private static CaliRes eval(List<CalibrationDataGenerator.CalibrationInstance> predictions, VectorCalibrator calibrator, Logger logger){
        double mse = CalibrationEval.mse(generateStream(predictions,calibrator));
        double ace = CalibrationEval.absoluteError(generateStream(predictions,calibrator),10);
        double sharpness = CalibrationEval.sharpness(generateStream(predictions,calibrator),10);
        logger.info("mse="+mse);
        logger.info("absolute calibration error="+ace);
        logger.info("square calibration error="+CalibrationEval.squareError(generateStream(predictions,calibrator),10));
        logger.info("sharpness="+sharpness);
        logger.info("variance="+CalibrationEval.variance(generateStream(predictions,calibrator)));
        logger.info(Displayer.displayCalibrationResult(generateStream(predictions,calibrator)));

        CaliRes caliRes = new CaliRes();
        caliRes.mse = mse;
        caliRes.ace= ace;
        caliRes.sharpness = sharpness;
        return caliRes;
    }





    private static Stream<Pair<Double,Integer>> generateStream(List<CalibrationDataGenerator.CalibrationInstance> predictions, VectorCalibrator vectorCalibrator){
        return predictions.stream()
                .parallel().map(pred->new Pair<>(vectorCalibrator.calibrate(pred.vector),(int)pred.correctness));
    }


    public static class CaliRes implements Serializable {
        public static final long serialVersionUID = 446782166720638575L;
        public double mse;
        public double ace;
        public double sharpness;
    }


}