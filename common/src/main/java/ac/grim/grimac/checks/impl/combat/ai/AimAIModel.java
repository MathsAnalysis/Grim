package ac.grim.grimac.checks.impl.combat.ai;

public final class AimAIModel {

    public static final int INPUT_SIZE = 8;
    public static final int HIDDEN_SIZE = 6;

    private static double[][] W1 = {
            { 3.2000, 0.0000, 2.0000, 0.0000, 0.0000, 0.0000, 0.0000, 1.2000 },
            { 1.5000, 3.5000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 1.2000 },
            { 0.0000, 0.0000, 3.2000, 2.5000, 0.0000, 0.0000, 0.0000, 1.2000 },
            { 1.8000, 0.0000, 0.0000, 0.0000, 3.5000, 0.0000, 0.0000, 1.2000 },
            { 1.5000, 1.5000, 1.5000, 0.0000, 0.0000, 0.0000, 0.0000, 2.8000 },
            { 0.0000, 0.0000, 0.0000, 1.5000, 0.0000, 3.5000, 2.0000, 0.4000 }
    };

    private static double[] B1 = {
            -1.0000, -1.0000, -1.0000, -1.0000, -1.0000, -1.0000
    };

    private static double[] W2 = {
            2.0000, 2.0000, 2.0000, 2.0000, 2.0000, 2.0000
    };

    private static double B2 = -4.2000;

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
