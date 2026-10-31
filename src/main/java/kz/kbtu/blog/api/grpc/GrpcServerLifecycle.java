package kz.kbtu.blog.api.grpc;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Starts the gRPC server with the Spring application and stops it gracefully. */
@Component
public class GrpcServerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);
    private static final long SHUTDOWN_GRACE_SECONDS = 5;

    private final BlogGrpcService blogService;
    private final UserIdInterceptor userIdInterceptor;
    private final int configuredPort;
    private final int threads;

    private ExecutorService executor;
    private Server server;

    public GrpcServerLifecycle(
            BlogGrpcService blogService,
            UserIdInterceptor userIdInterceptor,
            @Value("${grpc.server.port}") int configuredPort,
            @Value("${grpc.server.threads}") int threads) {
        this.blogService = blogService;
        this.userIdInterceptor = userIdInterceptor;
        this.configuredPort = configuredPort;
        this.threads = threads;
    }

    @Override
    public void start() {
        // gRPC's default pool grows without limit. Calls block on JDBC, so bound it like Tomcat.
        executor = Executors.newFixedThreadPool(threads);
        try {
            server = NettyServerBuilder.forPort(configuredPort)
                    .executor(executor)
                    .addService(ServerInterceptors.intercept(blogService, userIdInterceptor))
                    // Lets tools such as grpcurl discover the API without the .proto file.
                    .addService(ProtoReflectionServiceV1.newInstance())
                    .build()
                    .start();
        } catch (IOException e) {
            executor.shutdownNow();
            throw new UncheckedIOException("Could not start gRPC server on port " + configuredPort, e);
        }
        log.info("gRPC server started on port {}", server.getPort());
    }

    @Override
    public void stop() {
        server.shutdown();
        try {
            if (!server.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                server.shutdownNow();
            }
        } catch (InterruptedException e) {
            server.shutdownNow();
            Thread.currentThread().interrupt();
        }
        executor.shutdown();
    }

    @Override
    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }

    /** The real port, which differs from the configured one when that is 0 (used by tests). */
    public int getPort() {
        return server.getPort();
    }
}
