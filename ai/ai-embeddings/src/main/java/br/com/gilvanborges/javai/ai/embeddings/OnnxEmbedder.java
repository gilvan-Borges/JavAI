package br.com.gilvanborges.javai.ai.embeddings;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import br.com.gilvanborges.javai.ai.core.embedding.Embedder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Embeddings do multilingual-e5-small dentro da JVM, com ONNX Runtime. O modelo exige os prefixos "query: " e
 * "passage: " (por isso a porta tem dois métodos). A sessão do ONNX Runtime aguenta chamadas em paralelo; o
 * tokenizador é protegido por um lock, porque a conta dele é bem mais barata que a do modelo.
 */
public final class OnnxEmbedder implements Embedder, AutoCloseable {

    public static final int DIMENSIONS = 384;
    private static final int MAX_TOKENS = 512;
    private static final String MODEL_ID = "multilingual-e5-small-int8";

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final boolean needsTokenTypeIds;

    /** @param modelDir pasta com {@code model.onnx} e {@code tokenizer.json}, já verificados pelo {@link ModelStore} */
    public OnnxEmbedder(Path modelDir, int threads) {
        try {
            var options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(threads);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            this.session = env.createSession(modelDir.resolve("model.onnx").toString(), options);
            this.tokenizer = HuggingFaceTokenizer.newInstance(modelDir.resolve("tokenizer.json"),
                    Map.of("truncation", "true", "maxLength", String.valueOf(MAX_TOKENS)));
            this.needsTokenTypeIds = session.getInputNames().contains("token_type_ids");
        } catch (OrtException | IOException e) {
            throw new IllegalStateException("Não foi possível carregar o modelo de embeddings.", e);
        }
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public String modelId() {
        return MODEL_ID;
    }

    @Override
    public float[] embedQuery(String text) {
        return embed("query: " + text);
    }

    @Override
    public float[] embedPassage(String text) {
        return embed("passage: " + text);
    }

    private float[] embed(String text) {
        Encoding encoding;
        synchronized (tokenizer) {
            encoding = tokenizer.encode(text);
        }
        long[] ids = encoding.getIds();
        long[] mask = encoding.getAttentionMask();
        try (var idTensor = OnnxTensor.createTensor(env, new long[][] {ids});
             var maskTensor = OnnxTensor.createTensor(env, new long[][] {mask})) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", idTensor);
            inputs.put("attention_mask", maskTensor);
            OnnxTensor typeTensor = null;
            try {
                if (needsTokenTypeIds) {
                    typeTensor = OnnxTensor.createTensor(env, new long[][] {new long[ids.length]});
                    inputs.put("token_type_ids", typeTensor);
                }
                try (var result = session.run(inputs)) {
                    float[][][] hidden = (float[][][]) result.get(0).getValue();
                    return Pooling.meanPoolAndNormalize(hidden[0], mask);
                }
            } finally {
                if (typeTensor != null) {
                    typeTensor.close();
                }
            }
        } catch (OrtException e) {
            throw new IllegalStateException("Falha ao calcular o embedding.", e);
        }
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            throw new IllegalStateException(e);
        } finally {
            tokenizer.close();
        }
    }
}
