package kz.kbtu.blog.api.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import kz.kbtu.blog.api.DevAuth;
import org.springframework.stereotype.Component;

/**
 * Metadata is gRPC's version of HTTP headers. This copies the raw user id into the call's
 * Context so the service can read it. It rejects nothing, because reads are public.
 */
@Component
class UserIdInterceptor implements ServerInterceptor {

    static final Context.Key<String> RAW_USER_ID = Context.key("user-id");

    private static final Metadata.Key<String> USER_ID_KEY =
            Metadata.Key.of(DevAuth.USER_ID_HEADER, Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(
            ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
        Context context = Context.current().withValue(RAW_USER_ID, headers.get(USER_ID_KEY));
        return Contexts.interceptCall(context, call, headers, next);
    }
}
