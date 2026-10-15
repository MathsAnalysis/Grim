package ac.grim.grimac.checks.impl.combat.ai;

public final class AimAIModel {

    public static final int INPUT_SIZE = 8;
    public static final int HIDDEN_SIZE = 6;

    private static double[][] W1 = {
            { 1.5751, 1.3516, 0.9254, 1.3424, 1.3534, 0.7338, 0.8871, 0.9190 },
            { 1.3756, 1.0345, 0.8453, 1.3343, 0.9536, 0.8869, 0.9856, 0.9674 },
            { 1.2452, 1.0391, 0.8204, 1.3515, 0.9781, 1.0044, 1.1450, 1.0094 },
            { 1.3174, 1.2890, 0.9725, 1.4911, 1.2921, 0.6330, 1.0752, 0.9791 },
            { 1.2579, 0.9932, 0.8907, 1.0349, 1.0727, 0.9258, 1.1092, 0.6876 },
            { 1.1686, 1.1056, 0.8956, 1.3094, 0.9295, 0.8203, 0.9435, 0.6885 }
    };

    private static double[] B1 = {
            -0.4271, -0.6386, -0.5659, -0.3962, -0.5645, -0.6015
    };

    private static double[] W2 = {
            1.9813, 1.5523, 1.6567, 2.0065, 1.6129, 1.5640
    };

    private static double B2 = -8.8769;

    private AimAIModel() {}

    public static double predict(double[] x) {
        if (x == null || x.length < INPUT_SIZE) {
            return 0.0;
        }

        double[] hidden = new double[HIDDEN_SIZE];
        for (int j = 0; j < HIDDEN_SIZE; j++) {
            double sum = B1[j];
            for (int i = 0; i < INPUT_SIZE; i++) {
                sum += W1[j][i] * x[i];
            }
            hidden[j] = Math.max(0.0, sum);
        }

        double logit = B2;
        for (int j = 0; j < HIDDEN_SIZE; j++) {
            logit += W2[j] * hidden[j];
        }

        return sigmoid(logit);
    }

    public static double sigmoid(double z) {
        if (z < -45.0) return 0.0;
        if (z > 45.0) return 1.0;
        return 1.0 / (1.0 + Math.exp(-z));
    }

    public static synchronized void train(double[][] trainingData, double[] labels, int epochs, double lr) {
        if (trainingData == null || labels == null || trainingData.length != labels.length) {
            return;
        }

        int n = trainingData.length;
        for (int epoch = 0; epoch < epochs; epoch++) {
            for (int k = 0; k < n; k++) {
                double[] x = trainingData[k];
                double target = labels[k];

                double[] hidden = new double[HIDDEN_SIZE];
                for (int j = 0; j < HIDDEN_SIZE; j++) {
                    double sum = B1[j];
                    for (int i = 0; i < INPUT_SIZE; i++) {
                        sum += W1[j][i] * x[i];
                    }
                    hidden[j] = Math.max(0.0, sum);
                }

                double logit = B2;
                for (int j = 0; j < HIDDEN_SIZE; j++) {
                    logit += W2[j] * hidden[j];
                }
                double output = sigmoid(logit);

                double deltaOutput = output - target;

                double[] deltaHidden = new double[HIDDEN_SIZE];
                for (int j = 0; j < HIDDEN_SIZE; j++) {
                    if (hidden[j] > 0.0) {
                        deltaHidden[j] = deltaOutput * W2[j];
                    } else {
                        deltaHidden[j] = 0.0;
                    }
                }

                for (int j = 0; j < HIDDEN_SIZE; j++) {
                    W2[j] -= lr * deltaOutput * hidden[j];
                }
                B2 -= lr * deltaOutput;

                for (int j = 0; j < HIDDEN_SIZE; j++) {
                    for (int i = 0; i < INPUT_SIZE; i++) {
                        W1[j][i] -= lr * deltaHidden[j] * x[i];
                    }
                    B1[j] -= lr * deltaHidden[j];
                }
            }
        }
    }

    public static double[][] getWeightsHidden() {
        return W1;
    }

    public static double[] getWeightsOutput() {
        return W2;
    }
}
