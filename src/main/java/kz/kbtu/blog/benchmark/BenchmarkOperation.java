package kz.kbtu.blog.benchmark;

import java.util.Arrays;
import java.util.List;

public enum BenchmarkOperation {
    GET_POST("GetPost", false),
    GET_AUTHOR_POSTS("GetAuthorPosts", false),
    GET_COMMENTS("GetComments", false),
    CREATE_POST("CreatePost", true),
    CREATE_COMMENT("CreateComment", true);

    private final String label;
    private final boolean write;

    BenchmarkOperation(String label, boolean write) {
        this.label = label;
        this.write = write;
    }

    public String label() {
        return label;
    }

    public boolean isWrite() {
        return write;
    }

    static List<BenchmarkOperation> reads() {
        return Arrays.stream(values()).filter(operation -> !operation.write).toList();
    }

    static List<BenchmarkOperation> writes() {
        return Arrays.stream(values()).filter(BenchmarkOperation::isWrite).toList();
    }
}
