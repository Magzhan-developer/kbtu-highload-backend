package kz.kbtu.blog.api.grpc;

import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Times every gRPC call, the counterpart of Spring's automatic http.server.requests timer.
 * Spring Boot does not instrument gRPC, so this is what makes the protocols comparable. Both
 * start when the server receives the call and stop when the response is finished.
 */
@Component
class GrpcMetricsInterceptor implements ServerInterceptor {

    static final String TIMER_NAME = "grpc.server.requests";

    private final MeterRegistry registry;

    GrpcMetricsInterceptor(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(
            ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
        String method = call.getMethodDescriptor().getBareMethodName();
        Timer.Sample sample = Timer.start(registry);
        AtomicBoolean recorded = new AtomicBoolean();

        ServerCall<Q, R> timedCall = new SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                record(sample, recorded, method, status.getCode());
                super.close(status, trailers);
            }
        };
        return new SimpleForwardingServerCallListener<>(next.startCall(timedCall, headers)) {
            // A client that gives up never reaches close(), so its call is recorded here.
            @Override
            public void onCancel() {
                record(sample, recorded, method, Status.Code.CANCELLED);
                super.onCancel();
            }
        };
    }

    private void record(Timer.Sample sample, AtomicBoolean recorded, String method, Status.Code code) {
        if (recorded.compareAndSet(false, true)) {
            sample.stop(Timer.builder(TIMER_NAME)
                    .description("Duration of gRPC server calls")
                    .tag("method", method == null ? "unknown" : method)
                    .tag("status", code.name())
                    .tag("outcome", code == Status.Code.OK ? "SUCCESS" : "ERROR")
                    .register(registry));
        }
    }
}
