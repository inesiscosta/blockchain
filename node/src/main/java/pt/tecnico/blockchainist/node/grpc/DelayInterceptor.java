package pt.tecnico.blockchainist.node.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

/****
 * gRPC interceptor that reads a "delay" value from the request metadata and
 * attaches it to the gRPC context. This allows service implementations to
 * access the delay value for simulating processing delays in request handling.
 */
public class DelayInterceptor implements ServerInterceptor {
    public static final Metadata.Key<String> HEADER_KEY_DELAY = Metadata.Key.of("delay",
            Metadata.ASCII_STRING_MARSHALLER);
    public static final Context.Key<Integer> DELAY_CONTEXT_KEY = Context.key("delay");

    /**
     * Intercepts incoming gRPC calls to extract the "delay" value from the
     * metadata and attach it to the context.
     *
     * @param call    the server call being intercepted
     * @param headers the metadata headers of the incoming call
     * @param next    the next handler in the interceptor chain
     * @return a listener for processing the incoming request
     */
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        // Read the delay from metadata
        String delayStr = headers.get(HEADER_KEY_DELAY);
        int delay = 0;

        if (delayStr != null) {
            try {
                delay = Integer.parseInt(delayStr);
            } catch (NumberFormatException ignored) {
            }
        }

        // Attach delay to context
        Context context = Context.current().withValue(DELAY_CONTEXT_KEY, delay);

        // Process as usual
        return Contexts.interceptCall(context, call, headers, next);
    }
}