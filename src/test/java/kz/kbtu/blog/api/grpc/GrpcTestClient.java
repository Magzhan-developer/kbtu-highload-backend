package kz.kbtu.blog.api.grpc;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import kz.kbtu.blog.grpc.v1.BlogServiceGrpc;
import kz.kbtu.blog.grpc.v1.BlogServiceGrpc.BlogServiceBlockingStub;

/** Connects over a real TCP socket, so tests cover Protobuf and HTTP/2 as well as the service. */
public class GrpcTestClient implements AutoCloseable {

    private static final Metadata.Key<String> USER_ID =
            Metadata.Key.of("x-user-id", Metadata.ASCII_STRING_MARSHALLER);

    private final ManagedChannel channel;

    public GrpcTestClient(int port) {
        this.channel = NettyChannelBuilder.forAddress("localhost", port).usePlaintext().build();
    }

    public BlogServiceBlockingStub anonymous() {
        return BlogServiceGrpc.newBlockingStub(channel);
    }

    public BlogServiceBlockingStub as(long userId) {
        return as(String.valueOf(userId));
    }

    public BlogServiceBlockingStub as(String rawUserId) {
        Metadata headers = new Metadata();
        headers.put(USER_ID, rawUserId);
        return anonymous().withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    @Override
    public void close() {
        channel.shutdownNow();
    }
}
