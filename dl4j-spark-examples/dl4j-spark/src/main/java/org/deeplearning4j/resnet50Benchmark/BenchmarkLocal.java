package org.deeplearning4j.resnet50Benchmark;

import com.beust.jcommander.Parameter;
import org.datavec.api.io.labels.ParentPathLabelGenerator;
import org.datavec.api.records.reader.RecordReader;
import org.datavec.api.split.FileSplit;
import org.datavec.image.recordreader.ImageRecordReader;
import org.deeplearning4j.datasets.datavec.RecordReaderDataSetIterator;
import org.deeplearning4j.datasets.iterator.EarlyTerminationDataSetIterator;
import org.deeplearning4j.nn.conf.CacheMode;
import org.deeplearning4j.nn.conf.WorkspaceMode;
import org.deeplearning4j.nn.conf.layers.ConvolutionLayer;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.optimize.listeners.PerformanceListener;
import org.deeplearning4j.patent.utils.JCommanderUtils;
import org.deeplearning4j.zoo.model.ResNet50;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.factory.Nd4j;

import java.io.File;
import java.text.DecimalFormat;

/**
 * Before running this benchmark, you will need to download and extract the ImageNet ILSVRC2012 files from:
 * http://www.image-net.org/challenges/LSVRC/2012/nonpub-downloads
 * You can extract the files manually, OR you can run the PreprocessLocal script and pass in {localSaveDir}/rawImages_validation
 * as the image source.
 *
 * For the purposes of this benchmark (which measures images per second and batches per second, not convergence speed) either
 * the train or validation set should be suitable.
 *
 * Only a subset of the data is required for benchmarking purposes.
 *
 *
 */
public class BenchmarkLocal {

    @Parameter(names = {"--dataPath"}, description = "Path to the ImageNet files to use for benchmarking", required = true)
    private String dataPath;

    @Parameter(names = {"--batchSize"}, description = "Batch size for saving the data", required = false)
    private int batchSize = 128;

    @Parameter(names = {"--numBatchesBenchmark"}, description = "Number of minibatches to use for benchmarking", required = false)
    private int numBatches = 250;

    @Parameter(names = {"--numBatchesWarmup"}, description = "Number of minibatches to use for the warmup", required = false)
    private int numBatchesWarmup = 50;

    public static void main(String[] args) throws Exception {
        new BenchmarkLocal().entryPoint(args);
    }

    public void entryPoint(String[] args) throws Exception {
        JCommanderUtils.parseArgs(this, args);


        ResNet50 resNet50 = ResNet50.builder()
            .seed(12345)
            .numClasses(1000)
            .cudnnAlgoMode(ConvolutionLayer.AlgoMode.PREFER_FASTEST)
            .cacheMode(CacheMode.NONE)
            .workspaceMode(WorkspaceMode.ENABLED)
            .build();

        ComputationGraph net = resNet50.init();
        net.setListeners(new PerformanceListener(5));

        //Disable periodic GC - should not be required with workspaces enabled. See: https://deeplearning4j.org/docs/latest/deeplearning4j-config-workspaces#garbage-collector
        Nd4j.getMemoryManager().togglePeriodicGc(false);

        //Prepare training data
        ParentPathLabelGenerator labelMaker = new ParentPathLabelGenerator(); // parent path as the image label
        RecordReader rr = new ImageRecordReader(224,224,3, labelMaker);
        rr.initialize(new FileSplit(new File(dataPath)));
        DataSetIterator trainData = new RecordReaderDataSetIterator(rr, batchSize, 1, 1000);

        //First: perform a short warmup
        DataSetIterator subsetIter = new EarlyTerminationDataSetIterator(trainData, numBatchesWarmup);
        net.fit(subsetIter);
        trainData.reset();
        System.gc();

        //Perform benchmark on subset of data, 250 iterations
        subsetIter = new EarlyTerminationDataSetIterator(trainData, numBatches);
        int iterCountBefore = net.getIterationCount();
        long timeBefore = System.currentTimeMillis();
        net.fit(subsetIter);
        long timeAfter = System.currentTimeMillis();
        int iterCountAfter = net.getIterationCount();

        long totalTimeMillisec = (timeAfter - timeBefore);
        int totalBatches = iterCountAfter - iterCountBefore;      //Should be exactly 250, unless using small/wrong dataset

        double batchesPerSec = totalBatches / (totalTimeMillisec / 1000.0);
        double examplesPerSec = (totalBatches * batchSize) / (totalTimeMillisec / 1000.0);

        DecimalFormat df = new DecimalFormat("#.00");
        System.out.println("Completed " + totalBatches + " in " + df.format(totalTimeMillisec / 1000.0) + " seconds, batch size " + batchSize);
        System.out.println("Batches per second: " + df.format(batchesPerSec));
        System.out.println("Examples per second: " + df.format(examplesPerSec));
    }
}
