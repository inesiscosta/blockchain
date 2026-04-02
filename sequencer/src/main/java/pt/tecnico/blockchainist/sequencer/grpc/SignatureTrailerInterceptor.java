package pt.tecnico.blockchainist.sequencer.grpc;

import io.grpc.*;
import pt.tecnico.blockchainist.common.Constants;
import java.util.concurrent.atomic.AtomicReference;

public class SignatureTrailerInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata requestHeaders,
            ServerCallHandler<ReqT, RespT> next) {
        // Ignore other calls
        String method = call.getMethodDescriptor().getFullMethodName();
        if (!method.contains("DeliverBlock")) {
            return next.startCall(call, requestHeaders);
        }

        // Add trailer to response
        AtomicReference<byte[]> signatureRef = new AtomicReference<>();
        Context ctx = Context.current().withValue(Constants.SIGNATURE_REF_KEY, signatureRef);

        // Send trailer after response fully sent
        ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT> forwardingCall = new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(
                call) {

            @Override
            public void close(Status status, Metadata trailers) {
                // Grab the signature from the box!
                byte[] sig = signatureRef.get();
                if (status.isOk() && sig != null) {
                    trailers.put(Constants.KEY_SIGNATURE, sig);
                }
                super.close(status, trailers);
            }
        };

        // Propagate Context
        return Contexts.interceptCall(ctx, forwardingCall, requestHeaders, next);
    }
}