package org.deeplearning4j;

import org.deeplearning4j.module.Inception;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.ComputationGraphConfiguration;
import org.deeplearning4j.nn.conf.LearningRatePolicy;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.Updater;
import org.deeplearning4j.nn.conf.graph.L2Vertex;
import org.deeplearning4j.nn.conf.graph.StackVertex;
import org.deeplearning4j.nn.conf.graph.UnstackVertex;
import org.deeplearning4j.nn.conf.layers.BatchNormalization;
import org.deeplearning4j.nn.conf.layers.LocalResponseNormalization;
import org.deeplearning4j.nn.conf.layers.LossLayer;
import org.deeplearning4j.nn.conf.layers.SubsamplingLayer;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.weights.WeightInit;
import org.nd4j.linalg.lossfunctions.LossFunctions;

/**
 * FaceNetVariant
 *  Reference: https://arxiv.org/abs/1503.03832
 *  Also based on the OpenFace implementation: http://reports-archive.adm.cs.cmu.edu/anon/2016/CMU-CS-16-118.pdf
 *
 *  Revised and consolidated version by @crockpotveggies
 *
 * Warning this has not been run yet.
 * There are a couple known issues with CompGraph regarding combining different layer types into one and
 * combining different shapes of input even if the layer types are the same at least for CNN.
 */

public class FaceNetVariant {

    private int height;
    private int width;
    private int channels = 3;
    private int outputNum = 1000;
    private long seed = 123;
    private int iterations = 90;

    public FaceNetVariant(int height, int width, int channels, int outputNum, long seed, int iterations) {
        this.height = height;
        this.width = width;
        this.channels = channels;
        this.outputNum = outputNum;
        this.seed = seed;
        this.iterations = iterations;
    }

    public ComputationGraph init() {

        ComputationGraphConfiguration.GraphBuilder graph = new NeuralNetConfiguration.Builder()
            .seed(seed)
            .iterations(iterations)
            .activation("relu")
            .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
            .learningRate(1e-2)
            .biasLearningRate(2 * 1e-2)
            .learningRateDecayPolicy(LearningRatePolicy.Step)
            .lrPolicyDecayRate(0.96)
            .lrPolicySteps(320000)
            .updater(Updater.ADAM)
//        .momentum(0.9)
            .adamVarDecay(0.999)
            .adamMeanDecay(0.9)
            .weightInit(WeightInit.XAVIER)
            .regularization(true)
            .l2(2e-4)
            .graphBuilder();


        graph
            .addInputs("input1","input2","input3")
            .addVertex("stack1", new StackVertex(), "input1","input2","input3")
            .addLayer("cnn1", Inception.conv7x7(this.channels, 64, 0.2), "stack1")
            .addLayer("batch1", new BatchNormalization.Builder(1e-4, 0.75).nIn(64).nOut(64).build(), "cnn1")
            .addLayer("pool1", new SubsamplingLayer.Builder(SubsamplingLayer.PoolingType.MAX, new int[]{3,3}, new int[]{2,2}, new int[]{1,1}).build(), "batch1")
            .addLayer("lrn1", new LocalResponseNormalization.Builder(5, 1e-4, 0.75).build(), "pool1")

            // Inception 2
            .addLayer("inception-2-cnn1", Inception.conv1x1(64, 64, 0.2), "lrn1")
            .addLayer("inception-2-batch1", new BatchNormalization.Builder(false).nIn(64).nOut(64).build(), "inception-2-cnn1")
            .addLayer("inception-2-cnn2", Inception.conv3x3(64, 192, 0.2), "inception-2-batch1")
            .addLayer("inception-2-batch2", new BatchNormalization.Builder(false).nIn(192).nOut(192).build(), "inception-2-cnn2")
            .addLayer("inception-2-lrn1", new LocalResponseNormalization.Builder(5, 1e-4, 0.75).build(), "inception-2-batch2")
            .addLayer("inception-2-pool1", new SubsamplingLayer.Builder(SubsamplingLayer.PoolingType.MAX, new int[]{3,3}, new int[]{2,2}, new int[]{1,1}).build(), "inception-2-lrn1");

        // Inception 3a
        Inception.appendGraph(graph, "3a", 192,
            new int[]{3,5}, new int[]{1,1}, new int[]{128,32}, new int[]{96,16,32,64},
            SubsamplingLayer.PoolingType.MAX, true, "inception-2-pool1");
        // Inception 3b
        Inception.appendGraph(graph, "3b", 256,
            new int[]{3,5}, new int[]{1,1}, new int[]{128,64}, new int[]{96,32,64,64},
            SubsamplingLayer.PoolingType.MAX, true, "inception-3a"); // TODO: needs to be p norm pooling
        // Inception 3c
        Inception.appendGraph(graph, "3c", 320,
            new int[]{3,5}, new int[]{2,2}, new int[]{256,64}, new int[]{128,32},
            SubsamplingLayer.PoolingType.MAX, 2, 1, true, "inception-3b");

        // Inception 4a
        Inception.appendGraph(graph, "4a", 640,
            new int[]{3,5}, new int[]{1,1}, new int[]{192,64}, new int[]{96,32,128,256},
            SubsamplingLayer.PoolingType.MAX, true, "inception-3b"); // TODO: needs to be p norm pooling
        // Inception 4e
        Inception.appendGraph(graph, "4e", 640,
            new int[]{3,5}, new int[]{2,2}, new int[]{256,128}, new int[]{160,64},
            SubsamplingLayer.PoolingType.MAX, 2, 1, true, "inception-4a");

        // Inception 5a
        Inception.appendGraph(graph, "5a", 1024,
            new int[]{3}, new int[]{1}, new int[]{384}, new int[]{96,96,256},
            SubsamplingLayer.PoolingType.MAX, true, "inception-4e"); // TODO: needs to be p norm pooling
        // Inception 5b
        Inception.appendGraph(graph, "5b", 736,
            new int[]{3}, new int[]{1}, new int[]{384}, new int[]{96,96,256},
            SubsamplingLayer.PoolingType.MAX, 1, 1, true, "inception-5a");

        graph
            .addLayer("avg3", Inception.avgPoolNxN(3,3), "inception-5b") // output: 1x1x1024
            .addVertex("unstack0", new UnstackVertex(0,3), "avg3")
            .addVertex("unstack1", new UnstackVertex(1,3), "avg3")
            .addVertex("unstack2", new UnstackVertex(2,3), "avg3")
            .addVertex("l2-1", new L2Vertex(), "unstack1", "unstack0") // x - x-
            .addVertex("l2-2", new L2Vertex(), "unstack1", "unstack2") // x - x+
            .addLayer("lossLayer", new LossLayer.Builder()
                .lossFunction(LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD)
                .activation("softmax")
                .build(), "l2-1", "l2-2")
            .setOutputs("lossLayer")
            .backprop(true).pretrain(false);

        ComputationGraphConfiguration conf = graph.build();

        ComputationGraph model = new ComputationGraph(conf);
        model.init();

        return model;
    }



}

